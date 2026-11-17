#!/bin/bash
# @id: S06
# @name: 飞行模式开关
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 420
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,airplane
# @refs: docs/e2e-scenarios.md §4 S06
#
# S01 + airplane to=1 + sleep 8 + airplane to=0。
# 判据（docs §4 S06：「恢复后重连或按设计停留可重连态」——二选一皆算通过）：
#   路径 A（自动重连）：airplane to=0 后 75s 内双端回 STREAMING → 追加流存活 /
#     引擎单开 / 9637 ESTABLISHED / 哨兵置上 四条恢复态一致性断言；
#   路径 B（设计停留态）：双端收敛到 car=IDLE 且 phone=WAITING（client 存活并重新
#     LISTEN 9637，用户可在 A 的 UI 上重新 Connect）→ 追加 9637 重新 LISTEN 一致性；
#   二者都不满足 → 判败（既没恢复也没干净回落 = 静默挂死，产品缺陷）。
# 与 S05 的差别：飞行模式连无线电一起断，恢复比单纯 WiFi 开关慢，观测窗放到 75s。
#
# ── 无线拓扑适配（2026-10-17，同 s05）────────────────────────────────────
# 飞行模式断无线电 → PC→B 的 adb 断；op_airplane to=1 已预置 B 侧 nohup 自恢复
# （8s 关飞行+开 WiFi / 25s 保险）。恢复链：8s 任务 → 无线电重关联 → adb 自愈
# 重连（~20s）→ 产品重连 + 引擎重部署。故探路窗放宽到 150s，并在路径 A 的
# 恢复态一致性断言前等黑屏误报风暴静默（恢复后建流同样触发，同 try2 根因）。
# 实现注记：探路不用 wait_state_eq——它的超时 bad() 会污染「路径 B 也算通过」的
# 语义（编排层 FAIL 计数按 bad() 调用累计，见 e2e-dual-device.sh 的 ok/bad 覆写），
# 故用 car_state_now/phone_state_now 手工轮询，只在最终判据上记账。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  run_op airplane to=1 || { log_err "airplane to=1 op 失败"; return 1; }
  sleep 8
  run_op airplane to=0 || { log_err "airplane to=0 op 失败"; return 1; }
  log_step "B 飞行模式已开 8s 再关，等无线电恢复 + 产品重连（重关联比纯 WiFi 开关慢）"
  return 0
}

scenario_asserts() {
  local rc=0 deadline cur_car="" cur_phone=""
  # —— 探路：150s 内双端是否自动重连回 STREAMING（手工轮询，不借 wait_state_eq）——
  # （无线拓扑：8s 恢复任务 + 重关联 + adb 自愈 ~20s + 产品重连；75s 不够）
  deadline=$((SECONDS + 150))
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur_car=$(car_state_now); cur_phone=$(phone_state_now)
    if [ "$cur_car" = "STREAMING" ] && [ "$cur_phone" = "STREAMING" ]; then break; fi
    sleep 2
  done
  if [ "$cur_car" = "STREAMING" ] && [ "$cur_phone" = "STREAMING" ]; then
    ok "路径 A：飞行模式恢复后自动重连回 STREAMING"
    # 回 STREAMING 则流必须存活（帧计数增长）
    wait_stream_alive 20 || rc=1
    # 恢复后风暴静默（同 s05：9637 控制口在风暴中被反复拆除，先静默再断言）
    wait_stream_settled 150 30 20 || log_err "恢复后风暴未静默（继续断言）"
    # 恢复态一致性：引擎不双开、9637 重建 ESTABLISHED、哨兵重新置上
    assert_engine_single || rc=1
    wait_port_established phone 9637 60 || { bad "9637 无 ESTABLISHED"; rc=1; }
    assert_sentinel phone 2147483647 60 || rc=1
  else
    cur_car=$(car_state_now); cur_phone=$(phone_state_now)
    if [ "$cur_car" = "IDLE" ] && [ "$cur_phone" = "WAITING" ]; then
      ok "路径 B：按设计停留可重连态（car=IDLE phone=WAITING，A 上可重新 Connect）"
      # 一致性：WAITING 分支下 9637 应重新 LISTEN（「可重连态」的字面含义）
      wait_port_listening phone 9637 15 && ok "9637 重新 LISTEN（可重连态成立）" \
        || { bad "phone=WAITING 但 9637 未 LISTEN"; rc=1; }
    else
      bad "既未自动重连也未干净回落：car=$cur_car phone=$cur_phone（静默挂死？）"
      rc=1
    fi
  fi
  # 两条路径都要求：无 ANR/崩溃 + 无强制 kill
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
