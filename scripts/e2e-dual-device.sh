#!/bin/bash
# DiLink-Auto 双手机真机 E2E — 主编排（唯一入口）
#
# 拓扑（docs/e2e-scenarios.md §1）：B = 发送端（app-client，9637 监听、本机跑引擎）；
# A = 接收端（app-server，经 adb 连 B:5555 部署引擎、拉 9638 视频、回注 9639 触摸）。
# PC 双 USB adb 编排，两端同 WiFi。
#
# 流程（9 步，.plan/e2e-harness-design.md §1.4）：
#   0/9 参数解析 → --list/--dry-run 早退；场景发现 + 元数据校验；check-registry；
#       互斥锁（防双编排抢设备）
#   1/9 构建门（--skip-build 跳过）：gradlew test + 双 APK + dex 符号校验
#   2/9 安装与拉起：双端 install -r + monkey；B 建无线 adb 旁路（tcpip 5555）
#   3/9 preflight：双端在线 / PIN 已设 / 同网双向 ping / 9637 冲突清理
#   4/9 解锁（unlock_device×2）：硬失败 exit 2，禁重试循环猜 PIN（failsafe 5 次锁 30min）
#   5/9 中立态建立：op_neutral + 六项中立核验
#   6/9 场景循环（P0→P1→P2 序）：setup → ops → asserts → (失败? freeze) → restore
#   7/9 汇总：summary.txt + 控制台报表
#   8/9 退出码：0 全绿；1 有失败场景；2 用法/环境/构建/preflight/解锁失败
#
# 用法：
#   export DILINK_TEST_PIN_PHONE=<B 的 PIN>
#   export DILINK_TEST_PIN_CAR=<A 的 PIN>
#   bash scripts/e2e-dual-device.sh --phone <B_usb_serial> --car <A_usb_serial> \
#        [--scenario S01|S01,S05|P0|P1|P2|all] [--skip-build] [--include-manual]
#        [--list] [--dry-run] [--physical-ops on]
#
# PIN 不进门参数（弱密码不进 git，.plan/adb-unlock-for-e2e.md §5）。
# --phone/--car 必填：双机在册时自动挑设备会挑错（PIN 输错机器 → failsafe 计数）。
set -uo pipefail

# ── WorkBuddy 会话 shim 消毒（2026-10-16 真机实测；同 lib/mock-adb/run-mock-test.sh 范式）──
# BASH_ENV 注入的 rm/rmdir/unlink 函数走 broker IPC——单次 rm ~15s，且对 /tmp 等路径报
# SAFE_DELETE_INVALID_PATH。不消毒的后果实测：freeze_scene 一次取证花 2m38s、截图清理
# 失败导致取证件丢失、错误行污染 state-snapshot.txt。脚本自身只用 rm -f 清明确路径的
# 临时文件，直调真 rm 安全。
unset -f rm rmdir unlink 2>/dev/null || true
unset BASH_ENV
# 第二层消毒（2026-10-16 真机实证）：unset -f 只解除 BASH_ENV 注入的函数层，command -v rm
# 仍命中 PATH 第 2 位的 ...\shim\safe-bin/rm 替身（对含盘符路径报 SAFE_DELETE_INVALID_PATH，
# 单次 broker IPC ~15s，还会污染 state-snapshot.txt 等取证件）。函数覆盖指向 /usr/bin 真
# 二进制（GNU coreutils 8.32，已实证 63KB 真件）。不用 PATH 前置：会改变 python 等其他
# 命令的解析顺序（副作用面更大）。函数在同进程与 $( ) 子 shell 均生效；harness 无
# bash -c 'rm' 类跨进程调用（已 grep 核对全部 rm 调用点）。
if [ -x /usr/bin/rm ]; then
  rm()     { /usr/bin/rm "$@"; }
  rmdir()  { /usr/bin/rmdir "$@"; }
  unlink() { /usr/bin/unlink "$@"; }
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LIB="$ROOT/scripts/lib"
SCEN_DIR="$ROOT/scripts/scenarios"

# ── 参数 ────────────────────────────────────────────────────────────────
PHONE_SERIAL=""
CAR_SERIAL=""
SCENARIO_SEL="P0"
SKIP_BUILD=0
INCLUDE_MANUAL=0
DO_LIST=0
DRY_RUN=0
PHYSICAL_OPS="${PHYSICAL_OPS:-off}"
while [ $# -gt 0 ]; do
  case "$1" in
    --phone)  PHONE_SERIAL="${2:-}"; shift ;;
    --car)    CAR_SERIAL="${2:-}";   shift ;;
    --scenario) SCENARIO_SEL="${2:-}"; shift ;;
    --skip-build) SKIP_BUILD=1 ;;
    --include-manual) INCLUDE_MANUAL=1 ;;
    --physical-ops) PHYSICAL_OPS="${2:-on}"; shift ;;
    --list)   DO_LIST=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
  shift
done

# ── 层依赖（顺序即契约：logging → devices → asserts → ops → _common → 场景）──
. "$LIB/logging.sh"
. "$LIB/devices.sh"
. "$LIB/asserts.sh"
. "$LIB/ops.sh"
. "$SCEN_DIR/_common.sh"   # reach_streaming / neutral_verified / assert_* 组合

