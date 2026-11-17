#!/bin/bash
# @id: S17
# @name: 帧率/码率切换
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 300
# @priority: P2
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect
# @refs: docs/e2e-scenarios.md §4 S17（会话中 UI 未暴露该设置时挪桌面端 E2E）
#
# S01 + 会话中切换帧率/码率一档，断言会话不断（双端仍 STREAMING）+ 无 ANR。
#
# 源码勘察结论（2026-10-16 写场景时逐字核对）：
#   - 帧率/码率设置只暴露在 A（接收端 app-server）的**启动屏**：
#     CarLaunchScreen.kt 的 ConnectionStatusCard → SettingSection ×3，
#     标题 "Video Bitrate" / "Target FPS"，preset chips 原文见
#     app-server/src/main/res/values/strings.xml（2M/2.5M/3M/4M/6M、
#     20/24/30/60 FPS）。FPS 改动走 setStartupFps()（持久化 + 应用到在途
#     解码器，CarConnectionService.kt:76-79）；码率改动只写 prefs，下次握手
#     才生效（CarLaunchScreen.kt:78）。
#   - B（发送端 app-client）的设置页（SettingsScreen.kt）只有 权限/调试/关于，
#     无帧率码率入口。
#   - A 进入 STREAMING 后启动屏被流媒 UI 取代（CarShell.kt showStreamingMode）
#     ——「会话中切换」这条产品路径当前不存在。
# 因此本场景按 docs §4 S17 的豁免条款执行（诚实判据，不假通过）：
#   双端 uiautomator dump 找设置入口 →
#     找到   → 点进去切一档（re-dump 验证读数变化），断言会话不断 + 无 ANR；
#     找不到 → 显式 SKIPPED(env)（ops log_step 留痕 + asserts log_step + return 0），
#               @refs 注明「挪桌面端 E2E」。
#   后续建议（不在本场景范围）：补 pre-connect 变体——A 启动屏改档 → connect →
#    校验 client.log 握手行「Car display: … fps=N bitrate=M」
#    （ConnectionService.kt:521 逐字串）。
# isolated：入口恒为中立态，流由 setup 内部 reach_streaming 重建。

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  # —— 会话中 UI 探测：双端 uiautomator dump（只读 UI 命令，白名单内）——
  # 手法同 ops.sh _a_dump：dump 到 /sdcard 再 cat 回（uiautomator dump 只写文件）。
  local ui_car ui_phone
  ui_car=$(timeout 10 adb -s "$CAR_SERIAL" shell "uiautomator dump /sdcard/ui_s17.xml && cat /sdcard/ui_s17.xml" 2>/dev/null | tr -d '\r')
  ui_phone=$(timeout 10 adb -s "$PHONE_SERIAL" shell "uiautomator dump /sdcard/ui_s17.xml && cat /sdcard/ui_s17.xml" 2>/dev/null | tr -d '\r')
  printf '%s\n' "$ui_car"  > "$d/ui-car-dump.xml"
  printf '%s\n' "$ui_phone" > "$d/ui-phone-dump.xml"
  if ! printf '%s' "$ui_car" | grep -q '<hierarchy' && ! printf '%s' "$ui_phone" | grep -q '<hierarchy'; then
    log_err "双端 uiautomator dump 均失败——设置入口探测未成（env 问题，非产品判定）"
    return 1
  fi
  # 入口候选文本（中英双语；帧率/码率直达文本优先——命中通用 "Settings" 但
  # 子页无帧率码率时，后续切档验证会失败并如实记录，不谎称通过）。
  local t found="" side ui
  for side in car phone; do
    if [ "$side" = "car" ]; then ui="$ui_car"; else ui="$ui_phone"; fi
    for t in "帧率" "码率" "FPS" "Bitrate" "Target FPS" "Video Bitrate"; do
      if printf '%s' "$ui" | grep -qF "text=\"$t\"" || printf '%s' "$ui" | grep -qF "content-desc=\"$t\""; then
        found="$side:$t"; break 2
      fi
    done
  done
  if [ -z "$found" ]; then
    # 与源码勘察一致：会话中双端 UI 均不暴露帧率/码率（A 已进流媒 UI；
    # B 设置页仅权限/调试/关于）——按 docs §4 S17 豁免条款记 SKIPPED(env)。
    log_step "SKIPPED(env)：会话中双端 UI 均无帧率/码率设置入口（dump 已归档 $d/ui-*-dump.xml）"
    log_step "该设置只在 A 的启动屏暴露（CarLaunchScreen.kt），会话中被 CarShell 流媒 UI 取代——产品当前无「会话中切换」路径，挪桌面端 E2E（见 @refs）"
    S17_ENV_SKIP=1
    return 0
  fi
  log_step "找到设置入口：$found——点进去切一档"
  # —— 切一档：点 preset chip（候选串取自 strings.xml preset_* 原文）——
  # chip 文本数组：FPS 四档 + 码率五档。点中后 re-dump 验证「当前值读数」
  # 是否变化——变了才记已切换；没变不谎称。读数格式按源码逐字：
  #   formatBitrateMbps → "2.5M"/"4M"（SettingsFormat.kt:37-47）
  #   fps_value → "%d FPS"（strings.xml）
  # uiautomator dump 深度优先，标题行（含读数）先于 chips 行——head -1 即读数。
  local sh chips c node b p1 p2 cx cy before after ui2 switched=0
  if [ "$side" = "car" ]; then sh="$CAR_SERIAL"; else sh="$PHONE_SERIAL"; fi
  chips=("30 FPS (Smooth)" "60 FPS (High refresh)" "20 FPS (Eco)"
         "3M (Balanced)" "6M (High quality)" "2M (Fastest)")
  before=$(printf '%s' "$ui" | grep -oE '[0-9]+(\.[0-9]+)?M|[0-9]+ ?FPS' | head -1)
  for c in "${chips[@]}"; do
    node=$(printf '%s' "$ui" | grep -oE "text=\"$c\"[^>]*bounds=\"\[[0-9,]+\]\[[0-9,]+\]\"" | head -1)
    [ -z "$node" ] && continue
    b=$(printf '%s' "$node" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    [ -z "$b" ] && continue
    p1=${b%%][*}; p1=${p1#[}          # "[x1,y1][x2,y2]" → "x1,y1"
    p2=${b##*][}; p2=${p2%]}          #                  → "x2,y2"
    cx=$(( (${p1%,*} + ${p2%,*}) / 2 ))
    cy=$(( (${p1#*,} + ${p2#*,}) / 2 ))
    adb -s "$sh" shell input tap "$cx" "$cy" > /dev/null 2>&1
    sleep 2
    ui2=$(timeout 10 adb -s "$sh" shell "uiautomator dump /sdcard/ui_s17.xml && cat /sdcard/ui_s17.xml" 2>/dev/null | tr -d '\r')
    printf '%s\n' "$ui2" > "$d/ui-after-switch.xml"
    after=$(printf '%s' "$ui2" | grep -oE '[0-9]+(\.[0-9]+)?M|[0-9]+ ?FPS' | head -1)
    if [ -n "$before" ] && [ -n "$after" ] && [ "$before" != "$after" ]; then
      switched=1
      log_step "已切换一档：读数 ${before:-?} → $after（chip=$c）"
      break
    fi
    log_step "点了 chip=$c 但读数未变（${before:-?} → ${after:-?}）——可能该 chip 本就是当前档或读数正则未覆盖（P0.5 校准点），尝试下一个 chip"
  done
  [ "$switched" = "1" ] || log_step "未验证到档位变化——asserts 仍照常断言会话不断（不断言「切换成功」，只断言「切换动作不打断会话」）"
  return 0
}

scenario_asserts() {
  local rc=0
  if [ "${S17_ENV_SKIP:-0}" = "1" ]; then
    # 显式 SKIPPED(env)：缺断言=没测，不混入通过（P6）。留痕见 ops 的 log_step；
    # return 0 让编排记 SKIPPED(env) 而非 FAIL——本场景未构成任何产品判定。
    log_step "S17 SKIPPED(env)：会话中 UI 无帧率/码率设置入口——未执行任何产品断言（挪桌面端 E2E，见 @refs）"
    return 0
  fi
  # 找到入口并切换过：会话不断 = 双端仍 STREAMING + 流存活 + 引擎单开 + 无 ANR。
  # 不断言「新档位已生效在途会话」（码率本就只在下下次握手生效，CarLaunchScreen.kt:78）。
  wait_state_eq car   STREAMING 60 || { bad "切换后 car 未保持 STREAMING（$(car_state_now)）"; rc=1; }
  wait_state_eq phone STREAMING 60 || { bad "切换后 phone 未保持 STREAMING（$(phone_state_now)）"; rc=1; }
  wait_stream_alive 20 || rc=1
  assert_engine_single || rc=1
  assert_no_regression_markers || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
