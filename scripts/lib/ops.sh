#!/bin/bash
# DiLink-Auto 双手机真机 E2E — 动作层（op 的 bash 实现）
#
# 设计：.plan/e2e-harness-design.md §1.6。**本文件是唯一允许改变设备状态的层**
# （场景与 explore.py 都只能经 run_op 触碰设备）。产品级预期不在本层判定
# （那是 registry expect / asserts.sh 的职责）。
#
# 用法：
#   bash ops.sh list                # 打印全部 op id
#   bash ops.sh run <id> [k=v...]   # 执行 op（参数：to/index/pkg/label/display）
#
# 红线（.plan/e2e-risk-assessment.md §2）：
#   - 杀引擎只准 pgrep -f "[P]ipelineServer" + kill <pid>；禁 pkill -f（误杀 wrapper）
#   - 设备写入白名单：settings 仅 screen_off_timeout/user_rotation/airplane_mode_on/
#     default_input_method 四键；文件仅 /sdcard/DiLinkAuto/ 下
#   - 每 op 追加 JSONL 到 ${E2E_OP_RESULT:-/dev/null}
#   - 优雅停止只走哨兵；kill -9 后必须由 op_neutral 补 restore

set -uo pipefail

OP_TO="" OP_INDEX=1 OP_PKG="" OP_LABEL="" OP_DISPLAY=""
OPS_LOG="${E2E_RUN_DIR:-debug-logs/e2e/local}/ops.log"
E2E_CLIENT_APK="${E2E_CLIENT_APK:-app-client/build/outputs/apk/debug/app-client-debug.apk}"
E2E_SERVER_APK="${E2E_SERVER_APK:-app-server/build/outputs/apk/debug/app-server-debug.apk}"

op_log() { # harness 自己的记账（[neutral] force-kill 等），不进产品日志面
  mkdir -p "$(dirname "$OPS_LOG")" 2>/dev/null || true
  echo "[$(date +%H:%M:%S)] $*" >> "$OPS_LOG"
}

