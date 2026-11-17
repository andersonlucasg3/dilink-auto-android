#!/bin/bash
# DiLink-Auto E2E 统一入口 —— 一条命令、无人值守、自动出结论
#
# 三套件（下次会话只要说「跑车机手机的 e2e」/「跑电脑手机的 e2e」/「跑全量 e2e」）：
#   dual      车机+手机：A=车机端 app-server（USB），B=手机端 app-client（无线 TCP）
#   desktop   电脑+手机：app-desktop + 手机端 app-client
#   all       dual → desktop，产物落同一个 run 目录，出统一结论
#
# 用法：
#   bash scripts/e2e.sh <dual|desktop|all> [选项]
#   bash scripts/e2e.sh --list
#
# 选项：
#   --skip-build        跳过构建门（用已构建产物）
#   --scenario <sel>    dual 场景选择（S01 | S01,S05 | P0 | P1 | P2 | all；默认 all）
#   --include-manual    含需人工物理动作的场景（S22 adb 撤销授权）
#   --phone <serial>    覆盖自动发现的 B（发送端）
#   --car <serial>      覆盖自动发现的 A（接收端）
#   --serial <serial>   desktop 套件用（默认 = 自动发现的 phone）
#   --no-install        desktop 套件跳过 APK 安装
#   --retry <n>         失败场景自动复跑次数（默认 1；0 = 不复跑，复跑通过标记 FLAKY）
#   --yes               跳过桌面端「别动鼠标」倒计时（无人值守）
#   --physical-ops on   允许物理操作类场景（默认 off）
#   --dry-run           只做设备发现与计划，不跑
#   --list              列出套件与场景
#
# 退出码：0 全绿 / 1 有失败项 / 2 环境或用法问题（设备/PIN/构建/前置）
#
# 自动化边界（诚实说明）：
#   · 设备角色自动判定（包名 → 传输类型兜底），B 掉线时经 mDNS 自动重连；
#     两条都不成立才要求 --phone/--car 显式指定（绝不猜，猜错会把 PIN 输到别的机器）。
#   · PIN 从 .e2e.local.env（本机、git 忽略）或环境变量读；两者都没有 → 直接失败，不猜。
#   · desktop 套件会移动真实鼠标（Compose 窗口 UI 驱动），跑的时候电脑必须空闲；
#     --yes 只跳过倒计时，不改变这一点。
#   · 场景失败不中断（先收证据再 restore），失败场景自动复跑一次，结论里区分 FAIL / FLAKY。
set -uo pipefail

# ── WorkBuddy 会话 shim 消毒（同 scripts/e2e-dual-device.sh 的实证方案）──
unset -f rm rmdir unlink 2>/dev/null || true
unset BASH_ENV
if [ -x /usr/bin/rm ]; then
  rm()     { /usr/bin/rm "$@"; }
  rmdir()  { /usr/bin/rmdir "$@"; }
  unlink() { /usr/bin/unlink "$@"; }
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

DUAL="$ROOT/scripts/e2e-dual-device.sh"
DESKTOP="$ROOT/scripts/e2e-reconnect-cleanup.sh"
WIN_AUTO="$ROOT/debug-logs/uitest/win_auto.py"

# ── 输出小工具 ───────────────────────────────────────────────────────────
hr()    { printf '%s\n' "============================================================"; }
step()  { echo; echo "==================== [$(date +%H:%M:%S)] $* ===================="; }
info()  { echo "  · $*"; }
warn()  { echo "  ! $*"; }
ok()    { echo "  ✓ $*"; }
bad()   { echo "  ✗ $*"; }

# ── 参数 ────────────────────────────────────────────────────────────────
SUITE=""
SKIP_BUILD=0
SCENARIO_SEL="all"
INCLUDE_MANUAL=0
PHONE_ARG=""
CAR_ARG=""
SERIAL_ARG=""
NO_INSTALL=0
RETRY_N=1
ASSUME_YES=0
DRY_RUN=0
DO_LIST=0
PHYSICAL_OPS="${PHYSICAL_OPS:-off}"

