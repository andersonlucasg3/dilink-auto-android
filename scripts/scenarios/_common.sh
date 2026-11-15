#!/bin/bash
# DiLink-Auto 双手机真机 E2E — 场景共享 helper（source-only，_ 前缀不进场景发现）
#
# 依赖顺序（编排层与场景文件共同遵守）：
#   logging.sh → devices.sh → asserts.sh → ops.sh → scenarios/<sid>.sh
# 前置：PHONE_SERIAL/CAR_SERIAL/PHONE_IP/CAR_IP 已由 parse_roles + preflight 填充；
#       E2E_RUN_DIR / E2E_SCENARIO_ID / E2E_CLIENT_APK 已由编排层 export。
#
# 本文件只提供「场景共用的等待/断言组合」，不直接改设备状态（改状态只能 run_op）。
# 设计基准：.plan/e2e-harness-design.md §1.3.3 / §1.3.4 / §1.3.5。

# ── 中立态核验（编排层 setup 前也调用；任一不过 → ENV_BROKEN）────────────
# 六项：引擎归零 / 9637 释放 / 哨兵=基线 / 无 stop-request / 双端 IDLE / vdlog 已轮换
neutral_verified() {
  local bad=0
  [ -z "$(engine_pid)" ] || { log_err "中立核验：引擎未归零（pid=$(engine_pid)）"; bad=1; }
  [ "$(listening_on "$PHONE_SERIAL" 9637)" != "Y" ] || { log_err "中立核验：B 9637 仍 LISTEN"; bad=1; }
  [ "$(dphone 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')" = "$BASELINE_SCREEN_OFF_TIMEOUT" ] \
    || { log_err "中立核验：screen_off_timeout 非基线"; bad=1; }
  # issue #180 第三版：哨兵按会话分文件（`stop-request-<token>`），残留检查必须用通配。
  [ -z "$(dphone 'ls /sdcard/DiLinkAuto/stop-request* 2>/dev/null' 2>/dev/null | tr -d '\r\n ')" ] \
    || { log_err "中立核验：stop-request 残留"; bad=1; }
  [ "$(car_state_now)" = "IDLE" ]   || { log_err "中立核验：car 状态 $(car_state_now)≠IDLE"; bad=1; }
  [ "$(phone_state_now)" = "IDLE" ] || { log_err "中立核验：phone 状态 $(phone_state_now)≠IDLE"; bad=1; }
  [ "$bad" = "0" ] && { log_ok "中立态六项核验通过"; return 0; }
  return 1
}

# ── wait_stream_settled：等「黑屏误报重握手风暴」自愈 ─────────────────────
# 背景（2026-10-17 真机实证，try2 S02/S03 失败根因）：VD 的 home 是纯黑 Activity
# （VirtualDisplayHomeActivity 设计注释原文「the car receives a black video
# stream」）→ A 端 BlackScreenDetector 每会话建流后必然误报 → rehandshakeForBlackScreen
# 重建 VD（上限 MAX_BLACK_SCREEN_REBUILDS=2，每次 ≈ kill 引擎 + 重部署 ≈ 20s）。
# 风暴窗口内引擎被杀、输入连接被拆 → 任何 tap / 进程断言都会假失败。
# 实测时间线（2026-10-17 00:47，A 中继行）：建流 +2s 检测① → +4s 重建① → +20s
# 建流② → +22s 检测② → +24s 重建② → +40s 建流③ → +42s 检测③（预算耗尽，不再
# 重建）→ 此后静默；事件最大间隔 16s（重建→新流）。
# 判据：三类事件计数（建流 / 检测 / 重建）的静默窗口——见过 BLACK 相关行按
# quiet=30s（> 16s 最大间隔，留机器变慢余量）；全程无 BLACK 行（未来产品修复 /
# VD 非黑）按 early=20s 即返回。返回前复查引擎在位（防静默窗恰落在重部署中段）。
# 这是观测等待不是断言；超时（150s）返回 1 = 真异常（重建循环失控），调用方定夺。
wait_stream_settled() { # [timeout_s=150] [quiet_s=30] [early_s=20]
  local timeout=${1:-150} quiet=${2:-30} early=${3:-20}
  local deadline=$((SECONDS + timeout)) f sig last_sig="" last_change=$SECONDS
  local saw_black=0 n1 n2 n3 idle
  while [ "$SECONDS" -lt "$deadline" ]; do
    f=$(mktemp)
    clientlog_snapshot "$f" > /dev/null 2>&1 || true
    n1=$(grep -cF 'Video ready — streaming' "$f" 2>/dev/null); n1=${n1:-0}
    n2=$(grep -cF 'Suspected BLACK SCREEN' "$f" 2>/dev/null); n2=${n2:-0}
    n3=$(grep -cF 'requesting VD rebuild' "$f" 2>/dev/null); n3=${n3:-0}
    rm -f "$f"
    sig="$n1/$n2/$n3"
    if [ "$sig" != "$last_sig" ]; then
      last_sig=$sig; last_change=$SECONDS
      [ $((n2 + n3)) -gt 0 ] && saw_black=1
      log_step "风暴观测：建流=$n1 检测=$n2 重建=$n3"
    fi
    idle=$((SECONDS - last_change))
    if [ "$saw_black" = "0" ] && [ "$idle" -ge "$early" ]; then
      log_ok "无黑屏误报风暴（${early}s 内无 BLACK 行；建流=$n1）"
      return 0
    fi
    if [ "$saw_black" = "1" ] && [ "$idle" -ge "$quiet" ]; then
      if [ -n "$(engine_pid)" ]; then
        log_ok "风暴已静默 ${quiet}s（建流=$n1 检测=$n2 重建=$n3；引擎在位）"
        return 0
      fi
      last_change=$SECONDS   # 引擎不在位 = 仍在重部署窗口 → 继续等
    fi
    sleep 3
  done
  log_err "风暴静默等待超时（${timeout}s；末次计数 $last_sig）"
  return 1
}

