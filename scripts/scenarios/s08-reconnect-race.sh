#!/bin/bash
# @id: S08
# @name: 连点重连竞态
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 300
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,reconnect,disconnect
# @refs: docs/e2e-scenarios.md §4 S08
#
# S01 + disconnect + 重启 A 的 app（触发启动路径自动连接）快速 ×2。断言（docs §4
# S08）：引擎不双开、9637 不被抢、最终收敛 STREAMING、无强制 kill。
# 重连原语（2026-10-17 P1a 实证）：A 的「手动连接」UI 不可达（P0.5 §F 3 已收口），
# 重连只能靠 app 重启触发启动路径自动连接（见 _dcar_restart_server 注释）。
# 竞态构造：disconnect 的 Eject 点下去后，产品侧 teardown 要 ~6s 才让引擎退出
# （优雅退出实测 6.1~6.3s）；重启后的自动连接在 app 启动（~5s）后即发起——新会话
# 的部署序列（stop 旧引擎 → 等 GONE → launch 新引擎）与旧引擎的退出过程交叠，
# 正是「双开引擎 / 抢 9637」的触发窗口。第二轮在首轮达可 Eject 态（STREAMING/
# CONNECTING）后快速再断再重启；「快速」的语义实作为「旧引擎未死透就发起新部署」，
# 竞态强度不依赖轮间的 sleep。
# 附加判据（检测缺口补救，源码实证）：ConnectionService 的强制 kill 兜底串
# 「Previous VD server did not exit in time — forced kill used」经 FileLog 落在
# client.log，而 harness 的 assert_no_force_kill 只扫 vd-server.log——S08 正是
# 该兜底最可能被竞态触发的场景，故对 client.log 补扫这条串（countF + ok/bad
# 原语记账，不手写 echo）。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

# A 的重连原语已提取为正式 op `reconnect`（ops.sh；2026-10-17 S19 实证后统一）：
# A 的「手动连接」UI 真机不可达（ManualConnectBox 竖屏超高被裁、无滚动容器；P0.5 §F 3），
# Eject 后又不自动重连（userDisconnected 持久化，CarConnectionService:1365）——重连唯一
# 可靠路径 = app 重启（启动路径自动连接，实测 ~5s 进 CONNECTING、~11s 控制连接）。
# 竞态语义不变：重启后的新会话部署与旧引擎 teardown（~6s）交叠（op 不等收敛）。

scenario_ops() {
  local dl
  # 第一轮：断 → 立即重启 A 的 app（不等收敛；自动连接发起 ~5s 与旧引擎
  # teardown ~6s 交叠 = 竞态窗口）
  run_op disconnect || { log_err "disconnect #1 失败（Eject 按钮定位？）"; return 1; }
  sleep 1
  run_op reconnect || { log_err "reconnect #1 失败（重启 A 的 app）"; return 1; }
  log_step "connect #1：已重启 A 的 app（触发启动路径自动连接，不等收敛）"
  # 第二轮：等首轮达到可 Eject 态（STREAMING/CONNECTING），快速再断再重启
  dl=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$dl" ]; do
    case "$(car_state_now)" in STREAMING|CONNECTING) break ;; esac
    sleep 2
  done
  run_op disconnect || { log_err "disconnect #2 失败（首轮未达可 Eject 态？）"; return 1; }
  sleep 1
  run_op reconnect || { log_err "reconnect #2 失败（重启 A 的 app）"; return 1; }
  log_step "connect #2：已重启 A 的 app（触发启动路径自动连接）"
  log_step "两轮 disconnect+重启连接 已打完，等最终收敛"
  return 0
}

scenario_asserts() {
  local rc=0 d="$E2E_RUN_DIR/$E2E_SCENARIO_ID" n
  # 最终收敛 STREAMING（两轮竞态之后；120s 覆盖一次完整的优雅退出 + 重新部署）
  wait_state_eq car   STREAMING 120 || { bad "竞态后 car 未收敛（$(car_state_now)）"; rc=1; }
  wait_state_eq phone STREAMING 120 || { bad "竞态后 phone 未收敛（$(phone_state_now)）"; rc=1; }
  # 重连产生新会话 → 新一轮黑屏误报风暴（VD home 纯黑为设计行为）→ 重建期帧计数
  # 暂停（P1c+P2 try1 实证：20s 窗口恰落重建帧暂停窗 → frames 120→120 误判静止）。
  # 先等风暴静默再断言流存活。
  wait_stream_settled 150 30 20 || log_err "重连后风暴未静默（继续断言）"
  # 引擎不双开（>1 = 旧引擎没死透新引擎又起了）
  assert_engine_single || rc=1
  # 9637 不被抢：收敛后会话中应为 ESTABLISHED（被抢会表现为 bind 失败 / 无 ESTABLISHED）
  wait_port_established phone 9637 30 || { bad "9637 无 ESTABLISHED（控制口异常）"; rc=1; }
  # 流存活 + 哨兵（新会话重新置上）
  wait_stream_alive 20 || rc=1
  assert_sentinel phone 2147483647 30 || rc=1
  # 无 ANR/崩溃 + vdlog 面无强制 kill
  assert_no_regression_markers || rc=1
  # client.log 补扫：产品侧强制 kill 兜底串（FileLog 落 client.log，vdlog 扫描覆盖不到；
  # pull 失败则退直接 tail 快照，与 harness 对 vdlog 的 pull→snapshot 兜底同构）
  pull_clientlog "$d/client.log" > /dev/null 2>&1 || clientlog_snapshot "$d/client.log" > /dev/null 2>&1 || true
  if [ -s "$d/client.log" ]; then
    n=$(countF "$d/client.log" 'Previous VD server did not exit in time — forced kill used')
    [ "$n" = "0" ] && ok "client.log 无「Previous VD server did not exit in time — forced kill used」" \
      || { bad "client.log 出现强制 kill 兜底 ×$n（竞态触发强杀=回归）"; rc=1; }
  else
    bad "client.log 取不到（pull 与快照都失败）——无法补扫强制 kill 兜底串"
    rc=1
  fi
  return $rc
}

scenario_restore() {
  run_op neutral
}