while [ $# -gt 0 ]; do
  case "$1" in
    dual|car|car-phone|phone-car|车机|车机手机)   SUITE="dual" ;;
    desktop|pc|computer|电脑|电脑手机)            SUITE="desktop" ;;
    all|full|全量|全部)                           SUITE="all" ;;
    --skip-build)      SKIP_BUILD=1 ;;
    --scenario|--only) SCENARIO_SEL="${2:-}"; shift ;;
    --include-manual)  INCLUDE_MANUAL=1 ;;
    --phone)           PHONE_ARG="${2:-}"; shift ;;
    --car)             CAR_ARG="${2:-}"; shift ;;
    --serial)          SERIAL_ARG="${2:-}"; shift ;;
    --no-install)      NO_INSTALL=1 ;;
    --retry)           RETRY_N="${2:-1}"; shift ;;
    --no-retry)        RETRY_N=0 ;;
    --yes|-y)          ASSUME_YES=1 ;;
    --physical-ops)    PHYSICAL_OPS="${2:-on}"; shift ;;
    --dry-run)         DRY_RUN=1 ;;
    --list)            DO_LIST=1 ;;
    -h|--help)         sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
  shift
done

case "$RETRY_N" in ''|*[!0-9]*) echo "✗ --retry 需要非负整数（给的是 $RETRY_N）" >&2; exit 2 ;; esac

# ── --list（不需要设备/PIN）─────────────────────────────────────────────
if [ "$DO_LIST" = "1" ]; then
  echo "可用套件："
  echo "  dual      车机+手机（A=车机端 app-server / B=手机端 app-client）"
  echo "  desktop   电脑+手机（app-desktop + 手机端 app-client）"
  echo "  all       两个都跑（dual → desktop），出统一结论"
  echo
  echo "dual 场景清单："
  bash "$DUAL" --list
  exit 0
fi

[ -n "$SUITE" ] || { echo "✗ 缺少套件：bash scripts/e2e.sh <dual|desktop|all>（--list 看清单）" >&2; exit 2; }

RUN_ID="run-$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/debug-logs/e2e/$RUN_ID"
mkdir -p "$OUT" || { echo "✗ 无法创建产物目录 $OUT" >&2; exit 2; }
T0=$SECONDS
T_START="$(date '+%Y-%m-%d %H:%M:%S')"

# ═════════════════════════════════════════════════════════════════════════
step "0/5 环境自检（adb / PIN / 脚本）"
# ═════════════════════════════════════════════════════════════════════════
command -v adb > /dev/null 2>&1 || { echo "✗ 找不到 adb（PATH）" >&2; exit 2; }
[ -f "$DUAL" ]    || { echo "✗ 缺少 $DUAL" >&2; exit 2; }
[ -f "$DESKTOP" ] || { echo "✗ 缺少 $DESKTOP" >&2; exit 2; }
adb start-server > /dev/null 2>&1 || true

# PIN：环境变量优先，其次 .e2e.local.env（本机、git 忽略；被跟踪则拒绝运行 — 红线 8）
ENV_PIN_PHONE="${DILINK_TEST_PIN_PHONE:-}"
ENV_PIN_CAR="${DILINK_TEST_PIN_CAR:-}"
PIN_SRC="环境变量"
LOCAL_ENV="$ROOT/.e2e.local.env"
if [ -f "$LOCAL_ENV" ]; then
  if git -C "$ROOT" ls-files --error-unmatch ".e2e.local.env" > /dev/null 2>&1; then
    echo "✗ .e2e.local.env 被 git 跟踪（红线 8：PIN 禁落仓库）" >&2
    echo "  先执行：git rm --cached .e2e.local.env" >&2
    exit 2
  fi
  # shellcheck disable=SC1090
  set -a; . "$LOCAL_ENV"; set +a
  PIN_SRC=".e2e.local.env（本机、git 忽略）"
fi
[ -n "$ENV_PIN_PHONE" ] && DILINK_TEST_PIN_PHONE="$ENV_PIN_PHONE"
[ -n "$ENV_PIN_CAR" ]   && DILINK_TEST_PIN_CAR="$ENV_PIN_CAR"
export DILINK_TEST_PIN_PHONE="${DILINK_TEST_PIN_PHONE:-}"
export DILINK_TEST_PIN_CAR="${DILINK_TEST_PIN_CAR:-}"
if [ -z "$DILINK_TEST_PIN_PHONE" ] || [ -z "$DILINK_TEST_PIN_CAR" ]; then
  echo "✗ 缺 PIN（DILINK_TEST_PIN_PHONE = B 发送端，DILINK_TEST_PIN_CAR = A 接收端）" >&2
  echo "  方式一：export 两个环境变量；方式二：写 .e2e.local.env（.gitignore 已忽略）" >&2
  exit 2
fi
ok "PIN 已就绪（来源 $PIN_SRC，值不回显）"

