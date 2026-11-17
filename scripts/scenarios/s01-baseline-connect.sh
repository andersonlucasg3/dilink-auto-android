#!/bin/bash
# @id: S01
# @name: 基线建流
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 180
# @priority: P0
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect
# @refs: docs/e2e-scenarios.md §4 S01
#
# 最小闭环第一例：中立态 → connect → 健康会话。
# 断言（docs §4 S01）：双端 STREAMING；dumpsys display 有 DiLinkAutoVD 且 real 尺寸 =
# 握手 vdWidth/vdHeight；B screencap 非纯黑；vd-server.log 有 `Video on :9638`。
# isolated：入口恒为中立态，流由 setup 内部重建，不依赖任何上一场景残留。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # S01 无主动操作：connect 已在 setup 的 reach_streaming 内完成。
  # 留一个显式 no-op 使 ops 阶段在 sequence.jsonl 里有迹可循。
  log_step "S01：无附加操作（观测纯建流路径）"
  return 0
}

scenario_asserts() {
  local rc=0
  assert_streaming_healthy || rc=1
  # 双端状态面一致性（不变量表 §5：A 的 UI 状态 vs B 的 FileLog 状态收敛到同一复合态）
  [ "$(car_state_now)"   = "STREAMING" ] && ok "car 状态 STREAMING"   || { bad "car 状态=$(car_state_now)"; rc=1; }
  [ "$(phone_state_now)" = "STREAMING" ] && ok "phone 状态 STREAMING" || { bad "phone 状态=$(phone_state_now)"; rc=1; }
  # 日志面归档（restore 前 pull 终版，供事后复核）
  pull_clientlog "$E2E_RUN_DIR/$E2E_SCENARIO_ID/client.log" > /dev/null 2>&1 || true
  return $rc
}

scenario_restore() {
  run_op neutral
}