# ── reach_streaming：中立态 → 健康会话（S01+x 类场景的 setup 骨架）───────
# 语义即 S01 断言集；失败返回 1。约 30~90s（connect 90s 预算包住 Flyme 慢启动）。
# 前置：调用方已执行 run_op neutral（本函数不重复中立化，setup/restore 同一实现原则）。
reach_streaming() {
  log_step "reach_streaming：建立健康会话"
  run_op launch_client || { log_err "拉起 B 的 client 失败"; return 1; }
  wait_port_listening phone 9637 90 || { log_err "9637 未 LISTEN（client 未进 WAITING？）"; return 1; }
  run_op launch_server || { log_err "拉起 A 的 server 失败"; return 1; }
  sleep 2
  # op_connect 的 rc 只覆盖「UI 驱动链路 + 收敛窗口」；窗口末尾的状态抖动
  # （黑屏误报风暴正在重建）不该直接判 setup 失败——下面两道 wait_state_eq
  # 是产品级收敛判据（2026-10-17 批次 B 二跑实证：freeze 时刻已 STREAMING，
  # 仅因 op 内部 90s 窗口差几秒被误判）。
  run_op connect || log_err "connect op 未确认 STREAMING（继续等收敛，见下）"
  wait_state_eq car   STREAMING 90 || return 1
  wait_state_eq phone STREAMING 90 || return 1
  # 建流后必然出现的黑屏误报重握手风暴（~40s，2 次重建）——等它自愈再往下走，
  # 否则 assert_engine_single / tap 类操作会落在重部署空窗里假失败（try2 根因）。
  wait_stream_settled || { log_err "黑屏误报风暴未静默（重建循环异常？见 wait_stream_settled 注释）"; return 1; }
  assert_engine_single || return 1
  assert_sentinel phone 2147483647 30 || return 1
  wait_port_established phone 9638 30 || { log_err "9638 无 ESTABLISHED（视频轨没建起来）"; return 1; }
  # 视频口监听确认（引擎侧 bind 0.0.0.0:9638）
  vdlog_scan 'Video on :9638' --min 1 || return 1
  return 0
}

