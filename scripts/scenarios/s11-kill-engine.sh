#!/bin/bash
# @id: S11
# @name: 杀引擎进程
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, WAITING, absent, absent, off)
# @budget_s: 240
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,kill_engine
# @refs: docs/e2e-scenarios.md §4 S11
#
# S01 + kill_engine（pgrep + kill -9）。断言「不静默挂死」（docs §4 S11：按设计
# 检测到并恢复或干净降级）。三态判据（实现即文档）：
#   ① 产品恢复：120s 内双端回 STREAMING，且流存活（wait_stream_alive 帧计数真在
#     增长）、引擎恰一个、哨兵置上——kill -9 后产品能自愈重建会话；
#   ② 干净降级：双端回 car=IDLE 且 phone=WAITING（client 存活）或 IDLE（client 同死），
#     且引擎确已退出、9637 无残留 ESTABLISHED——会话明确结束，UI 不报假 STREAMING，
#     用户可重新 Connect；
#   ③ 判败：其余一切终态——尤其「UI 仍报 STREAMING 而帧计数不增长」（陈旧状态行
#     假象 = 静默挂死，由①里的 wait_stream_alive 揭穿）与「引擎双开 / 降级态引擎
#     还在」（assert_engine_single / engine_pid 判）。
# 实现注记：探路不用 wait_state_eq——它的超时 bad() 会污染「②也算通过」的语义
# （编排层 FAIL 计数按 bad() 调用累计），故用 *_state_now 手工轮询，只在最终判据上记账。
# @post 取②的收敛形；①恢复形为 (STREAMING, STREAMING, present, present, on)。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  run_op kill_engine || { log_err "kill_engine op 失败（pgrep 未找到引擎？）"; return 1; }
  log_step "引擎已 kill -9（SIGKILL 不跑 cleanup：VD/面板/端口全留给产品自愈）"
  return 0
}

scenario_asserts() {
  local rc=0 cur_car="" cur_phone="" deadline
  # —— 探路：120s 内双端是否重回 STREAMING（手工轮询，避免 wait_state_eq 记账副作用）——
  deadline=$((SECONDS + 120))
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur_car=$(car_state_now); cur_phone=$(phone_state_now)
    if [ "$cur_car" = "STREAMING" ] && [ "$cur_phone" = "STREAMING" ]; then break; fi
    sleep 2
  done
  if [ "$cur_car" = "STREAMING" ] && [ "$cur_phone" = "STREAMING" ]; then
    # ① 产品恢复——先防「陈旧状态行」假象：帧计数必须真在增长（不增长 = 静默挂死）
    log_step "判据①：双端报 STREAMING，验流存活（帧计数增长）"
    wait_stream_alive 20 || rc=1
    assert_engine_single || rc=1
    assert_sentinel phone 2147483647 30 || rc=1
    [ "$rc" = "0" ] && ok "S11 判据①达成：产品恢复（STREAMING + 流存活 + 引擎单开 + 哨兵置上）"
  else
    cur_car=$(car_state_now); cur_phone=$(phone_state_now)
    # ② 干净降级——引擎必须真的没了（若产品又拉起新引擎却未成流，既非①也非②）。
    # issue #31 后 car_state_now 的 B 进程缺失分支返 UNKNOWN 不再强行 IDLE：
    # kill_engine 只杀 PipelineServer（ops.sh:350），不杀 client——client 存活子支路
    # 走状态行扫描返 IDLE 不受影响；client 同死子支路（phone=IDLE）会走进程缺失分支
    # 返 UNKNOWN，故 phone=IDLE 时也接受 car=UNKNOWN（真卡死时 phone 不会是 IDLE，
    # 不担心把卡死掩盖）。
    if { [ "$cur_car" = "IDLE" ] || [ "$cur_car" = "UNKNOWN" ]; } && { [ "$cur_phone" = "WAITING" ] || [ "$cur_phone" = "IDLE" ]; }; then
      ok "S11 判据②达成：干净降级（car=$cur_car phone=$cur_phone）"
      if [ -z "$(engine_pid)" ]; then
        ok "引擎已退出（降级态）"
      else
        bad "降级态但引擎仍在（pid=$(engine_pid)）——非恢复非降级"; rc=1
      fi
      # 9637 无残留 ESTABLISHED（旧会话连接应随引擎消亡断开）
      if [ "$(port_established "$PHONE_SERIAL" 9637)" = "Y" ]; then
        bad "9637 仍有 ESTABLISHED（旧会话连接残留）"; rc=1
      else
        ok "9637 无残留 ESTABLISHED"
      fi
    else
      # ③ 判败：既没恢复也没干净降级（含 UI 假报 STREAMING 的静默挂死）
      bad "S11 判据③：既未恢复也未干净降级（car=$cur_car phone=$cur_phone）——静默挂死？"
      rc=1
    fi
  fi
  # 恢复/降级两条路径都要求：无 ANR/崩溃 + 无强制 kill（帧静止信号由①分支覆盖）
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
