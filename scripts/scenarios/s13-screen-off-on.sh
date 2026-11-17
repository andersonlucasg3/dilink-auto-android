#!/bin/bash
# @id: S13
# @name: 锁屏/亮屏
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 240
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,screen_off,screen_on
# @refs: docs/e2e-scenarios.md §4 S13
#
# S01 + screen_off + sleep 10 + screen_on。断言（docs §4 S13）：按设计暂停/恢复；
# 无 tex=0 静止（黑屏回归信号，vdlog `Pipeline: N frames ... tex=0` 或帧计数
# 不增长由 wait_stream_alive 判定）。
#
# D7 铁律（.plan/e2e-dual-device-impl.md 偏差记录 D7，risk R09/R16）：
# screen_off 期间**禁用 screencap 类断言**——Dozing 期间物理屏全黑，截屏必然
# 纯黑，那不是回归信号。熄灭期间只做只读观测：记录 vdlog 帧计数
#（Pipeline: N frames）供事后对比，不判败。点亮后才恢复判据。
#
# op_screen_off = `cmd display power-off 0`（DisplayManager 层强制电源管理，
# 绕过 screen_off_timeout 哨兵——哨兵值是 2147483647，power-off 照熄）；
# op_screen_on = `cmd display power-reset 0` + KEYCODE_WAKEUP（power-on 不存在，
# 2026-10-10 实机证伪：写 power-on 报 Unknown command 且留黑屏）。

_s13_vd_frames_now() { # → vdlog 末条 Pipeline 帧计数（只读观测；无则空）
  vdlog_snapshot > /dev/null 2>&1 || true
  grep -oE 'Pipeline: [0-9]+ frames' "$E2E_VDLOG" 2>/dev/null | tail -1 | grep -oE '[0-9]+'
}

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  local f_before f_off
  f_before=$(_s13_vd_frames_now)
  log_step "熄灭前帧计数：${f_before:-无（vdlog 无 Pipeline 行？）}"
  run_op screen_off || { log_err "screen_off op 失败"; return 1; }
  sleep 10
  # D7：熄灭期间只观测不判败（Dozing 全黑，screencap 断言在此窗口无意义；
  # VD 捕获链与物理屏电源是两条路，帧计数停不停都只记录）
  f_off=$(_s13_vd_frames_now)
  log_step "熄灭 10s 期间帧计数：${f_off:-无}（D7：仅记录不判败）"
  run_op screen_on || { log_err "screen_on op 失败"; return 1; }
  log_step "已点亮（frames before=${f_before:-?} during=${f_off:-?}）"
  return 0
}

scenario_asserts() {
  local rc=0
  # 点亮后会话收敛（按设计恢复；90s 包住亮屏后的重握手/恢复窗口）
  wait_state_eq car   STREAMING 90 && ok "点亮后 car 回 STREAMING"   || { bad "点亮后 car=$(car_state_now)"; rc=1; }
  wait_state_eq phone STREAMING 90 && ok "点亮后 phone 回 STREAMING" || { bad "点亮后 phone=$(phone_state_now)"; rc=1; }
  # 帧计数重新增长（tex=0 静止 = 黑屏回归信号；D7 点亮后恢复判据）
  wait_stream_alive 20 || rc=1
  # A 屏（解码渲染面）非纯黑——主判据（S01 校准）。B 物理屏在会话中被引擎持续熄掉
  # （产品设计行为：displayId=0 isActive=false，见 screencap_variance 注释），
  # 对 B 物理屏截图恒黑、不能作判据（2026-10-17 P1a 实证：原 phone 判据恒败）。
  # 记账必须走 ok/bad（原 `|| rc=1` 无记账 → 场景 FAIL 但 ledger ✗=0，破坏
  # 「FAIL == bad() 调用数」不变量，2026-10-17 P1a 实证）。
  screencap_variance car "$E2E_RUN_DIR/$E2E_SCENARIO_ID/screencap-car" 1.0 \
    && ok "A 屏非纯黑（点亮后主判据）" \
    || { bad "A 屏纯黑（点亮后解码面未恢复？）"; rc=1; }
  # 哨兵仍在 = 会话未结束（收尾后才会恢复基线）
  assert_sentinel phone 2147483647 30 || rc=1
  # 无 ANR/崩溃 + 无强制 kill
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
