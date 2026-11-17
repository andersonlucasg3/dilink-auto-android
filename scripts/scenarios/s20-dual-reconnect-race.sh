#!/bin/bash
# @id: S20
# @name: 双端同时重连竞态
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 300
# @priority: P2
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,reconnect,kill_client
# @refs: docs/e2e-scenarios.md §4 S20
#
# S01 + 竞态：kill_client（B 侧 force-stop client，当场拆会话）与 reconnect
# （A 侧重启 app 触发重连）几乎同时触发，随后补 B 侧恢复（monkey 拉起 client）
# ——模拟「A、B 在 2s 内同时发起重连」。
# **2026-10-17 修正（S19 同型实证）**：原设计 A 侧用 connect（手动 UI）——真机
# 不可达（ManualConnectBox 竖屏超高被裁，P0.5 §F 3 / S19 实证）；且 kill_client
# 后 B 无恢复路径（原场景缺「B 侧重连」另一半——force-stop 后 client 不自启）。
# 产品侧行为（源码实证）：A 对被动断连会「Connection lost — will reconnect」
# （CarConnectionService:1452，backoff 4s/8s），B 的 client 由本场景 monkey 补起。
#
# 判据（二选一均合法；不接受中间态——判据即注释）：
#   A) 重连成功：car 收敛 STREAMING、phone 跟随收敛、竞态窗口引擎数峰值 ≤1
#      （不双开）、终态引擎恰 1、9637 ESTABLISHED（会话中 LISTEN 是瞬态，
#      observability §A0）、A 侧 9637 无 LISTEN（不抢口）、流存活。
#   B) 干净回落：car=IDLE、引擎归零、9637 释放。
# 两种判向共同的硬断言：无强制 kill、无 ANR/崩溃。
# 缺陷态：引擎双开（竞态窗口峰值 >1）/ 9637 冲突（A 侧 LISTEN）/ 卡死
# （120s 既不 STREAMING 也不干净回落）/ 任何强制 kill。
#
# 竞态构造方式（主观判断，记录在案）：两条 run_op 无 sleep 连续后台触发 +
# wait 收尸。这不是严格原子并发——真·原子并发需在 op 层加 barrier（超出
# 场景文件职责，P3 单一实现原则）；本条是「双端在 ~2s 窗口内同时发起」的
# 工程近似：kill_client 约 1~2s 落地，reconnect 的 force-stop+monkey 约
# 3~5s 落地——两者落在同一产品竞态窗口内。run_op 的 JSONL 记账是 append、
# 底层 adb 并发短命令，可重入（ops.sh run_op 契约）。
# 引擎计数说明：asserts.sh 的 engine_pid_all 用 tr -d '\r\n ' 会把多 pid
# 的换行也删掉、串成一个大数（grep -c 恒 1）——本场景竞态窗口内自己数
# （pgrep 逐行 | grep -c .），等 asserts.sh 修复后回收。
# isolated：入口恒为中立态，流由 setup 内部 reach_streaming 重建。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # 竞态触发：kill_client（B 拆会话）与 reconnect（A 重启 app 触发重连）几乎
  # 同时后台化，不留 sleep。
  run_op kill_client &
  local kpid=$!
  run_op reconnect &
  local cpid=$!
  wait "$kpid"; local krc=$?
  wait "$cpid"; local crc=$?
  log_step "竞态触发完成：kill_client rc=$krc / reconnect rc=$crc"
  # kill_client 的 rc 必须 0（竞态没构造上 = env 问题）；reconnect 只做重启动作、
  # rc 恒 0，产品级收敛由 asserts 判。
  [ "$krc" -eq 0 ] || { log_err "kill_client op 失败（rc=$krc）——竞态构造不完整"; return 1; }
  [ "$crc" -eq 0 ] || { log_err "reconnect op 失败（rc=$crc）"; return 1; }
  # B 侧恢复（2026-10-17 修正）：kill_client 后 B 的 9637 无恢复路径——必须由本
  # 场景补「B 侧同时重连」动作：monkey 拉起 client 进 WAITING，A 的 backoff 重试
  # 随后连上。这是「双端同时重连」的另一半。
  log_step "B 侧重连：monkey 拉起 client（补 kill_client 的恢复面）"
  dphone 'monkey -p com.dilinkauto.client -c android.intent.category.LAUNCHER 1' > /dev/null 2>&1
  if wait_port_listening phone 9637 90; then
    log_step "B 已回 WAITING（9637 LISTEN）——等 A 的自动重连收敛"
  elif [ "$(port_established "$PHONE_SERIAL" 9637)" = "Y" ]; then
    # A 的重连可能抢先连上（LISTEN → ESTABLISHED 瞬态窗口被跨过）——同样证明 B 已恢复
    log_step "9637 已 ESTABLISHED（A 抢先连上）——B 已恢复，等收敛"
  else
    log_err "B 的 9637 未恢复（LISTEN/ESTABLISHED 皆无——client 未起？）"; return 1
  fi
  return 0
}

