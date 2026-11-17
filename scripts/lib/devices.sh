#!/bin/bash
# DiLink-Auto 双设备 E2E — 设备角色认领 / 唤醒解锁 / 前置检查 / adb 包装。
#
# 复用 logging.sh 的 log_step/log_ok/log_err。本文件被 e2e-dual-device.sh
# 和后续 P1 脚本 source，提供：
#   parse_roles    — 强制 --phone/--car 显式传，不自动挑设备
#   unlock_device  — 唤醒 + 解锁（MIUI / Flyme 两条路径，实测有差异）
#   preflight      — 两台在线、PIN 已设、同网双向可达、端口冲突
#   dphone/dcar    — adb -s <serial> shell 的薄包装
#   pull_phone/pull_car / wait_until / screencap_car
#
# 解锁策略依据 .plan/adb-unlock-for-e2e.md + 2026-10-14 实测：
#   MIUI（MI 9）: KEYCODE_WAKEUP → input text <PIN> 直接成（锁屏未设 FLAG_SECURE）
#   Flyme（MEIZU 20 Inf）: 必须先上滑露出 PIN 板 + tap passwordEntry 聚焦，
#     再 input text —— 密码框 password="true"，不先 tap 则 input text 被吞。
# 判定不靠 input 的退出码（永远 0），只看 dumpsys window 的 isKeyguardShowing。
# 不写重试循环猜密码（Android failsafe：连错 5 次锁 30 分钟）。
set -uo pipefail

# ── 全局（由 parse_roles / preflight 填充）─────────────────────────────
# 用 ${VAR:-} 保留调用方已 export 的值：asserts.sh probe / ops.sh list|run /
# explore.py 委派都是「先 export PHONE_SERIAL=.. 再 source 本文件」的用法，
# 无条件置空会把它们全部打成空 serial（2026-10-15 实测 bash -x 抓到）。
PHONE_SERIAL="${PHONE_SERIAL:-}"
CAR_SERIAL="${CAR_SERIAL:-}"
PHONE_IP="${PHONE_IP:-}"
CAR_IP="${CAR_IP:-}"

# ── adb 薄包装 ──────────────────────────────────────────────────────────
dphone() { adb -s "$PHONE_SERIAL" shell "$@"; }
dcar()   { adb -s "$CAR_SERIAL"   shell "$@"; }
pull_phone() { MSYS_NO_PATHCONV=1 adb -s "$PHONE_SERIAL" pull "$@"; }
pull_car()   { MSYS_NO_PATHCONV=1 adb -s "$CAR_SERIAL"   pull "$@"; }

# ── 活动 WiFi 网卡探测 ─────────────────────────────────────────────────
# Meizu 20 Inf 的 WiFi 是 wlan1，MI9 是 wlan0；不能把网卡名写死。
# 优先读 `ip -4 route show table all` 里第一条 `default ... dev wlan*`，
# 这样能排除 dummy0 / rmnet_data*；没有默认路由时退回到第一个有 IPv4 的 wlan*。
# 保留 PHONE_IP / CAR_IP 环境变量覆盖（preflight 只在其为空时探测）。
active_wifi_iface() { # <serial> → wlan0/wlan1/...
  local serial=${1:-${PHONE_SERIAL:-}} out iface
  [ -n "$serial" ] || return 1
  out=$(adb -s "$serial" shell "ip -4 route show table all 2>/dev/null" 2>/dev/null | tr -d '\r')
  iface=$(printf '%s\n' "$out" | awk '$1=="default" {for(i=1;i<=NF;i++) if($i=="dev" && $(i+1) ~ /^wlan/) {print $(i+1); exit}}')
  if [ -z "$iface" ]; then
    out=$(adb -s "$serial" shell "ip -4 addr show 2>/dev/null" 2>/dev/null | tr -d '\r')
    iface=$(printf '%s\n' "$out" | awk '/^[0-9]+: wlan/ {gsub(":","",$2); cand=$2} /inet / {if(cand!=""){print cand; exit}}')
  fi
  [ -n "$iface" ] && printf '%s\n' "$iface"
}

active_wifi_ip() { # <serial> → IPv4
  local serial=${1:-${PHONE_SERIAL:-}} iface
  [ -n "$serial" ] || return 1
  iface=$(active_wifi_iface "$serial") || return 1
  [ -n "$iface" ] || return 1
  adb -s "$serial" shell "ip -4 addr show $iface 2>/dev/null" 2>/dev/null \
    | tr -d '\r' | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -1
}

# 当前光标位置等小工具留给调用方自己用 win_auto，这里不引。

# wait_until <预算秒> <描述> <判定命令...>
# 按**真实经过时间**（SECONDS）设预算，不按迭代次数——本机 fork/exec ~1s
# （杀软实时扫描），「迭代次数 × sleep」会把 150s 预算跑成 10 分钟。
wait_until() {
  local budget=$1 desc=$2; shift 2
  local deadline=$((SECONDS + budget))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$@" > /dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  log_err "等待超时（${budget}s）：$desc"
  return 1
}

