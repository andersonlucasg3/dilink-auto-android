#!/bin/bash
# 端到端实机回归：「应用并重连」+「关窗」的收尾清理（gracefulStop / 哨兵 / 预算）自动化验证。
#
# 把 2026-10-10 MI 9 实机手工跑过的整条验证链固化成一条命令。每一步都有断言，
# 单步失败不中断（继续收集证据），最后统一汇总；失败时自动把设备/窗口恢复到
# 中立状态，并保留全部现场日志。
#
# 流程（9 步）：
#   1  构建门：./gradlew test + 双 APK；断言「BUILD SUCCESSFUL + 单测 0 失败 0 错误」
#   2  APK 内嵌引擎校验：assets/vd-server.jar 的 classes.dex 必须含本轮修复的符号
#      （ShellSyncPoint / __DILINK_SYNC__ / awaitIdle / pgrep -f / stop-request）
#   3  安装 APK + monkey 拉起手机端，等 9637 LISTEN
#   4  设备基线（清引擎/哨兵/vd-server.log，screen_off_timeout 捕获原值后置基线）+ 启动桌面端
#   5  会话中核对：screen_off_timeout=2147483647（引擎哨兵已置）+ 引擎在位
#   6  UI 驱动「显示 → 应用并重连」：断言 探针 GONE / 引擎已优雅退出 / 无强制 kill /
#      重新 STREAMING / 新引擎 pid 变化 / 哨兵再次置上
#   7  UI 驱动关窗：断言 优雅退出 / [main] 已退出 / 窗口消失 / gradle run 进程退出
#   8  设备终态：screen_off_timeout 回到**设备原值**（不硬编码，见步骤 4 注释）+ 引擎消失
#   9  拉取 vd-server.log，跑 scripts/verify-reconnect-cleanup.sh 全套离线断言
#
# 用法：
#   bash scripts/e2e-reconnect-cleanup.sh --serial <adb序列号> [--skip-build] [--no-install]
#
# --serial 必填：双机/TCP 设备在册时自动挑选会挑错设备（把 PIN 输到别的机器上并
# 计入那台的 failsafe 计数，见 .plan/adb-unlock-for-e2e.md §5）。
#
# 前置条件：ADB 设备在线；Windows 桌面已登录（脚本会移动鼠标/点击窗口，跑的时候别用电脑）。
#
# 退出码 0 = 全部通过；1 = 有失败项（现场保留在 debug-logs/e2e/<时间戳>/）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SKIP_BUILD=0
DO_INSTALL=1
SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --no-install) DO_INSTALL=0 ;;
    --serial) SERIAL="${2:-}"; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）"; exit 2 ;;
  esac
  shift
done

# 产物目录：默认按时间戳；统一入口 scripts/e2e.sh 用 E2E_RUN_ID 指定子目录，
# 让一次「全量」运行的所有产物落在同一个 run 目录下（<run>/dual、<run>/desktop）。
TS="${E2E_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN="debug-logs/e2e/$TS"
mkdir -p "$RUN"

WIN_AUTO="$ROOT/debug-logs/uitest/win_auto.py"
WINTITLE="DiLink Desktop"
APK="$ROOT/app-client/build/outputs/apk/debug/app-client-debug.apk"
PASS=0
FAIL=0

