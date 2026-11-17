#!/usr/bin/env python3
"""DiLink-Auto 双手机 E2E — mock-adb fixture 生成器。

生成 scripts/lib/mock-adb/fixtures/ 下的应答文件：
  ui-hierarchy.xml      A 的 UI（IP 输入框 + Connect 按钮 + Eject + Launcher/Recent 页签）
  clientlog-idle.log    B 的 client.log（中立态：旧残留 + 重新监听行 → WAITING）
  clientlog-streaming.log  建流后的 client.log（含 A 中继进来的 CarLog 状态行）
  clientlog-ejected.log    Eject 收尾后的 client.log（handleDisconnect + 重监听 → car IDLE / phone WAITING）
  clientlog-killed.log     会话中 client 被杀后的 client.log（handleDisconnect，无重监听 → car IDLE）
  vd-server.log         引擎日志（@FRAMES@/@TEX@ 由 mock 逐次读取时替换，模拟帧增长）
  logcat-phone.log / logcat-car.log   双端 logcat（无 ANR/崩溃）
  screencap.png         非纯黑截图（PIL 生成渐变，stddev > 1.0）

日志串全部从源码/真机双源逐字抄（.plan/e2e-observability.md 的断言面）：
  文件格式    [ts][I][Tag] msg（B 本地）/ [ts][I][CarLog] [ts][I] msg（A 中继）
              2026-10-16 从设备实际 pull 的 client.log 与 client-20261016-183625 双源核对
  app-client  FileLog.i "Listening for car connection on port 9637..."
  app-client  FileLog.i "Car connected (control, session #N), waiting for handshake"
  app-client  FileLog.i "VD server lifecycle connected (displayId=N)"
  app-server  carLogSend "Control connected to host:port — sending handshake"
  app-server  carLogSend "All connections ready — connected"
  app-server  carLogSend "Video ready — streaming"
  vd-server   log("Starting: VD=1080x1920 @480dpi, ...")
  vd-server   log("Video on :9638")
  vd-server   log("VD via DisplayManagerGlobal: id=3")
  vd-server   PipeLog.log("Pipeline: N frames 4Mbps keys=5 tex=M cb=... swapFails=0 ...")
"""
from pathlib import Path
import random

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures"
FIX.mkdir(parents=True, exist_ok=True)


def w(name: str, text: str) -> None:
    (FIX / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"  {name}: {len(text)} bytes")


# ── A 的 UI hierarchy（uiautomator dump 的应答）─────────────────────────
# 与真实 app-server UI 对齐（2026-10-16 从源码校准）：
#   - 连接态：IP 输入框（EditText）+ Connect 按钮
#   - 导航栏只有 Home/Back/Eject 三个钮（PersistentNavBar.kt NavAction 枚举实证），
#     Icon 的 contentDescription = label（默认英文 "Home"/"Back"/"Eject"）
#   - 应用网格：两个可点击的 app 磁贴（供 op_switch_app / ime_open 选目标）
UI = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.dilinkauto.server" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2340]">
    <node index="0" text="Phone IP address" resource-id="com.dilinkauto.server:id/ipLabel" class="android.widget.TextView" package="com.dilinkauto.server" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[120,300][500,360]"/>
    <node index="1" text="" resource-id="com.dilinkauto.server:id/ipInput" class="android.widget.EditText" package="com.dilinkauto.server" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[120,380][960,470]"/>
    <node index="2" text="Connect" resource-id="com.dilinkauto.server:id/connectButton" class="android.widget.Button" package="com.dilinkauto.server" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[400,520][680,620]"/>
    <node index="3" text="Settings" resource-id="com.dilinkauto.server:id/appTile0" class="android.widget.TextView" package="com.dilinkauto.server" content-desc="Settings" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[120,700][480,950]"/>
    <node index="4" text="Dialer" resource-id="com.dilinkauto.server:id/appTile1" class="android.widget.TextView" package="com.dilinkauto.server" content-desc="Dialer" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[600,700][960,950]"/>
    <node index="5" text="" resource-id="" class="android.widget.ImageButton" package="com.dilinkauto.server" content-desc="Home" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[120,2150][240,2300]"/>
    <node index="6" text="" resource-id="" class="android.widget.ImageButton" package="com.dilinkauto.server" content-desc="Back" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[420,2150][540,2300]"/>
    <node index="7" text="" resource-id="" class="android.widget.ImageButton" package="com.dilinkauto.server" content-desc="Eject" checkable="false" checked="false" clickable="true" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[840,2150][960,2300]"/>
  </node>