# ── 设备角色认领 ────────────────────────────────────────────────────────
# 强制 --phone <serial> --car <serial>。两台 USB 设备时自动挑第一个会挑错
# （e2e-reconnect-cleanup.sh:109-113 的坑，.plan/adb-unlock-for-e2e.md §5）。
parse_roles() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --phone) PHONE_SERIAL="${2:-}"; shift 2 ;;
      --car)   CAR_SERIAL="${2:-}";   shift 2 ;;
      *) shift ;;
    esac
  done

  if [ -z "$PHONE_SERIAL" ] || [ -z "$CAR_SERIAL" ]; then
    log_err "必须显式指定两台设备：--phone <serial> --car <serial>"
    echo "  当前在线设备：" >&2
    adb devices -l | awk 'NR>1 && $2=="device" {print "    " $1 "  " $NF}' >&2
    return 2
  fi
  if [ "$PHONE_SERIAL" = "$CAR_SERIAL" ]; then
    log_err "phone 与 car 不能是同一台设备（$PHONE_SERIAL）"
    return 2
  fi
  # 两台都得在 adb devices 里且为 device 状态
  local s
  for s in "$PHONE_SERIAL" "$CAR_SERIAL"; do
    local state
    state=$(adb -s "$s" get-state 2>/dev/null | tr -d '\r\n')
    if [ "$state" != "device" ]; then
      log_err "设备 $s 不在线（state=${state:-无}）"
      return 2
    fi
  done
  log_ok "phone=$PHONE_SERIAL  car=$CAR_SERIAL"
}

# ── 唤醒解锁 ────────────────────────────────────────────────────────────
# unlock_device <serial> <pin> <label>
# 退出码 0 = 已解锁；1 = 解锁失败（调用方应 abort，不要带锁屏继续跑）。
unlock_device() {
  local serial=$1 pin=$2 label=$3

  # 0) 先看是不是已经解锁了（幂等）
  if [ "$(adb -s "$serial" shell "dumpsys window | grep -c 'isKeyguardShowing=false'" 2>/dev/null | tr -d '\r\n')" = "1" ] && \
     [ "$(adb -s "$serial" shell "dumpsys power | grep -oE 'mWakefulness=Awake'" 2>/dev/null | tr -d '\r\n')" = "mWakefulness=Awake" ]; then
    log_ok "$label 已解锁（幂等跳过）"
    return 0
  fi

  # 1) 亮屏。Dozing 时 screencap 全黑（2026-10-14 实测 2527200/2527200），
  #    且 KEYCODE_WAKEUP 本身只让 mWakefulness=Awake，锁屏层还在上面——
  #    只看 Awake 不算解锁成功。
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
  sleep 2  # 等 Display State=ON（Flyme 亮屏慢，<1.5s 仍是 OFF）

  # 2) 判 ROM 走对应解锁路径
  local model
  model=$(adb -s "$serial" shell "getprop ro.product.model" 2>/dev/null | tr -d '\r\n')
  log_step "$label 唤醒解锁（model=$model）"

  if _unlock_via_direct_text "$serial" "$pin"; then
    : # MIUI / 无 PIN 路径
  else
    # Flyme / 通用密码框路径：上滑露板 + tap 聚焦 + text + ENTER
    _unlock_via_swipe_tap "$serial" "$pin"
  fi

  # 3) 断言真的解锁了（硬失败，见 .plan §4：input 失败是静默的）
  local i kg
  for i in $(seq 1 10); do
    kg=$(adb -s "$serial" shell "dumpsys window | grep -oE 'isKeyguardShowing=(true|false)'" 2>/dev/null | head -1 | tr -d '\r\n')
    [ "$kg" = "isKeyguardShowing=false" ] && break
    sleep 1
  done

  if [ "$kg" != "isKeyguardShowing=false" ]; then
    log_err "$label 解锁失败：isKeyguardShowing 仍为 true（PIN 不对？ROM 锁屏设了 FLAG_SECURE？）"
    adb -s "$serial" shell "dumpsys window | grep -E 'isKeyguardShowing|mCurrentFocus'" 2>/dev/null | tr -d '\r' >&2
    return 1
  fi
  log_ok "$label 已解锁（isKeyguardShowing=false）"
  return 0
}

# MIUI / 免 PIN 路径：裸 input text 直接被锁屏接收。
_unlock_via_direct_text() {
  local serial=$1 pin=$2
  [ -z "$pin" ] && return 1
  adb -s "$serial" shell input text "$pin" > /dev/null 2>&1
  sleep 1.5
  # 是否生效由调用方的断言判定；这里只返回「是否可能已解锁」
  [ "$(adb -s "$serial" shell "dumpsys window | grep -c 'isKeyguardShowing=false'" 2>/dev/null | tr -d '\r\n')" = "1" ]
}