# op id 全集（ops.sh 已 source 进本进程，直接 compgen；op_log 是内部 helper 不算 op）。
# 本机 fork/exec ~1s——校验绝不能每个场景去 fork 一次 ops.sh。
# 纯 bash 收集（compgen 内建 + read 循环；旧 grep|sed|sort|tr 管线 6 proc ≈ 5s）。
OPS_IDS=" "
while IFS= read -r _fn; do
  case "$_fn" in
    op_*) [ "$_fn" = "op_log" ] || OPS_IDS+="${_fn#op_} " ;;
  esac
done < <(compgen -A function)
unset _fn

# ── 通用小工具 ───────────────────────────────────────────────────────────
hr() { printf '%s\n' "------------------------------------------------------------"; }
step() { echo; echo "==================== [$(date +%H:%M:%S)] $* ===================="; }
info() { echo "  · $*"; }
PASS=0; FAIL=0; SKIPPED=0; ENV_BROKEN=0; SCEN_INCONCLUSIVE=0
# ok/bad 同时累加两套账：PASS/FAIL（本次运行总账，含编排层自身断言）与
# E2E_PASS/E2E_FAIL（asserts.sh 场景台账——run_scenario 用其增量做 ledger 判定，见
# §判定 dfail）。2026-10-16 修：此前覆盖 ok/bad 后 E2E_* 永不增长 → "ledger ✗=0"
# 恒真，ledger 判定形同虚设（P6：缺断言=没测）。
# 不变量（2026-10-16 二修，防重复计数）：FAIL == bad() 调用数 == 控制台 ✗ 行数；
# PASS == ok() 调用数。除本处定义外，任何地方都**不得**手动改 PASS/FAIL——
# 场景判定等路径一律走 bad()（曾有三处手动 FAIL+1 与 bad() 叠加，mock S02 实测
# 1 个 ✗ 报成「失败 3 项」）。log_ok/log_err 是信息行（op 内部进度/现场记录），
# 不进两套账；op 失败经 ops_failed → 场景 bad() 记账。
ok()   { echo "  ✓ $*"; PASS=$((PASS + 1)); E2E_PASS=$((E2E_PASS + 1)); }
bad()  { echo "  ✗ $*"; FAIL=$((FAIL + 1)); E2E_FAIL=$((E2E_FAIL + 1)); }
skip() { echo "  - $*"; SKIPPED=$((SKIPPED + 1)); }

CLIENT_APK="$ROOT/app-client/build/outputs/apk/debug/app-client-debug.apk"
SERVER_APK="$ROOT/app-server/build/outputs/apk/debug/app-server-debug.apk"
E2E_CLIENT_APK="$CLIENT_APK"   # op_reinstall 消费
export E2E_CLIENT_APK

command -v adb > /dev/null 2>&1 || { echo "✗ 找不到 adb"; exit 2; }

# ═════════════════════════════════════════════════════════════════════════
# 场景发现与元数据
# ═════════════════════════════════════════════════════════════════════════
# 元数据块（# @key: value），必填键缺失 = 注册失败 exit 2
META_KEYS_REQUIRED="id name pre post budget_s priority expect invariants ops"
# 元数据缓存：本机 fork/exec ~0.7s（杀软实时扫描），per-key 调 sed|head 在
# 22 场景 × 6 键规模下 ≈ 400 次 fork ≈ 6-8 分钟（2026-10-16 实测：--list 跑
# 25s 才校验到 s01 的 @ops）。发现阶段用纯 bash read 循环一次灌满，之后零
# fork；缓存未命中（外部单独调用）回落原 sed|head 实现。
declare -A SCEN_META=() SCEN_META_LOADED=()
load_scenario_meta() { # <file>：单次 read 循环提取全部 '# @key: value'（0 fork）
  local f=$1 line k v
  while IFS= read -r line; do
    case "$line" in
      '# @'*)
        k=${line#\# @}; k=${k%%:*}
        case "$line" in
          *': '*) v=${line#*: } ;;
          *':')   v="" ;;
          *)      continue ;;
        esac
        v=${v%$'\r'}
        while [ "${v# }" != "$v" ]; do v=${v# }; done
        while [ "${v% }" != "$v" ]; do v=${v% }; done
        # first-wins（对齐旧 sed|head 语义：s22 的文档注释行也以 '# @expect:' 开头）
        if [ -z "${SCEN_META[$f|$k]+x}" ]; then SCEN_META["$f|$k"]=$v; fi
        ;;
    esac
  done < "$f"
  SCEN_META_LOADED[$f]=1
}
META_V=""   # scenario_meta_var 的输出槽（免 $(...) 子 shell——本机 fork ~0.7s）
scenario_meta_var() { # <file> <key> → 结果进 $META_V（已加载文件 0 fork，缺键=空）
  local f=$1 k=$2
  # 注意：已加载文件里「键不存在」也必须走内存路径（返回空）——否则 S01 等
  # 无 @manual 行的场景每查一次 manual 就回落 sed|head ≈ 1.2s（2026-10-16 实测
  # --list 因此多花 26s）。
  if [ -n "${SCEN_META_LOADED[$f]:-}" ]; then META_V=${SCEN_META[$f|$k]:-}; return 0; fi
  META_V=$(sed -n 's/^# @'"$k"': *//p' "$f" | head -1)
}
scenario_files() { # 发现 sNN-*.sh（_ 前缀排除）
  local f
  for f in "$SCEN_DIR"/s[0-9][0-9]-*.sh; do
    [ -f "$f" ] || continue
    echo "$f"
  done
}
validate_scenario() { # <file> → 0=合法
  local f=$1 k v
  load_scenario_meta "$f"
  for k in $META_KEYS_REQUIRED; do
    scenario_meta_var "$f" "$k"; v=$META_V
    [ -z "$v" ] && { echo "  ✗ $f 缺必填元数据 @$k"; return 1; }
  done
  # id 与文件名前缀一致（文件名小写 sNN，@id 大写 SNN——大小写不敏感比较）
  local id base
  scenario_meta_var "$f" id; id=$META_V
  base=${f##*/}; base=${base%%-*}     # 纯 bash（basename/tr 各 1 fork ≈ 0.9s）
  base=${base^^}
  [ "$id" = "$base" ] || { echo "  ✗ $f 的 @id($id) 与文件名前缀($base) 不一致"; return 1; }
  # priority / expect 取值合法
  scenario_meta_var "$f" priority
  case "$META_V" in P0|P1|P2) ;; *) echo "  ✗ $f @priority 非法"; return 1 ;; esac
  scenario_meta_var "$f" expect
  case "$META_V" in pass|fail) ;; *) echo "  ✗ $f @expect 非法"; return 1 ;; esac
  # budget_s 必须是正整数
  scenario_meta_var "$f" budget_s; v=$META_V
  case "$v" in ''|*[!0-9]*) echo "  ✗ $f @budget_s 非正整数：$v"; return 1 ;; esac
  # ops 列表里每个 id 必须存在于 ops.sh（check-registry 同源）
  local op ok_all=1
  scenario_meta_var "$f" ops
  for op in ${META_V//,/ }; do
    case "$OPS_IDS" in
      *" $op "*) ;;
      *) echo "  ✗ $f @ops 含未知 op：$op"; ok_all=0 ;;
    esac
  done
  [ "$ok_all" = "1" ] || return 1
  return 0
}