# ═════════════════════════════════════════════════════════════════════════
step "1/5 设备角色自动发现"
# ═════════════════════════════════════════════════════════════════════════
# 判定优先级（绝不猜）：
#   1) 已装包名：com.dilinkauto.server → car；com.dilinkauto.client → phone
#   2) 传输类型：TCP → phone（拓扑要求 A 必须能经 5555 访问 B）；USB → car
#   3) 两条都不成立 → 要求显式 --phone/--car
# 每次 adb 往返本机约 1s，所以每台设备只用一次 shell 调用取「型号 + 包名」。
PHONE_SERIAL="${PHONE_ARG:-}"
CAR_SERIAL="${CAR_ARG:-}"
DESKTOP_SERIAL="${SERIAL_ARG:-}"

_scan_and_pick() {
  local -a SER=() MODEL=() ROLE=() CSCORE=() PSCORE=()
  local serial state rest i raw model has_srv has_cli role cs ps

  while read -r serial state rest; do
    # Windows 上 `adb devices` 输出是 CRLF：不清掉 \r 的话 state 永远是 "device\r"，
    # 所有设备都会被判成非 device 而跳过（2026-10-17 实测踩坑，表现为「一台都发现不了」）。
    state="${state%$'\r'}"
    [ -n "${serial:-}" ] || continue
    [ "$state" = "device" ] || continue
    case "$serial" in emulator-*) continue ;; esac   # 模拟器不是被测对象
    SER+=("$serial")
  done < <(adb devices 2>/dev/null | tail -n +2)

  [ "${#SER[@]}" -gt 0 ] || return 1

  for i in "${!SER[@]}"; do
    raw=$(adb -s "${SER[$i]}" shell 'getprop ro.product.model; pm list packages com.dilinkauto' 2>/dev/null | tr -d '\r')
    model=$(printf '%s\n' "$raw" | head -1)
    has_srv=0; has_cli=0
    case "$raw" in *package:com.dilinkauto.server*) has_srv=1 ;; esac
    case "$raw" in *package:com.dilinkauto.client*) has_cli=1 ;; esac
    if [ "$has_srv" = "1" ] && [ "$has_cli" = "1" ]; then role="both"
    elif [ "$has_srv" = "1" ]; then role="car"
    elif [ "$has_cli" = "1" ]; then role="phone"
    else role="none"; fi
    MODEL[$i]="${model:-?}"
    ROLE[$i]="$role"
  done

  for i in "${!SER[@]}"; do
    cs=0; ps=0
    case "${ROLE[$i]}" in
      car)   cs=2 ;;
      phone) ps=2 ;;
      both)  cs=1; ps=1 ;;
    esac
    case "${SER[$i]}" in
      *:*) ps=$((ps + 1)) ;;   # TCP 传输 → 更像发送端 B
      *)   cs=$((cs + 1)) ;;   # USB 传输 → 更像接收端 A
    esac
    CSCORE[$i]=$cs; PSCORE[$i]=$ps
  done

  # 打印发现表（一次 adb 调用换来的信息，全部展示，便于事后核对判定依据）
  info "在线设备（已排除模拟器）："
  for i in "${!SER[@]}"; do
    printf '       %-24s %-18s 角色=%-5s car分=%s phone分=%s\n' \
      "${SER[$i]}" "${MODEL[$i]}" "${ROLE[$i]}" "${CSCORE[$i]}" "${PSCORE[$i]}"
  done

  # 选 car（分最高）→ 选 phone（分最高且不是同一台）。
  # 显式参数优先：给了就直接用，同时把它从另一角色的候选里排除。
  local excl_for_c=-1 excl_for_p=-1
  for i in "${!SER[@]}"; do
    if [ -n "$PHONE_ARG" ] && [ "${SER[$i]}" = "$PHONE_ARG" ]; then excl_for_c=$i; fi
    if [ -n "$CAR_ARG" ]   && [ "${SER[$i]}" = "$CAR_ARG" ];   then excl_for_p=$i; fi
  done

  if [ -n "$CAR_ARG" ]; then
    CAR_SERIAL="$CAR_ARG"
  else
    local best_c=-1 cval=0
    for i in "${!SER[@]}"; do
      [ "$i" = "$excl_for_c" ] && continue
      if [ "${CSCORE[$i]}" -gt "$cval" ]; then cval=${CSCORE[$i]}; best_c=$i; fi
    done
    [ "$best_c" -ge 0 ] && [ "$cval" -ge 1 ] || return 1
    CAR_SERIAL="${SER[$best_c]}"
  fi

  if [ -n "$PHONE_ARG" ]; then
    PHONE_SERIAL="$PHONE_ARG"
  else
    local best_p=-1 pval=0
    for i in "${!SER[@]}"; do
      [ "${SER[$i]}" = "$CAR_SERIAL" ] && continue
      [ "$i" = "$excl_for_p" ] && continue
      if [ "${PSCORE[$i]}" -gt "$pval" ]; then pval=${PSCORE[$i]}; best_p=$i; fi
    done
    [ "$best_p" -ge 0 ] && [ "$pval" -ge 1 ] || return 1
    PHONE_SERIAL="${SER[$best_p]}"
  fi
  return 0
}

