#!/bin/bash
# @id: S05
# @name: WiFi 断 5s 自动重连
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 420
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,wifi_off,wifi_on
# @refs: docs/e2e-scenarios.md §4 S05
#
# S01 + wifi_off + sleep 5 + wifi_on。断言（docs §4 S05）：自动重连回 STREAMING；
# 无强制 kill、无 ANR；流存活（帧计数增长）；9637 最终 ESTABLISHED。
# 机理注记（源码实证）：wifi_off 后 B 的 wlan0 没了，A↔B 的 9638/9639 数据面全断，
# 引擎 readTouchAndCommands 的 finally 会把引擎自己收掉（干净降级）；wifi_on 后
# 产品应重新部署引擎并建流——「自动重连」指的就是这条产品路径，不需要人工点 Connect。
# svc wifi enable 后重关联要数秒，op_wifi_on 不等 IP，由下面的状态等待一并包住。
# isolated：入口恒为中立态，流由 setup 内部重建，不依赖任何上一场景残留。
#
# ── 无线拓扑适配（2026-10-17 真机微验证，B 走 TCP adb）────────────────────
# B 的 WiFi 一断，PC→B 的 adb 即断（offline）——「再开」命令发不出去。op 层已改
# 为「关之前先预置 B 侧 nohup 自恢复任务（8s/25s）+ cmd wifi 关闭」（svc wifi 在
# MEIZU Android 15 实测不生效）。实测时间线（T0=disable）：+3s ping DOWN → +13s
# adb offline → +8s 恢复任务开 WiFi → 重关联数秒 → adb 经 authorizing 自愈重连
# （~+50s device）。产品层（A↔B TCP 9637/9638/9639）与 adb 无关：WiFi 回来 A 的
# 重试即可连上。故断言窗从 ops 结束起算放宽到 150s（含产品重连 + 引擎重部署）。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  run_op wifi_off || { log_err "wifi_off op 失败"; return 1; }
  sleep 5
  run_op wifi_on || { log_err "wifi_on op 失败"; return 1; }
  log_step "B 的 WiFi 已关 5s 再开，等产品自动重连（含重关联 + 重新部署引擎建流）"
  return 0
}

scenario_asserts() {
  local rc=0
  # 自动重连回 STREAMING（软等待：覆盖 WiFi 重关联 + adb 自愈 + 产品重新建流全过程；
  # 无线拓扑下 adb 重连本身要 ~20s，预算放宽到 150s）
  wait_state_eq car   STREAMING 150 || { bad "wifi 恢复后 car 未回 STREAMING（$(car_state_now)）"; rc=1; }
  wait_state_eq phone STREAMING 150 || { bad "wifi 恢复后 phone 未回 STREAMING（$(phone_state_now)）"; rc=1; }
  # 恢复后的建流同样触发黑屏误报风暴（2 次重建 ~40s，9637 控制口被反复拆除）——
  # 等风暴静默再断言控制口，否则会读到拆除窗口假失败（同 try2 根因）
  wait_stream_settled 150 30 20 || log_err "恢复后风暴未静默（继续断言）"
  # 9637 最终 ESTABLISHED（新会话的控制口重建；LISTEN 只是 accept 前瞬态，D 表已纠正）
  wait_port_established phone 9637 60 || { bad "9637 无 ESTABLISHED（控制口没重建）"; rc=1; }
  # 引擎恰一个（重连风暴后不双开）
  assert_engine_single || rc=1
  # 流存活（帧计数增长；tex=0 静止 = 黑屏回归信号）
  wait_stream_alive 20 || rc=1
  # 新会话的哨兵应重新置上（引擎重启会写 SESSION_TIMEOUT_SENTINEL=2147483647）
  assert_sentinel phone 2147483647 60 || rc=1
  # 无 ANR/崩溃 + 无强制 kill（vdlog 面）
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
