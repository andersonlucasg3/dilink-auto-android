# 功能：接收服务端"模式"按钮（车机反向控制 / 代理转发）+ VD→投屏自动降级

状态：**设计 + 可测核心已落地**；上层 UI/协议/采集接线与真机验证列为待办（见 §5）。

## 1. 需求（用户原述归纳）
在"接收服务端"的接收页加一个 **"模式"** 按钮，在两形态间切换：
1. **模拟车机·反向控制手机**：把对端投到本端、本端反控对端，"让我当车机"——走 **vd-server 模式**。
2. **接收服务端作"代理服务端"**：接收端充当中继，把"手机投屏"**转发**到另一端（一键车机投到手机）。

细则：VD 模式可用，但**很多手机品牌点不亮 VirtualDisplay 画面**，需在采集侧**自动降级**到投屏模式：`投影模式 → 息屏投屏 → 主屏投屏`。

## 2. 代码勘察结论（现状）
- 现有管线是**单向**：`手机采集 → 编码 H.264 → 接收端(车机 app-server / 桌面 app-desktop) 解码+回注触摸`。
- 已具备：vd-server 采集（`vd-server/VirtualDisplayCreator`+`PipelineServer`）、手机侧 `VirtualDisplayClient`、车机 `CarConnectionService`、桌面 `DesktopConnectionService`、ADB/Shizuku 部署。
- **不存在**：代理/中继转发、反向控制、`MediaProjection` 投屏采集、设备品牌 VD 兼容名单。这些是本功能要新增的。

## 3. 设计
### 3.1 面向用户的两种"模式"（接收端态）
```
enum class ReceiverMode {
    REVERSE_CONTROL,   // 模拟车机·反向控制对端（vd-server 模式）
    PROXY_RELAY,       // 本端作代理服务端，转发对端投屏
}
```
- **REVERSE_CONTROL**：本端触发对端（或被本端代管）启动 vd-server；对端 VD 画面编码后发给本端；本端回注触摸 = 反向控制对端。
- **PROXY_RELAY**：本端作为中继，把一端（源）的 video/input/lifecycle 流原样转发给另一端（目的）。源→本端→目的，本端只转发不解码（低延迟中继）。

### 3.2 采集侧降级阶梯（本回合已实现，平台无关）
`protocol-core/ScreenMode.kt` + `CaptureModePolicy`：
- 首选 VD（`VIRTUAL_DISPLAY`）；设备点不亮 VD 时 `preferred(vdLightingSupported=false)=PROJECTION_SCREEN_OFF`。
- 运行时失败逐级降级：`VIRTUAL_DISPLAY → PROJECTION_SCREEN_OFF(息屏投屏) → PROJECTION_MAIN(主屏投屏) → (无)`。
- `ladder()` 供 UI 展示"将依次尝试"。
- 单测 `CaptureModePolicyTest`（6 例）已锁定契约。

## 4. 精确接线落点（待办，按模块）
| 模块 | 文件/类 | 改动 |
|---|---|---|
| `protocol-core` | 新增 `ReceiverMode`、降级结果帧 `DataMsg.SET_CAPTURE_MODE`/`ack` | 让两端就"当前采集模式"达成一致（握手或 control 帧） |
| `app-client`（手机：VD/投屏源） | `service/ConnectionService`、`display/VirtualDisplayClient`、`service/PhoneDisplayRestorer` | 依据 `CaptureModePolicy` 选择 vd-server 采集或新增 `MediaProjection` 采集路径；VD 首帧超时→`fallbackFrom` |
| `vd-server`（对端运行） | `PipelineServer`/`VirtualDisplayCreator` | 暴露"VD 是否点亮/首帧是否产出"信号，失败时上报以触发降级 |
| `app-server` / `app-desktop`（接收端） | `CarLaunchScreen`（车机接收页）/ `DesktopWindow`（桌面接收页） | 加**"模式"按钮** + `ReceiverMode` 状态；PROXY_RELAY 走新中继通道 |
| 设备兼容 | 新增 `VdBrandBlacklist`（或 Build.MODEL 规则）喂给 `preferred()` | 命中品牌/机型直接跳过 VD |

## 5. 本回合已交付 vs 待办
**已交付（本机验证通过）**
- `protocol-core`：`ScreenMode` + `CaptureModePolicy`（VD 优先 + 息屏→主屏降级阶梯）+ `CaptureModePolicyTest`(6)。
- 全仓测试覆盖补齐：18 个新测试文件 / 139 用例全绿（见 `docs/IMPLEMENTATION_REPORT_TESTING.md`）。同报告 §5 另记录了后续的"为可测性重构"seam（ADB 帧/加解密、`Connection` 心跳可注入、`AssetSource`、`CarInstaller`、重连退避/图标预算纯函数，约 +44 用例，合计 183，全部行为不变）。

**待办（需一次 brainstorm 敲定 UX + 真机验证，不建议盲改主连线）**
1. 明确"接收服务端"到底是哪一端（`app-desktop` 是纯 JVM 无法 VD/screencast；VD/投屏降级须落在 Android 接收端）与两模式的 UX 文案/入口。
2. `MediaProjection` 采集通道（息屏投屏/主屏投屏）新增实现 + 前台服务权限。
3. PROXY_RELAY 的转发协议（不解码中继）与两端会话协商。
4. VD "是否点亮"判据 + 各品牌降级验证（需真机矩阵）。
5. "模式"按钮 UI + `ReceiverMode` 状态机接入现有 `CarLaunchScreen` / `DesktopWindow` 而不破坏现有连接/部署主线。

## 6. 风险备注
- 代理转发与反向控制都会改动现有连接/部署主线，缺真机验证下盲改风险高；建议按 §4 分模块、以 `CaptureModePolicy` 这类纯策略为锚点、逐块接入并配回归测试。
- 设备品牌 VD 兼容名单需真机矩阵校准，先用 `preferred(vdLightingSupported=false)` 的显式开关兜底。
