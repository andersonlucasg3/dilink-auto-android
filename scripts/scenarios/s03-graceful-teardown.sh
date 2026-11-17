#!/bin/bash
# @id: S03
# @name: 优雅收尾
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, WAITING, absent, absent, off)
# @budget_s: 180
# @priority: P0
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,disconnect
# @refs: docs/e2e-scenarios.md §4 S03
#
# S01 + disconnect（A 上 UI 断开）。断言：引擎优雅退出（6.1~6.3s 内）无「强制 kill」；
# screen_off_timeout 恢复基线；9637 释放；pgrep 归零。
# @post 的 phone=WAITING：UI 断开只结束会话，client 进程仍在（9637 重新 LISTEN）。
# isolated：入口恒为中立态，流由 setup 内部重建，不依赖 S01 残留。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  run_op disconnect || { log_err "disconnect op 失败（Eject 按钮定位？）"; return 1; }
  return 0
}

scenario_asserts() {
  local rc=0
  # —— 软等待（收敛类判据）——
  wait_engine_gone 30 && ok "引擎已优雅退出" || { bad "引擎仍在（pid=$(engine_pid)）"; rc=1; }
  # Eject 后 client 活着回 WAITING：9637 **重新 LISTEN**（等 free 与下一条
  # "phone 回 WAITING" 自相矛盾，2026-10-16 修）；数据面 9638/9639 必须拆干净。
  wait_port_listening phone 9637 30 && ok "9637 已恢复 LISTEN（client 回 WAITING）" \
                                    || { bad "9637 未恢复 LISTEN"; rc=1; }
  wait_no_established phone 9638 30 && ok "9638 会话连接已断" || { bad "9638 仍有 ESTABLISHED"; rc=1; }
  wait_no_established phone 9639 30 && ok "9639 会话连接已断" || { bad "9639 仍有 ESTABLISHED"; rc=1; }
  # 会话结束收敛：car 回 IDLE、phone 回 WAITING（client 还活着重新监听）。
  # car=IDLE 的判定来源（2026-10-16 P0 实证）：A 端 Eject 后中继随连接断开，
  # A 端自己的「handleDisconnect/User disconnect — idle」到不了 B——car_state_now
  # 依赖 B 端本地行「Car disconnected (control)」判 IDLE（见 asserts.sh 注释）。
  wait_state_eq car   IDLE     30 && ok "car 收敛 IDLE"     || { bad "car=$(car_state_now)"; rc=1; }
  wait_state_eq phone WAITING  30 && ok "phone 收敛 WAITING" || { bad "phone=$(phone_state_now)"; rc=1; }
  # —— 硬断言（日志面事实，禁重试）——
  assert_sentinel phone "$BASELINE_SCREEN_OFF_TIMEOUT" 30 || rc=1
  assert_teardown_clean listening || rc=1
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