# B 掉线自愈：无线调试开着但 PC 侧没连（adb server 重启 / 手机重启后常见）。
# mDNS 优先经典 _adb._tcp（5555，正是 A 端要用的端口），再退 _adb-tls-connect。
_recover_phone_via_mdns() {
  local out name type addr
  out=$(timeout 15 adb mdns services 2>/dev/null) || { warn "adb mdns services 不可用"; return 1; }
  [ -n "$out" ] || { warn "mDNS 未发现任何服务"; return 1; }
  local want
  for want in "_adb._tcp." "_adb-tls-connect._tcp."; do
    while read -r name type addr; do
      type="${type%$'\r'}"; addr="${addr%$'\r'}"
      [ "${type:-}" = "$want" ] || continue
      [ -n "${addr:-}" ] || continue
      info "mDNS 发现 $type $addr → adb connect"
      adb connect "$addr" > /dev/null 2>&1 || true
      sleep 1
      if [ "$(adb -s "$addr" get-state 2>/dev/null | tr -d '\r\n')" = "device" ]; then
        ok "已连上 $addr"
        return 0
      fi
    done < <(printf '%s\n' "$out" | tail -n +2)
  done
  return 1
}

if [ -z "$PHONE_SERIAL" ] || [ -z "$CAR_SERIAL" ]; then
  if ! _scan_and_pick; then
    if [ -z "$PHONE_SERIAL" ] && [ -z "$PHONE_ARG" ]; then
      info "未发现手机端设备，尝试 mDNS 自动重连（无线调试）…"
      if _recover_phone_via_mdns; then
        _scan_and_pick || true
      fi
    fi
  fi
fi

if [ -z "$PHONE_SERIAL" ] || [ -z "$CAR_SERIAL" ]; then
  echo "✗ 角色判定失败：没能同时确定车机端(A)与手机端(B)" >&2
  echo "  在线设备：" >&2
  adb devices -l 2>/dev/null | tail -n +2 | sed 's/^/    /' >&2
  echo "  处理：插好两台后重跑；或用 --phone <B序列号> --car <A序列号> 显式指定" >&2
  exit 2
fi
ok "角色：phone(B)=$PHONE_SERIAL  car(A)=$CAR_SERIAL"
[ -n "$DESKTOP_SERIAL" ] || DESKTOP_SERIAL="$PHONE_SERIAL"
ok "desktop 目标：$DESKTOP_SERIAL"

# desktop 套件的前置：win_auto.py 必须存在（UI 驱动靠它）
if [ "$SUITE" != "dual" ] && [ ! -f "$WIN_AUTO" ]; then
  echo "✗ 缺 $WIN_AUTO（desktop 套件的 UI 驱动）" >&2
  exit 2
fi

# ── 1.5/5 A 的锁屏前置（只读检查，不消耗 PIN 尝试次数）──────────────
# 车机端 ROM（MI 9 / AOSP 15）的锁屏系统性忽略注入触摸（runbook §0）：脚本解锁会失败，
# 而且要到第 4 步才失败——先装 APK、跑 preflight，白烧 4 分钟。这里提前喊话。
_keyguard_locked() { # <serial>
  adb -s "$1" shell "dumpsys window | grep -oE 'isKeyguardShowing=(true|false)' | head -1" 2>/dev/null \
    | tr -d '\r\n' | grep -q 'true'
}
if [ "$SUITE" != "desktop" ] && _keyguard_locked "$CAR_SERIAL"; then
  warn "A($CAR_SERIAL) 处于锁屏：该 ROM 会忽略注入触摸，脚本解锁必失败（runbook §0）"
  if [ "$DRY_RUN" = "1" ]; then
    info "dry-run：跳过人工解锁等待"
  elif [ -t 0 ]; then
    printf '     请在 A 上人工解锁，完成后按回车继续（最多等 120 秒）… '
    if read -r -t 120 _; then
      ok "收到，继续"
    else
      echo
      bad "等待超时——解锁 A 后重跑（无人值守请先解锁再启动）"
      exit 2
    fi
  else
    warn "非交互会话：不等待，继续（若 A 无法自动解锁，会在解锁步骤以 ENV_FAIL 结束并给出原因）"
  fi
