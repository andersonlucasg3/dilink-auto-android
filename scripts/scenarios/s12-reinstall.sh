#!/bin/bash
# @id: S12
# @name: 重装发送端 APK
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 300
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,reinstall
# @refs: docs/e2e-scenarios.md §4 S12
#
# S01 + reinstall（B 的 app-client install -r）。断言（docs §4 S12）：
# client 重新起来（9637 socket 重新活跃，包住 START_STICKY ~18s 重启 + 冷启动）、
# 之后能重新建流、无 ANR、无强制 kill。
#
# 重新建流的两条路径都处理（产品是否自动重连未定，两种都合法）：
#   路径 A（产品自动重连）：install -r 后 A 的 server 侧重拨（重连退避
#     500ms→8s 封顶，CarConnectionService.reconnectBackoffMs），B 的 client
#     一回到 WAITING 就连上——观测窗内 phone 直接回 STREAMING，只记 ok。
#   路径 B（不自动重连）：观测窗内 phone 停在 WAITING，由 harness 补一次
#     run_op connect 后再等收敛。
# 分叉用「无记账轮询」判定走向：wait_state_eq 超时会记 bad，而路径 B 是合法
# 路径、不能污染记账——所以先只读轮询 30s 分叉，再用 wait_state_eq 收敛。
#
# op_reinstall 内部已是确定性策略：install -r（会先停 app）→ force-stop 压掉
# START_STICKY 的 ~18s 迟到重启 → monkey 单次拉起（op_launch_client 内含
# wait_port_listening 90）。本场景的 9637 断言是对该行为的独立复核。
#
# vdlog 计数差：引擎的 vd-server.log 是 >> 追加写（VdDeploy 不截断），setup 会话
# 的 'Video on :9638'/'Starting: VD=' 行仍在文件里——直接扫 --min 1 会被旧会话
# 行喂饱而假通过。所以 ops 阶段先记基线计数，asserts 要求计数**增长**（新会话
# 自己写过这两行）。

S12_N_VIDEO_BEFORE=0
S12_N_VD_BEFORE=0

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # 重装前记 vdlog 关键行计数基线（供 asserts 做计数差）
  vdlog_snapshot > /dev/null 2>&1 || true
  S12_N_VIDEO_BEFORE=$(countF "$E2E_VDLOG" 'Video on :9638')
  S12_N_VD_BEFORE=$(countF "$E2E_VDLOG" 'Starting: VD=')
  log_step "S12：重装 B 的 client（install -r + 压重启 + monkey 拉起）；基线 Video×$S12_N_VIDEO_BEFORE VD×$S12_N_VD_BEFORE"
  run_op reinstall || { log_err "reinstall op 失败（APK 缺失/install 失败？）"; return 1; }
  return 0
}

scenario_asserts() {
  local rc=0
  # 1) client 重新起来：9637 的 socket 重新活跃（90s 包住 START_STICKY ~18s +
  #    冷启动）。注意判据取「LISTEN 或 ESTABLISHED」而不是单等 LISTEN——
  #    Connection.accept 每接受一个连接就 close() LISTEN socket
  #    （protocol-core Connection.kt:466-468 finally），会话中 9637 只有
  #    ESTABLISHED（observability §A0）。路径 A（产品已自动重连）下 9637 必然
  #    是 ESTABLISHED，单等 LISTEN 会空转 90s 假失败。
  local deadline=$((SECONDS + 90)) live=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(listening_on "$PHONE_SERIAL" 9637)" = "Y" ] || \
       [ "$(port_established "$PHONE_SERIAL" 9637)" = "Y" ]; then live=1; break; fi
    sleep 1
  done
  [ "$live" = "1" ] && ok "client 已重起（9637 socket 活跃：LISTEN=等待重连 / ESTABLISHED=已自动重连）" \
                    || { bad "reinstall 后 9637 无 LISTEN 也无 ESTABLISHED（client 没起来）"; rc=1; }

  # 2) 重新建流——路径 A/B 分叉（见文件头注释）
  local auto=0
  local deadline=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$(phone_state_now)" = "STREAMING" ] && { auto=1; break; }
    sleep 1
  done
  if [ "$auto" = "1" ]; then
    ok "路径 A：产品自动重连（reinstall 后未经 harness 介入回 STREAMING）"
  else
    log_step "路径 B：30s 观测窗内未自动重连（phone=$(phone_state_now)）——补一次 connect"
    run_op connect || { bad "路径 B 补 connect 失败"; rc=1; }
  fi
  wait_state_eq phone STREAMING 120 || rc=1
  wait_state_eq car   STREAMING 120 || rc=1

  # 3) 重建后的会话与 S01 同健康度
  assert_engine_single || rc=1
  assert_sentinel phone 2147483647 30 || rc=1
  wait_port_established phone 9638 30 && ok "9638 重新 ESTABLISHED（视频轨重建）" \
    || { bad "9638 无 ESTABLISHED（视频轨没重建）"; rc=1; }
  # 引擎重启后 vd-server.log 由新会话追加写（VdDeploy 用 >> 不截断）——
  # 取新快照后做计数差：新会话必须自己写过这两行（旧会话行不算）
  vdlog_snapshot > /dev/null 2>&1 || true
  local n_video n_vd
  n_video=$(countF "$E2E_VDLOG" 'Video on :9638')
  n_vd=$(countF "$E2E_VDLOG" 'Starting: VD=')
  if [ "$n_video" -gt "$S12_N_VIDEO_BEFORE" ]; then
    ok "新会话有 'Video on :9638'（$S12_N_VIDEO_BEFORE → $n_video）"
  else
    bad "vdlog 无新 'Video on :9638' 行（$S12_N_VIDEO_BEFORE → $n_video）——新会话没起视频轨？"; rc=1
  fi
  if [ "$n_vd" -gt "$S12_N_VD_BEFORE" ]; then
    ok "新会话有 'Starting: VD='（$S12_N_VD_BEFORE → $n_vd）"
  else
    bad "vdlog 无新 'Starting: VD=' 行（$S12_N_VD_BEFORE → $n_vd）——新 VD 没创建？"; rc=1
  fi
  wait_stream_alive 20 || rc=1

  # 4) 无 ANR/崩溃 + 无强制 kill
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