step() { echo; echo "==================== [$(date +%H:%M:%S)] $* ===================="; }
info() { echo "  · $*"; }
ok()   { echo "  ✓ $*"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $*"; FAIL=$((FAIL + 1)); }
hr()   { printf '%s\n' "------------------------------------------------------------"; }
countF() { local n; n=$(grep -cF "$2" "$1" 2>/dev/null); echo "${n:-0}"; }
# 这台机器 fork/exec 很贵（杀软实时扫描，2026-10-10 实测每次 ~1s），所以所有
# 轮询循环都按**真实经过时间**（SECONDS）设预算，而不是"迭代次数 × sleep"——
# 后者在慢机器上会把 150s 预算跑成 10 分钟。
SHOT_TIMEOUT=""
command -v timeout > /dev/null 2>&1 && SHOT_TIMEOUT="timeout 45"
shot() { $SHOT_TIMEOUT python "$WIN_AUTO" shot "$RUN/fail-$1-$(date +%H%M%S).png" > /dev/null 2>&1 || true; }
# 窗口激活（RX/RY 由 step 6 填入）：win_auto 的 focus 走 SetForegroundWindow，会被
# Windows 前台锁定拒绝（实测 ok=False，且此时注入的点击会被吞掉）；而真实鼠标点击
# 不受前台锁定限制 —— 所以以「点标题栏中段」为可靠激活手段，focus 只作加分项。
activate() { python "$WIN_AUTO" click $((RX + 860)) $((RY + 20)) > /dev/null 2>&1 || true; sleep 0.8; }

wait_count() { # <file> <fixed-pattern> <target> <timeout_s>
  local f=$1 pat=$2 target=$3 t=${4:-60} n
  local deadline=$((SECONDS + t))
  while [ "$SECONDS" -lt "$deadline" ]; do
    n=$(countF "$f" "$pat")
    [ "$n" -ge "$target" ] && return 0
    sleep 0.5
  done
  return 1
}

# ── 设备小工具 ────────────────────────────────────────────────────────
dev() { adb -s "$SERIAL" shell "$@"; }
# 活动 WiFi IP：Meizu 是 wlan1、MI9 是 wlan0，不能写死网卡名。
wifi_ip() {
  local out iface
  out=$(dev 'ip -4 route show table all 2>/dev/null' 2>/dev/null | tr -d '\r')
  iface=$(printf '%s\n' "$out" | awk '$1=="default" {for(i=1;i<=NF;i++) if($i=="dev" && $(i+1) ~ /^wlan/) {print $(i+1); exit}}')
  if [ -z "$iface" ]; then
    out=$(dev 'ip -4 addr show 2>/dev/null' 2>/dev/null | tr -d '\r')
    iface=$(printf '%s\n' "$out" | awk '/^[0-9]+: wlan/ {gsub(":","",$2); cand=$2} /inet / {if(cand!=""){print cand; exit}}')
  fi
  [ -n "$iface" ] && dev "ip -4 addr show $iface 2>/dev/null" 2>/dev/null | tr -d '\r' | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -1
}
cur_timeout() { dev 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n '; }
engine_pid() { dev 'pgrep -f "[P]ipelineServer" | head -1' 2>/dev/null | tr -d '\r\n '; }
wait_timeout_eq() { # <value> <timeout_s>（SECONDS 预算：每次 adb 往返在本机约 1s，别按迭代次数算）
  local want=$1 deadline=$((SECONDS + ${2:-30}))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$(cur_timeout)" = "$want" ] && return 0
    sleep 0.5
  done
  return 1
}
wait_engine_gone() { # <timeout_s>
  local deadline=$((SECONDS + ${1:-30}))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ -z "$(engine_pid)" ] && return 0
    sleep 0.5
  done
  return 1
}

command -v adb >/dev/null 2>&1 || { echo "✗ 找不到 adb"; exit 1; }
command -v python >/dev/null 2>&1 || { echo "✗ 找不到 python（win_auto 与配置生成需要）"; exit 1; }
[ -f "$WIN_AUTO" ] || { echo "✗ 找不到 $WIN_AUTO"; exit 1; }

if [ -z "$SERIAL" ]; then
  echo "✗ 必须显式指定 --serial <adb序列号>（双机/TCP 设备在册时自动挑选会挑错设备，" >&2
  echo "  把 PIN 输到别的机器上并计入那台的 failsafe 计数，见 .plan/adb-unlock-for-e2e.md §5）" >&2
  echo "  当前在线设备：" >&2
  adb devices -l | awk 'NR>1 && $2=="device" { $1=""; sub(/^[ \t]+/,""); print "    " $0 }' >&2
  echo "  用法：bash $0 --serial <序列号>" >&2
  exit 2
fi
# 指定的 serial 必须在线且为 device 状态（防止打错/掉线后带着错误设备盲跑）
SSTATE=$(adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r\n')
if [ "$SSTATE" != "device" ]; then
  echo "✗ 设备 $SERIAL 不在线（state=${SSTATE:-无}）" >&2
  adb devices -l >&2
  exit 2
fi
echo "序列号：$SERIAL"
echo "产物目录：$RUN"

# ═════════════════════════════════════════════════════════════════════
step "1/9 构建门：./gradlew test + 双 APK"
if [ "$SKIP_BUILD" -eq 1 ]; then
  info "跳过（--skip-build）"
else
  info "执行中（可能数分钟）..."
  ./gradlew --console=plain test :app-client:assembleDebug :app-server:assembleDebug \
    > "$RUN/build.log" 2>&1
  BRC=$?
  if [ "$BRC" -eq 0 ]; then
    ok "BUILD SUCCESSFUL"
  else
    bad "构建失败（rc=$BRC，见 $RUN/build.log）"
    tail -25 "$RUN/build.log"
  fi
  if [ "$BRC" -eq 0 ]; then
    T=$(find . -path "*/build/test-results/*" -name '*.xml' -exec grep -hoE 'tests="[0-9]+"' {} + 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {printf "%d", s+0}')
    FF=$(find . -path "*/build/test-results/*" -name '*.xml' -exec grep -hoE 'failures="[0-9]+"' {} + 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {printf "%d", s+0}')
    EE=$(find . -path "*/build/test-results/*" -name '*.xml' -exec grep -hoE 'errors="[0-9]+"' {} + 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {printf "%d", s+0}')
    if [ "${T:-0}" -gt 0 ] && [ "${FF:-1}" -eq 0 ] && [ "${EE:-1}" -eq 0 ]; then
      ok "单测 $T 个，0 失败 0 错误"
    else
      bad "单测异常：total=$T failures=$FF errors=$EE"
    fi
  fi
fi

# ═════════════════════════════════════════════════════════════════════
step "2/9 APK 内嵌引擎校验（本轮修复的符号必须进 dex）"
if [ ! -f "$APK" ]; then
  bad "找不到 $APK（先去掉 --skip-build 构建）"
else
  CHECK=$(python - "$APK" <<'PY'
import sys, zipfile, io
need = [b"ShellSyncPoint", b"__DILINK_SYNC__", b"awaitIdle", b"pgrep -f", b"stop-request"]
try:
    apk = zipfile.ZipFile(sys.argv[1])
    jar = zipfile.ZipFile(io.BytesIO(apk.read("assets/vd-server.jar")))
    dex = jar.read("classes.dex")
except Exception as e:
    print("ERROR:", e)
    sys.exit(0)
missing = [n.decode() for n in need if n not in dex]
print(f"dex={len(dex)} issues=" + (",".join(missing) if missing else "NONE"))
PY
)
  echo "     $CHECK"
  case "$CHECK" in
    *"issues=NONE"*) ok "classes.dex 含全部修复符号" ;;
    *) bad "dex 校验失败：$CHECK" ;;
  esac
fi

# ═════════════════════════════════════════════════════════════════════
step "3/9 安装 APK + 重启手机端，等 9637 LISTEN"
if [ "$DO_INSTALL" -eq 1 ]; then
  if [ -f "$APK" ]; then
    IOUT=$(adb -s "$SERIAL" install -r "$APK" 2>&1)
    if echo "$IOUT" | grep -q "Success"; then
      ok "install -r Success"
    else
      bad "安装失败：$(echo "$IOUT" | tail -2 | tr '\n' ' ')"
    fi
  else
    bad "无 APK 可装"
  fi
else
  info "跳过安装（--no-install）"
fi
adb -s "$SERIAL" shell monkey -p com.dilinkauto.client -c android.intent.category.LAUNCHER 1 \
  > /dev/null 2>&1
T3=$SECONDS
LISTEN=0
while [ $((SECONDS - T3)) -lt 90 ]; do
  if [ "$(dev 'if ss -ltn 2>/dev/null | grep -q ":9637"; then echo Y; else echo N; fi' | tr -d '\r\n ')" = "Y" ]; then
    LISTEN=1
    break
  fi
  sleep 0.5
done
if [ "$LISTEN" -eq 1 ]; then
  ok "客户端已在 9637 监听（等待 $((SECONDS - T3))s）"
else
  bad "等待 90s 后 9637 仍未监听"
fi

# ═════════════════════════════════════════════════════════════════════
step "4/9 设备基线 + 启动桌面端"
LEFTOVER=$(engine_pid)
if [ -n "$LEFTOVER" ]; then
  dev 'for p in $(pgrep -f "[P]ipelineServer"); do kill -9 $p; done; exit 0'
  sleep 1
  info "已清理残留引擎（原 pid=$LEFTOVER）"
fi
dev 'rm -f /sdcard/DiLinkAuto/stop-request'
dev 'rm -f /sdcard/DiLinkAuto/vd-server.log'
# 基线只要求「有辨识度且不等于引擎哨兵 2147483647」。原先硬编码 300000 会把设备
# 原值永久改掉（2026-10-17 发现：MI 9 原值 1800000，跑一次桌面端套件就被改成 300000）。
# 改为先捕获原值，基线设置与终态断言都按原值走；原值异常（空/非数字/哨兵）才回落 300000。
ORIG_TIMEOUT=$(cur_timeout)
case "$ORIG_TIMEOUT" in
  ""|*[!0-9]*|2147483647) ORIG_TIMEOUT=300000 ;;
esac
info "设备原 screen_off_timeout=$ORIG_TIMEOUT（终态按原值恢复）"
dev "settings put system screen_off_timeout $ORIG_TIMEOUT"
sleep 0.5
if [ "$(cur_timeout)" = "$ORIG_TIMEOUT" ]; then
  ok "基线 screen_off_timeout=$ORIG_TIMEOUT 就绪（设备原值，区别于引擎哨兵 2147483647）"
else
  bad "基线设置失败，当前=$(cur_timeout)（期望 $ORIG_TIMEOUT）"
fi

export DILINK_DESKTOP_HOME="$ROOT/$RUN/desktop-home"
mkdir -p "$DILINK_DESKTOP_HOME"
cp "$APPDATA/DiLinkAuto/config.json" "$DILINK_DESKTOP_HOME/config.json" 2>/dev/null || true
PHONE_IP=$(wifi_ip)
CFG=$(python - "$DILINK_DESKTOP_HOME/config.json" "$PHONE_IP" <<'PY'
import json, sys
dst, ip = sys.argv[1], sys.argv[2].strip()
try:
    cfg = json.load(open(dst, encoding="utf-8"))
except Exception:
    cfg = {}
if ip:
    cfg["dev_phone_ip"] = ip          # 以 adb 实测活动 WiFi IP 为准（Meizu=wlan1 / MI9=wlan0）
cfg["dev_mode"] = True                # ADB 部署路径是本机（无 Shizuku）主路径
cfg["log_enabled"] = True
cfg.setdefault("startup_fps", 24)
cfg.setdefault("startup_bitrate", 4000000)
json.dump(cfg, open(dst, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
print(cfg.get("dev_phone_ip", ""))
PY
)
if [ -n "$CFG" ]; then
  ok "桌面端配置就绪（dev_phone_ip=$CFG，隔离目录 $RUN/desktop-home）"
else
  bad "无法确定手机 IP（活动 WiFi 网卡未取到 IP 且 config 无值）——桌面端会直接退出"
fi

if python "$WIN_AUTO" rect "$WINTITLE" > /dev/null 2>&1; then
  info "检测到已有 DiLink Desktop 窗口，先关闭（多实例会抢连接）"
  python "$WIN_AUTO" close "$WINTITLE" > /dev/null 2>&1
  TO=$SECONDS
  while [ $((SECONDS - TO)) -lt 30 ]; do
    python "$WIN_AUTO" rect "$WINTITLE" > /dev/null 2>&1 || break
    sleep 0.5
  done
  if python "$WIN_AUTO" rect "$WINTITLE" > /dev/null 2>&1; then
    bad "旧窗口 30s 内未关闭，无法继续（请手动关闭后重跑）"
  else
    ok "旧窗口已关闭"
  fi
fi

DLOG="$DILINK_DESKTOP_HOME/desktop.log"
info "启动桌面端（./gradlew :app-desktop:run，日志见 $RUN/desktop-console.log）..."
( ./gradlew --console=plain :app-desktop:run > "$RUN/desktop-console.log" 2>&1 ) &
GRADLE_PID=$!
if wait_count "$DLOG" 'state -> STREAMING' 1 240; then
  ok "会话建立（state -> STREAMING）"
else
  bad "240s 内未到 STREAMING（看 $RUN/desktop-console.log 与 desktop.log）"
  shot "no-streaming"
fi

# ═════════════════════════════════════════════════════════════════════
step "5/9 会话中核对"
if wait_timeout_eq 2147483647 30; then
  ok "引擎已把 screen_off_timeout 置为哨兵 2147483647"
else
  bad "会话中 screen_off_timeout=$(cur_timeout)，应为 2147483647"
fi
EPID1=$(engine_pid)
if [ -n "$EPID1" ]; then
  ok "引擎在位（pid=$EPID1）"
else
  bad "pgrep 找不到引擎"
fi

# ═════════════════════════════════════════════════════════════════════
# 黑屏自愈风暴静默（2026-10-17 MEIZU 真机实测）
#
# 桌面端与车机端共用 BlackScreenDetector：当 VD 内容本身就是黑的（空 launcher /
# 手机屏熄灭）时，检测器会连发「疑似持续黑屏 → 自动重连」，每次自动重连都产生
# 一次「优雅停止设备侧引擎」。本进程上限 2 次（DesktopApp 的 MAX_BLACK_SCREEN_RECONNECTS）。
#
# 不等它静默就点「应用并重连」，会把自愈的停止算进「点击触发的停止」——实测首跑：
# 断言 1→3（期望 +1）误判，实际点击只触发 1 次，另 2 次是自愈。
# 静默判据：连续 12s 无新增自愈；上限 90s。
# ═════════════════════════════════════════════════════════════════════
# 观察窗口：检测器的 sustain 窗口是 10s，第一次自愈通常出现在 STREAMING 后 10~15s。
# 必须等过这个窗口再点，否则「点击窗口」和「自愈窗口」重叠（首跑就是这样误判的）。
info "观察黑屏自愈窗口（15s）..."
sleep 15
ARW0=$(countF "$DLOG" '疑似持续黑屏：自动重连')
if [ "$ARW0" -gt 0 ]; then
  info "黑屏自愈已触发 $ARW0 次，等待静默（≤90s）..."
  TQ=$SECONDS; _last=$ARW0
  while [ $((SECONDS - TQ)) -lt 90 ]; do
    _cur=$(countF "$DLOG" '疑似持续黑屏：自动重连')
    if [ "$_cur" != "$_last" ]; then _last=$_cur; TQ=$SECONDS; fi
    [ $((SECONDS - TQ)) -ge 12 ] && break
    sleep 2
  done
  ARW1=$(countF "$DLOG" '疑似持续黑屏：自动重连')
  info "黑屏自愈共 $ARW1 次（上限 2，已知产品行为）——停止计数按「1 + 新增自愈次数」核账"
fi

# ═════════════════════════════════════════════════════════════════════
step "6/9 UI 驱动「显示 → 应用并重连」"
LOG="$DLOG"
UI_OK=1
RX=0; RY=0
RL=""
T6=$SECONDS
while [ $((SECONDS - T6)) -lt 30 ]; do
  RL=$(python "$WIN_AUTO" rect "$WINTITLE" 2>/dev/null | head -1)
  [ -n "$RL" ] && break
  sleep 0.5
done
if [ -z "$RL" ]; then
  bad "找不到窗口（标题含 \"$WINTITLE\"）"
  UI_OK=0
else
  read -r RHWND RX RY RW RH _REST <<< "$(echo "$RL" | tr '\t' ' ')"
  info "窗口矩形：x=$RX y=$RY w=$RW h=$RH"
  if [ "$RW" -gt 2000 ]; then
    info "窗口疑似全屏（w=$RW），发 F11 还原"
    python "$WIN_AUTO" focus "$WINTITLE" > /dev/null 2>&1
    python "$WIN_AUTO" key 0x7A
    sleep 1.2
    RL=$(python "$WIN_AUTO" rect "$WINTITLE" 2>/dev/null | head -1)
    read -r RHWND RX RY RW RH _REST <<< "$(echo "$RL" | tr '\t' ' ')"
    info "F11 后：x=$RX y=$RY w=$RW h=$RH"
  fi
  if [ "$RW" -ge 1704 ] && [ "$RW" -le 1736 ] && [ "$RH" -ge 884 ] && [ "$RH" -le 916 ]; then
    ok "窗口尺寸符合校准基准（1720x900 ±16）"
  else
    bad "窗口尺寸 $RWx$RH 偏离校准基准 1720x900，拒绝盲点击"
    UI_OK=0
    shot "rect-mismatch"
  fi
fi

if [ "$UI_OK" -eq 1 ]; then
  activate
  if python "$WIN_AUTO" focus "$WINTITLE" 2>/dev/null | grep -q "ok=True"; then
    ok "窗口已置前（focus ok=True）"
  else
    info "focus 被前台锁定拒绝（已用点标题栏兜底，点击仍有效）"
  fi
  S0=$(countF "$LOG" '优雅停止设备侧引擎')
  AR0=$(countF "$LOG" '疑似持续黑屏：自动重连')
  GR0=$(countF "$LOG" '引擎已优雅退出')
  GO0=$(countF "$LOG" '探针 GONE @+')
  HS0=$(countF "$LOG" 'handshake accepted:')
  ST0=$(countF "$LOG" 'state -> STREAMING')
  # 一次「显示 → 应用并重连」点击序列；成功判据 = desktop.log 出现新的「优雅停止设备侧引擎」。
  # 这是「点击确实被应用吃到」最直接的证据（比截图目视可靠）。
  reconnect_once() {
    python "$WIN_AUTO" click $((RX + 67)) $((RY + 184)) > /dev/null 2>&1 || true   # 左栏「显示」
    sleep 1.5
    python "$WIN_AUTO" click $((RX + 210)) $((RY + 542)) > /dev/null 2>&1 || true  # 「应用并重连」
    wait_count "$LOG" '优雅停止设备侧引擎' $((S0 + 1)) 150
  }
  RC_RETRIED=0
  info "点击「显示 → 应用并重连」（第 1 次）..."
  if reconnect_once; then
    ok "重连已触发收尾（出现新的「优雅停止设备侧引擎」）"
  else
    info "第 1 次点击 150s 内未见优雅停止，激活窗口后重试一次..."
    shot "reconnect-attempt1"
    activate
    RC_RETRIED=1
    if reconnect_once; then
      ok "重试后重连已触发收尾"
    else
      bad "两次点击均未触发重连（各等 150s）"
      shot "no-reconnect"
    fi
  fi
  if wait_count "$LOG" 'state -> STREAMING' $((ST0 + 1)) 150; then
    ok "重连后重新回到 STREAMING"
  else
    bad "150s 内未重新 STREAMING"
    shot "no-restream"
  fi
  S1=$(countF "$LOG" '优雅停止设备侧引擎')
  AR1=$(countF "$LOG" '疑似持续黑屏：自动重连')
  # 本窗口内新增的黑屏自愈次数：每次自愈也会产生一次优雅停止，必须单独入账，
  # 否则「点击只触发 1 次」的断言会把自愈算成点击的副作用（2026-10-17 实测）。
  AR_DELTA=$((AR1 - AR0))
  GR1=$(countF "$LOG" '引擎已优雅退出')
  GO1=$(countF "$LOG" '探针 GONE @+')
  F1=$(countF "$LOG" '强制 kill')
  HS1=$(countF "$LOG" 'handshake accepted:')
  if [ "$RC_RETRIED" -eq 1 ]; then
    [ "$S1" -ge $((S0 + 1 + AR_DELTA)) ] && ok "重连触发优雅停止（$S0→$S1；走过重试，只查下界）" || bad "重连未触发优雅停止（$S0→$S1）"
  elif [ "$S1" -eq $((S0 + 1 + AR_DELTA)) ]; then
    if [ "$AR_DELTA" -gt 0 ]; then
      ok "重连触发 1 次优雅停止（$S0→$S1；其中黑屏自愈 ×$AR_DELTA）"
    else
      ok "重连触发 1 次优雅停止（$S0→$S1）"
    fi
  else
    bad "优雅停止次数 $S0→$S1（期望 +1$( [ "$AR_DELTA" -gt 0 ] && echo " + 黑屏自愈 ×$AR_DELTA" )）"
  fi
  [ "$GR1" -ge $((GR0 + 1)) ] && ok "新增「引擎已优雅退出」（$GR0→$GR1）" || bad "未见新的「引擎已优雅退出」（$GR0→$GR1）"
  [ "$GO1" -ge $((GO0 + 1)) ] && ok "新增探针 GONE（$GO0→$GO1）" || bad "未见新探针 GONE（$GO0→$GO1）"
  [ "$F1" -eq 0 ] && ok "全程无强制 kill" || bad "出现 $F1 次强制 kill"
  [ "$HS1" -ge $((HS0 + 1)) ] && ok "二次握手完成（$HS0→$HS1）" || bad "重连未建立新握手（$HS0→$HS1）"
  P2=""
  TP2=$SECONDS
  while [ $((SECONDS - TP2)) -lt 60 ]; do
    P2=$(engine_pid)
    [ -n "$P2" ] && [ "$P2" != "$EPID1" ] && break
    sleep 0.5
  done
  if [ -n "$P2" ] && [ "$P2" != "$EPID1" ]; then
    ok "新引擎已启动（pid $EPID1 → $P2）"
  else
    bad "新引擎 pid 未变化或缺失（旧=$EPID1 现=$P2）"
  fi
  if wait_timeout_eq 2147483647 30; then
    ok "新引擎已重新置上 screen_off_timeout 哨兵（完整初始化）"
  else
    bad "重连后 screen_off_timeout=$(cur_timeout)，应为 2147483647"
  fi
else
  bad "窗口状态异常，UI 步骤未执行（6/9、7/9 的断言缺失）"
fi

# ═════════════════════════════════════════════════════════════════════
step "7/9 UI 驱动关窗"
if [ "$UI_OK" -eq 1 ]; then
  GS0=$(countF "$LOG" '优雅停止设备侧引擎')
  ARC0=$(countF "$LOG" '疑似持续黑屏：自动重连')
  GR0=$(countF "$LOG" '引擎已优雅退出')
  GO0=$(countF "$LOG" '探针 GONE @+')
  activate                                                            # 点标题栏激活（点击 × 才会被吃到）
  python "$WIN_AUTO" click $((RX + 1681)) $((RY + 20)) > /dev/null 2>&1 || true  # 标题栏 ×
  info "已点击关闭按钮，等待收尾退出（≤90s）..."
  if wait_count "$LOG" '[main] 已退出' 1 90; then
    ok "关窗后进程正常退出（[main] 已退出）"
  else
    info "90s 未见退出，兜底 WM_CLOSE"
    python "$WIN_AUTO" close "$WINTITLE" > /dev/null 2>&1
    if wait_count "$LOG" '[main] 已退出' 1 60; then
      ok "WM_CLOSE 兜底后正常退出"
    else
      bad "关窗 150s 后仍未退出"
      shot "no-close"
    fi
  fi
  GS1=$(countF "$LOG" '优雅停止设备侧引擎')
  ARC1=$(countF "$LOG" '疑似持续黑屏：自动重连')
  ARC_DELTA=$((ARC1 - ARC0))
  GR1=$(countF "$LOG" '引擎已优雅退出')
  GO1=$(countF "$LOG" '探针 GONE @+')
  F1=$(countF "$LOG" '强制 kill')
  if [ "$GS1" -eq $((GS0 + 1 + ARC_DELTA)) ]; then
    if [ "$ARC_DELTA" -gt 0 ]; then
      ok "关窗触发 1 次优雅停止（$GS0→$GS1；其中黑屏自愈 ×$ARC_DELTA）"
    else
      ok "关窗触发 1 次优雅停止（$GS0→$GS1）"
    fi
  else
    bad "优雅停止次数 $GS0→$GS1（期望 +1$( [ "$ARC_DELTA" -gt 0 ] && echo " + 黑屏自愈 ×$ARC_DELTA" )）"
  fi
  [ "$GR1" -ge $((GR0 + 1)) ] && ok "关窗路径「引擎已优雅退出」（$GR0→$GR1）" || bad "关窗路径未见「引擎已优雅退出」（$GR0→$GR1）"
  [ "$GO1" -ge $((GO0 + 1)) ] && ok "关窗路径探针 GONE（$GO0→$GO1）" || bad "关窗路径未见探针 GONE（$GO0→$GO1）"
  [ "$F1" -eq 0 ] && ok "全程无强制 kill" || bad "出现 $F1 次强制 kill"
  WGW=0
  TW=$SECONDS
  while [ $((SECONDS - TW)) -lt 30 ]; do
    python "$WIN_AUTO" rect "$WINTITLE" > /dev/null 2>&1 || { WGW=1; break; }
    sleep 0.5
  done
  [ "$WGW" -eq 1 ] && ok "窗口已消失" || bad "窗口仍在"
  GG=0
  TG=$SECONDS
  while [ $((SECONDS - TG)) -lt 60 ]; do
    kill -0 "$GRADLE_PID" 2> /dev/null || { GG=1; break; }
    sleep 0.5
  done
  if [ "$GG" -eq 0 ]; then
    info "gradlew 60s 未自行退出，兜底 kill（窗口已关、应用侧收尾已确认完成）"
    kill -9 "$GRADLE_PID" 2> /dev/null || true
    sleep 2
    kill -0 "$GRADLE_PID" 2> /dev/null || GG=1
  fi
  [ "$GG" -eq 1 ] && ok "桌面端进程（gradlew run）已退出" || bad "gradlew run 60s 后仍在运行（兜底 kill 亦失败）"
else
  bad "窗口状态异常，关窗步骤未执行"
  python "$WIN_AUTO" close "$WINTITLE" > /dev/null 2>&1 || true
fi

# ═════════════════════════════════════════════════════════════════════
step "8/9 设备终态"
if wait_timeout_eq "$ORIG_TIMEOUT" 30; then
  ok "screen_off_timeout 已恢复为基线 $ORIG_TIMEOUT（cleanup 真落盘）"
else
  bad "screen_off_timeout=$(cur_timeout)，应为 $ORIG_TIMEOUT（若为 2147483647 = 恢复没跑）"
fi
if wait_engine_gone 30; then
  ok "设备侧引擎已消失"
else
  bad "引擎仍在（pid=$(engine_pid)）"
fi
if [ "$(dev 'ls /sdcard/DiLinkAuto/stop-request* 2>/dev/null | head -1' | tr -d '\r\n ')" != "" ]; then
  info "哨兵文件残留（关窗路径不消费它，属已知无害残留；引擎下次启动自清）"
fi

# ═════════════════════════════════════════════════════════════════════
step "9/9 拉取日志 + 离线断言"
# MSYS_NO_PATHCONV=1：Git Bash 会把 /sdcard/... 自动改写成 Windows 路径
MSYS_NO_PATHCONV=1 adb -s "$SERIAL" pull /sdcard/DiLinkAuto/vd-server.log "$RUN/vd-server.log" \
  > /dev/null 2>&1
if [ -f "$RUN/vd-server.log" ]; then
  ok "vd-server.log 已拉取（$(wc -c < "$RUN/vd-server.log" | tr -d ' ') bytes）"
else
  bad "vd-server.log 拉取失败"
fi
cp "$DLOG" "$RUN/desktop.log" 2>/dev/null
bash "$ROOT/scripts/verify-reconnect-cleanup.sh" "$RUN"
if [ $? -eq 0 ]; then
  ok "离线断言（verify-reconnect-cleanup.sh）全部通过"
else
  bad "离线断言有失败项（见上）"
fi

# ═════════════════════════════════════════════════════════════════════
echo
hr
echo "结果：通过 $PASS 项；失败 $FAIL 项"
echo "产物目录：$RUN"
if [ "$FAIL" -eq 0 ]; then
  echo "全部通过 ✓"
  # 成功路径也要清哨兵：引擎启动时会自清（PipelineServer.run 开头），所以残留不致命，
  # 但留一份「上一轮的停止请求」在 /sdcard 上不符合零残留约定（2026-10-17 实测发现
  # 首跑成功后 stop-request 仍在，失败路径清了、成功路径漏了）。
  dev 'rm -f /sdcard/DiLinkAuto/stop-request' > /dev/null 2>&1 || true
  exit 0
fi

echo
echo "== 失败收尾：把设备/窗口恢复到中立状态 =="
if python "$WIN_AUTO" rect "$WINTITLE" > /dev/null 2>&1; then
  python "$WIN_AUTO" close "$WINTITLE" > /dev/null 2>&1 || true
  TF=$SECONDS
  while [ $((SECONDS - TF)) -lt 20 ]; do
    python "$WIN_AUTO" rect "$WINTITLE" > /dev/null 2>&1 || break
    sleep 0.5
  done
  echo "   窗口：已尝试关闭"
fi
if [ -n "$(engine_pid)" ]; then
  dev 'for p in $(pgrep -f "[P]ipelineServer"); do kill -9 $p; done; exit 0'
  echo "   引擎：已强制清理"
fi
dev "settings put system screen_off_timeout ${ORIG_TIMEOUT:-300000}"
dev 'rm -f /sdcard/DiLinkAuto/stop-request'
echo "   screen_off_timeout：已恢复 ${ORIG_TIMEOUT:-300000}；哨兵：已删除"
echo "失败详情与现场日志：$RUN"
exit 1