ALL_SCEN=()
discover_scenarios() {
  local f bad=0
  while IFS= read -r f; do
    ALL_SCEN+=("$f")
    validate_scenario "$f" || bad=1
  done < <(scenario_files)
  [ "${#ALL_SCEN[@]}" -gt 0 ] || { echo "✗ 未发现任何场景（$SCEN_DIR/s[0-9][0-9]-*.sh）" >&2; return 1; }
  # 语法门（批量：cat|bash -n 一次覆盖全部 ≈ 2 proc；失败才逐文件定位。
  # 拼接后各文件仍是完整脚本，语法合法性不受拼接影响）
  if ! cat -- "${ALL_SCEN[@]}" | bash -n 2>/dev/null; then
    echo "✗ 场景文件存在语法错误（逐文件定位）：" >&2
    for f in "${ALL_SCEN[@]}"; do bash -n "$f" || echo "  ✗ $f bash -n 失败" >&2; done
    bad=1
  fi
  [ "$bad" = "0" ] || { echo "✗ 场景注册校验失败（见上）" >&2; return 1; }
  return 0
}

# 场景选择：S01 / S01,S05 / P0 / P1 / P2 / all（可重复 --scenario）
# 逗号与空格都当时隔符（--scenario "S01,S03" 与 --scenario S01 --scenario S03 等价）
select_scenarios() {
  local sel want out=() f id pri
  for sel in ${SCENARIO_SEL//,/ }; do
    case "$sel" in
      all) want="ALL" ;;
      P0|P1|P2) want="$sel" ;;
      S[0-9][0-9]) want="$sel" ;;
      *) echo "✗ --scenario 取值非法：$sel（S01|S01,S05|P0|P1|P2|all）" >&2; return 2 ;;
    esac
    for f in "${ALL_SCEN[@]}"; do
      scenario_meta_var "$f" id; id=$META_V
      scenario_meta_var "$f" priority; pri=$META_V
      if [ "$want" = "ALL" ] || [ "$want" = "$pri" ] || [ "$want" = "$id" ]; then
        case " ${out[*]:-} " in *" $f "*) ;; *) out+=("$f") ;; esac
      fi
    done
  done
  # P0→P1→P2 排序（同优先级内按 id）
  SELECTED=()
  local p
  for p in P0 P1 P2; do
    for f in "${out[@]:-}"; do
      [ -n "$f" ] || continue
      scenario_meta_var "$f" priority
      [ "$META_V" = "$p" ] && SELECTED+=("$f")
    done
  done
  [ "${#SELECTED[@]}" -gt 0 ] || { echo "✗ --scenario $SCENARIO_SEL 未选中任何场景" >&2; return 2; }
  return 0
}

print_scenario_table() {
  local f
  printf '%-6s %-4s %-7s %-7s %-6s %-8s %s\n' ID PRI EXPECT MANUAL BUDGET OPS NAME
  for f in "${SELECTED[@]:-}"; do
    [ -n "$f" ] || continue
    scenario_meta_var "$f" id; local mid=$META_V
    scenario_meta_var "$f" priority; local mpri=$META_V
    scenario_meta_var "$f" expect; local mexp=$META_V
    scenario_meta_var "$f" manual; local mman=$META_V
    scenario_meta_var "$f" budget_s; local mbud=$META_V
    scenario_meta_var "$f" ops; local mops=$META_V
    scenario_meta_var "$f" name; local mname=$META_V
    printf '%-6s %-4s %-7s %-7s %-6s %-8s %s\n' \
      "$mid" "$mpri" "$mexp" "$mman" "$mbud" "$mops" "$mname"
  done
}