# ── assert_streaming_healthy：健康会话判据（S01 主断言集）────────────────
# 调用时机：reach_streaming 之后（或场景自己的等价 setup 之后）。
assert_streaming_healthy() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  local rc=0
  # 1) 引擎恰一个（>1 = 双开）
  assert_engine_single || rc=1
  # 2) 9637 会话中为 ESTABLISHED（LISTEN 是 accept 前瞬态——设计稿 D 表已纠正）
  if [ "$(port_established "$PHONE_SERIAL" 9637)" = "Y" ]; then ok "9637 ESTABLISHED（会话中）"; else bad "9637 无 ESTABLISHED"; rc=1; fi
  # 3) 哨兵置上（会话中 = 2147483647）
  assert_sentinel phone 2147483647 30 || rc=1
  # 4) vd-server.log 有关键行
  pull_vdlog "$d/vd-server.log" > /dev/null 2>&1 || true
  vdlog_scan 'Video on :9638' --min 1 || rc=1
  vdlog_scan 'Starting: VD=' --min 1 || rc=1
  # 5) VD 尺寸 = 握手下发值（硬断言；wm size -d <vdId> 的 Physical size）
  local hs; hs=$(vd_handshake_size)
  if [ -n "$hs" ]; then
    assert_vd_size_eq $hs 15 || rc=1
  else
    bad "vdlog 无握手尺寸行（Starting: VD=WxH）——按 .plan/e2e-observability.md §F 校准"; rc=1
  fi
  # 6) 画面非黑。判据口径（.plan/e2e-observability.md §168 + 2026-10-16 v5 真机实证）：
  #    **A 屏（解码渲染面）为主判据**——STREAMING 后渲染 HomeContent 应用网格，实测
  #    stddev≈42（>>1.0）。**VD 截屏为辅助观测、不判败**：VD 上无 app 时是空 launcher
  #    黑背景，实测 stddev=0.000 属正常形态（v5 V3：VD 上启动设置 app 后 28.716，证明
  #    截屏机制本身正常）。不能截 display 0 验 VD：会话中引擎会熄物理屏（try4 冻结件
  #    displayId=0 isActive=false，对 display 0 截图恒黑）。
  local cout crc=0
  cout=$(screencap_variance car "$d/screencap-car" 1.0) || crc=$?
  case $crc in
    0) ok "A 屏非纯黑（${cout:-stddev=?}，主判据）" ;;
    1) bad "A 屏纯黑（${cout:-stddev≤1.0}）——解码渲染面空，链路回归"; rc=1 ;;
    *) bad "A 屏截屏环境错（rc=$crc）"; rc=1 ;;
  esac
  local vdpid vcout vcrc=0
  vdpid=$(vd_phys_display_id)
  if [ -n "$vdpid" ]; then
    vcout=$(screencap_variance phone "$d/screencap-vd" 1.0 "$vdpid") || vcrc=$?
    case $vcrc in
      0) ok "VD 截屏非纯黑（${vcout:-}，辅判据）" ;;
      *) log_step "VD 截屏未过非黑判据（rc=$vcrc ${vcout:-}；VD 空 launcher 形态属预期——辅判据不判败，见 v5 实证）" ;;
    esac
  else
    log_step "VD SF DisplayId 未知（dumpsys SurfaceFlinger 无 Virtual Display 段）——辅判据跳过"
  fi
  # 7) 视频流存活（帧计数增长；tex=0 静止 = 黑屏回归信号）
  wait_stream_alive 20 || rc=1
  return $rc
}

# ── assert_touch_echo：S02 触摸回传判据 ────────────────────────────────
# 证据链（2026-10-16 P0 S02 实证重构）：A 端 CarTouchSender 对**前 5 次**触摸
# 打印「Touch #N action=… x=… y=…」（CarTouchSender.kt SEND_LOG_FIRST），经
# carLogSend 中继进 B 的 client.log（tag=CarLog）——这是「A 触摸捕获→编码→发出」
# 的完整正向证据（历史 10-05/10-07 与 10-16 18:36 日志面均实证）。
# 旧判据 `grep -E 'InputManager|inject' vdlog` 已废：TouchInjector **成功注入时
# 不打印任何日志**（只在错误时打），该 pattern 命中的是引擎启动行
# 「[Pipeline] InputManager ready」——S01（无触摸）也恒过，等于没测。
# 反向证据：无「Touch DROP」（9639 输入连接不可用时的降级行）。
# 等待窗口 15s：中继 + 编码有 ~1s 延迟，ops 完成后断言立即跑，需轮询。
assert_touch_echo() {
  local rc=0 n=0 deadline=$((SECONDS + 15))
  while [ "$SECONDS" -lt "$deadline" ]; do
    clientlog_snapshot > /dev/null 2>&1 || true
    n=$(grep -cE 'CarLog\].*Touch #[0-9]+ action=' "$E2E_CLIENTLOG" 2>/dev/null || true)
    case "${n:-}" in ''|*[!0-9]*) n=0 ;; esac
    [ "$n" -gt 0 ] && break
    sleep 1
  done
  if [ "$n" -gt 0 ]; then
    ok "触摸回传：client.log 有 A 端 Touch #N 中继行 ×$n（正向证据）"
  else
    bad "触摸回传：15s 内无 A 端 Touch #N 中继行（tap 未达 MirrorScreen：镜像面未进入/风暴未静默/链路断——看上文的「进入镜像面」「风暴观测」日志）"; rc=1
  fi
  if grep -qE 'Touch( batch)? DROP #' "$E2E_CLIENTLOG" 2>/dev/null; then
    bad "触摸回传：出现 Touch DROP（9639 输入连接不可用）"; rc=1
  else
    ok "触摸回传：无 Touch DROP"
  fi
  # 画面侧反馈：注入后 VD 仍在出帧（流没被触摸打断）
  wait_stream_alive 15 || rc=1
  return $rc
}