# ── A 侧 UI 驱动helper（uiautomator dump + bounds 解析）─────────────────
_a_dump() { # → stdout 打印 UI XML；失败返回 1（timeout 包裹 + 重试一次，T9）
  local ui i
  for i in 1 2; do
    ui=$(timeout 10 adb -s "$CAR_SERIAL" shell "uiautomator dump /sdcard/ui_op.xml && cat /sdcard/ui_op.xml" 2>/dev/null | tr -d '\r')
    if echo "$ui" | grep -q '<hierarchy'; then echo "$ui"; return 0; fi
    sleep 1
  done
  log_err "A 侧 uiautomator dump 失败（connect/switch_app 等 UI 驱动链路不通）"
  return 1
}
_bounds_of_text() { # <ui> <text>（正反两种属性顺序）
  echo "$1" | grep -oE "text=\"$2\"[^>]*bounds=\"\[[0-9,]+\]\[[0-9,]+\]\"" | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"'
  local b
  b=$(echo "$1" | grep -oE "bounds=\"\[[0-9,]+\]\[[0-9,]+\]\"[^>]*text=\"$2\"" | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  [ -n "$b" ] && echo "$b"
}
_bounds_of_desc() { # <ui> <content-desc>
  echo "$1" | grep -oE "content-desc=\"$2\"[^>]*bounds=\"\[[0-9,]+\]\[[0-9,]+\]\"" | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"'
  local b
  b=$(echo "$1" | grep -oE "bounds=\"\[[0-9,]+\]\[[0-9,]+\]\"[^>]*content-desc=\"$2\"" | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  [ -n "$b" ] && echo "$b"
}
_bounds_of_edittext() { # <ui>（首个 EditText）
  echo "$1" | grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"'
  local b
  b=$(echo "$1" | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"[^>]*class="android.widget.EditText"' | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  [ -n "$b" ] && echo "$b"
}
_bounds_top_y() { # <bounds串> → 左上角 y（"bounds=\"[x1,y1][x2,y2]\"" → y1）
  local s=${1#*bounds=\"[}
  s=${s#*,}
  echo "${s%%]*}"
}
_bounds_of_ip_edittext() { # <ui> → ManualConnectBox 的 IP 输入框 bounds（不可达时返回空）
  # 真机校准（2026-10-16 try4）：启动页 CarLaunchScreen 的窄屏内容列里 ManualConnectBox
  # 是**最底部**卡片（顺序：Branding → statusCard → [WifiAdbSetupCard] → HowToConnect
  # → ManualConnectBox），设置卡片的 EditText 都在它上方。issue #173 PA 已给该列加
  # verticalScroll + imePadding，但老 APK / 更高竖屏仍可能不可达，故保留可见性校验。
  # 优先按「手机 IP 地址」标签定位（Compose 把 TextField label 渲染在框内、y 与框重叠）。
  # **2026-10-17 修正（S19 实证）**：ManualConnectBox 在真机（1080x2152 竖屏）上被挤出
  # 屏幕外且无滚动容器（3 个设置卡已占满到 y=2048，HowToConnect/ManualConnectBox 恒不可见）
  # ——标签恒缺失。旧 fallback「取最后一个 EditText」会把 IP 输进**目标帧率设置框**
  # （SettingSection 输入过滤 filter(isDigit).take(3) → "192"，失焦提交 coerceFps 污染设置）。
  # 故移除 tail -1 兜底：标签缺失即返回空，调用方按「找不到 IP 输入框」失败，不碰任何设置框。
  local ui=$1 all lb lby b by
  all=$(echo "$ui" | grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  [ -n "$all" ] || all=$(echo "$ui" | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"[^>]*class="android.widget.EditText"' | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  [ -n "$all" ] || return 0
  for lb in "手机 IP 地址" "Phone IP address"; do
    lby=$(_bounds_top_y "$(_bounds_of_text "$ui" "$lb")")
    [ -n "$lby" ] || continue
    while IFS= read -r b; do
      by=$(_bounds_top_y "$b")
      if [ -n "$by" ] && [ "$by" -ge $((lby - 200)) ] && [ "$by" -le $((lby + 200)) ]; then
        echo "$b"; return 0
      fi
    done <<< "$all"
  done
  return 0
}

# ── op 实现 ─────────────────────────────────────────────────────────────
op_neutral() {
  # 目标复合态：(car=IDLE, phone=IDLE, engine=absent, vd=absent, sentinel=off)
  # 幂等：每步对「已在中立态」安全；失败不 abort，收集证据后统一汇总。
  log_step "op_neutral：恢复中立态"
  # 1) A 侧 force-stop server
  dcar 'am force-stop com.dilinkauto.server' > /dev/null 2>&1 || true
  # 2) B 侧 force-stop client（9637 监听随之释放）
  dphone 'am force-stop com.dilinkauto.client' > /dev/null 2>&1 || true
  sleep 1
  # 3) 杀引擎（方括号技巧硬性；兜底强杀记账到 harness ops.log，不进产品日志面）
  #    注意 pgrep 无输出时 grep -c . 打 0 但退出码 1——不能再 `|| echo 0`（会叠成
  #    "0\n0" 让 [ -gt ] 报 integer expression expected；2026-10-16 mock 回放抓到）。
  local pids p
  pids=$(dphone 'pgrep -f "[P]ipelineServer"' 2>/dev/null | tr -d '\r' | tr ' ' '\n' | grep -c . 2>/dev/null || true)
  case "${pids:-}" in ''|*[!0-9]*) pids=0 ;; esac
  if [ "$pids" -gt 0 ]; then
    for p in $(dphone 'pgrep -f "[P]ipelineServer"' 2>/dev/null | tr -d '\r'); do
      dphone "kill -9 $p" > /dev/null 2>&1 || true
      op_log "[neutral] force-kill pid=$p"
    done
  fi
  # 4) 杀 S21 的占位进程（如有）
  local occ
  occ=$(dphone 'cat /sdcard/DiLinkAuto/e2e-occupy.pid 2>/dev/null' 2>/dev/null | tr -d '\r\n ')
  if [ -n "$occ" ]; then
    dphone "kill -9 $occ" > /dev/null 2>&1 || true
    op_log "[neutral] kill occupy-9637 pid=$occ"
  fi
  dphone 'rm -f /sdcard/DiLinkAuto/e2e-occupy.pid' > /dev/null 2>&1 || true
  # 5) 等引擎归零（真实时间预算）
  if wait_engine_gone 30; then log_ok "引擎已归零"; else log_err "引擎仍在（pid=$(engine_pid)）"; fi
  # 6) 删哨兵
  dphone 'rm -f /sdcard/DiLinkAuto/stop-request' > /dev/null 2>&1 || true
  # 7) settings 四项基线回写（R12：写回再读回验证）
  dphone "settings put system screen_off_timeout $BASELINE_SCREEN_OFF_TIMEOUT" > /dev/null 2>&1 || true
  dphone "settings put system user_rotation $BASELINE_USER_ROTATION" > /dev/null 2>&1 || true
  dphone "settings put global airplane_mode_on $BASELINE_AIRPLANE" > /dev/null 2>&1 || true
  [ -n "$BASELINE_IME" ] && dphone "settings put secure default_input_method $BASELINE_IME" > /dev/null 2>&1 || true
  # 8) WiFi 恢复（wifi_off/airplane op 的残留；B 的无线 adb 也靠它）
  _ensure_wifi "$PHONE_SERIAL" "$PHONE_IP" || log_err "B 的 WiFi 未恢复到 $PHONE_IP"
  _ensure_wifi "$CAR_SERIAL"   "$CAR_IP"   || log_err "A 的 WiFi 未恢复到 $CAR_IP"
  # 9) 端口面验证（B:9637/9638/9639 + A:9637 全无监听）
  local p bad=0
  for p in 9637 9638 9639; do
    [ "$(listening_on "$PHONE_SERIAL" "$p")" = "Y" ] && { log_err "B 侧 $p 仍 LISTEN"; bad=1; }
  done
  [ "$(listening_on "$CAR_SERIAL" 9637)" = "Y" ] && { log_err "A 侧 9637 仍 LISTEN"; bad=1; }
  [ "$bad" = "0" ] && log_ok "端口面干净（B:9637/9638/9639 + A:9637 均无监听）"
  # 10) 日志归档：vd-server.log 归档后删（跨场景证据链在 RUN 目录）；client.log 归档不删
  if [ -n "${E2E_RUN_DIR:-}" ]; then
    mkdir -p "$E2E_RUN_DIR/neutral-archive"
    pull_vdlog    "$E2E_RUN_DIR/neutral-archive/vd-server-$(date +%H%M%S).log" > /dev/null 2>&1 || true
    pull_clientlog "$E2E_RUN_DIR/neutral-archive/client-$(date +%H%M%S).log"   > /dev/null 2>&1 || true
    dphone 'rm -f /sdcard/DiLinkAuto/vd-server.log' > /dev/null 2>&1 || true
  fi
  # 11) logcat 轮换（防跨轮假阳性）
  logcat_clear phone; logcat_clear car
  # 12) 六项中立核验，任一不过 return 1
  [ -z "$(engine_pid)" ] || { log_err "中立核验失败：引擎未归零"; return 1; }
  [ "$(dphone 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')" = "$BASELINE_SCREEN_OFF_TIMEOUT" ] || { log_err "中立核验失败：screen_off_timeout"; return 1; }
  [ "$(listening_on "$PHONE_SERIAL" 9637)" != "Y" ] || { log_err "中立核验失败：9637 未释放"; return 1; }
  # issue #180 第三版：哨兵按会话分文件（`stop-request-<token>`），残留检查用通配。
  [ -z "$(dphone 'ls /sdcard/DiLinkAuto/stop-request* 2>/dev/null' 2>/dev/null | tr -d '\r\n ')" ] || { log_err "中立核验失败：哨兵残留"; return 1; }
  log_ok "中立态收敛完成"
  return 0
}

_ensure_wifi() { # <serial> <expect_ip>：IP 不符则 svc wifi enable 并等待（30s 真实预算）
  local serial=$1 ip=$2 cur iface
  cur=$(active_wifi_ip "$serial")
  [ "$cur" = "$ip" ] && return 0
  iface=$(active_wifi_iface "$serial")
  log_step "$serial 活动 WiFi 网卡=${iface:-未知} IP=$cur（期望 $ip），执行 svc wifi enable"
  adb -s "$serial" shell svc wifi enable > /dev/null 2>&1
  local deadline=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur=$(active_wifi_ip "$serial")
    [ "$cur" = "$ip" ] && return 0
    sleep 1
  done
  return 1
}

op_launch_client() { # B 上 monkey 拉起 client（进 WAITING，9637 监听）
  dphone "monkey -p com.dilinkauto.client -c android.intent.category.LAUNCHER 1" > /dev/null 2>&1
  wait_port_listening phone 9637 90
}
op_launch_server() { # A 上 monkey 拉起 server
  dcar "monkey -p com.dilinkauto.server -c android.intent.category.LAUNCHER 1" > /dev/null 2>&1
  return 0
}

# ── issue #173 PA：首次连接入口的降级路径 ──────────────────────────────────
# 竖屏超高被裁（S19 实证）或 uiautomator 拿不到 XML 时，ManualConnectBox 不可达。
# 旧行为是直接 return 1，S01 setup 必失败；现在改用仓库内已被 S08/S19/S20/S21
# 实证的 op_reconnect（重启 app 触发启动路径自动连接）兜底，并继续走 ③ 收敛窗口。
# 不引入 bad()、不改 rc 语义（e2e-dual-device.sh:103-115 的记账不变量）。
_connect_manual_ui() { # <ip> → 0 已点「连接」；1 ManualConnectBox 不可达（调用方走重启兜底）
  local ip=$1 ui eb cb cand i
  ui=$(_a_dump) || return 1
  # 先验证 ManualConnectBox 可见性（「连接」按钮 = 存在性标志）——不可见时**不碰任何
  # 输入框**。S19 实证：竖屏 1080x2152 下该卡片被挤出屏幕外；任何 EditText 都是设置卡
  # 数值框，旧序「先输后找」会把 IP 污染进目标帧率框（filter 后 "192"、失焦提交）。
  cb=""
  for cand in "连接" "Connect"; do
    cb=$(_bounds_of_text "$ui" "$cand"); [ -n "$cb" ] && break
  done
  [ -n "$cb" ] || return 1
  eb=$(_bounds_of_ip_edittext "$ui")
  [ -n "$eb" ] || return 1
  _tap_bounds "$CAR_SERIAL" "$eb"; sleep 0.5
  for i in $(seq 1 24); do dcar input keyevent KEYCODE_DEL > /dev/null 2>&1; done
  dcar input text "$ip" > /dev/null 2>&1
  sleep 0.3
  _tap_bounds "$CAR_SERIAL" "$cb"
  log_step "已点击「连接」（ip=$ip），待命处理 B 的授权框"
  return 0
}

_connect_restart_fallback() { # <car_state> → 0 已发起重启（继续 ③ 收敛）；1 原语失败
  # 降级不是失败：只用 log_step（▶ 信息行），不新增 ✗、不进两套账。
  log_step "ManualConnectBox 不可达（car_state=$1）——改用「重启 app」原语触发自动连接（issue #173 PA）"
  if op_reconnect; then
    log_step "已发起 app 重启，继续收敛窗口等待 STREAMING"
    return 0
  fi
  log_err "「重启 app」原语失败（ManualConnectBox 不可达，car_state=$1）"
  return 1
}

op_connect() {
  # 2026-10-16 真机校准（try4 取证）——A 的 app **自动连接是主路径**：
  # CarConnectionService.startConnection() 同时起 WiFi 轨（网关探测 + mDNS 发现）与
  # USB/TCP-ADB 轨；实测启动 ~1.5s 控制连接 + 握手、~18s 到 STREAMING（瓶颈是引擎
  # 部署 15s，A 侧 logcat 时间线实证）。IP 输入框/「连接」按钮只在「未连上」的
  # CarLaunchScreen.ManualConnectBox 出现（宽屏 / 窄屏两个分支各自的
  # state != STREAMING/CONNECTED 分支），且中文 ROM 按钮文案是「连接」不是 "Connect"
  # （values-zh 实证）——try4 就死在照搬英文串 + 在自动连接已进行时找手动按钮。
  # 策略：① 先等自动连接（顺带代点 B 的无线调试授权框）；② 超时才走手动 UI 兜底；
  # ③ 收敛等待到 STREAMING（授权框是硬阻塞——不点掉会永远卡 CONNECTING，必须在本
  #    op 内处理完；reach_streaming 的 wait_state_eq 是二道保险）；
  # ④ issue #173 PA：② 里 ManualConnectBox 不可达（竖屏超高被裁 / dump 失败）时，
  #   不再 return 1，改用 op_reconnect 重启 app 触发自动连接，然后照常进 ③；
  #   不在 ③ 超时后二次重启（会掩盖产品侧断连根因、把失败拖到 360s）。
  # 行号会随布局改动漂移，故本段不写死 CarLaunchScreen.kt 的行号。
  local ip=${PHONE_IP:-}
  [ -n "$ip" ] || { log_err "PHONE_IP 未知（preflight 没跑？）"; return 1; }

  # ① 自动连接窗口（实测 2~5s 进 CONNECTING；给 30s 容 WiFi 慢发现）
  # 两个窗口提为环境变量只为离线单测可置 0；生产默认值 30 / 180 一字不变。
  # st 显式初始化为 UNKNOWN：0 窗口下 set -u 不会因未绑定变量退出。
  local auto_wait_s=${E2E_CONNECT_AUTO_WAIT_S:-30}
  local settle_wait_s=${E2E_CONNECT_SETTLE_WAIT_S:-180}
  local st=UNKNOWN t0=$SECONDS n=0 deadline=$((SECONDS + auto_wait_s))
  while [ "$SECONDS" -lt "$deadline" ]; do
    st=$(car_state_now)
    case "$st" in
      STREAMING|CONNECTED|CONNECTING) break ;;
    esac
    n=$((n + 1))
    # 每 ~6s 探一次 B 的调试授权框（A 首连 B:5555 时弹；不点掉会永远卡 CONNECTING）
    [ $((n % 3)) -eq 0 ] && { accept_auth_dialog "$PHONE_SERIAL" 3 > /dev/null 2>&1 || true; }
    sleep 2
  done

  case "$st" in
    STREAMING|CONNECTED|CONNECTING)
      log_step "A 已自动进入连接流程（car_state=$st，耗时 $((SECONDS - t0))s）——跳过手动 UI 输入" ;;
    *)
      # ② 手动 UI 兜底（自动发现失败；A 上 CarLaunchScreen 的 ManualConnectBox）
      # issue #173 PA：把原先内联的 UI 驱动抽到 _connect_manual_ui；不可达时不再直接
      # 失败，改用 _connect_restart_fallback 走「重启 app」原语，然后照常进 ③ 收敛。
      # 保住 UI 主路径（mock 回放的 S01 靠它建流）与记账不变量（不引入 bad 记账）。
      log_step "A 未自动连接（car_state=$st）——走手动 UI 输入（ip=$ip）"
      _connect_manual_ui "$ip" || _connect_restart_fallback "$st" || return 1 ;;
  esac

  # ③ 收敛到 STREAMING（授权框轮询 + 状态等待）
  # 2026-10-17 批次 B 二跑修正：90s → 180s。建流后的黑屏误报风暴是**每会话必发**
  # 的（重建 ×2，每次重建 = 重新握手 + 重新部署引擎 16s），叠加首次部署后
  # 最坏情况 ~110s（实测 freeze 时刻 car_state=STREAMING 只差几秒——90s 窗口
  # 卡在第三次部署完成前）。180s 覆盖「部署 + 风暴 ×2 + 波动」。
  local deadline2=$((SECONDS + settle_wait_s))
  while [ "$SECONDS" -lt "$deadline2" ]; do
    [ "$(car_state_now)" = "STREAMING" ] && break
    accept_auth_dialog "$PHONE_SERIAL" 6 > /dev/null 2>&1 || true
    sleep 2
  done
  st=$(car_state_now)
  if [ "$st" = "STREAMING" ]; then log_ok "A→B 会话已建立（STREAMING）"; return 0; fi
  log_err "op_connect 超时：car_state=$st（≠STREAMING）"
  return 1
}

op_reconnect() { # A 侧重连原语：重启 app 触发启动路径自动连接（2026-10-17 实证）
  # 背景：A 的「手动连接」UI 在真机上不可达（ManualConnectBox 竖屏超高被裁、无滚动
  # 容器；P0.5 §F 3 / S19 实证），且 Eject（User disconnect）后产品明确
  # 「will not auto-reconnect」（CarConnectionService:1365，userDisconnected 持久化）
  # ——重连唯一可靠路径 = app 重启（启动路径自动连接；实测 ~5s 进 CONNECTING、
  # ~11s 控制连接、~18s STREAMING，见 S08 微验证）。
  # 语义：只做「重启动作」，**不等收敛**——调用方自行 wait_state_eq。竞态类场景
  # （S08/S20）依赖「不等收敛」让新会话部署与旧引擎 teardown（~6s）交叠。
  dcar 'am force-stop com.dilinkauto.server' > /dev/null 2>&1
  sleep 2
  dcar 'monkey -p com.dilinkauto.server -c android.intent.category.LAUNCHER 1' > /dev/null 2>&1
  return 0
}

op_disconnect() {
  # A 的 nav bar：Eject 钮（PersistentNavBar，Icon contentDescription = label）。
  # content-desc 随语言变：默认「Eject」/ 中文「断开连接」——三种都试，再退文本，
  # 最后才是比例坐标（2026-10-16 修：原先只认 "Eject"，中文 ROM 上必走兜底）。
  local ui; ui=$(_a_dump) || return 1
  local eb cand
  for cand in "Eject" "断开连接" "Disconnect"; do
    eb=$(_bounds_of_desc "$ui" "$cand")
    [ -n "$eb" ] && break
  done
  [ -z "$eb" ] && eb=$(_bounds_of_text "$ui" "Eject")
  [ -z "$eb" ] && eb=$(_bounds_of_text "$ui" "断开连接")
  if [ -z "$eb" ]; then
    # 兜底：横屏左轨底部 / 竖屏底栏左侧（Eject 在两种布局都靠「下/左」端）
    local w W H
    w=$(dcar wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
    W=${w%x*}; H=${w#*x}
    if [ -n "$W" ] && [ "$W" -gt "$H" ]; then
      dcar input tap $((W * 4 / 100)) $((H * 85 / 100)) > /dev/null 2>&1
    else
      dcar input tap $((W * 16 / 100)) $((H * 96 / 100)) > /dev/null 2>&1
    fi
    log_step "Eject 按钮 dump 未解析出，已按比例坐标兜底点击"
  else
    _tap_bounds "$CAR_SERIAL" "$eb"
  fi
  return 0
}

op_rotate() {
  # B：关 accelerometer + 翻 user_rotation（to=0|1，缺省翻转）
  local cur to
  cur=$(dphone 'settings get system user_rotation' 2>/dev/null | tr -d '\r\n ')
  case "$cur" in ''|null|*[!0-9]*) cur=0 ;; esac
  if [ -n "$OP_TO" ]; then to=$OP_TO; else to=$((1 - cur)); fi
  dphone 'settings put system accelerometer_rotation 0' > /dev/null 2>&1 || true
  dphone "settings put system user_rotation $to" > /dev/null 2>&1
  log_step "B 旋转到 user_rotation=$to"
  return 0
}

op_wifi_off() {
  # 无线拓扑适配（2026-10-17 真机微验证，见 debug-logs/e2e/drafts-20261017/）：
  # B 的 WiFi 一断，PC→B 的 adb 即断（offline），「再开」命令发不出去。故关之前
  # 先在 B 上预置 nohup 自恢复任务（8s 主 + 25s 保险，幂等），再由 cmd wifi 关闭。
  # 注意：svc wifi disable 在 MEIZU Android 15 上实测不生效（WiFi 保持 enabled），
  # 必须用 cmd wifi 版（实测稳定断网 ≥30s）；nohup 后台任务经 marker 文件验证
  # 在 adb shell 会话退出后仍生效。
  dphone 'nohup sh -c "sleep 8; cmd wifi set-wifi-enabled enabled" >/dev/null 2>&1 & nohup sh -c "sleep 25; cmd wifi set-wifi-enabled enabled" >/dev/null 2>&1 & echo ok' > /dev/null 2>&1
  dphone 'cmd wifi set-wifi-enabled disabled' > /dev/null 2>&1
  log_step "B WiFi 已关（cmd wifi；已预置 B 侧 8s/25s 自恢复任务）"
  return 0
}
op_wifi_on()  {
  # 无线拓扑下本 op 执行时 adb 大概率仍断（8s 恢复任务未到/重关联中）——尝试下发
  # 即可，失败不判负：B 侧自恢复任务兜底（幂等）。
  dphone 'cmd wifi set-wifi-enabled enabled' > /dev/null 2>&1
  log_step "B WiFi 已开（cmd wifi；adb 未回时由自恢复任务兜底）"
  return 0
}

op_airplane() {
  local cur to
  cur=$(dphone 'settings get global airplane_mode_on' 2>/dev/null | tr -d '\r\n ')
  case "$cur" in ''|null|*[!0-9]*) cur=0 ;; esac
  if [ -n "$OP_TO" ]; then to=$OP_TO; else to=$((1 - cur)); fi
  if [ "$to" = "1" ]; then
    # 无线拓扑适配（同 op_wifi_off）：飞行模式断无线电 → PC→B 的 adb 断。预置
    # nohup 自恢复任务：8s 关飞行 + 开 WiFi（部分 ROM 关飞行后不自动恢复 WiFi，
    # 显式 enable 幂等），25s 保险再补一次。
    dphone 'nohup sh -c "sleep 8; settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false; cmd wifi set-wifi-enabled enabled" >/dev/null 2>&1 & nohup sh -c "sleep 25; settings put global airplane_mode_on 0; cmd wifi set-wifi-enabled enabled" >/dev/null 2>&1 & echo ok' > /dev/null 2>&1
  fi
  dphone "settings put global airplane_mode_on $to" > /dev/null 2>&1
  dphone "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $([ "$to" = "1" ] && echo true || echo false)" > /dev/null 2>&1
  log_step "B 飞行模式=$to（settings + 广播双写）"
  return 0
}

op_kill_engine() {
  # pgrep + kill -9（禁 pkill -f）。SIGTERM 对 app_process 无效（ART 直接终止不跑
  # shutdown hook）——本 op 就是测「引擎被残忍杀死」的产品行为。
  local p killed=0
  for p in $(dphone 'pgrep -f "[P]ipelineServer"' 2>/dev/null | tr -d '\r'); do
    dphone "kill -9 $p" > /dev/null 2>&1 && killed=1
    op_log "[op kill_engine] pid=$p"
  done
  [ "$killed" = "1" ] && { log_step "引擎已 kill -9"; return 0; }
  log_err "pgrep 未找到引擎（本就没起来？）"
  return 1
}
op_kill_client() { dphone 'am force-stop com.dilinkauto.client' > /dev/null 2>&1; log_step "B client 已 force-stop"; return 0; }
op_kill_server() { dcar   'am force-stop com.dilinkauto.server' > /dev/null 2>&1; log_step "A server 已 force-stop"; return 0; }

op_reinstall() {
  [ -f "$E2E_CLIENT_APK" ] || { log_err "找不到 $E2E_CLIENT_APK"; return 2; }
  log_step "重装 B 的 client（install -r 会先停 app；START_STICKY ~18s 后才可能重启）"
  timeout 180 adb -s "$PHONE_SERIAL" install -r "$E2E_CLIENT_APK" > /dev/null 2>&1
  local rc=$?
  [ "$rc" = "0" ] || { log_err "install -r 失败（rc=$rc）"; return 1; }
  # 确定性策略：先 force-stop 压掉 START_STICKY 的重启，再 monkey 单次拉起
  dphone 'am force-stop com.dilinkauto.client' > /dev/null 2>&1
  op_launch_client
}

op_screen_off() {
  # cmd display power-off 0：DisplayManager 层强制电源管理，绕过 screen_off_timeout
  # 哨兵（risk R09）——物理屏真黑，VD 存活改看 vdlog 帧计数（S13 禁用 screencap 断言）。
  dphone 'cmd display power-off 0' > /dev/null 2>&1
  log_step "B 物理屏已熄灭（power-off 0）"
  return 0
}
op_screen_on() {
  # power-reset 点亮（power-on 不存在；写 power-on 会 Unknown command 且留黑屏）
  dphone 'cmd display power-reset 0' > /dev/null 2>&1
  sleep 1
  dphone input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
  log_step "B 物理屏已点亮（power-reset 0 + WAKEUP）"
  return 0
}

op_switch_app() {
  # A：点 Launcher 页签 → 应用列表 → 点第 N 个 app（OP_INDEX，缺省 1；OP_PKG 指定时按名字找）
  # 2026-10-16 修：当前 app-server 流式 UI 直接展示应用网格，导航栏只有
  # Home/Back/Eject（PersistentNavBar.kt 的 NavAction 枚举实证，nav_launcher 串是
  # 无引用残留）——找不到 Launcher 页签时不再返回 1，直接在当前 UI 上选磁贴。
  local ui nb
  ui=$(_a_dump) || return 1
  nb=$(_bounds_of_text "$ui" "Launcher")
  [ -z "$nb" ] && nb=$(_bounds_of_desc "$ui" "Launcher")
  [ -z "$nb" ] && nb=$(_bounds_of_text "$ui" "主页")
  [ -z "$nb" ] && nb=$(_bounds_of_desc "$ui" "主页")
  if [ -n "$nb" ]; then
    _tap_bounds "$CAR_SERIAL" "$nb"
    sleep 2
    ui=$(_a_dump) || return 1
  else
    log_step "未找到 Launcher/主页 页签（当前 UI 直接是应用网格）——直接选磁贴"
  fi
  local target=""
  if [ -n "$OP_PKG" ]; then
    target=$(_bounds_of_text "$ui" "$OP_PKG")
    [ -z "$target" ] && target=$(_bounds_of_desc "$ui" "$OP_PKG")
  fi
  if [ -z "$target" ]; then
    # 应用网格磁贴解析（2026-10-17 真机校准，同 _grid_tile_bounds）：真机 dump 是
    # **单行** XML + 磁贴父节点 text/content-desc 均为空串（文本在子节点）——旧
    # 「按行 grep + text 过滤」在真机上恒失败（S15 P1a 实证）。位置判据：
    # clickable + 非 EditText + y1<800 顶部网格区，按 (y1,x1) 排序取第 OP_INDEX 个。
    target=$(_grid_tile_bounds "$ui" "${OP_INDEX:-1}")
  fi
  if [ -n "$target" ]; then
    _tap_bounds "$CAR_SERIAL" "$target"
    log_step "已在 VD 上启动第 ${OP_INDEX} 个 app（pkg=${OP_PKG:-自动选择}）"
    return 0
  fi
  # 兜底：应用网格首行磁贴中心比例坐标（2026-10-17 实测 4 磁贴中心 x≈16%/
  # 40%/64%/87%、y≈11%；index>1 每列 +24%，clamp 到 87%——旧 x=15%/y=25% 的
  # y 落在磁贴区（136~398）外，S15 P1a 实证兜底 tap 落空、画面无变化）
  local w W H cx idx
  idx=${OP_INDEX:-1}
  w=$(dcar wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
  W=${w%x*}; H=${w#*x}
  if [ -n "$W" ] && [ -n "$H" ]; then
    cx=$((W * (16 + (idx - 1) * 24) / 100))
    [ "$cx" -gt $((W * 87 / 100)) ] && cx=$((W * 87 / 100))
    dcar input tap "$cx" $((H * 11 / 100)) > /dev/null 2>&1 || true
  fi
  log_step "应用列表解析失败，已按比例坐标兜底（x≈16%+24%×(n-1) y≈11%）"
  return 0
}

op_ime_open() {
  # S14：在 VD 上打开带输入框的 app（OP_PKG 指定，缺省 dialer），点输入框区域。
  OP_PKG="${OP_PKG:-com.android.dialer}"
  op_switch_app || true
  sleep 2
  local w W H
  w=$(dcar wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
  W=${w%x*}; H=${w#*x}
  # 镜像区上部（拨号盘输入区在顶部；横屏时避开左轨）
  if [ -n "$W" ] && [ "$W" -gt "$H" ]; then
    dcar input tap $((W * 70 / 100)) $((H * 18 / 100)) > /dev/null 2>&1
  else
    dcar input tap $((W * 50 / 100)) $((H * 15 / 100)) > /dev/null 2>&1
  fi
  log_step "已点开 VD 上 app 的输入区（IME 应在 VD 弹出；键盘行宽 = 手机物理宽）"
  return 0
}

op_usb_pull() {
  # 物理拔 USB（S07 专用）。PHYSICAL_OPS != on → 2（场景应已被标记 SKIP）
  if [ "${PHYSICAL_OPS:-off}" != "on" ]; then
    log_err "usb_pull 被调用但 PHYSICAL_OPS=off——@manual 场景应已被标记 SKIP"
    return 2
  fi
  local serial=${OP_LABEL:-$PHONE_SERIAL}
  echo
  echo "  ┌────────────────────────────────────────────────────────────┐"
  echo "  │ 人工操作：请现在拔掉 $serial 的 USB 线，然后……              │"
  echo "  └────────────────────────────────────────────────────────────┘"
  if [ "${AUTO_WAIT:-0}" -gt 0 ]; then
    log_step "无人值守模式：${AUTO_WAIT}s 后自动继续（赌人工已拔）"
    sleep "$AUTO_WAIT"
  else
    read -r -t 600 -p "  拔线后按回车继续（600s 超时）：" _ || { log_err "等待人工确认超时"; return 1; }
  fi
  # 验证 USB 通道真的没了（防没拔就回车造成假阳性）
  if [ "$(adb -s "$serial" get-state 2>/dev/null | tr -d '\r\n')" = "device" ]; then
    log_err "$serial 的 USB 通道仍在——没拔线？拒绝继续"
    return 1
  fi
  log_ok "$serial USB 通道已断开"
  return 0
}

op_adb_revoke() {
  # S22：B 上撤销 USB 调试授权（开发者选项 UI 驱动，best-effort）。
  # 之后 B 对 PC 变 unauthorized——只能人工恢复，脚本不得尝试自动重新授权（risk R07/R11）。
  dphone 'am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS' > /dev/null 2>&1
  sleep 2
  local ui found="" i
  for i in 1 2 3 4 5; do
    ui=$(timeout 10 adb -s "$PHONE_SERIAL" shell "uiautomator dump /sdcard/ui_revoke.xml && cat /sdcard/ui_revoke.xml" 2>/dev/null | tr -d '\r')
    found=$(echo "$ui" | grep -oE 'text="[^"]*(撤销|Revoke)[^"]*"' | head -1)
    [ -n "$found" ] && break
    # 下滑一屏再找
    dphone input touchscreen swipe 500 1800 500 600 100 > /dev/null 2>&1
    sleep 1.5
  done
  if [ -z "$found" ]; then
    log_err "开发者选项里找不到「撤销 USB 调试授权」（ROM 差异）——S22 记 SKIPPED(env)"
    return 2
  fi
  local b
  b=$(echo "$ui" | grep -oE "text=\"[^\"]*\(撤销\|Revoke\)[^\"]*\"[^>]*bounds=\"\[[0-9,]+\]\[[0-9,]+\]\"" | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  [ -n "$b" ] && _tap_bounds "$PHONE_SERIAL" "$b"
  sleep 1.5
  # 确认对话框（若有）
  local ui2 cb
  ui2=$(timeout 10 adb -s "$PHONE_SERIAL" shell "uiautomator dump /sdcard/ui_revoke.xml && cat /sdcard/ui_revoke.xml" 2>/dev/null | tr -d '\r')
  cb=$(_bounds_of_text "$ui2" "确定")
  [ -z "$cb" ] && cb=$(_bounds_of_text "$ui2" "Revoke")
  [ -n "$cb" ] && _tap_bounds "$PHONE_SERIAL" "$cb"
  dphone 'rm -f /sdcard/ui_revoke.xml' > /dev/null 2>&1 || true
  log_step "已点击撤销授权——B 对 PC 将变 unauthorized，需人工恢复（S22 之后无场景）"
  return 0
}

op_occupy_9637() {
  # S21：用 toybox nc 占住 B 的 9637（client 未起时端口应空闲）
  # 2026-10-17 修正（S21 首跑 UNEXPECTED-PASS 根因）：旧参数 `-lp` 是**单连接**模式
  # （toybox nc 帮助原文：-l = "Listen for one incoming connection, then exit"）——
  # A 的控制轨首次连接（nc 无协议响应，A 10s watchdog 断开）就把 nc 耗退了，随后
  # A 的 TCP ADB 轨 am start 拉起 client 时 9637 已空闲 → client bind 成功 → 建流
  # STREAMING（实测 07:50：nc pid=18414 消失、9637 属主变为 app）。改用 -L
  # （"Listen and background each incoming connection"）持续监听，端口被真实占死。
  if [ "$(listening_on "$PHONE_SERIAL" 9637)" = "Y" ]; then
    log_err "9637 已被占（上轮残留？先 op_neutral）"
    return 1
  fi
  dphone 'am force-stop com.dilinkauto.client' > /dev/null 2>&1
  sleep 1
  local pid
  pid=$(dphone 'setsid toybox nc -Lp 9637 >/dev/null 2>&1 & echo $!' 2>/dev/null | tr -d '\r\n ')
  sleep 2
  if [ "$(listening_on "$PHONE_SERIAL" 9637)" = "Y" ]; then
    echo "$pid" > /dev/null
    dphone "echo $pid > /sdcard/DiLinkAuto/e2e-occupy.pid" > /dev/null 2>&1 || true
    op_log "[op occupy_9637] pid=$pid"
    log_ok "9637 已被 toybox nc 占用（pid=$pid）"
    return 0
  fi
  log_err "无法占用 9637（toybox 无 nc？）——S21 记 SKIPPED(env)"
  return 2
}

# ── 分发器 ──────────────────────────────────────────────────────────────
run_op() { # <id> [k=v...]
  local id=$1; shift
  local t0 rc
  # 参数解析（白名单键）
  while [ $# -gt 0 ]; do
    case "$1" in
      to=*)      OP_TO=${1#*=} ;;
      index=*)   OP_INDEX=${1#*=} ;;
      pkg=*)     OP_PKG=${1#*=} ;;
      label=*)   OP_LABEL=${1#*=} ;;
      display=*) OP_DISPLAY=${1#*=} ;;
      *) log_step "run_op $id 忽略未知参数：$1" ;;
    esac
    shift
  done
  command -v "op_$id" > /dev/null 2>&1 || { log_err "未知 op：$id"; return 2; }
  t0=$SECONDS
  "op_$id"; rc=$?
  echo "{\"t\":\"$(date +%H:%M:%S)\",\"sid\":\"${E2E_SCENARIO_ID:-}\",\"op\":\"$id\",\"rc\":$rc,\"elapsed_s\":$((SECONDS - t0))}" >> "${E2E_OP_RESULT:-/dev/null}" 2>/dev/null || true
  return $rc
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  . "$(dirname "$0")/logging.sh"; . "$(dirname "$0")/devices.sh"; . "$(dirname "$0")/asserts.sh"
  : "${PHONE_SERIAL:?export PHONE_SERIAL}" "${CAR_SERIAL:?export CAR_SERIAL}"
  case "${1:-}" in
    # op_log 是内部记账 helper，不是 op（会污染 check-registry 集合比对）
    list) for f in $(compgen -A function | grep '^op_' | grep -v '^op_log$' | sort); do echo "${f#op_}"; done ;;
    run)  shift; run_op "$@" ;;
    *) echo "用法：bash ops.sh list | run <id> [k=v...]" >&2; exit 2 ;;
  esac
  exit $?
fi