# ═════════════════════════════════════════════════════════════════════════
# 0/9 参数与注册（--list / --dry-run 在此早退）
# ═════════════════════════════════════════════════════════════════════════
discover_scenarios || exit 2

if [ "$DO_LIST" = "1" ]; then
  SCENARIO_SEL="all"; select_scenarios || exit 2
  echo "场景清单（共 ${#SELECTED[@]} 个）："
  print_scenario_table
  exit 0
fi

if [ "$DRY_RUN" = "1" ]; then
  # 层②构造验证：参数 → 角色格式（不查在线）→ 场景校验 → registry → bash -n → 计划
  echo "== --dry-run：命令计划构造检查（不碰设备、不构建）=="
  [ -n "$PHONE_SERIAL" ] && [ -n "$CAR_SERIAL" ] || { echo "✗ dry-run 也要求 --phone/--car（校验角色格式）" >&2; exit 2; }
  select_scenarios || exit 2
  echo "角色：phone=$PHONE_SERIAL（发送端 B）  car=$CAR_SERIAL（接收端 A）"
  echo "APK：client=$CLIENT_APK"; echo "     server=$SERVER_APK"
  echo
  echo "选中场景（${#SELECTED[@]} 个）："
  print_scenario_table
  echo
  echo "每场景计划（setup → ops → asserts → restore；restore 恒 run_op neutral）："
  for f in "${SELECTED[@]}"; do
    scenario_meta_var "$f" id; sid=$META_V
    scenario_meta_var "$f" name
    echo "  [$sid] $META_V"
    scenario_meta_var "$f" pre;  dpre=$META_V
    scenario_meta_var "$f" post; dpost=$META_V
    scenario_meta_var "$f" budget_s; dbud=$META_V
    scenario_meta_var "$f" ops;  dops=$META_V
    echo "        pre=$dpre  post=$dpost  budget_s=$dbud"
    echo "        ops=$dops"
    echo "        产物：debug-logs/e2e/<TS>/$sid/"
  done
  echo
  echo "构建门：$([ "$SKIP_BUILD" = "1" ] && echo '跳过（--skip-build）' || echo './gradlew test + assembleDebug×2 + dex 符号校验')"
  echo "PIN：$([ -n "${DILINK_TEST_PIN_PHONE:-}" ] && [ -n "${DILINK_TEST_PIN_CAR:-}" ] && echo '两端已设（环境变量）' || echo '未设——实跑前必须 export DILINK_TEST_PIN_PHONE/CAR')"
  echo
  echo "dry-run 通过（未执行任何设备/构建命令）"
  exit 0
fi

# 实跑：角色必填 + PIN 必填（弱密码不进 git）
[ -n "$PHONE_SERIAL" ] && [ -n "$CAR_SERIAL" ] || {
  echo "✗ 必须显式指定两台设备：--phone <B_serial> --car <A_serial>" >&2
  echo "  （双机在册时自动挑设备会挑错，PIN 会输到别的机器上并计入那台 failsafe，见 .plan/adb-unlock-for-e2e.md §5）" >&2
  adb devices -l | awk 'NR>1 && $2=="device" {print "    " $1}' >&2
  exit 2
}
: "${DILINK_TEST_PIN_PHONE:?export DILINK_TEST_PIN_PHONE（B 的解锁 PIN；不进门参数，防弱密码进 git）}"
: "${DILINK_TEST_PIN_CAR:?export DILINK_TEST_PIN_CAR（A 的解锁 PIN）}"

# check-registry（explore.py 若在位就跑；不在位不阻塞——JSON 是 P2 资产）
if [ -f "$LIB/explore.py" ] && [ -f "$LIB/ops-registry.json" ]; then
  step "0/9 check-registry：ops-registry.json ↔ ops.sh list"
  if python "$LIB/explore.py" check-registry; then
    ok "注册表一致"
  else
    bad "注册表不一致（explore.py check-registry）——修复后再跑"
    exit 2
  fi
fi

select_scenarios || exit 2

# 互斥锁：防双编排抢设备（陈旧锁按 PID 存活判断后告警，不自动删）
# 产物目录：默认按时间戳；统一入口 scripts/e2e.sh 用 E2E_RUN_ID 指定子目录，
# 让一次「全量」运行的所有产物落在同一个 run 目录下（<run>/dual、<run>/desktop）。
TS="${E2E_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN="debug-logs/e2e/$TS"
mkdir -p "$RUN" || { echo "✗ 无法创建产物目录 $RUN"; exit 2; }
LOCKDIR="debug-logs/e2e/.lock"
if ! mkdir "$LOCKDIR" 2>/dev/null; then
  LOCKPID=$(cat "$LOCKDIR/pid" 2>/dev/null | tr -d '\r\n ')
  if [ -n "$LOCKPID" ] && kill -0 "$LOCKPID" 2>/dev/null; then
    echo "✗ 另一个编排正在运行（pid=$LOCKPID，锁=$LOCKDIR）。双编排抢设备会互相踩会话。" >&2
    exit 2
  fi
  echo "⚠ 发现陈旧锁（pid=${LOCKPID:-无} 已不存在），告警继续（按设计不自动删）"
fi
echo $$ > "$LOCKDIR/pid"
trap 'rm -f "$LOCKDIR/pid" 2>/dev/null; rmdir "$LOCKDIR" 2>/dev/null' EXIT