# ── assert_teardown_clean：S03/S09/S19 收尾判据 ────────────────────────
# 引擎优雅退出（12s 硬预算内）+ 无强制 kill + 9637 端口面 + 哨兵恢复。
# 参数：$1 = expect_9637 = listening|free（默认 free）
#   listening：Eject 类收尾——client 活着回 WAITING，9637 **会重新 LISTEN**，
#              等 free 永远不过（2026-10-16 修；与"phone 收敛 WAITING"配套）。
#   free：     杀 client 类收尾——进程没了，9637 真释放。
assert_teardown_clean() {
  local expect_9637=${1:-free}
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  local rc=0
  wait_engine_gone 30 && ok "引擎已优雅退出（≤30s 观测窗；优雅退出实测 6.1~6.3s）" \
                     || { bad "引擎仍在（pid=$(engine_pid)）——超出 12s 硬预算即回归"; rc=1; }
  if [ "$expect_9637" = "listening" ]; then
    wait_port_listening phone 9637 30 && ok "9637 已恢复 LISTEN（client 回 WAITING）" \
                                      || { bad "9637 未恢复 LISTEN（client 没回到监听态？）"; rc=1; }
  else
    wait_port_free phone 9637 30 && ok "9637 已释放" || { bad "9637 未释放"; rc=1; }
  fi
  # 数据面拆干净（两种收尾都成立）：视频/触摸口的会话连接必须消失
  wait_no_established phone 9638 30 && ok "9638 会话连接已断" || { bad "9638 仍有 ESTABLISHED"; rc=1; }
  wait_no_established phone 9639 30 && ok "9639 会话连接已断" || { bad "9639 仍有 ESTABLISHED"; rc=1; }
  assert_sentinel phone "$BASELINE_SCREEN_OFF_TIMEOUT" 30 || rc=1
  pull_vdlog "$d/vd-server-teardown.log" > /dev/null 2>&1 || true
  assert_no_force_kill "$d/vd-server-teardown.log" "$E2E_VDLOG" || rc=1
  return $rc
}

# ── assert_no_regression_markers：全套健康不变量（每场景 asserts 收尾调用）─
# ANR/崩溃 + 强制 kill + 帧静止，三个「出现即回归」的硬断言。
assert_no_regression_markers() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  local rc=0
  logcat_dump phone "$d/logcat-phone.txt" > /dev/null 2>&1 || true
  logcat_dump car   "$d/logcat-car.txt"   > /dev/null 2>&1 || true
  assert_no_anr "$d/logcat-phone.txt" "$d/logcat-car.txt" || rc=1
  pull_vdlog "$d/vd-server.log" > /dev/null 2>&1 || true
  assert_no_force_kill "$d/vd-server.log" || rc=1
  return $rc
}

# ── 场景自吐槽（explore.py 与编排层共用）────────────────────────────────
scenario_note() { echo "# NOTE: $*"; }

# ── SKIPPED(env) 通道 ───────────────────────────────────────────────────
# 场景因环境缺能力（toybox 无 nc、ROM 无撤销入口……）无法构造前置时，**不得假通过**：
# 设 E2E_SCENARIO_SKIP="<原因>" 并让 asserts 早退，编排层即记 SKIPPED(env)，
# 不计通过也不计失败（P6：缺断言=没测）。
# 用法：
#   E2E_SCENARIO_SKIP="toybox 无 nc，无法构造 9637 占用"
#   return 0        # setup/asserts 里
skip_env() { E2E_SCENARIO_SKIP="$*"; }
