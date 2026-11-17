#!/bin/bash
# @id: S10
# @name: 会话中杀 server
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, WAITING, absent, absent, off)
# @budget_s: 180
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,kill_server
# @refs: docs/e2e-scenarios.md §4 S10
#
# S01 + kill_server（am force-stop A 的 server）。断言（docs §4 S10）：B 侧回落
# WAITING/IDLE；引擎退出；9637 无残留；无 ANR。
# 机理注记（源码实证）：引擎的 carInput/carVideo accept 自 A，A 一死，
# readTouchAndCommands 的 finally 同样置 running=false + cleanup()——引擎退出；
# B 的 client 看到 9637 上的对端消失，回到 WAITING 重新 LISTEN（同 S03 的 phone
# 回落路径），所以「9637 释放」的准确判据是**无残留 ESTABLISHED**：WAITING 分支下
# LISTEN 是正常态，wait_port_free 会把正常的可重连态误判为失败（判据二义性在此消除）。
# car 侧：server 进程已死 → car_state_now 恒 IDLE（同时反证 kill_server 真的杀了进程）。
# 按任务书 S10 判据列表不断言强制 kill 串（abrupt-death 路径的 watchdog 兜底语义
# 留人工判读）；ANR 用 assert_no_anr 直查双端 logcat。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  run_op kill_server || { log_err "kill_server op 失败"; return 1; }
  log_step "A 的 server 已 force-stop，等 B 侧回落 + 引擎自收"
  return 0
}

scenario_asserts() {
  local rc=0 d="$E2E_RUN_DIR/$E2E_SCENARIO_ID" phone_now="" deadline
  # 引擎退出（A 死亡 → carInput 通道断 → 引擎自收；优雅退出实测 6.1~6.3s，30s 包抖动）
  wait_engine_gone 30 || { bad "引擎未退出（pid=$(engine_pid)）"; rc=1; }
  # B 侧回落：WAITING（client 存活重新监听）或 IDLE（client 进程同死）——30s 内二者其一
  deadline=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$deadline" ]; do
    phone_now=$(phone_state_now)
    if [ "$phone_now" = "WAITING" ] || [ "$phone_now" = "IDLE" ]; then break; fi
    sleep 2
  done
  case "$phone_now" in
    WAITING) ok "phone 回落 WAITING（client 存活，9637 应已重新 LISTEN）" ;;
    IDLE)    ok "phone 回落 IDLE（client 进程也已死）" ;;
    *)       bad "phone 未回落（$phone_now，期望 WAITING/IDLE）"; rc=1 ;;
  esac
  # car：server 进程已死 → 恒 IDLE
  wait_state_eq car IDLE 30 || { bad "car 未回 IDLE（$(car_state_now)）"; rc=1; }
  # 9637 无残留 ESTABLISHED（旧会话连接已断；WAITING 分支下 LISTEN 属正常态，不判 free）
  if [ "$(port_established "$PHONE_SERIAL" 9637)" = "Y" ]; then
    bad "9637 仍有 ESTABLISHED（旧会话连接残留）"; rc=1
  else
    ok "9637 无残留 ESTABLISHED"
  fi
  # 会话覆盖清理：哨兵恢复基线（引擎 cleanup() 写回原值）
  assert_sentinel phone "$BASELINE_SCREEN_OFF_TIMEOUT" 30 || rc=1
  # 无 ANR/崩溃（任务书 S10 判据；双端 logcat 直查）
  logcat_dump phone "$d/logcat-phone.txt" > /dev/null 2>&1 || true
  logcat_dump car   "$d/logcat-car.txt"   > /dev/null 2>&1 || true
  assert_no_anr "$d/logcat-phone.txt" "$d/logcat-car.txt" || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