export E2E_RUN_DIR="$ROOT/$RUN"
export E2E_OP_RESULT="$E2E_RUN_DIR/sequence.jsonl"
export E2E_SEQ_LOG="$E2E_RUN_DIR/sequence.jsonl"
export PHYSICAL_OPS
export E2E_STRICT_PROBES="${E2E_STRICT_PROBES:-1}"
: > "$E2E_RUN_DIR/sequence.jsonl"

{
  echo "ts=$TS"
  echo "phone=$PHONE_SERIAL (sender B)"
  echo "car=$CAR_SERIAL (receiver A)"
  echo "scenario_sel=$SCENARIO_SEL"
  echo "physical_ops=$PHYSICAL_OPS include_manual=$INCLUDE_MANUAL skip_build=$SKIP_BUILD"
  echo "git_head=$(git rev-parse --short HEAD 2>/dev/null || echo n/a)"
  echo "client_apk=$CLIENT_APK"
  echo "server_apk=$SERVER_APK"
  echo "phone_model=$(adb -s "$PHONE_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n') android=$(adb -s "$PHONE_SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r\n')"
  echo "car_model=$(adb -s "$CAR_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n') android=$(adb -s "$CAR_SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r\n')"
} > "$E2E_RUN_DIR/run-meta.txt"
info "产物目录：$RUN"

# ═════════════════════════════════════════════════════════════════════════
# 1/9 构建门
# ═════════════════════════════════════════════════════════════════════════
step "1/9 构建门：./gradlew test + 双 APK + dex 符号校验"
if [ "$SKIP_BUILD" = "1" ]; then
  info "跳过（--skip-build）"
else
  info "执行中（可能数分钟）..."
  ./gradlew --console=plain test :app-client:assembleDebug :app-server:assembleDebug \
    > "$RUN/build.log" 2>&1
  BRC=$?
  if [ "$BRC" = "0" ]; then
    ok "BUILD SUCCESSFUL"
    T=$(find . -path "*/build/test-results/*" -name '*.xml' -exec grep -hoE 'tests="[0-9]+"' {} + 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {printf "%d", s+0}')
    FF=$(find . -path "*/build/test-results/*" -name '*.xml' -exec grep -hoE 'failures="[0-9]+"' {} + 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {printf "%d", s+0}')
    EE=$(find . -path "*/build/test-results/*" -name '*.xml' -exec grep -hoE 'errors="[0-9]+"' {} + 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {printf "%d", s+0}')
    if [ "${T:-0}" -gt 0 ] && [ "${FF:-1}" = "0" ] && [ "${EE:-1}" = "0" ]; then
      ok "单测 $T 个，0 失败 0 错误"
    else
      bad "单测异常：total=$T failures=$FF errors=$EE"
    fi
  else
    bad "构建失败（rc=$BRC，见 $RUN/build.log）"
    tail -25 "$RUN/build.log"
    # 有意偏离范式「失败不中断」：拿坏构建跑 22 个场景是数小时真机时间的浪费
    echo "✗ 构建门失败，不碰设备（修复后重跑，或 --skip-build 用已构建产物）" >&2
    exit 2
  fi
fi
for apk in "$CLIENT_APK" "$SERVER_APK"; do
  [ -f "$apk" ] || { echo "✗ 找不到 $apk（先构建，或去掉 --skip-build）" >&2; exit 2; }
done

# dex 符号校验（APK 内嵌引擎必须含本轮关键符号）
DEXCHK=$(python - "$CLIENT_APK" <<'PY'
import sys, zipfile, io
need = [b"ShellSyncPoint", b"__DILINK_SYNC__", b"awaitIdle", b"pgrep -f", b"stop-request"]
try:
    apk = zipfile.ZipFile(sys.argv[1])
    jar = zipfile.ZipFile(io.BytesIO(apk.read("assets/vd-server.jar")))
    dex = jar.read("classes.dex")
except Exception as e:
    print("ERROR:", e); sys.exit(0)
missing = [n.decode() for n in need if n not in dex]
print(f"dex={len(dex)} issues=" + (",".join(missing) if missing else "NONE"))
PY
)
echo "     $DEXCHK"
case "$DEXCHK" in
  *"issues=NONE"*) ok "classes.dex 含全部关键符号" ;;
  *) bad "dex 校验失败：$DEXCHK" ;;
esac

# ═════════════════════════════════════════════════════════════════════════
# 2/9 安装与拉起 + B 的无线 adb 旁路
# ═════════════════════════════════════════════════════════════════════════
step "2/9 安装 APK + monkey 拉起 + B 建无线 adb 旁路"
IOUT=$(adb -s "$PHONE_SERIAL" install -r "$CLIENT_APK" 2>&1)
if echo "$IOUT" | grep -q "Success"; then ok "client install -r Success"; else bad "client 安装失败：$(echo "$IOUT" | tail -2 | tr '\n' ' ')"; fi
IOUT=$(adb -s "$CAR_SERIAL" install -r "$SERVER_APK" 2>&1)
if echo "$IOUT" | grep -q "Success"; then ok "server install -r Success"; else bad "server 安装失败：$(echo "$IOUT" | tail -2 | tr '\n' ' ')"; fi
dphone "monkey -p com.dilinkauto.client -c android.intent.category.LAUNCHER 1" > /dev/null 2>&1
dcar   "monkey -p com.dilinkauto.server -c android.intent.category.LAUNCHER 1" > /dev/null 2>&1
ok "两端已 monkey 拉起"
# B 的无线 adb 通道：A 的 app 要经 5555 连 B 部署引擎。这里只下发 tcpip；
# 完整的幂等建立 + 硬件序列号校验在 preflight 的 F2（ensure_tcp_adb），
# 因为那一步需要 preflight 取到的 PHONE_IP。
# 幂等守卫：B 若已是 TCP 5555 连接（无线调试预切换场景），重发 tcpip 会打断
# 唯一通道（无线没有 USB 兜底）→ 跳过。
case "$PHONE_SERIAL" in
  *:5555)
    ok "B 已在 TCP 5555 模式（$PHONE_SERIAL，幂等跳过 tcpip）" ;;
  *)
    adb -s "$PHONE_SERIAL" tcpip 5555 > /dev/null 2>&1 \
      && ok "B 已进 TCP 模式（5555；授权框若弹，preflight F2 会经原通道代点）" \
      || bad "B tcpip 5555 下发失败" ;;
