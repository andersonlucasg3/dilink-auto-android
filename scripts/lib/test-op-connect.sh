#!/bin/bash
# op_connect 接缝单测（issue #173 PA 回归钉子）
#
# 缺陷形态：竖屏超高时 ManualConnectBox 被裁 → op_connect 的 ② 分支直接 return 1，
# S01 setup 必失败；仓库里明明已有 op_reconnect（重启 app）原语却从未被调用。
#
# 本单测用「注入 UI/设备 stub + 把两个等待窗口置 0」离线回放 op_connect 的三条分支：
#   ① 按钮不可达 → 必须发起 force-stop + monkey、不碰任何输入框、收敛后 rc=0；
#   ② 按钮可达   → 必须仍走 UI 输入、零重启（mock 回放 S01 靠这条主路径建流）；
#   ③ 收敛超时   → 必须仍 rc=1 且只用 log_err（不改 rc 语义、不进 bad() 记账）。
#   ④ 源码级记账纪律：op_connect 段不含 bad()，默认窗口仍是 30 / 180。
#
# 跑法：bash scripts/lib/test-op-connect.sh
# 挂进 run-mock-test.sh step 1/4 的接缝单测区。

set -uo pipefail
# 环境陷阱（同 run-mock-test.sh）：WorkBuddy 会话经 BASH_ENV 注入的 rm shim 会让
# 临时目录清理慢到十几秒；这里也剔除（干净环境是无害 no-op）。
unset -f rm rmdir unlink 2>/dev/null || true
unset BASH_ENV
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMPD=$(mktemp -d)
# 直调 /usr/bin/rm：WorkBuddy 的 safe-delete wrapper 会拦 MSYS 临时路径的盘符前缀。
trap '/usr/bin/rm -rf "$TMPD" 2>/dev/null || true' EXIT
. "$ROOT/scripts/lib/logging.sh"

# 同一份墓碑必须能在 pristine base（没有窗口环境变量、op_connect 内联）上跑红：
# 检测到缺失时生成「等待窗口=0」的内存副本，只改窗口不改任何判定逻辑，跑完即删。
# 否则 base 上会真空等 30×3 + 180×2 秒且 while 空转，既慢又会干扰并发的 mock 回放。
if grep -q 'E2E_CONNECT_AUTO_WAIT_S' "$ROOT/scripts/lib/ops.sh"; then
  . "$ROOT/scripts/lib/ops.sh"
  BASELINE_COPY=0
else
  python - "$ROOT/scripts/lib/ops.sh" "$TMPD/ops-baseline.sh" <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
s = s.replace('deadline=$((SECONDS + 30))', 'deadline=$((SECONDS + 0))')
s = s.replace('deadline2=$((SECONDS + 180))', 'deadline2=$((SECONDS + 0))')
s = s.replace('local st t0=', 'local st=UNKNOWN t0=')
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(s)
PY
  . "$TMPD/ops-baseline.sh"
  BASELINE_COPY=1
fi
TRACE="$TMPD/trace.txt"
STATE="$TMPD/state.txt"
OUT="$TMPD/out.txt"

pass=0; fail=0
ck() { if [ "$1" = "$2" ]; then echo "  ok: $3"; pass=$((pass+1)); else echo "  FAIL: $3 — got '$1' want '$2'"; fail=$((fail+1)); fi; }
has() { if grep -qE "$1" "$2"; then echo 1; else echo 0; fi; }
xcount() { grep -c '  ✗' "$1" || true; }

# ── 生产函数的离线替身（source ops.sh 之后覆盖，只改动作面不改判定逻辑）──
UI_HAS_BUTTON=0
STATE_AFTER_RESTART=UNKNOWN
STATE_AFTER_UI=UNKNOWN

_a_dump() { echo '<hierarchy><node text="目标帧率" bounds="[0,0][100,100]"/></hierarchy>'; }
_bounds_of_text() { [ "$UI_HAS_BUTTON" = "1" ] && echo 'bounds="[10,1900][200,1960]"'; return 0; }
_bounds_of_ip_edittext() { [ "$UI_HAS_BUTTON" = "1" ] && echo 'bounds="[10,1800][200,1860]"'; return 0; }
_tap_bounds() { echo "TAP $*" >> "$TRACE"; }
dcar() {
  echo "CAR $*" >> "$TRACE"
  case "$*" in
    *monkey*) echo "$STATE_AFTER_RESTART" > "$STATE" ;;
    *'input text'*) echo "$STATE_AFTER_UI" > "$STATE" ;;
  esac
  return 0
}
dphone() { echo "PHONE $*" >> "$TRACE"; }
accept_auth_dialog() { return 0; }
car_state_now() { cat "$STATE"; }
sleep() { :; }