# Flyme / 通用密码框路径：密码框 password="true"，不先 tap 则 input text 被吞。
# 2026-10-14 实测：swipe(684,2800→400,100) + tap(684,583) + text + ENTER 一次成。
_unlock_via_swipe_tap() {
  local serial=$1 pin=$2
  local w
  w=$(adb -s "$serial" shell "wm size" 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
  local W H
  W=${w%x*}; H=${w#*x}
  # 屏幕中点附近做上滑；距离用真实分辨率按比例，不硬编码像素
  local cx=$((W / 2)) y_start=$((H * 88 / 100)) y_end=$((H * 15 / 100))
  adb -s "$serial" shell input touchscreen swipe "$cx" "$y_start" "$cx" "$y_end" 100 > /dev/null 2>&1
  sleep 1.5

  # 定位 passwordEntry（com.android.systemui:id/passwordEntry）并 tap 聚焦。
  # 不硬编码坐标——通过 uiautomator dump 找它的 bounds，跨分辨率稳。
  # 关键 bug 修正：必须先 grep 出含 passwordEntry 的那一行，再从该行取 bounds，
  # 不能 dump 后取「第一个 bounds」——那是根节点 [0,0][W,H]（2026-10-14 实测踩坑）。
  local pb
  pb=$(adb -s "$serial" shell "uiautomator dump /sdcard/ui_unlock.xml" >/dev/null 2>&1; \
       adb -s "$serial" shell "cat /sdcard/ui_unlock.xml" 2>/dev/null | tr -d '\r' \
       | grep -oE 'passwordEntry"[^/]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' \
       | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
  if [ -n "$pb" ]; then
    # bounds="[481,541][887,625]" → 中心点
    local tl br x1 y1 x2 y2
    tl=${pb#bounds=\"[}; tl=${tl%%]*}; x1=${tl%,*}; y1=${tl#*,}
    br=${pb##*\[}; br=${br%%\]*}; x2=${br%,*}; y2=${br#*,}
    if [ -n "$x1" ] && [ -n "$y2" ]; then
      adb -s "$serial" shell input tap $(((x1+x2)/2)) $(((y1+y2)/2)) > /dev/null 2>&1
      sleep 0.4
    fi
  else
    # dump 没抓到密码框（swipe 没露出 PIN 板）—— 用已知 Flyme 坐标兜底
    adb -s "$serial" shell input tap "$cx" $((H * 18 / 100)) > /dev/null 2>&1
    sleep 0.4
  fi

  [ -z "$pin" ] && return 1
  adb -s "$serial" shell input text "$pin" > /dev/null 2>&1
  sleep 0.6
  adb -s "$serial" shell input keyevent KEYCODE_ENTER > /dev/null 2>&1
  sleep 2
}

# ── 前置检查 ────────────────────────────────────────────────────────────
# preflight <phone_pin> <car_pin>
preflight() {
  local phone_pin=${1:-} car_pin=${2:-}

  # P0 的 PIN 需求：无论怎么分配角色都绕不开 wake，wake 对两台都跑。
  # 不设默认值（弱密码进 git 历史等同公开，.plan §5）。
  if [ -z "$phone_pin" ] || [ -z "$car_pin" ]; then
    log_err "需要两个 PIN：DILINK_TEST_PIN_PHONE / DILINK_TEST_PIN_CAR"
    echo "  export DILINK_TEST_PIN_PHONE=<phone PIN>  DILINK_TEST_PIN_CAR=<car PIN>" >&2
    echo "  （设备没设锁屏密码就传任意非空占位）" >&2
    return 2
  fi

  # 两台 Android 版本记入产物（影响 B2 的 FGS 行为判定）
  local pr cr
  pr=$(adb -s "$PHONE_SERIAL" shell "getprop ro.build.version.release" 2>/dev/null | tr -d '\r\n')
  cr=$(adb -s "$CAR_SERIAL"   shell "getprop ro.build.version.release" 2>/dev/null | tr -d '\r\n')
  log_step "Android 版本：phone=$pr  car=$cr"

  # 取两台活动 WiFi 网卡 IP（Meizu=wlan1，MI9=wlan0；不要写死网卡名）
  PHONE_IP="${PHONE_IP:-$(active_wifi_ip "$PHONE_SERIAL")}"
  CAR_IP="${CAR_IP:-$(active_wifi_ip "$CAR_SERIAL")}"
  if [ -z "$PHONE_IP" ] || [ -z "$CAR_IP" ]; then
    log_err "取不到活动 WiFi IP（phone=$PHONE_IP car=$CAR_IP；接口 phone=$(active_wifi_iface "$PHONE_SERIAL") car=$(active_wifi_iface "$CAR_SERIAL")）—— WiFi 没连？"
    return 2
  fi
  log_ok "IP：phone=$PHONE_IP（$(active_wifi_iface "$PHONE_SERIAL")）  car=$CAR_IP（$(active_wifi_iface "$CAR_SERIAL")）"

  # 双向 ping（同网前提，脚本化不了，只能检查 + 提示）
  log_step "双向可达性检查"
  if ! adb -s "$CAR_SERIAL"   shell "ping -c 2 -W 2 $PHONE_IP" > /dev/null 2>&1; then
    log_err "car($CAR_IP) → phone($PHONE_IP) 不通"
    echo "  让两台设备连同一路由器，或让 --phone 开热点 --car 接入" >&2
    return 2
  fi
  if ! adb -s "$PHONE_SERIAL" shell "ping -c 2 -W 2 $CAR_IP" > /dev/null 2>&1; then
    log_err "phone($PHONE_IP) → car($CAR_IP) 不通"
    echo "  让两台设备连同一路由器，或让 --phone 开热点 --car 接入" >&2
    return 2
  fi
  log_ok "双向 ping 通"

  # phone 侧 9637 若已被占（上轮没清干净 / 现役 client），先杀掉，否则抢端口
  local listen9637
  listen9637=$(dphone 'if ss -ltn 2>/dev/null | grep -q ":9637"; then echo Y; else echo N; fi' 2>/dev/null | tr -d '\r\n ')
  if [ "$listen9637" = "Y" ]; then
    log_step "phone 侧 9637 已被占用，先 force-stop client"
    dphone 'am force-stop com.dilinkauto.client' 2>/dev/null
    sleep 2
    listen9637=$(dphone 'if ss -ltn 2>/dev/null | grep -q ":9637"; then echo Y; else echo N; fi' 2>/dev/null | tr -d '\r\n ')
    [ "$listen9637" = "N" ] && log_ok "9637 已释放" || log_err "9637 仍被占用"
  fi
  return 0
}

# ═══════════════════════════════════════════════════════════════════════
# 双手机 E2E 扩展（2026-10-15，设计见 .plan/e2e-dual-device-infra.md）
# 全局：PHONE_TPC_ID（B 的 TCP adb id，形如 IP:5555）、四项 settings 基线
# ═══════════════════════════════════════════════════════════════════════
PHONE_TPC_ID="${PHONE_TPC_ID:-}"
BASELINE_SCREEN_OFF_TIMEOUT="${BASELINE_SCREEN_OFF_TIMEOUT:-300000}"
BASELINE_USER_ROTATION="${BASELINE_USER_ROTATION:-0}"
BASELINE_AIRPLANE="${BASELINE_AIRPLANE:-0}"
BASELINE_IME="${BASELINE_IME:-}"

# ── PIN 失败计数（risk R01/R03：每设备累计失败 ≥2 → 调用方全局 abort）─────
# 返回：1 = 失败但未到阈值；2 = 达到阈值，必须 abort 转人工。
E2E_PIN_FAIL_PHONE="${E2E_PIN_FAIL_PHONE:-0}"
E2E_PIN_FAIL_CAR="${E2E_PIN_FAIL_CAR:-0}"
note_pin_failure() { # <phone|car>
  case "$1" in
    phone) E2E_PIN_FAIL_PHONE=$((E2E_PIN_FAIL_PHONE + 1))
           log_err "phone 侧 PIN 失败累计 $E2E_PIN_FAIL_PHONE 次（failsafe：连错 5 次锁 30 分钟）"
           [ "$E2E_PIN_FAIL_PHONE" -ge 2 ] && return 2; return 1 ;;
    car)  E2E_PIN_FAIL_CAR=$((E2E_PIN_FAIL_CAR + 1))
           log_err "car 侧 PIN 失败累计 $E2E_PIN_FAIL_CAR 次（failsafe：连错 5 次锁 30 分钟）"
           [ "$E2E_PIN_FAIL_CAR" -ge 2 ] && return 2; return 1 ;;
    *) return 2 ;;
  esac
}

# ── 截图取证 ────────────────────────────────────────────────────────────
# screencap_car <label> [outdir] / screencap_phone <label> [outdir]
# stdout 打印 jpg 路径；失败返回 1。exec-out 免 CRLF 毁 PNG；PNG magic 校验；
# 压缩优先 magick（PATH 或本机实证路径），缺失回落 PIL。
_find_magick() {
  if command -v magick > /dev/null 2>&1; then echo "magick"; return 0; fi
  local p
  for p in "/c/program files/imagemagick-7.1.2-q16-hdri/magick" \
           "/c/Program Files/ImageMagick-7.1.2-Q16-HDRI/magick"; do
    [ -x "$p" ] && { echo "$p"; return 0; }
  done
  return 1
}

_screencap_one() { # <serial> <label> <outdir>
  local serial=$1 label=$2 outdir=$3
  mkdir -p "$outdir"
  local png="$outdir/shot-$label.png" jpg="$outdir/shot-$label.jpg"
  local TO=""
  command -v timeout > /dev/null 2>&1 && TO="timeout 15"
  if ! $TO adb -s "$serial" exec-out screencap -p > "$png" 2>/dev/null || [ ! -s "$png" ]; then
    log_err "screencap 失败或超时（label=$label serial=$serial）"
    rm -f "$png"; return 1
  fi
  # PNG magic 校验：传输截断时文件非空但内容损坏
  if [ "$(head -c 8 "$png" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]; then
    log_err "screencap 输出不是合法 PNG（label=$label）——传输被截断，重试一次"
    rm -f "$png"
    if ! $TO adb -s "$serial" exec-out screencap -p > "$png" 2>/dev/null || [ ! -s "$png" ]; then
      rm -f "$png"; return 1
    fi
    if [ "$(head -c 8 "$png" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]; then
      log_err "重试后仍非法 PNG（label=$label），放弃"
      rm -f "$png"; return 1
    fi
  fi
  local M
  if M=$(_find_magick); then
    if ! "$M" "$png" -resize 30% -quality 40 "$jpg" 2>/dev/null; then
      log_err "magick 压缩失败（label=$label）"; rm -f "$png"; return 1
    fi
  else
    python - "$png" "$jpg" <<'PY' || { rm -f "$png"; return 1; }
import sys
from PIL import Image
src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src)
w, h = im.size
im.resize((max(1, w * 30 // 100), max(1, h * 30 // 100))).save(dst, "JPEG", quality=40)
PY
  fi
  rm -f "$png"
  echo "$jpg"
}

screencap_car()   { _screencap_one "$CAR_SERIAL"   "${1:?label}" "${2:-${RUN:-.}}"; }
screencap_phone() { _screencap_one "$PHONE_SERIAL" "${1:?label}" "${2:-${RUN:-.}}"; }

# ── 端口探测（ss 优先，缺失回落 /proc/net/tcp hex）──────────────────────
# listening_on <serial> <port> → Y/N（LISTEN，st=0A）
# port_established <serial> <port> → Y/N（ESTABLISHED，st=01）
# 注意（.plan/e2e-observability.md §A0）：9637/9638/9639/19647 的 LISTEN 都是
# 瞬态，会话中只有 ESTABLISHED——会话内存活判据用 port_established。
_port_hex() { printf '%04X' "$1"; }
listening_on() { # <serial> <port>
  local hex; hex=$(_port_hex "$2")
  adb -s "$1" shell "if ss -ltn 2>/dev/null | grep -q \":$2\"; then echo Y; elif grep -E \":$hex [0-9A-F]+:[0-9A-F]+ 0A \" /proc/net/tcp >/dev/null 2>&1; then echo Y; else echo N; fi" 2>/dev/null | tr -d '\r\n '
}
port_established() { # <serial> <port>
  local hex; hex=$(_port_hex "$2")
  adb -s "$1" shell "if ss -tn 2>/dev/null | grep -E \":$2 \" | grep -q ESTAB; then echo Y; elif grep -E \":$hex [0-9A-F]+:[0-9A-F]+ 01 \" /proc/net/tcp >/dev/null 2>&1; then echo Y; else echo N; fi" 2>/dev/null | tr -d '\r\n '
}

# A→B 可达性（A 侧 toybox nc）。返回：0=可连接 1=拒绝/不可达 2=探测手段不可用
a_to_b_reachable() { # <port>
  local out rc
  out=$(timeout 8 adb -s "$CAR_SERIAL" shell "echo | nc -w 2 $PHONE_IP $1" 2>&1); rc=$?
  case "$rc" in
    0) return 0 ;;
    2) log_step "A 侧 toybox nc 不可用，端口 $1 的 A→B 探测降级（跳过）"; return 2 ;;
    *) return 1 ;;
  esac
}
# PC 侧兜底探测（PC→B，同子网时用；注意这不是 A→B）
probe_from_pc() { # <ip> <port>
  timeout 8 python - "$1" "$2" <<'PY'
import socket, sys
ip, port = sys.argv[1], int(sys.argv[2])
s = socket.socket(); s.settimeout(3)
try:
    s.connect((ip, port)); print("OPEN")
except Exception:
    sys.exit(1)
finally:
    s.close()
PY
}

# ── TCP adb 通道（B 的无线调试） ────────────────────────────────────────
dphone_tcp()    { adb -s "$PHONE_TPC_ID" shell "$@"; }
pull_phone_tcp() { MSYS_NO_PATHCONV=1 adb -s "$PHONE_TPC_ID" pull "$@"; }
# 自适应通道：USB 优先，USB 不在（S07 拔线后）自动降级 TCP
dphone_any() {
  if [ "$(adb -s "$PHONE_SERIAL" get-state 2>/dev/null | tr -d '\r\n')" = "device" ]; then
    dphone "$@"
  else
    dphone_tcp "$@"
  fi
}

# ensure_tcp_adb <b_serial> <ip>：幂等。
#   <b_serial> 可以是 USB 序列号（如 9e779dea）或 TCP id（无线调试场景，如 IP:37245 / IP:5555）。
#   同机判定用硬件序列号 ro.serialno（TCP id 与硬件序列号不同，不能直接相等比较）。
#   USB 场景：tcpip 后确认 USB 通道仍在（ROM 异常则人工介入）；
#   无线场景：tcpip 后原通道必然掉线（预期行为），不确认原通道。
ensure_tcp_adb() {
  local serial=$1 ip=$2
  local tcp_id="$ip:5555"
  PHONE_TPC_ID="$tcp_id"
  local is_tcp=0
  case "$serial" in *:*) is_tcp=1 ;; esac

  # 参考身份：B 当前通道的硬件序列号
  local hw=""
  if [ "$(adb -s "$serial" get-state 2>/dev/null | tr -d '\r\n')" = "device" ]; then
    hw=$(adb -s "$serial" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
  fi
  if [ -z "$hw" ] && [ "$is_tcp" = "1" ]; then
    # 无线 B 且原通道已被 tcpip 打断（步骤 2 预下发过）：从 5555 自证
    adb connect "$tcp_id" > /dev/null 2>&1
    sleep 1
    if [ "$(adb -s "$tcp_id" get-state 2>/dev/null | tr -d '\r\n')" = "device" ]; then
      hw=$(adb -s "$tcp_id" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
    fi
  fi
  [ -n "$hw" ] || { log_err "B（$serial）不在线，取不到硬件序列号"; return 1; }

  # 快路径：5555 已就绪且硬件序列号一致
  if [ "$(adb -s "$tcp_id" get-state 2>/dev/null | tr -d '\r\n')" = "device" ] && \
     [ "$(adb -s "$tcp_id" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')" = "$hw" ]; then
    log_ok "TCP adb 已就绪（$tcp_id，硬件序列号 $hw 一致，跳过 tcpip）"
    return 0
  fi
  if [ "$(adb -s "$serial" get-state 2>/dev/null | tr -d '\r\n')" != "device" ]; then
    log_err "B 设备 $serial 不在线，无法下发 tcpip"
    return 1
  fi
  adb -s "$serial" tcpip 5555 > /dev/null 2>&1
  sleep 2
  adb connect "$tcp_id" > /dev/null 2>&1
  local deadline=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(adb -s "$tcp_id" get-state 2>/dev/null | tr -d '\r\n')" = "device" ] && \
       [ "$(adb -s "$tcp_id" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')" = "$hw" ]; then
      break
    fi
    # 等不到 device 的常见原因是授权框——仅 USB 场景可经原通道代点
    # （无线场景 tcpip 后原通道已断，无法代点）
    if [ "$is_tcp" = "0" ]; then
      accept_auth_dialog "$serial" 15 || true
    fi
    sleep 1
  done
  if [ "$(adb -s "$tcp_id" get-state 2>/dev/null | tr -d '\r\n')" != "device" ]; then
    log_err "$tcp_id 30s 未就绪（授权框未处理？见 accept_auth_dialog）"
    return 1
  fi
  log_ok "TCP adb 就绪：$tcp_id（硬件序列号 $hw 校验通过）"
  if [ "$is_tcp" = "0" ]; then
    if [ "$(adb -s "$serial" get-state 2>/dev/null | tr -d '\r\n')" != "device" ] || \
       ! adb -s "$serial" shell echo usb-ok > /dev/null 2>&1; then
      log_err "tcpip 后 USB 通道不可用——ROM 行为异常，人工介入"
      return 1
    fi
    log_ok "USB 通道保持可用（$serial）"
  else
    log_ok "B 原无线通道已按预期切换（$serial → $tcp_id，硬件序列号 $hw 一致）"
  fi
  return 0
}

# bounds="[481,541][887,625]" → 中心点 tap（裸 [x,y][x,y] 也接受）
_tap_bounds() { # <serial> <bounds串>
  local serial=$1 pb=$2 tl br x1 y1 x2 y2
  pb=${pb#bounds=\"}; pb=${pb%\"}    # 剥包装（若有）
  # 形状校验 + 数字守卫：残串（如 "[400"）进 $(( )) 会触发算术展开错误——那会
  # 静默中止整个命令链（2026-10-17 fn-test T4 实证：&&/|| 检查行被跳过、不报错）。
  case "$pb" in
    \[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]) ;;
    *) return 1 ;;
  esac
  tl=${pb#\[}; tl=${tl%%]*}; x1=${tl%,*}; y1=${tl#*,}
  br=${pb##*\[}; br=${br%%\]*}; x2=${br%,*}; y2=${br#*,}
  case "$x1$y1$x2$y2" in ''|*[!0-9]*) return 1 ;; esac
  adb -s "$serial" shell input tap $(((x1+x2)/2)) $(((y1+y2)/2)) > /dev/null 2>&1
  return 0
}

# 应用网格磁贴：从 uiautomator dump 找第 N 个磁贴的 bounds。
# 真机 dump 是**单行** XML（mock fixture 才是多行）——统一 `tr '<' '\n'` 归一化成
# 一节点一行再解析（2026-10-17 真机校准：旧的「按行 grep + text 过滤」在真机上
# 恒取到根节点 bounds——真机磁贴父节点 text/content-desc 都是空串，文本在子节点）。
# 磁贴判据（live-ui-streaming.xml 实测）：clickable + 非 EditText + y1<800
#（顶部网格区；搜索框是 EditText y≈1980、底部导航栏 y≈2171 均被排除）。排序 (y1,x1)。
_grid_tile_bounds() { # <ui> [index=1] → bounds 串 bounds="[x,y][x,y]"（无命中输出空）
  local line y1 x1 x2 y2 rest
  line=$(printf '%s' "$1" | tr '<' '\n' | grep 'clickable="true"' \
    | grep -v 'class="android.widget.EditText"' \
    | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\2 \1 \3 \4/p' \
    | awk '$1 < 800' \
    | sort -n -k1 -k2 | sed -n "${2:-1}p")
  [ -n "$line" ] || return 0
  y1=${line%% *}; rest=${line#* }
  x1=${rest%% *}; rest=${rest#* }
  x2=${rest%% *}; y2=${rest##* }
  echo "bounds=\"[$x1,$y1][$x2,$y2]\""
}

# accept_auth_dialog <serial> [budget_s=40]
# 处理无线调试授权框：先勾「一律允许」（持久化，无人值守前提）再点「允许」。
# 返回 0 = 处理过且框已消失；1 = 预算内没见到授权框（可能本来就没有）。
accept_auth_dialog() {
  local serial=$1 budget=${2:-40}
  local deadline=$((SECONDS + budget)) handled=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    local ui
    ui=$(timeout 10 adb -s "$serial" shell "uiautomator dump /sdcard/ui_auth.xml && cat /sdcard/ui_auth.xml" 2>/dev/null | tr -d '\r')
    if echo "$ui" | grep -q '允许 USB 调试\|允许调试\|ALLOW USB'; then
      handled=1
      local cb
      cb=$(echo "$ui" | grep -oE 'text="一律允许[^"]*"[^>]*checkable="true"[^>]*checked="false"[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
      if [ -z "$cb" ]; then
        cb=$(echo "$ui" | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"[^>]*text="一律允许[^"]*"[^>]*checked="false"' | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
      fi
      [ -n "$cb" ] && _tap_bounds "$serial" "$cb" && log_ok "已勾选「一律允许」"
      local okb
      okb=$(echo "$ui" | grep -oE 'text="允许"[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
      if [ -z "$okb" ]; then
        okb=$(echo "$ui" | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"[^>]*text="允许"' | head -1 | grep -oE 'bounds="\[[0-9,]+\]\[[0-9,]+\]"')
      fi
      if [ -n "$okb" ]; then
        _tap_bounds "$serial" "$okb"
      else
        local w W H
        w=$(adb -s "$serial" shell wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
        W=${w%x*}; H=${w#*x}
        [ -n "$W" ] && [ -n "$H" ] && adb -s "$serial" shell input tap $((W * 75 / 100)) $((H * 72 / 100)) > /dev/null 2>&1
        log_step "授权框 dump 未解析出按钮，已按比例坐标兜底点击"
      fi
      sleep 2
      local ui2
      ui2=$(timeout 10 adb -s "$serial" shell "uiautomator dump /sdcard/ui_auth.xml && cat /sdcard/ui_auth.xml" 2>/dev/null | tr -d '\r')
      if ! echo "$ui2" | grep -q '允许 USB 调试\|允许调试\|ALLOW USB'; then
        log_ok "授权框已处理并消失（$serial）"
        adb -s "$serial" shell rm -f /sdcard/ui_auth.xml > /dev/null 2>&1
        return 0
      fi
      log_step "授权框仍在，重试点击"
    fi
    sleep 2
  done
  [ "$handled" = "1" ] && return 1
  return 1
}

# ── preflight 扩展（F1~F8，设计 .plan/e2e-dual-device-infra.md §1.3）────
# 调用顺序：parse_roles → preflight → 装 APK → ensure_tcp_adb → 解锁 →
#           preflight_extended → op_neutral → 场景循环
check_pkg_installed() { # <serial> <pkg> → 0=已装
  adb -s "$1" shell pm path "$2" 2>/dev/null | grep -q '^package:'
}

preflight_extended() {
  # F1 双 APK 安装与角色纯净
  if ! check_pkg_installed "$PHONE_SERIAL" com.dilinkauto.client; then
    log_err "Phone B($PHONE_SERIAL) 未安装 com.dilinkauto.client"
    echo "  adb -s $PHONE_SERIAL install -r app-client/build/outputs/apk/debug/app-client-debug.apk" >&2
    return 2
  fi
  if ! check_pkg_installed "$CAR_SERIAL" com.dilinkauto.server; then
    log_err "Phone A($CAR_SERIAL) 未安装 com.dilinkauto.server"
    echo "  adb -s $CAR_SERIAL install -r app-server/build/outputs/apk/debug/app-server-debug.apk" >&2
    return 2
  fi
  log_ok "双 APK 在位（B=client / A=server）"

  # F2 B 的 tcpip 5555 就绪（幂等）
  ensure_tcp_adb "$PHONE_SERIAL" "$PHONE_IP" || return 2

  # F3 A→B 端口可达性分层语义
  if a_to_b_reachable 5555; then
    log_ok "A→B:5555 可达"
  else
    log_err "A→B:5555 不可达——A 的 app 将无法经 adb 部署引擎"
    return 2
  fi
  local p
  local dirty=0
  for p in 9637 9638 9639; do
    [ "$(listening_on "$PHONE_SERIAL" "$p")" = "Y" ] && dirty=1
  done
  if [ "$dirty" = "1" ]; then
    # 2026-10-16 try7 实测：2/9 的 monkey 拉起会让 A 自动连上 B（mDNS/网关轨）→
    # 引擎在 preflight 跑到这里前已 bind 9638/9639 → 原来的硬失败会误判成
    # 「上轮没清干净」。这是 harness 自己制造的残留（try4 只是恰好没赶上竞态），
    # 自愈清理：force-stop 双端 app + 方括号 pgrep 杀引擎，等端口面回落。
    log_step "B 侧端口面有残留监听（多为 2/9 monkey 拉起引发的自连会话）——自动清理"
    adb -s "$PHONE_SERIAL" shell am force-stop com.dilinkauto.client > /dev/null 2>&1
    adb -s "$CAR_SERIAL"   shell am force-stop com.dilinkauto.server > /dev/null 2>&1
    local ep
    for ep in $(adb -s "$PHONE_SERIAL" shell 'pgrep -f "[P]ipelineServer"' 2>/dev/null | tr -d '\r'); do
      adb -s "$PHONE_SERIAL" shell "kill -9 $ep" > /dev/null 2>&1 || true
    done
    local dl=$((SECONDS + 30))
    while [ "$SECONDS" -lt "$dl" ]; do
      dirty=0
      for p in 9637 9638 9639; do
        [ "$(listening_on "$PHONE_SERIAL" "$p")" = "Y" ] && dirty=1
      done
      [ "$dirty" = "0" ] && break
      sleep 1
    done
    if [ "$dirty" = "1" ]; then
      log_err "B 侧端口 9637/9638/9639 清理后仍有 LISTEN——人工排查残留进程"
      return 2
    fi
    log_ok "残留会话已清理，端口面干净"
  fi
  log_ok "B 侧 9637/9638/9639 均无残留监听"

  # F4 设备 doze / 亮屏。未亮屏**不硬失败**：4/9 unlock_device 就在下一步，会负责
  # KEYCODE_WAKEUP + 解锁（2026-10-16 首跑实测：手机放着息屏时硬失败会让
  # 「插上就跑」不可用——preflight 在 unlock 之前，这个校验是自相矛盾的）。
  local s
  for s in "$PHONE_SERIAL" "$CAR_SERIAL"; do
    if [ "$(adb -s "$s" shell "dumpsys power | grep -oE 'mWakefulness=Awake'" 2>/dev/null | tr -d '\r\n')" != "mWakefulness=Awake" ]; then
      log_step "设备 $s 未亮屏——交由 4/9 unlock_device 唤醒（非失败）"
    fi
    local di
    di=$(adb -s "$s" shell "dumpsys deviceidle | grep -oE 'mState=[A-Z]+'" 2>/dev/null | head -1 | tr -d '\r\n')
    log_step "设备 $s doze 状态：${di:-未知}"
    case "$di" in
      mState=ACTIVE|"") : ;;
      *) log_err "设备 $s 处于 doze（$di）——请人工关闭该机的省电/神隐对两个 app 的限制" ;;
    esac
  done
  log_ok "两端亮屏检查完成（未亮屏的由 unlock 唤醒）"

  # F5 A 侧 9637 端口冲突（A 上误跑 client 才会出现）
  if [ "$(listening_on "$CAR_SERIAL" 9637)" = "Y" ]; then
    log_step "A 侧 9637 被占用，force-stop A 上的 client（若误装误开）"
    adb -s "$CAR_SERIAL" shell am force-stop com.dilinkauto.client > /dev/null 2>&1
    sleep 2
    if [ "$(listening_on "$CAR_SERIAL" 9637)" = "Y" ]; then
      log_err "A 侧 9637 仍被占用——角色混乱，人工排查"
      return 2
    fi
    log_ok "A 侧 9637 已释放"
  fi
  # 用 adb devices 探 server 健康（不能无 -s 用 get-state：本 harness 恒有 ≥2 台设备，
  # 多设备时 `adb get-state` 必报 more than one device——2026-10-16 真机首跑暴露）。
  if ! adb devices > /dev/null 2>&1; then
    log_err "PC 侧 adb server 异常（5037？）：adb devices 失败"
    return 2
  fi

  # F7 settings 基线快照（restore 的依据，不硬编码）
  BASELINE_SCREEN_OFF_TIMEOUT=$(dphone 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')
  case "$BASELINE_SCREEN_OFF_TIMEOUT" in
    # 会话哨兵 = 上一轮引擎被强杀没走恢复（SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL，
    # 与引擎 SessionScreenTimeout.DEFAULT_TIMEOUT_MS=60000 同源）。不能把哨兵当基线——
    # 否则收尾断言拿 2147483647 比对永远错（S-M10 教训的 harness 侧同型）。回落 60000。
    2147483647) log_step "screen_off_timeout 停在会话哨兵（上轮引擎被强杀）——基线回落 60000"
                BASELINE_SCREEN_OFF_TIMEOUT=60000 ;;
    ''|null|*[!0-9]*) BASELINE_SCREEN_OFF_TIMEOUT=300000 ;;
  esac
  BASELINE_USER_ROTATION=$(dphone 'settings get system user_rotation' 2>/dev/null | tr -d '\r\n ')
  case "$BASELINE_USER_ROTATION" in ''|null|*[!0-9-]*) BASELINE_USER_ROTATION=0 ;; esac
  BASELINE_AIRPLANE=$(dphone 'settings get global airplane_mode_on' 2>/dev/null | tr -d '\r\n ')
  case "$BASELINE_AIRPLANE" in ''|null|*[!0-9]*) BASELINE_AIRPLANE=0 ;; esac
  BASELINE_IME=$(dphone 'settings get secure default_input_method' 2>/dev/null | tr -d '\r\n ')
  log_ok "基线：screen_off_timeout=$BASELINE_SCREEN_OFF_TIMEOUT user_rotation=$BASELINE_USER_ROTATION airplane=$BASELINE_AIRPLANE ime=${BASELINE_IME:-<未记录>}"

  # F8 A 的 UI 可驱动性快检（connect op 的 UI 驱动链路）
  if ! adb -s "$CAR_SERIAL" shell "uiautomator dump /sdcard/ui_preflight.xml" > /dev/null 2>&1; then
    log_err "A 侧 uiautomator dump 失败——connect op 的 UI 驱动链路不通"
    return 2
  fi
  adb -s "$CAR_SERIAL" shell rm -f /sdcard/ui_preflight.xml > /dev/null 2>&1
  log_ok "A 侧 uiautomator dump 通路正常"
  return 0
}

# lock_phones：E2E 整体退出时的幂等锁屏（默认不调用；--lock-at-exit 时调）。
# 已锁时按电源键会亮屏——先查 keyguard，只在未锁时按。
lock_phones() {
  local s
  for s in "$PHONE_SERIAL" "$CAR_SERIAL"; do
    if [ "$(adb -s "$s" shell "dumpsys window | grep -oE 'isKeyguardShowing=(true|false)'" 2>/dev/null | head -1 | tr -d '\r\n')" = "isKeyguardShowing=false" ]; then
      adb -s "$s" shell input keyevent KEYCODE_SLEEP > /dev/null 2>&1
      log_ok "$s 已锁屏（KEYCODE_SLEEP）"
    else
      log_ok "$s 已是锁屏态（跳过，避免按成亮屏）"
    fi
  done
}
