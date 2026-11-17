#!/bin/bash
# @id: S19
# @name: 20 轮会话循环
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, WAITING, absent, absent, off)
# @budget_s: 1800
# @priority: P2
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,reconnect,disconnect
# @refs: docs/e2e-scenarios.md §4 S19
#
# 20 轮 reconnect（重启 A 的 app）→ sleep 8 → disconnect（Eject）→ sleep 8 循环。
# **2026-10-17 修正（首跑实证）**：原设计每轮用 connect op——但 Eject 是 User
# disconnect（产品「will not auto-reconnect」持久化，CarConnectionService:1365），
# 且手动连接框真机不可达（竖屏超高被裁、无滚动容器；P0.5 §F 3）→ 每轮 connect
# 必失败（且旧 op_connect 会把 IP 污染进设置框）。改用 reconnect op（重启触发
# 启动路径自动连接，同 S08 原语）。
# 断言（docs §4 S19 + 不变量表 §5）：无 ANR/崩溃、无强制 kill、
# vd-server.log 无异常堆栈、末轮干净收尾（引擎归零 / 9637 释放 / 哨兵恢复 /
# 双端收敛 IDLE/WAITING）。
# 引擎 RSS/句柄为**观测项**（ops 内 log_step 留痕，不判定）：docs §4 S19 的
# 「无单调增长」需要首跑真实分布才能定阈值——宁缺勿假绿（§2.5），不预设阈值。
# 单轮约 55~60s（reconnect ~5s + 自动连接/部署收敛 ~30s + 双 sleep 8 + Eject ~10s），
# 20 轮 + setup/asserts/restore 包进 1800s 预算。
# isolated：入口恒为中立态；setup 只把两端拉到「可建流」位（client→WAITING、
# server 起来），不预建流——每轮会话由 ops 的 reconnect 自建。

scenario_setup() {
  run_op neutral || return 1
  run_op launch_client || { log_err "拉起 B 的 client 失败"; return 1; }
  wait_port_listening phone 9637 90 || { log_err "9637 未 LISTEN（client 未进 WAITING？）"; return 1; }
  run_op launch_server || { log_err "拉起 A 的 server 失败"; return 1; }
  sleep 2
  return 0
}

scenario_ops() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  local i fail=0 rss1="" pid snip
  for i in $(seq 1 20); do
    # RSS/句柄观测采样（只读 /proc/<pid>/status；仅留痕，不判定）
    if [ "$i" = "1" ] || [ "$i" = "10" ] || [ "$i" = "20" ]; then
      pid=$(engine_pid)
      if [ -n "$pid" ]; then
        snip=$(dphone "grep -E 'VmRSS|Threads' /proc/$pid/status" 2>/dev/null | tr -d '\r' | tr '\n' ' ')
        log_step "S19 观测 round=$i pid=$pid ${snip:-</proc 读取失败>}"
        [ "$i" = "1" ] && rss1="$snip"
        [ "$i" = "20" ] && log_step "S19 观测对比 round1=[$rss1] round20=[$snip]（仅留痕；「无单调增长」阈值判据待 P0.5 首跑数据固化）"
      else
        log_step "S19 观测 round=$i：引擎不在（轮间空隙，正常）"
      fi
    fi
    run_op reconnect || { log_err "round $i：reconnect op 失败（重启 A 的 app）"; fail=1; }
    # reconnect 只做重启动作（不等收敛）；产品级成败看状态面收敛。90s 覆盖
    # 启动路径自动连接（~11s 进 CONNECTING）+ 引擎部署（~18s 到 STREAMING）+ 余量。
    wait_state_eq car STREAMING 90 || { log_err "round $i：重启后 90s 未收敛 STREAMING（car=$(car_state_now)）"; fail=1; }
    sleep 8
    run_op disconnect || { log_err "round $i：disconnect op 失败（Eject 定位？）"; fail=1; }
    sleep 8
  done
  if [ "$fail" = "0" ]; then
    log_ok "20 轮循环全部完成且每轮收敛 STREAMING"
    return 0
  fi
  log_err "20 轮循环存在失败轮（见上方 ✗）——继续 asserts 收证据（P2）"
  return 1
}

scenario_asserts() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  local rc=0
  # —— 末轮干净收尾（判据同 S03）——
  wait_state_eq car   IDLE    30 || { bad "car 未回 IDLE（$(car_state_now)）"; rc=1; }
  wait_state_eq phone WAITING 30 || { bad "phone 未回 WAITING（$(phone_state_now)，client 掉了？）"; rc=1; }
  wait_engine_gone 30 && ok "引擎已归零（20 轮无挂死残留）" || { bad "引擎残留（pid=$(engine_pid)）"; rc=1; }
  # 末轮是 Eject 收尾：client 活着回 WAITING，9637 重新 LISTEN（同 S03 判据）
  wait_port_listening phone 9637 30 && ok "9637 已恢复 LISTEN（client 回 WAITING）" \
                                    || { bad "9637 未恢复 LISTEN"; rc=1; }
  wait_no_established phone 9638 30 && ok "9638 会话连接已断" || { bad "9638 仍有 ESTABLISHED"; rc=1; }
  wait_no_established phone 9639 30 && ok "9639 会话连接已断" || { bad "9639 仍有 ESTABLISHED"; rc=1; }
  assert_sentinel phone "$BASELINE_SCREEN_OFF_TIMEOUT" 30 || rc=1
  # —— 日志面（20 轮累积；logcat 缓冲可能被 20 轮冲掉早期行——命中缺失时
  #    结合 sequence.jsonl 的 op 时间戳判读，防跨轮假阳性优先）——
  logcat_dump phone "$d/logcat-phone.txt" > /dev/null 2>&1 || true
  logcat_dump car   "$d/logcat-car.txt"   > /dev/null 2>&1 || true
  assert_no_anr "$d/logcat-phone.txt" "$d/logcat-car.txt" || rc=1
  pull_vdlog "$d/vd-server.log" > /dev/null 2>&1 || true
  assert_no_force_kill "$d/vd-server.log" || rc=1
  # vd-server.log 无异常堆栈（docs §4 S19）：未捕获线程异常 / Android FATAL /
  # 宽松兜底 'Exception'（三串均 --expect-zero）。'Exception' 命中先人工判读
  # 是否良性再定性——PipelineServer.kt:268 编码器重试路径会用异常类名兜底
  #  message（CodecException 等），属已知良性噪声源。
  vdlog_scan 'Exception in thread' --expect-zero --file "$d/vd-server.log" || rc=1
  vdlog_scan 'FATAL EXCEPTION' --expect-zero --file "$d/vd-server.log" || rc=1
  vdlog_scan 'Exception' --expect-zero --file "$d/vd-server.log" || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