else
  [ "$SUITE" = "desktop" ] || ok "A 已解锁"
fi

if [ "$DRY_RUN" = "1" ]; then
  echo
  info "dry-run 计划："
  info "  suite=$SUITE  scenario=$SCENARIO_SEL  skip_build=$SKIP_BUILD  retry=$RETRY_N"
  if [ "$SUITE" != "desktop" ]; then
    info "  dual   ：E2E_RUN_ID=$RUN_ID/dual bash scripts/e2e-dual-device.sh --phone $PHONE_SERIAL --car $CAR_SERIAL --scenario $SCENARIO_SEL"
  fi
  if [ "$SUITE" != "dual" ]; then
    info "  desktop：E2E_RUN_ID=$RUN_ID/desktop bash scripts/e2e-reconnect-cleanup.sh --serial $DESKTOP_SERIAL"
  fi
  info "  产物目录：debug-logs/e2e/$RUN_ID/（verdict.md / verdict.json / 各套件 console 日志）"
  exit 0
fi

# ═════════════════════════════════════════════════════════════════════════
# dual 套件
# ═════════════════════════════════════════════════════════════════════════
DUAL_RC=0
DUAL_DIR=""
DUAL_TOTAL=0; DUAL_PASS=0; DUAL_FAIL=0; DUAL_SKIP=0; DUAL_FLAKY=0; DUAL_INCONCLUSIVE=0
DUAL_VERDICT="SKIP"
DUAL_WHY=""
FAILED_IDS=()

# 合并首轮 + 复跑：PASS > FLAKY（首轮失败、复跑通过）> FAIL；SKIPPED 不计失败
_dual_merge() { # <first_summary> <retry_summary|空>
  awk -F'\t' '
    FNR==NR { if (NF >= 2) first[$1] = $2; next }
    NF >= 2 { retry[$1] = $2 }
    END {
      for (id in first) {
        f = first[id]; r = (id in retry) ? retry[id] : "-"
        if (f ~ /^PASS/)              v = "PASS";
        else if (f ~ /^SKIPPED/)      v = "SKIP";
        else if (f ~ /^INCONCLUSIVE/) v = "INCONCLUSIVE";
        else if (r ~ /^PASS/)         v = "FLAKY";
        else                          v = "FAIL";
        printf "%s\t%s\t%s\t%s\n", id, f, r, v;
      }
    }' "$1" "${2:-/dev/null}" | sort
}

_summary_rows() { awk '$1 ~ /^S[0-9][0-9]$/ {print $1 "\t" $4}' "$1" 2>/dev/null; }

