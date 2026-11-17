#!/bin/bash
# car_state_now() 接缝单测（issue #31 回归钉子）
#
# CAR_STATE_SAMPLER 注入假采样器，断言 car_state_now 的判定边界：
#   - B 进程缺失 → 返 UNKNOWN（不再早退为 IDLE）
#   - 拉起中瞬间非 IDLE（接缝契约：返回值由采样器决定，UNKNOWN 是合法的「不可观测」）
#
# 跑法：bash scripts/lib/test-car-state-now.sh
# 挂进 run-mock-test.sh step 1/4 之后（bash -n 通过后立即跑接缝单测）。

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/scripts/lib/logging.sh"
. "$ROOT/scripts/lib/asserts.sh"

pass=0; fail=0
ck() { if [ "$1" = "$2" ]; then echo "  ok: $3"; pass=$((pass+1)); else echo "  FAIL: $3 — got '$1' want '$2'"; fail=$((fail+1)); fi; }

# 用例①：注入「B 进程缺失」假 sampler → car_state_now 应返 UNKNOWN（不早退为 IDLE）
_sampler_missing() { echo UNKNOWN; }
CAR_STATE_SAMPLER=_sampler_missing
ck "$(car_state_now)" "UNKNOWN" "B 进程缺失 → UNKNOWN（issue #31：不再早退为 IDLE）"
unset CAR_STATE_SAMPLER

# 用例②：注入「拉起中瞬间」假 sampler（进程还没在 = 语义上 UNKNOWN）→ 不应是 IDLE
# 接缝契约：car_state_now 不会单独把拉起中瞬间误判成 IDLE。早退逻辑在 s21 ops 循环
# （稳定采样）而非 asserts，这里钉的是「car_state_now 不把不可观测态谎报成 IDLE」。
_sampler_launching() { echo UNKNOWN; }
CAR_STATE_SAMPLER=_sampler_launching
got="$(car_state_now)"
[ "$got" = "IDLE" ] && ck "IDLE" "non-IDLE" "拉起中瞬间不应早退为 IDLE" \
                  || ck "non-IDLE" "non-IDLE" "拉起中瞬间不早退为 IDLE（实际=$got）"
unset CAR_STATE_SAMPLER

# 用例③（契约层）：接缝未设时 car_state_now 不应短路——生产路径走 dcar/dphone 采样。
# 这条由 run-mock-test.sh 层③回放覆盖（S01 PASS 即证明未短路），这里只断言接缝变量
# 确实被 unset（防止漏清污染同进程后续调用）。
ck "${CAR_STATE_SAMPLER:-unset}" "unset" "接缝用完已 unset（不污染生产路径）"

if [ "$fail" = "0" ]; then
  echo "car_state_now 接缝单测全过（$pass）"
  exit 0
fi
echo "car_state_now 接缝单测失败 $fail/$((pass+fail))"
exit 1
