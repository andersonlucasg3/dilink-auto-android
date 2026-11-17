#!/bin/bash
# @id: S22
# @name: 撤销调试授权
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (IDLE, WAITING, absent, absent, off)
# @budget_s: 360
# @priority: P2
# @expect: fail
# @invariants: full
# @ops: neutral,launch_client,launch_server,adb_revoke,connect
# @manual: true
# @refs: docs/e2e-scenarios.md §4 S22；.plan/e2e-harness-design.md §2.4/§5-1
#
# 撤销 B 的 USB 调试授权后重连：按设计应引导重新授权，不静默失败。
# @expect: fail + @manual: true——默认跳过记 SKIPPED(manual)，仅编排带
# --include-manual 时运行；且应排在全套场景最后（跑完 PC↔B 通道已断）。
#
# ── 运营陷阱（必读；.plan/e2e-harness-design.md §5-1 / risk R07/R11）──
# 撤销的是 PC↔B 的 **USB 调试授权** → PC 立即失去对 B 的 USB 控制：
#   · 场景后半段与 restore 的 run_op neutral 全部依赖 B 可达；
#   · 编排层必须预建 IP:5555 WiFi adb 旁路、以网络 serial 作 --phone
#     （Android 11+ 无线授权是否独立于 USB 授权：MEIZU 20 Inf = Android 15，
#      预期独立但 design §5-1 标注未实测——首跑需人工确认旁路存活）；
#   · 无旁路时：asserts 记 SKIPPED(dep)（B 不可达，B 侧日志面空洞），
#     restore 必败 → 编排应视作 ENV_BROKEN 中止后续场景；
#   · 人工恢复授权后才能继续：B 重新插 USB → 手机上确认「允许 USB 调试」
#     授权框（或开发者选项重新授权）。脚本禁止自动重新授权（R07/R11）。
# 另注意 harness 自扰：op_connect 内部的 accept_auth_dialog 会自动代点 B 的
# 授权框——有 WiFi 旁路时它可能替产品「完成引导」使会话意外建成。此时记
# UNEXPECTED-PASS（§2.4：计入退出码 1），需人工判读是产品缺陷还是运营旁路。
#
# 与 §2.4 骨架的一处偏差（有意）：骨架 setup 只 neutral；本场景补
# launch_client / launch_server——否则 connect 的失败面是「client 没起」
# （Connection refused，CarConnectionService.kt:606）而非「授权被撤」，
# 测的不是本场景要验的东西。setup 不建流：授权撤销不影响已建会话的 WiFi
# socket（控制/视频/触摸是 app 级 TCP），要验的是「撤销后**新连接**必须过
# 授权闸」——所以只把两端拉到可建流位（client→WAITING、server 起来）。
# 主操作判向同 S21 偏差①：op_connect 的 rc 只覆盖 UI 驱动链路，产品级
# 成败改看 60s 收敛窗口（与 expect_fail 语义等价）。

scenario_setup() {
  run_op neutral || return 1
  run_op launch_client || { log_err "拉起 B 的 client 失败"; return 1; }
  wait_port_listening phone 9637 90 || { log_err "9637 未 LISTEN（client 未进 WAITING？）"; return 1; }
  run_op launch_server || { log_err "拉起 A 的 server 失败"; return 1; }
  sleep 2
  return 0
}

scenario_ops() {
  # adb_revoke 返回 2 = 开发者选项里找不到撤销入口（ROM 差异）→ SKIPPED(env)
  run_op adb_revoke || {
    local rc=$?
    if [ "$rc" = "2" ]; then
      log_step "SKIPPED(env)：开发者选项无「撤销 USB 调试授权」入口（ROM 差异）"
      skip_env "开发者选项无「撤销 USB 调试授权」入口（ROM 差异）"
      return 0
    fi
    log_err "adb_revoke 失败（rc=$rc）"
    return 1
  }
  # 撤销后 PC↔B 的 USB 通道可能已断（见文件头运营陷阱）；connect 只驱动 A 的
  # UI，不依赖 PC↔B（授权框代点失败会被 op 内部的 || true 吞掉）。
  run_op connect || { log_err "connect 的 UI 驱动链路失败（A 侧 dump/定位）——非预期失败面"; return 1; }
  local deadline=$((SECONDS + 60)) cur=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur=$(car_state_now)
    [ "$cur" = "STREAMING" ] && break
    [ "$cur" = "IDLE" ] && break   # 已收敛到明确的失败终态，无需等满窗口
    sleep 2
  done
  if [ "$cur" = "STREAMING" ]; then
    # 主操作意外成功。若曾弹授权框且被 harness 代点（WiFi 旁路在位时
    # accept_auth_dialog 会自动点），属运营旁路复授权——需人工判读。
    bad "UNEXPECTED-PASS：撤销授权后重连竟收敛 STREAMING（若为 accept_auth_dialog 代点授权框所致，属运营旁路——需人工判读，§2.4）"
    return 1
  fi
  ok "按预期失败：撤销授权后重连未收敛（car=$cur phone=$(phone_state_now)）"
  return 0
}

