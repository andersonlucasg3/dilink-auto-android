#!/bin/bash
# @id: S07
# @name: 拔发送端 USB
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 240
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,usb_pull
# @manual: true
# @refs: docs/e2e-scenarios.md §4 S07
#
# S01 + usb_pull（人工拔 B 的 USB 线）。断言（docs §4 S07）：会话继续 STREAMING
# ——A↔B 的会话数据面（9637 控制 / 9638 视频 / 9639 触摸）全走 B 的 wlan0，
# 与 PC 的 USB 通道无关；PC 双 USB 只是编排通道。
# 关键限制：拔线后 PC 经 USB serial 对 B 的 adb 通道消失（dphone / pull_vdlog /
# listening_on / port_established 全部不可用），B 上的 vd-server.log / client.log
# 也拉不到——所以「熄灭窗口」内的存续判据只能靠 A 侧：A 的 VD 画面非纯黑
# （screencap_variance car；必要不充分：连接表单 UI 也非纯黑，充分判据等插回后
# 用日志面补全）。插回 USB 后才跑全套断言（双端 STREAMING + 流存活 + 无 ANR）。
# 编排层双保险：非 --include-manual 不跑本场景；PHYSICAL_OPS!=on 时三个函数都设
# 早退闸（op_usb_pull 对 PHYSICAL_OPS=off 直接返回 2，不能不设闸）。

scenario_setup() {
  run_op neutral || return 1
  if [ "${PHYSICAL_OPS:-off}" != "on" ]; then
    log_step "SKIPPED(manual)：S07 需人工拔插 USB——PHYSICAL_OPS!=on（--physical-ops on 开启）"
    return 0
  fi
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  [ "${PHYSICAL_OPS:-off}" = "on" ] || return 0   # 与 setup 的自跳过闸一致
  run_op usb_pull || { log_err "usb_pull op 失败（人工未拔/超时/USB 通道仍在）"; return 1; }
  # —— 熄灭窗口：PC 对 B 全盲（B 的日志拉不到），只做 A 侧观测 ——
  sleep 10
  # 先 WAKEUP 再截屏（freeze_scene 的同款先例：双端 KEYCODE_WAKEUP 是断言层唯一
  # 放行的副作用；A 的物理屏若已休眠，截到的会是黑帧，与「会话断」无法区分）
  dcar input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  if screencap_variance car "$E2E_RUN_DIR/$E2E_SCENARIO_ID/screencap-car-window" 1.0; then
    S07_WINDOW_NONBLACK=1
    log_step "熄灭窗口：A 的 VD 画面非纯黑（拔线期间会话仍在出画面）"
  else
    S07_WINDOW_NONBLACK=0
    log_err "熄灭窗口：A 的 VD 画面纯黑/截屏失败——会话可能在拔线期间已断"
  fi
  # —— 等人工插回 USB（PC 恢复对 B 的 adb 通道才能补全日志面断言）——
  # 轮询自验证（不设 read 回车闸：插线即自动继续，也不会被提前回车骗过；
  # AUTO_WAIT 无人值守模式下同样走到这，超时即明确失败）
  log_step "请插回 B 的 USB 线（轮询等待恢复，180s 预算；检测到即自动继续）"
  local deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$(adb -s "$PHONE_SERIAL" get-state 2>/dev/null | tr -d '\r\n')" = "device" ] && break
    sleep 2
  done
  if [ "$(adb -s "$PHONE_SERIAL" get-state 2>/dev/null | tr -d '\r\n')" = "device" ]; then
    log_ok "USB 已插回，PC 恢复对 B 的 adb 通道"
    return 0
  fi
  log_err "USB 未在 180s 内插回——无法补全日志面断言"
  return 1
}

scenario_asserts() {
  [ "${PHYSICAL_OPS:-off}" = "on" ] || { ok "S07 SKIPPED(manual)：PHYSICAL_OPS=off，无人工物理动作"; return 0; }
  local rc=0
  # 熄灭窗口的 A 侧存续观测（必要不充分；充分性由下面插回后的日志面断言补全）
  [ "${S07_WINDOW_NONBLACK:-0}" = "1" ] && ok "熄灭窗口 A 侧画面非纯黑" \
    || { bad "熄灭窗口 A 侧画面纯黑——会话在拔线期间出过画面中断"; rc=1; }
  # 插回后：双端 STREAMING（会话全程未断，不需要任何重连）
  wait_state_eq car   STREAMING 30 || { bad "插回后 car 未在 STREAMING（$(car_state_now)）"; rc=1; }
  wait_state_eq phone STREAMING 30 || { bad "插回后 phone 未在 STREAMING（$(phone_state_now)）"; rc=1; }
  # 流存活 + 引擎恰一个 + 9637 ESTABLISHED + 哨兵仍在（会话未被重建过）
  wait_stream_alive 20 || rc=1
  assert_engine_single || rc=1
  wait_port_established phone 9637 30 || { bad "9637 无 ESTABLISHED"; rc=1; }
  assert_sentinel phone 2147483647 30 || rc=1
  # 无 ANR/崩溃 + 无强制 kill（logcat 缓冲覆盖熄灭窗口，断在窗口里也抓得到）
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