run_dual() {
  local rid="$RUN_ID/dual"
  local -a args=(--phone "$PHONE_SERIAL" --car "$CAR_SERIAL" --scenario "$SCENARIO_SEL")
  [ "$SKIP_BUILD" = "1" ] && args+=(--skip-build)
  [ "$INCLUDE_MANUAL" = "1" ] && args+=(--include-manual)
  [ "$PHYSICAL_OPS" = "on" ] && args+=(--physical-ops on)

  step "车机+手机（dual）：phone=$PHONE_SERIAL  car=$CAR_SERIAL  scenario=$SCENARIO_SEL"
  E2E_RUN_ID="$rid" bash "$DUAL" "${args[@]}" 2>&1 | tee "$OUT/dual-console.log"
  DUAL_RC=${PIPESTATUS[0]}
  DUAL_DIR="$ROOT/debug-logs/e2e/$rid"
  info "dual 退出码=$DUAL_RC  产物=$DUAL_DIR"

  # ── 失败场景复跑（无人值守时用来区分真失败与抖动）──
  local retry_dir="" retry_list=""
  if [ "$DUAL_RC" = "1" ] && [ "$RETRY_N" -gt 0 ] && [ -f "$DUAL_DIR/summary.txt" ]; then
    retry_list=$(_summary_rows "$DUAL_DIR/summary.txt" | awk -F'\t' '$2 ~ /^FAIL/ {printf "%s,", $1}')
    retry_list="${retry_list%,}"
    if [ -n "$retry_list" ]; then
      step "复跑失败场景：$retry_list（复跑通过 → 判 FLAKY）"
      retry_dir="$RUN_ID/dual-retry1"
      E2E_RUN_ID="$retry_dir" bash "$DUAL" \
        --phone "$PHONE_SERIAL" --car "$CAR_SERIAL" --scenario "$retry_list" \
        $([ "$SKIP_BUILD" = "1" ] && echo --skip-build) \
        $([ "$PHYSICAL_OPS" = "on" ] && echo --physical-ops on) \
        2>&1 | tee "$OUT/dual-retry1-console.log"
      info "复跑退出码=${PIPESTATUS[0]}"
    fi
  fi

  # ── 汇总判定 ──
  if [ ! -f "$DUAL_DIR/summary.txt" ]; then
    DUAL_VERDICT="ENV_FAIL"
    # 结论必须自己说得清「为什么」——从控制台日志抓最后几条 ✗，省得人去翻 3MB 日志。
    local why
    why=$(grep -F '✗' "$OUT/dual-console.log" 2>/dev/null | tail -3 | sed 's/^[[:space:]]*//' | tr '\n' ' ')
    DUAL_WHY="dual 未产出 summary.txt（退出码 $DUAL_RC，前置/构建/解锁阶段失败）"
    [ -n "$why" ] && DUAL_WHY="$DUAL_WHY。最后错误：$why"
    return 0
  fi
  _summary_rows "$DUAL_DIR/summary.txt" > "$OUT/dual-first.tsv"
  local retryf="/dev/null"
  if [ -n "$retry_dir" ] && [ -f "$ROOT/debug-logs/e2e/$retry_dir/summary.txt" ]; then
    _summary_rows "$ROOT/debug-logs/e2e/$retry_dir/summary.txt" > "$OUT/dual-retry.tsv"
    retryf="$OUT/dual-retry.tsv"
  fi

  local id f r v
  while IFS=$'\t' read -r id f r v; do
    [ -n "${id:-}" ] || continue
    DUAL_TOTAL=$((DUAL_TOTAL + 1))
    case "$v" in
      PASS)          DUAL_PASS=$((DUAL_PASS + 1)) ;;
      FLAKY)         DUAL_PASS=$((DUAL_PASS + 1)); DUAL_FLAKY=$((DUAL_FLAKY + 1)); FAILED_IDS+=("$id(FLAKY)") ;;
      SKIP)          DUAL_SKIP=$((DUAL_SKIP + 1)) ;;
      INCONCLUSIVE)  DUAL_INCONCLUSIVE=$((DUAL_INCONCLUSIVE + 1)); FAILED_IDS+=("$id(INCONCLUSIVE)") ;;
      *)             DUAL_FAIL=$((DUAL_FAIL + 1)); FAILED_IDS+=("$id") ;;
    esac
  done < <(_dual_merge "$OUT/dual-first.tsv" "$retryf")

  if [ "$DUAL_FAIL" -gt 0 ]; then DUAL_VERDICT="FAIL"
  elif [ "$DUAL_RC" = "2" ]; then DUAL_VERDICT="ENV_FAIL"; DUAL_WHY="退出码 2（环境/构建/解锁）"
  elif [ "$DUAL_INCONCLUSIVE" -gt 0 ]; then DUAL_VERDICT="INCONCLUSIVE"; DUAL_WHY="有 $DUAL_INCONCLUSIVE 个场景无法判定（A 端无连接尝试证据），需人工 review"
  else DUAL_VERDICT="PASS"; fi
  return 0
}

# ═════════════════════════════════════════════════════════════════════════
# desktop 套件
# ═════════════════════════════════════════════════════════════════════════
DESKTOP_RC=0
DESKTOP_DIR=""
DESKTOP_PASS=0; DESKTOP_FAIL=0
DESKTOP_VERDICT="SKIP"
DESKTOP_WHY=""