esac

# ═════════════════════════════════════════════════════════════════════════
# 3/9 preflight
# ═════════════════════════════════════════════════════════════════════════
step "3/9 preflight（双端在线 / PIN / 同网 / IP / 9637 冲突 / 基线快照）"
if { preflight "$DILINK_TEST_PIN_PHONE" "$DILINK_TEST_PIN_CAR" && preflight_extended; } > "$RUN/preflight.log" 2>&1; then
  ok "preflight 通过（明细 $RUN/preflight.log）"
  tail -8 "$RUN/preflight.log" | sed 's/^/     /'
else
  bad "preflight 失败（明细 $RUN/preflight.log）"
  tail -20 "$RUN/preflight.log" | sed 's/^/     /'
  echo "✗ preflight 失败，不碰设备" >&2
  exit 2
fi
# PHONE_IP/CAR_IP 由 preflight 填充（op_connect 消费 PHONE_IP）
[ -n "$PHONE_IP" ] && [ -n "$CAR_IP" ] || { echo "✗ preflight 未取到双端活动 WiFi IP（Meizu 可能是 wlan1，MI9 是 wlan0）" >&2; exit 2; }

# ═════════════════════════════════════════════════════════════════════════
# 4/9 解锁（禁重试循环猜 PIN；每设备累计失败 ≥2 → 全局 abort，D6）
# ═════════════════════════════════════════════════════════════════════════
step "4/9 解锁（MIUI 直输 / Flyme 上滑+聚焦；failsafe：连错 5 次锁 30 分钟）"
# 硬失败即 exit 2（设计 §1.4.2）：带锁屏跑会让所有场景假失败。note_pin_failure
# 记账（D6：每设备累计 ≥2 → abort；当前每次运行每设备只试一次，计数供日志与未来
# 重试路径使用）。禁重试循环猜 PIN——猜错设备的代价由真机 failsafe 承担。
if ! unlock_device "$PHONE_SERIAL" "$DILINK_TEST_PIN_PHONE" "B(client)"; then
  note_pin_failure phone || true
  bad "B 解锁失败——abort（人工确认 PIN 后重跑；别让脚本重试猜 PIN）"
  exit 2
fi
if ! unlock_device "$CAR_SERIAL" "$DILINK_TEST_PIN_CAR" "A(server)"; then
  note_pin_failure car || true
  bad "A 解锁失败——abort（人工确认 PIN 后重跑）"
  exit 2
fi
ok "两端已解锁（或本就没锁屏）"

# ═════════════════════════════════════════════════════════════════════════
# 5/9 中立态建立
# ═════════════════════════════════════════════════════════════════════════
step "5/9 中立态建立（op_neutral + 六项核验）"
if run_op neutral && neutral_verified; then
  ok "中立态就绪（@pre 复合态）"
else
  bad "中立态建立失败——环境不干净，拒绝开跑（先看上面哪一项不过）"
  exit 2
fi

# ═════════════════════════════════════════════════════════════════════════
# 6/9 场景循环
# ═════════════════════════════════════════════════════════════════════════
declare -a SUM_ID SUM_PRI SUM_EXP SUM_RES SUM_PASS SUM_FAIL SUM_SKIP SUM_SEC SUM_DIR
SCEN_TOTAL=0; SCEN_PASS=0; SCEN_FAIL=0