# 两个窗口置 0 只为让单测瞬时完成；生产默认值由用例④单独钉住。
E2E_CONNECT_AUTO_WAIT_S=0
E2E_CONNECT_SETTLE_WAIT_S=0
PHONE_IP=192.168.3.20
CAR_SERIAL=mockA
PHONE_SERIAL=mockB

run_case() { # <has_button> <initial_state> <after_restart> <after_ui>
  UI_HAS_BUTTON=$1
  STATE_AFTER_RESTART=$3
  STATE_AFTER_UI=$4
  echo "$2" > "$STATE"
  : > "$TRACE"
  op_connect > "$OUT" 2>&1
  echo $? > "$TMPD/rc"
}

# ── 用例①：按钮不可达 → 走重启兜底 ───────────────────────────────────
run_case 0 UNKNOWN STREAMING UNKNOWN
ck "$(cat "$TMPD/rc")" "0" "按钮不可达 + 重启后建流 → op_connect rc=0（不再直接失败）"
ck "$(has 'am force-stop com\.dilinkauto\.server' "$TRACE")" "1" "兜底确实调用了 op_reconnect 的 force-stop"
ck "$(has 'monkey -p com\.dilinkauto\.server' "$TRACE")" "1" "兜底确实调用了 op_reconnect 的 monkey"
ck "$(has 'input text' "$TRACE")" "0" "按钮不可达时不向任何输入框写 IP（S19：不污染目标帧率框）"
ck "$(has '^TAP ' "$TRACE")" "0" "按钮不可达时不点任何 bounds"
ck "$(has 'keyevent' "$TRACE")" "0" "按钮不可达时不发 KEYCODE_DEL"
ck "$(xcount "$OUT")" "0" "降级成功不新增 ✗（记账不变量：log_err 才出 ✗）"
ck "$(has '重启 app.*原语' "$OUT")" "1" "输出如实交代走了重启兜底"

# ── 用例②：按钮可达 → UI 主路径必须保留 ──────────────────────────────
run_case 1 UNKNOWN UNKNOWN STREAMING
ck "$(cat "$TMPD/rc")" "0" "按钮可达 + UI 建流 → op_connect rc=0"
ck "$(has 'input text 192\.168\.3\.20' "$TRACE")" "1" "UI 主路径仍执行 input text"
ck "$(has '^TAP ' "$TRACE")" "1" "UI 主路径仍点击 bounds"
ck "$(has 'am force-stop|monkey -p' "$TRACE")" "0" "按钮可达时不触发重启兜底（mock 回放 S01 不受影响）"
ck "$(has '已点击「连接」' "$OUT")" "1" "UI 主路径日志保留"

# ── 用例③：收敛超时 → rc 语义与失败文案不变 ───────────────────────────
run_case 0 IDLE IDLE IDLE
ck "$(cat "$TMPD/rc")" "1" "收敛不到 STREAMING → op_connect 仍 rc=1"
ck "$(has 'op_connect 超时：car_state=IDLE' "$OUT")" "1" "超时文案保留（#173 原始失败形态）"
ck "$(xcount "$OUT")" "1" "超时只产生 1 条 ✗（不重复记账）"
ck "$(has 'am force-stop' "$TRACE")" "1" "rc=1 前兜底确实执行过（不是没走就直接失败）"

# ── 用例④：源码级记账纪律与生产默认值 ───────────────────────────────
# baseline 副本模式下跳过：副本已把窗口置 0、base 本就没有两个 helper，
# 让 base-red 的红签名集中在「按钮不可达时零兜底」这一条症状上。
if [ "$BASELINE_COPY" = "1" ]; then
  echo "  - baseline 副本模式：跳过源码级断言（见上）"
else
  BODY=$(sed -n '/^op_connect() {/,/^}/p' "$ROOT/scripts/lib/ops.sh")
  ck "$(printf '%s' "$BODY" | grep -c 'bad(' || true)" "0" "op_connect 段不得引入 bad()（FAIL == bad() 不变量）"
  ck "$(printf '%s' "$BODY" | grep -c 'E2E_CONNECT_AUTO_WAIT_S:-30' || true)" "1" "生产自动连接窗口默认仍是 30s"
  ck "$(printf '%s' "$BODY" | grep -c 'E2E_CONNECT_SETTLE_WAIT_S:-180' || true)" "1" "生产收敛窗口默认仍是 180s"
  ck "$(grep -c '^_connect_manual_ui()' "$ROOT/scripts/lib/ops.sh" || true)" "1" "手动 UI 已抽成独立 helper"
  ck "$(grep -c '^_connect_restart_fallback()' "$ROOT/scripts/lib/ops.sh" || true)" "1" "重启兜底已抽成独立 helper"
fi

echo
if [ "$fail" = "0" ]; then
  echo "op_connect 接缝单测全过（$pass）"
  exit 0
fi
echo "op_connect 接缝单测失败 $fail/$((pass+fail))"
exit 1
