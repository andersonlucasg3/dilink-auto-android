#!/bin/bash
# DiLink-Auto 双手机真机 E2E — 场景模板（_ 前缀，不进 --list / 场景发现）
#
# 用法：cp 本文件为 sNN-<slug>.sh，改元数据块与四函数。
# 契约（.plan/e2e-harness-design.md §1.3）：
#   scenario_setup   把双端带到本场景工作态；失败 return 1（编排按环境失败处理）
#   scenario_ops     只许调 run_op（P3：场景不直接拼 adb 改状态）
#   scenario_asserts 每个断言记账（ok/bad 由断言原语内部做）；return 非 0 = 场景失败
#   scenario_restore 与 setup 同目标（中立态）；编排保证无论成败必跑
#
# 元数据块（编排层 --list/--dry-run 解析；必填键缺失 = 注册失败 exit 2）：
#   @id / @name / @pre / @post / @budget_s / @priority / @expect /
#   @invariants / @ops（可选 @manual / @refs）

# @id: SNN
# @name: 场景名（一句话）
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, IDLE, absent, absent, off)
# @budget_s: 150
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: connect,disconnect
# @refs: docs/e2e-scenarios.md §4

scenario_setup() {
  run_op neutral || return 1
  # 需要会话的场景：reach_streaming（内部 launch_client → connect → 双端 STREAMING）
  # reach_streaming || return 1
  return 0
}

scenario_ops() {
  # run_op <id> [k=v...]
  return 0
}

scenario_asserts() {
  # 软等待（wait_*，有预算）与硬断言（assert_*，禁重试）边界见 harness §2.1
  return 0
}

scenario_restore() {
  run_op neutral
}