run_scenario() { # <file>
  local f=$1 sid name manual budget t0 arc ops_failed p0 f0 pri exp
  scenario_meta_var "$f" id;       sid=$META_V
  scenario_meta_var "$f" name;     name=$META_V
  scenario_meta_var "$f" manual;   manual=$META_V
  scenario_meta_var "$f" budget_s; budget=$META_V
  scenario_meta_var "$f" priority; pri=$META_V
  scenario_meta_var "$f" expect;   exp=$META_V
  SCEN_TOTAL=$((SCEN_TOTAL + 1))
  p0=$E2E_PASS; f0=$E2E_FAIL   # 场景级断言增量（E2E_PASS/FAIL 是跨场景累计的）
  mkdir -p "$E2E_RUN_DIR/$sid"
  export E2E_SCENARIO_ID="$sid"
  export E2E_SCENARIO_NAME="$name"
  export E2E_VDLOG="$E2E_RUN_DIR/$sid/vdlog.snapshot"
  export E2E_CLIENTLOG="$E2E_RUN_DIR/$sid/clientlog.snapshot"
  OPS_LOG="$E2E_RUN_DIR/$sid/ops.log"; export OPS_LOG
  : > "$OPS_LOG"
  step "场景 $sid：$name（budget ${budget}s）"

  if [ "$manual" = "true" ] && [ "$INCLUDE_MANUAL" != "1" ]; then
    skip "$sid SKIPPED(manual)：需人工物理动作（--include-manual 才跑）"
    SUM_ID+=("$sid"); SUM_PRI+=("$pri"); SUM_EXP+=("$exp")
    SUM_RES+=("SKIPPED(manual)"); SUM_PASS+=(0); SUM_FAIL+=(0); SUM_SKIP+=(1); SUM_SEC+=(0); SUM_DIR+=("$sid")
    return 0
  fi

  t0=$SECONDS
  # ── source 场景文件（四函数 + 元数据已在 discover 阶段校验）──
  # shellcheck source=/dev/null
  . "$f" || { bad "$sid source 失败"; return 1; }
  E2E_SCENARIO_SKIP=""   # 场景可设它来申请 SKIPPED(env)（S21 无 nc / S22 找不到撤销入口）
  E2E_SCENARIO_INCONCLUSIVE=""   # 场景设它来申请 INCONCLUSIVE（S21 A 端无连接尝试证据）
  # ── setup ──
  if ! scenario_setup; then
    bad "$sid setup 失败（未到 @pre 复合态）"
    freeze_scene "$sid-setup"
    ENV_BROKEN=1
    SUM_ID+=("$sid"); SUM_PRI+=("$pri"); SUM_EXP+=("$exp")
    SUM_RES+=("FAIL(setup)"); SUM_PASS+=("$((E2E_PASS - p0))"); SUM_FAIL+=("$((E2E_FAIL - f0))"); SUM_SKIP+=(0)
    SUM_SEC+=("$((SECONDS - t0))"); SUM_DIR+=("$sid")
    return 1
  fi
  # ── ops（失败不中断：先收证据，P2）──
  ops_failed=0
  if ! scenario_ops; then
    bad "$sid ops 失败（继续 asserts 收证据）"
    ops_failed=1
  fi
  # ── asserts ──
  scenario_asserts; arc=$?
  # ── 失败先冻结再 restore（P5：restore 会杀引擎/清哨兵，现场就没了）──
  if [ "$arc" != "0" ] || [ "$ops_failed" = "1" ]; then
    freeze_scene "$sid"
  fi
  # ── restore（无论成败必跑；失败 = 零残留被破坏）──
  if ! scenario_restore; then
    bad "$sid restore 失败（中立态未恢复，零残留破坏）"
    freeze_scene "$sid-restore"
    ENV_BROKEN=1
    SUM_ID+=("$sid"); SUM_PRI+=("$pri"); SUM_EXP+=("$exp")
    SUM_RES+=("FAIL(restore)"); SUM_PASS+=("$((E2E_PASS - p0))"); SUM_FAIL+=("$((E2E_FAIL - f0))"); SUM_SKIP+=(0)
    SUM_SEC+=("$((SECONDS - t0))"); SUM_DIR+=("$sid")
    return 1
  fi
  # ── 判定（.plan/e2e-harness-design.md §2.4 三态语义 + SKIPPED(env) 通道）──
  #   @expect: pass：asserts rc=0 且 ops 未失败且场景 ledger 无 ✗ → PASS
  #   @expect: fail ：判向全写在场景 ledger 里（按预期失败=ok、静默失败/无证据=bad、
  #                    主操作意外成功=bad）→ ledger 无 ✗ 且 ops 未失败 = PASS(expected)
  #   任一路径：场景设了 E2E_SCENARIO_SKIP → SKIPPED(env)，不计通过也不计失败
  #             场景设了 E2E_SCENARIO_INCONCLUSIVE → INCONCLUSIVE（A 端无连接尝试
  #             证据，无法判定产品是否按预期失败），不进 PASS/FAIL 账、退出码非 0
  local res dfail
  dfail=$((E2E_FAIL - f0))
  if [ -n "${E2E_SCENARIO_SKIP:-}" ]; then
    res="SKIPPED(env)"
    skip "$sid SKIPPED(env)：$E2E_SCENARIO_SKIP（未执行产品判定，不混入通过——P6）"
  elif [ -n "${E2E_SCENARIO_INCONCLUSIVE:-}" ]; then
    res="INCONCLUSIVE"
    info "$sid INCONCLUSIVE：$E2E_SCENARIO_INCONCLUSIVE（A 端无连接尝试证据，需人工 review）"
    freeze_scene "$sid-inconclusive"   # INCONCLUSIVE 现场也要冻结供 review（asserts return 0 不会经 :560 触发）
  elif [ "$exp" = "fail" ]; then
    if [ "$dfail" = "0" ] && [ "$ops_failed" = "0" ]; then
      res="PASS(expected)"; ok "$sid 按预期失败（@expect: fail 达成，证据在位）"
    else
      res="FAIL"; bad "$sid 预期失败场景未按设计失败（ledger ✗=$dfail ops_failed=$ops_failed）——静默失败/无证据/意外成功，见上"
    fi
  else
    if [ "$arc" = "0" ] && [ "$ops_failed" = "0" ] && [ "$dfail" = "0" ]; then
      res="PASS"; ok "$sid 通过（$((SECONDS - t0))s）"
    else
      res="FAIL"; bad "$sid 失败（asserts rc=$arc ops_failed=$ops_failed ledger ✗=$dfail）"
    fi
  fi
  case "$res" in
    PASS|PASS\(expected\)) SCEN_PASS=$((SCEN_PASS + 1)) ;;
    SKIPPED\(env\)) SKIPPED=$((SKIPPED + 1)) ;;
    INCONCLUSIVE) SCEN_INCONCLUSIVE=$((SCEN_INCONCLUSIVE + 1)) ;;
    # 2026-10-16 修：此处不再手动 FAIL+1——res=FAIL 的三条路径都先经 bad()（已 +1），
    # 手动再 +1 会让「失败 N 项」比控制台 ✗ 行数虚高（mock S02 实测 1 ✗ 却报 3）。
    *) SCEN_FAIL=$((SCEN_FAIL + 1)) ;;
  esac
  SUM_ID+=("$sid"); SUM_PRI+=("$pri"); SUM_EXP+=("$exp")
  SUM_RES+=("$res"); SUM_PASS+=("$((E2E_PASS - p0))"); SUM_FAIL+=("$((E2E_FAIL - f0))"); SUM_SKIP+=(0)
  SUM_SEC+=("$((SECONDS - t0))"); SUM_DIR+=("$sid")
  [ "$res" = "PASS" ] || return 1
  return 0
}