</hierarchy>
"""
w("ui-hierarchy.xml", UI)

# ── B 的 client.log（中立态：上一会话的残留行 + 本次重新监听行）──────────
# 尾行必须是 "Listening for car connection"——phone_state_now 取最后命中的
# 标记行，残留的旧 STREAMING 行在后会把你活着的 client 读成 STREAMING。
# **2026-10-16 三修：全部改用真机文件格式**（`[ts][I][Tag] msg`；A 中继行
# 为 `[ts][I][CarLog] [ts][I] msg`）。旧版误用 logcat 形态 `MM-DD HH:MM:SS.mmm
# I/Tag(PID):`——那是 `logcat -d` 的输出，不是 pull /sdcard/DiLinkAuto/client.log
# 拿到的文件内容；Touch 行曾因此对不上 assert_touch_echo 的 `CarLog\]` 正则。
w("clientlog-idle.log", """=== DiLink Auto Client log started 2026-10-15 22:31:04 ===
[22:31:04.112][I][ConnectionService] Listening for car connection on port 9637...
[22:31:58.700][I][ConnectionService] Car connected (control, session #7), waiting for handshake
[22:32:01.204][I][ConnectionService] VD server lifecycle connected (displayId=3)
[22:41:12.998][W][ConnectionService] ControlConn: reader: IOException: Connection reset by peer
[22:41:13.010][I][ConnectionService] Car disconnected (control)
[22:41:13.020][I][ConnectionService] cleanupSession: tearing down session #7
[23:00:05.000][I][ConnectionService] Listening for car connection on port 9637...
""")

# ── B 的 client.log（建流后：B 侧行 + A 中继进来的 CarLog 行）───────────
w("clientlog-streaming.log", """=== DiLink Auto Client log started 2026-10-15 23:02:11 ===
[23:02:11.004][I][ConnectionService] Listening for car connection on port 9637...
[23:02:19.812][I][ConnectionService] Car connected (control, session #8), waiting for handshake
[23:02:20.100][I][VirtualDisplayClient] VD server connected, displayId=3 directInjection=true
[23:02:20.101][I][ConnectionService] Sent VD_PORTS_BOUND to car — direct video/input streaming
[23:02:20.340][I][ConnectionService] VD server lifecycle connected (displayId=3)
[23:02:19.900][I][CarLog] [23:02:20.560][I] Control connected to 192.168.3.12:9637 — sending handshake
[23:02:19.930][I][CarLog] [23:02:20.590][I] Handshake OK: MI 9 jarPath=/data/local/tmp/vd-server.jar connMethod=tcp vdDpi=480
[23:02:20.050][I][CarLog] [23:02:20.710][I] VD ports bound — connecting video and input directly to VD server
[23:02:20.150][I][CarLog] [23:02:20.810][I] All connections ready — connected
[23:02:20.360][I][CarLog] [23:02:21.020][I] Video ready — streaming
""")

# ── B 的 client.log（Eject 优雅收尾后）──────────────────────────────────
# **2026-10-16 真机抄录形态**（S03 P0 实证，源：/sdcard/DiLinkAuto/client-20261016-224332）：
# A 端 Eject 后中继随连接断开，A 端自己的「handleDisconnect / User disconnect — idle
# (persisted)」**永远到不了 B**——能到 B 的只有断开前最后一条 CarLog
# 「User disconnect — will not auto-reconnect」。car_state_now 判 IDLE 靠的是
# **B 端本地行**「Car disconnected (control)」（不依赖中继，B 自己打印）。
# 旧 fixture 曾放 handleDisconnect CarLog 行——真机传不过来，已废。
w("clientlog-ejected.log", """=== DiLink Auto Client log started 2026-10-15 23:02:11 ===
[23:02:11.004][I][ConnectionService] Listening for car connection on port 9637...
[23:02:19.812][I][ConnectionService] Car connected (control, session #8), waiting for handshake
[23:02:20.100][I][VirtualDisplayClient] VD server connected, displayId=3 directInjection=true
[23:02:20.340][I][ConnectionService] VD server lifecycle connected (displayId=3)
[23:02:19.900][I][CarLog] [23:02:20.560][I] Control connected to 192.168.3.12:9637 — sending handshake
[23:02:19.930][I][CarLog] [23:02:20.590][I] Handshake OK: MI 9 jarPath=/data/local/tmp/vd-server.jar connMethod=tcp vdDpi=480
[23:02:20.150][I][CarLog] [23:02:20.810][I] All connections ready — connected
[23:02:20.360][I][CarLog] [23:02:21.020][I] Video ready — streaming
[23:03:12.400][I][CarLog] [23:03:13.160][I] User disconnect — will not auto-reconnect
[23:03:12.408][I][CarLog] [23:03:13.168][I] VideoConn: reader: EOF
[23:03:12.410][W][ConnectionService] ControlConn: reader: EOF
[23:03:12.412][W][ConnectionService] ControlConn: disconnect() reason=reader: EOF
[23:03:13.100][I][ConnectionService] Car disconnected (control)
[23:03:13.110][I][ConnectionService] cleanupSession: tearing down session #8
[23:03:13.450][I][ConnectionService] Listening for car connection on port 9637...
""")

# ── B 的 client.log（会话中 client 被 force-stop/install -r 杀掉）───────
# **2026-10-16 真机形态**：B 的 client 进程被杀 → client.log **突然停止增长**
# （无断开处理行——进程没机会写）；A 端的断开日志也传不过来（B 死）。
# car_state_now 走「B 端 client 进程不在 → UNKNOWN」分支（issue #31 修正，
# 原为 IDLE 短路），不依赖本 fixture 的尾行。fixture 保真为「停在最后一条正常中继行」。
w("clientlog-killed.log", """=== DiLink Auto Client log started 2026-10-15 23:02:11 ===
[23:02:11.004][I][ConnectionService] Listening for car connection on port 9637...
[23:02:19.812][I][ConnectionService] Car connected (control, session #8), waiting for handshake
[23:02:20.100][I][VirtualDisplayClient] VD server connected, displayId=3 directInjection=true
[23:02:20.340][I][ConnectionService] VD server lifecycle connected (displayId=3)
[23:02:19.900][I][CarLog] [23:02:20.560][I] Control connected to 192.168.3.12:9637 — sending handshake
[23:02:19.930][I][CarLog] [23:02:20.590][I] Handshake OK: MI 9 jarPath=/data/local/tmp/vd-server.jar connMethod=tcp vdDpi=480
[23:02:20.150][I][CarLog] [23:02:20.810][I] All connections ready — connected
[23:02:20.360][I][CarLog] [23:02:21.020][I] Video ready — streaming
""")

# ── B 的 client.log（S21：9637 被占，client 被 A 的 adb 轨 am start 拉起后
#    bind 撞 nc → Connection error；后随连接失败断开 → Car disconnected (control)）─
# car_state_now 阶段性产物（issue #31 mock 时间线）：
#   reconnect_tick ≥4 时 cat 本文件 → 失败证据 + 末行 Car disconnected (control)
#   → car_state_now 走状态行扫描命中 IDLE（双端进程在）。连接尝试串 WiFi track: trying
#   在 A logcat（logcat-car-s21-fail.log）侧产出，本文件只放 B 侧串。
w("clientlog-s21-fail.log", """=== DiLink Auto Client log started 2026-10-28 08:24:46 ===
[08:24:46.100][I][ConnectionService] Listening for car connection on port 9637...
[08:24:49.200][I][ConnectionService] Car connected (control, session #9), waiting for handshake
[08:24:49.350][E][ConnectionService] Connection error: bind 9637 failed (Address already in use)
[08:24:50.800][W][ConnectionService] ControlConn: reader: IOException: Connection reset by peer
[08:24:50.810][I][ConnectionService] Car disconnected (control)
[08:24:50.820][I][ConnectionService] cleanupSession: tearing down session #9
[08:24:51.000][I][ConnectionService] Listening for car connection on port 9637...
""")

# ── A 的 logcat（S21：reconnect 后启动路径自动连接尝试 → 失败证据）────────
# 连接尝试串（issue #31 INCONCLUSIVE 门禁的判据）：WiFi track: trying / Failed to
# connect ADB to / WiFi connect failed: ——三串任一命中即视为「A 端确有连接尝试」。
# 失败证据串与连接尝试串部分重叠（WiFi connect failed: 既是尝试也是失败）。
w("logcat-car-s21-fail.log", """10-28 08:24:47.792 I/CarConnectionService: WiFi track: trying gateway 192.168.3.206
10-28 08:24:48.100 E/RemoteAdbController: Failed to connect ADB to 192.168.3.206:5555
10-28 08:24:49.500 W/CarConnectionService: WiFi connect failed: timeout connecting 192.168.3.206:9637
10-28 08:24:50.900 I/ActivityManager: Displayed com.dilinkauto.server/.MainActivity: +420ms
""")

# ── vd-server.log（@FRAMES@/@TEX@ 由 mock 逐次读取时替换）──────────────
w("vd-server.log", """10-15 23:02:19.950 I/PipelineServer: Starting: VD=1080x1920 @480dpi, encode=1080x1920, phoneHost=192.168.3.12, fps=24, bitrate=4M, carHost=ANY
10-15 23:02:19.960 I/PipelineServer: VD via DisplayManagerGlobal: id=3
10-15 23:02:20.020 I/PipelineServer: Video on :9638
10-15 23:02:20.080 I/PipelineServer: InputManager ready
10-15 23:02:20.500 I/PipelineServer: Pipeline: @FRAMES@ frames 4Mbps keys=5 tex=@TEX@ cb=@FRAMES@ swapFails=0 ts=1234567 glErr=0
""")

# ── 双端 logcat（无 ANR/崩溃；freeze/断言要 0 命中）────────────────────
w("logcat-phone.log", """10-15 23:02:11.004 I/DiLinkAuto: Listening for car connection on port 9637...
10-15 23:02:19.812 I/DiLinkAuto: Car connected (control, session #8), waiting for handshake
10-15 23:02:20.340 I/DiLinkAuto: VD server lifecycle connected (displayId=3)
10-15 23:02:21.100 I/ActivityManager: Displayed com.dilinkauto.client/.MainActivity: +412ms
""")
w("logcat-car.log", """10-15 23:02:19.900 I/DiLinkAuto: Control connected to 192.168.3.12:9637 — sending handshake
10-15 23:02:20.150 I/DiLinkAuto: All connections ready — connected
10-15 23:02:20.360 I/DiLinkAuto: Video ready — streaming
10-15 23:02:21.300 I/ActivityManager: Displayed com.dilinkauto.server/.MainActivity: +380ms
""")

# ── screencap.png（非纯黑：PIL 渐变，stddev 远大于 1.0）────────────────
def make_png() -> None:
    from PIL import Image
    rng = random.Random(20261015)
    im = Image.new("L", (1080, 400))
    px = im.load()
    for y in range(400):
        for x in range(0, 1080, 4):
            v = (x * 255 // 1080 + y // 4 + rng.randint(0, 12)) % 256
            for dx in range(4):
                if x + dx < 1080:
                    px[x + dx, y] = v
    im.save(FIX / "screencap.png")
    print(f"  screencap.png: {im.size[0]}x{im.size[1]} (stddev 渐变)")


make_png()
print(f"fixtures 就绪：{FIX}")
