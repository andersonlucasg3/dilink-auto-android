#!/bin/bash
# @id: S04
# @name: 旋转重握手
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 240
# @priority: P0
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,rotate
# @refs: docs/e2e-scenarios.md §4 S04
#
# S01 + rotate（B 的 user_rotation 翻转）。断言：旧 VD teardown → 新握手 →
# 重回 STREAMING；新 VD 尺寸正确（握手下发值 vs dumpsys real）。
# 注意：rotate 改的是 B 的 user_rotation（白名单 settings 键），restore 由
# op_neutral 写回 BASELINE_USER_ROTATION。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # 先记录旋转前的握手尺寸，供旋转后对比（尺寸应随方向互换）
  local before; before=$(vd_handshake_size)
  log_step "旋转前握手尺寸：${before:-未知}"
  run_op rotate || { log_err "rotate op 失败"; return 1; }
  # 触发重摆：部分 ROM 要一次 configuration change 才重建 VD——亮/灭一次物理屏太吵，
  # 用 setprop 不可取（system 分区只读）；直接等产品的 orientation 监听回调。
  sleep 3
  return 0
}

scenario_asserts() {
  local rc=0
  # 重新收敛到 STREAMING（重握手路径）
  wait_state_eq car   STREAMING 90 || { bad "旋转后 car 未回 STREAMING（$(car_state_now)）"; rc=1; }
  wait_state_eq phone STREAMING 90 || { bad "旋转后 phone 未回 STREAMING（$(phone_state_now)）"; rc=1; }
  # 新 VD 尺寸 = 新握手下发值（注意：握手行可能有多条，vd_handshake_size 取末条）
  local after; after=$(vd_handshake_size)
  if [ -n "$after" ]; then
    assert_vd_size_eq $after 20 || rc=1
  else
    bad "旋转后 vdlog 无新握手尺寸行（Starting: VD=WxH）"; rc=1
  fi
  # 引擎仍恰一个（重握手不是双开）
  assert_engine_single || rc=1
  # 流恢复存活
  wait_stream_alive 20 || rc=1
  # 旋转后哨兵应仍在（会话未结束）
  assert_sentinel phone 2147483647 30 || rc=1
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