step "6/9 场景循环（选中 ${#SELECTED[@]} 个；P0 未全绿不跑 P1/P2）"
P0_ALL_GREEN=1
for f in "${SELECTED[@]}"; do
  [ -n "$f" ] || continue
  scenario_meta_var "$f" priority; pri=$META_V
  if [ "$pri" != "P0" ] && [ "$P0_ALL_GREEN" != "1" ]; then
    # 用户显式单点指定时也放行（--scenario S05 / --scenario "S01,S05" 都是显式）
    case " ${SCENARIO_SEL//,/ } " in *" S"[0-9][0-9]" "*) ;; *) continue ;; esac
  fi
  run_scenario "$f" || true
  [ "$ENV_BROKEN" = "1" ] && { bad "环境损坏（setup/restore 失败），中止后续场景"; break; }
  # P0 收口：跑完一个 P0 场景后检查是否有 P0 失败
  if [ "$pri" = "P0" ]; then
    p0_bad=0
    for i in "${!SUM_ID[@]}"; do
      [ "${SUM_PRI[$i]}" = "P0" ] || continue
      case "${SUM_RES[$i]}" in PASS|PASS\(expected\)|SKIPPED*|INCONCLUSIVE) ;; *) p0_bad=1 ;; esac
    done
    [ "$p0_bad" = "1" ] && P0_ALL_GREEN=0
  fi
done

# ═════════════════════════════════════════════════════════════════════════
# 7/9 汇总
# ═════════════════════════════════════════════════════════════════════════
step "7/9 汇总"
{
  echo "DiLink-Auto 双手机 E2E 汇总（$TS）"
  echo "phone=$PHONE_SERIAL  car=$CAR_SERIAL  scenario=$SCENARIO_SEL"
  echo
  printf '%-6s %-4s %-7s %-16s %s\n' ID PRI EXPECT RESULT SECONDS
  for i in "${!SUM_ID[@]}"; do
    printf '%-6s %-4s %-7s %-16s %s\n' "${SUM_ID[$i]}" "${SUM_PRI[$i]}" "${SUM_EXP[$i]}" "${SUM_RES[$i]}" "${SUM_SEC[$i]}"
  done
  echo
  echo "场景：$SCEN_PASS 通过 / $SCEN_FAIL 失败 / $SCEN_INCONCLUSIVE 无法判定 / 共 $SCEN_TOTAL"
  echo "断言：通过 $PASS 项，失败 $FAIL 项"
  echo "产物目录：$RUN"
} | tee "$E2E_RUN_DIR/summary.txt"

# ═════════════════════════════════════════════════════════════════════════
# 8/9 退出码 + 收尾中立化（防带着会话/哨兵/引擎离开）
# ═════════════════════════════════════════════════════════════════════════
step "8/9 收尾：确保设备回中立态"
run_op neutral || bad "收尾中立化失败（手动检查：引擎/哨兵/9637）"
neutral_verified && ok "收尾中立态核验通过" || bad "收尾中立态核验未过（见上）"

echo
hr
echo "结果：场景 $SCEN_PASS 通过 / $SCEN_FAIL 失败 / $SCEN_INCONCLUSIVE 无法判定；断言 $PASS 通过 / $FAIL 失败"
echo "产物目录：$RUN（summary.txt / sequence.jsonl / freeze-*/ / neutral-archive/）"
if [ "$FAIL" = "0" ] && [ "$SCEN_FAIL" = "0" ] && [ "$SCEN_INCONCLUSIVE" = "0" ]; then
  echo "全部通过 ✓"
  exit 0
fi
if [ "$SCEN_FAIL" = "0" ] && [ "$SCEN_INCONCLUSIVE" -gt 0 ]; then
  echo "有 $SCEN_INCONCLUSIVE 项无法判定（INCONCLUSIVE）——需人工 review，见 $RUN"
  exit 1
fi
echo "有失败项——现场与冻结件见 $RUN"
echo "排查入口：docs/e2e-dual-device-runbook.md"
exit 1