run_desktop() {
  local rid="$RUN_ID/desktop"
  local -a args=(--serial "$DESKTOP_SERIAL")
  [ "$SKIP_BUILD" = "1" ] && args+=(--skip-build)
  [ "$NO_INSTALL" = "1" ] && args+=(--no-install)

  step "电脑+手机（desktop）：serial=$DESKTOP_SERIAL"
  if [ "$ASSUME_YES" != "1" ]; then
    echo "  ⚠ 这个套件会移动真实鼠标、点击桌面窗口（约 3~6 分钟）。"
    echo "    请勿使用电脑。10 秒后开始（Ctrl-C 取消；无人值守加 --yes 跳过本提示）。"
    local i
    for i in 10 9 8 7 6 5 4 3 2 1; do printf '\r    倒计时 %2d ' "$i"; sleep 1; done
    printf '\r%*s\r' 20 ""
  fi

  E2E_RUN_ID="$rid" bash "$DESKTOP" "${args[@]}" 2>&1 | tee "$OUT/desktop-console.log"
  DESKTOP_RC=${PIPESTATUS[0]}
  DESKTOP_DIR="$ROOT/debug-logs/e2e/$rid"
  info "desktop 退出码=$DESKTOP_RC  产物=$DESKTOP_DIR"

  local line
  line=$(grep -F '结果：通过' "$OUT/desktop-console.log" 2>/dev/null | tail -1)
  DESKTOP_PASS=$(printf '%s' "$line" | sed -n 's/.*通过 \([0-9][0-9]*\) 项.*/\1/p')
  DESKTOP_FAIL=$(printf '%s' "$line" | sed -n 's/.*失败 \([0-9][0-9]*\) 项.*/\1/p')
  DESKTOP_PASS="${DESKTOP_PASS:-0}"; DESKTOP_FAIL="${DESKTOP_FAIL:-0}"

  if [ "$DESKTOP_RC" = "0" ] && [ "$DESKTOP_FAIL" = "0" ]; then
    DESKTOP_VERDICT="PASS"
  elif [ "$DESKTOP_FAIL" -gt 0 ]; then
    DESKTOP_VERDICT="FAIL"
  else
    DESKTOP_VERDICT="ENV_FAIL"
    local why
    why=$(grep -F '✗' "$OUT/desktop-console.log" 2>/dev/null | tail -3 | sed 's/^[[:space:]]*//' | tr '\n' ' ')
    DESKTOP_WHY="退出码 $DESKTOP_RC 且没有结果行（构建/设备/桌面会话前置失败）"
    [ -n "$why" ] && DESKTOP_WHY="$DESKTOP_WHY。最后错误：$why"
  fi
  return 0
}

# ═════════════════════════════════════════════════════════════════════════
step "2/5 执行（suite=$SUITE  retry=$RETRY_N）"
# ═════════════════════════════════════════════════════════════════════════
case "$SUITE" in
  dual)    run_dual ;;
  desktop) run_desktop ;;
  all)
    run_dual
    if [ "$DUAL_VERDICT" = "ENV_FAIL" ]; then
      warn "dual 环境级失败 → 跳过 desktop（同一套设备/PIN 前置，跑也白跑）"
      DESKTOP_VERDICT="SKIP"
      DESKTOP_WHY="dual 环境级失败，未执行"
    else
      run_desktop
    fi
    ;;
esac

# ═════════════════════════════════════════════════════════════════════════
step "3/5 汇总判定"
# ═════════════════════════════════════════════════════════════════════════
OVERALL="PASS"
for _v in "$DUAL_VERDICT" "$DESKTOP_VERDICT"; do
  case "$_v" in
    FAIL)         OVERALL="FAIL" ;;
    ENV_FAIL)     [ "$OVERALL" = "PASS" ] && OVERALL="ENV_FAIL" ;;
    INCONCLUSIVE) [ "$OVERALL" = "PASS" ] && OVERALL="INCONCLUSIVE" ;;
  esac
done
unset _v

ELAPSED=$((SECONDS - T0))

# ── verdict.md ──
{
  echo "# DiLink-Auto E2E 运行结论"
  echo
  echo "| 项 | 值 |"
  echo "|---|---|"
  echo "| 总判定 | **$OVERALL** |"
  echo "| 套件 | $SUITE |"
  echo "| 运行 ID | \`$RUN_ID\` |"
  echo "| 开始 / 结束 | $T_START / $(date '+%Y-%m-%d %H:%M:%S') |"
  echo "| 耗时 | $((ELAPSED / 60)) 分 $((ELAPSED % 60)) 秒 |"
  echo "| 设备 | phone(B)=\`$PHONE_SERIAL\`  car(A)=\`$CAR_SERIAL\` |"
  echo
  if [ "$SUITE" != "desktop" ]; then
    echo "## 车机+手机（dual）— $DUAL_VERDICT"
    echo
    echo "场景 $DUAL_TOTAL 个：通过 $DUAL_PASS（其中复跑救回 $DUAL_FLAKY）/ 失败 $DUAL_FAIL / 跳过 $DUAL_SKIP / 无法判定 $DUAL_INCONCLUSIVE"
    [ -n "$DUAL_WHY" ] && echo && echo "> $DUAL_WHY"
    echo
    if [ -f "$DUAL_DIR/summary.txt" ]; then
      echo "| 场景 | 首轮 | 复跑 | 判定 |"
      echo "|---|---|---|---|"
      local_first="$OUT/dual-first.tsv"; local_retry="/dev/null"
      if [ -f "$OUT/dual-retry.tsv" ]; then local_retry="$OUT/dual-retry.tsv"; fi
      _dual_merge "$local_first" "$local_retry" | awk -F'\t' '{printf "| %s | %s | %s | %s |\n", $1, $2, $3, $4}'
      echo
    fi
    echo "产物：\`debug-logs/e2e/$RUN_ID/dual/\`（summary.txt / 各场景目录 / freeze-*）"
    echo
  fi
  if [ "$SUITE" != "dual" ]; then
    echo "## 电脑+手机（desktop）— $DESKTOP_VERDICT"
    echo
    echo "断言通过 $DESKTOP_PASS 项 / 失败 $DESKTOP_FAIL 项"
    [ -n "$DESKTOP_WHY" ] && echo && echo "> $DESKTOP_WHY"
    echo
    echo "产物：\`debug-logs/e2e/$RUN_ID/desktop/\`（desktop.log / vd-server.log）"
    echo
  fi
  if [ "${#FAILED_IDS[@]}" -gt 0 ]; then
    echo "## 需 review 的场景（失败 / 无法判定）"
    echo
    for id in "${FAILED_IDS[@]}"; do echo "- $id"; done
    echo
    echo "排查入口：\`docs/e2e-dual-device-runbook.md\`（冻结件在各场景目录 freeze-*）"
  fi
} > "$OUT/verdict.md"

