#!/bin/bash
# @id: S18
# @name: 来电/通知
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 300
# @priority: P2
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect
# @refs: docs/e2e-scenarios.md §4 S18（真机 SIM 来电场景未覆盖，ROM 差异大）
#
# S01 + 推一条通知，断言会话不断：双端仍 STREAMING、流存活、无 ANR/崩溃。
# 做法：cmd notification post（Android 隐藏 shell 命令，无害——只往通知栏
# 插一条）经 dcar 直接执行（任务许可的无害命令白名单；无对应 op 也不该为
# 测试造 op）。无需 SIM。
# 覆盖边界（诚实声明）：只测「通知」面——heads-up/通知栏不该打断镜像会话；
# 「来电」面（TelephonyManager 模拟/真机 SIM）ROM 差异大，未覆盖。
# isolated：入口恒为中立态，流由 setup 内部 reach_streaming 重建。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # cmd notification post -t bigtext <title> <text>：往 A 的通知栏插一条
  # bigtext 样式通知（无害命令，白名单内，不经 op 层）。
  local out rc
  out=$(dcar 'cmd notification post -t bigtext dilink "E2E test notification"' 2>&1); rc=$?
  log_step "已推通知（rc=$rc）：${out:-<无输出>}"
  if [ "$rc" -ne 0 ]; then
    # 诚实提示：命令失败 = 通知可能没推上，后续「会话不断」断言存在空洞
    # （没有通知可断）——记 env caveat，不静默放过。
    log_err "cmd notification post 失败（rc=$rc）——通知可能未推送，本场景断言有空洞风险（env caveat，非产品判定）"
  fi
  # 给 heads-up 弹出/通知栏落账留窗口（settle：通知面无产品状态机迁移）。
  sleep 5
  return 0
}

scenario_asserts() {
  local rc=0
  # 会话不断 = S01 健康判据全集（引擎单开 / 9637 ESTABLISHED / 哨兵 /
  # vdlog 关键行 / VD 尺寸 / 截屏非黑 / 流存活）+ 无 ANR、无强制 kill。
  assert_streaming_healthy || rc=1
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