scenario_asserts() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  local rc=0
  # —— 主判据：竞态后重连成功（A）/ 干净回落（B），二选一 ——
  # 竞态窗口内持续采样引擎数与 car 状态（双开往往只在窗口内瞬时出现，
  # 只看终态会漏——取窗口峰值判据）。
  local deadline=$((SECONDS + 120)) cur="" n maxn=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur=$(car_state_now)
    n=$(dphone 'pgrep -f "[P]ipelineServer"' 2>/dev/null | tr -d '\r' | grep -c .)
    [ "${n:-0}" -gt "$maxn" ] && maxn=$n
    [ "$cur" = "STREAMING" ] && break
    sleep 2
  done
  if [ "$cur" = "STREAMING" ]; then
    ok "竞态后重连成功（car=STREAMING，判据 A）"
    wait_state_eq phone STREAMING 30 || { bad "phone 未跟随收敛（$(phone_state_now)）"; rc=1; }
    # 重连产生新会话 → 新一轮黑屏误报风暴 → 重建期帧计数暂停（S08 三失败同型，
    # 2026-10-17 P1c+P2 实证）。先等风暴静默再断言流存活。
    wait_stream_settled 150 30 20 || log_err "重连后风暴未静默（继续断言）"
    if [ "$maxn" -le 1 ]; then
      ok "竞态窗口引擎数峰值=$maxn（≤1，无双开）"
    else
      bad "竞态窗口引擎数峰值=$maxn（>1=双开——竞态缺陷）"; rc=1
    fi
    assert_engine_single || rc=1
    if [ "$(port_established "$PHONE_SERIAL" 9637)" = "Y" ]; then
      ok "9637 ESTABLISHED（会话中，无冲突）"
    else
      bad "9637 无 ESTABLISHED（会话中控制口不在？）"; rc=1
    fi
    if [ "$(listening_on "$CAR_SERIAL" 9637)" = "Y" ]; then
      bad "A 侧 9637 竟 LISTEN（抢口冲突）"; rc=1
    else
      ok "A 侧 9637 无监听（不抢口）"
    fi
    wait_stream_alive 20 || rc=1
  else
    log_step "竞态后 120s 未收敛 STREAMING（car=$cur）——按判据 B 检验干净回落"
    if [ "$maxn" -gt 1 ]; then bad "竞态窗口引擎数峰值=$maxn（>1=双开——竞态缺陷）"; rc=1; fi
    wait_state_eq car IDLE 30 || { bad "car 未回 IDLE（$(car_state_now)）——卡死中间态，非干净回落"; rc=1; }
    wait_engine_gone 30 && ok "引擎已归零（干净回落）" || { bad "引擎残留（pid=$(engine_pid)）"; rc=1; }
    wait_port_free phone 9637 30 && ok "9637 已释放（无冲突残留）" || { bad "9637 未释放"; rc=1; }
  fi
  # —— 健康不变量（两种判向都不翻转）——
  logcat_dump phone "$d/logcat-phone.txt" > /dev/null 2>&1 || true
  logcat_dump car   "$d/logcat-car.txt"   > /dev/null 2>&1 || true
  assert_no_anr "$d/logcat-phone.txt" "$d/logcat-car.txt" || rc=1
  pull_vdlog "$d/vd-server.log" > /dev/null 2>&1 || true
  assert_no_force_kill "$d/vd-server.log" || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