# ── verdict.json（给机器读：CI/后续 agent 不用解析控制台）──
cat > "$OUT/verdict.json" <<JSON
{
  "run_id": "$RUN_ID",
  "suite": "$SUITE",
  "verdict": "$OVERALL",
  "started": "$T_START",
  "elapsed_seconds": $ELAPSED,
  "phone_serial": "$PHONE_SERIAL",
  "car_serial": "$CAR_SERIAL",
  "dual": {
    "verdict": "$DUAL_VERDICT",
    "exit_code": $DUAL_RC,
    "scenarios_total": $DUAL_TOTAL,
    "scenarios_pass": $DUAL_PASS,
    "scenarios_fail": $DUAL_FAIL,
    "scenarios_skip": $DUAL_SKIP,
    "scenarios_inconclusive": $DUAL_INCONCLUSIVE,
    "flaky": $DUAL_FLAKY,
    "failed_ids": "$(printf '%s ' "${FAILED_IDS[@]:-}" | sed 's/ $//')"
  },
  "desktop": {
    "verdict": "$DESKTOP_VERDICT",
    "exit_code": $DESKTOP_RC,
    "asserts_pass": $DESKTOP_PASS,
    "asserts_fail": $DESKTOP_FAIL
  }
}
JSON

# 复跑明细已直接落 dual-first.tsv / dual-retry.tsv（供人工核对）

# ═════════════════════════════════════════════════════════════════════════
step "4/5 结论"
# ═════════════════════════════════════════════════════════════════════════
echo
hr
printf '  E2E 结论：%s\n' "$OVERALL"
if [ "$SUITE" != "desktop" ]; then
  printf '  车机+手机 : %-9s 场景 %d 通过 / %d 失败 / %d 跳过 / %d 无法判定' \
    "$DUAL_VERDICT" "$DUAL_PASS" "$DUAL_FAIL" "$DUAL_SKIP" "$DUAL_INCONCLUSIVE"
  [ "$DUAL_FLAKY" -gt 0 ] && printf '（复跑救回 %d）' "$DUAL_FLAKY"
  echo
fi
if [ "$SUITE" != "dual" ]; then
  printf '  电脑+手机 : %-9s 断言 %d 通过 / %d 失败\n' \
    "$DESKTOP_VERDICT" "$DESKTOP_PASS" "$DESKTOP_FAIL"
fi
printf '  耗时      : %d 分 %d 秒\n' $((ELAPSED / 60)) $((ELAPSED % 60))
printf '  报告      : debug-logs/e2e/%s/verdict.md\n' "$RUN_ID"
printf '  机器可读  : debug-logs/e2e/%s/verdict.json\n' "$RUN_ID"
if [ "${#FAILED_IDS[@]}" -gt 0 ]; then
  printf '  失败场景  : %s\n' "${FAILED_IDS[*]}"
fi
[ -n "$DUAL_WHY" ] && printf '  原因      : %s\n' "$DUAL_WHY"
[ -n "$DESKTOP_WHY" ] && printf '  原因      : %s\n' "$DESKTOP_WHY"
hr

case "$OVERALL" in
  PASS)          exit 0 ;;
  FAIL)          exit 1 ;;
  INCONCLUSIVE)  exit 1 ;;   # 需人工 review，不是全绿（与 FAIL 同退出码，但 verdict 区分）
  *)             exit 2 ;;   # ENV_FAIL / 用法 / 构建问题
esac