scenario_asserts() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  if [ -n "${E2E_SCENARIO_SKIP:-}" ]; then
    log_step "S22 SKIPPED(env)：撤销授权入口未找到（ROM 差异）——未执行任何产品断言"
    return 0
  fi
  local rc=0
  # —— B 可达性门：USB 授权已撤且无 WiFi 旁路 → B 侧日志面空洞 ——
  local b_reach=1
  [ "$(adb -s "$PHONE_SERIAL" get-state 2>/dev/null | tr -d '\r\n ')" = "device" ] || b_reach=0
  # —— 取证（A 侧恒可采；B 侧可达才采）——
  logcat_dump car "$d/logcat-car.txt" > /dev/null 2>&1 || true
  if [ "$b_reach" = "1" ]; then
    logcat_dump phone "$d/logcat-phone.txt" > /dev/null 2>&1 || true
    # B 的授权对话框快照（撤销后 A 连 B:5555 必弹「允许 USB 调试吗」；
    # 被 PC 代点后可能已消失——在/不在都是证据，归档备查）。
    dphone "uiautomator dump /sdcard/ui_s22.xml && cat /sdcard/ui_s22.xml" 2>/dev/null | tr -d '\r' > "$d/ui-phone-dump.xml"
  else
    log_step "SKIPPED(dep)：B 不可达（USB 授权被撤销且无 IP:5555 WiFi 旁路）——B 侧断言空洞，仅判 A 侧证据；restore 预计失败，需人工恢复授权（见文件头运营陷阱）"
  fi
  # —— 失败必须「明确」：错误码 / 授权引导；静默失败 = 产品缺陷（§2.4）——
  # 源码逐字证据串：RemoteAdbController.kt:37 Log.e「Failed to connect ADB to」；
  # CarConnectionService.kt:809「Dev mode: TCP ADB connection failed to」；
  # CarConnectionService.kt:606「WiFi connect failed:」（控制口被拒时）。
  # 引导面补充证据：B 授权对话框文本（允许/Allow/调试/debugging/RSA）——弱证据，
  # 仅作补充（对话框文案 ROM 差异大，命中需人工确认语义）。
  local hit=0 f pat
  for f in "$d/logcat-car.txt" "$d/ui-phone-dump.xml"; do
    [ -s "$f" ] || continue
    for pat in 'Failed to connect ADB to' 'Dev mode: TCP ADB connection failed to' 'WiFi connect failed:' \
               '允许' 'Allow' '调试' 'debugging' 'RSA'; do
      if [ "$(countF "$f" "$pat")" -ge 1 ]; then
        ok "失败/引导证据命中（源码逐字串或授权框文本）：「$pat」@$(basename "$f")"
        hit=1; break 2
      fi
    done
  done
  if [ "$hit" = "0" ]; then
    # P0.5 校准点：源码串与授权框文本均未命中 → 宽松兜底（弱通过+告警）。
    local c; c=$(countF "$d/logcat-car.txt" 'ERROR')
    if [ "$c" -ge 1 ]; then
      ok "失败证据（弱通过·宽松兜底串「ERROR」×$c）——P0.5 待按 §F 收紧"
    else
      bad "静默失败：无任何错误码/授权引导证据——撤销授权后重连既不报错也不引导，是产品缺陷"
      rc=1
    fi
  fi
  # —— 健康不变量照常硬断言（A 必查；B 可达才查，不可达已记 SKIPPED(dep)）——
  if [ "$b_reach" = "1" ]; then
    assert_no_anr "$d/logcat-phone.txt" "$d/logcat-car.txt" || rc=1
  else
    assert_no_anr "$d/logcat-car.txt" || rc=1
  fi
  return $rc
}

scenario_restore() {
  # best-effort：B 无 WiFi 旁路时 neutral 必败（PC↔B USB 已断）——编排按
  # ENV_BROKEN 处理并中止，人工恢复授权后才能继续（见文件头运营陷阱）。
  run_op neutral
}
