#!/bin/bash
# @id: S02
# @name: 触摸回传
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 180
# @priority: P0
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect
# @refs: docs/e2e-scenarios.md §4 S02
#
# S01 + A 上 tap 三个点。断言：触摸注入链路有证据（client.log 的 A 端 Touch #N
# 中继行）、流未被触摸打断（帧计数继续增长）。
#
# 进入镜像面的必要性（2026-10-17 try2 根因）：CarShell 的 currentScreen 初始 =
# HOME（HomeContent 应用网格），MirrorContent(visible = currentScreen == APP)
# ——镜像面只在点开某个 app 后才可见；Home 态下 MirrorScreen 的 SurfaceView 是
# INVISIBLE（不接收触摸）→ 旧版直接 tap 恒无回传。步骤：dump A UI → 顶部网格区
# 第一个磁贴（_grid_tile_bounds）→ tap → 等 A logcat「CarShell: launchApp
# clicked」证据（切到 Screen.APP）。黑屏误报风暴的等待已在 reach_streaming 内
# 完成（wait_stream_settled）——风暴窗口内输入连接被反复拆除，tap 必丢。
#
# 触摸点选取：进入镜像面后，A 屏幕相对坐标 25%/50%/75% 横向 × 55% 纵向——y=55%
# （≈1287）落在镜像区中部，避开顶部磁贴带（y≤398）、搜索框（y≈1980）与底部
# 导航栏（y≥2171）。坐标由场景用 wm size 现算，不写死（UI 自动化坐标铁律：
# 坐标一律程序检测）。

_s02_ui() { # → A 侧 UI XML（重试 3 次；单行/多行兼容见 _grid_tile_bounds）
  local ui i f="/sdcard/DiLinkAuto/ui-e2e-s02.xml"
  for i in 1 2 3; do
    ui=$(timeout 10 adb -s "$CAR_SERIAL" shell "uiautomator dump $f && cat $f" 2>/dev/null | tr -d '\r')
    echo "$ui" | grep -q '<hierarchy' && { echo "$ui"; return 0; }
    sleep 1
  done
  return 1
}

_s02_enter_mirror() { # → 0=已进 Screen.APP（A logcat 有 launchApp 证据）
  # 逐候选磁贴尝试（第 1、2 个）：dump → _grid_tile_bounds → _tap_bounds →
  # 轮询 A logcat 12s 等「launchApp clicked」。失败返回 1——调用方**软处理**：
  # scenario_ops 失败会把场景直接判 FAIL，而这里只是前置动作，真正判据是触摸回传。
  local ui b lf found i deadline
  lf="$E2E_RUN_DIR/${E2E_SCENARIO_ID:-S02}/logcat-car-launch.txt"
  for i in 1 2; do
    ui=$(_s02_ui) || { log_err "A 侧 uiautomator dump 失败"; continue; }
    b=$(_grid_tile_bounds "$ui" "$i")
    if [ -z "$b" ]; then
      log_err "dump 里无第 $i 个应用磁贴（顶部网格区无 clickable 节点？）"
      continue
    fi
    _tap_bounds "$CAR_SERIAL" "$b" || { log_err "磁贴 tap 失败（bounds=$b）"; continue; }
    log_step "已点应用网格第 $i 个磁贴（bounds=$b）→ 等 launchApp 证据"
    found=0; deadline=$((SECONDS + 12))
    while [ "$SECONDS" -lt "$deadline" ]; do
      if logcat_dump car "$lf" 2>/dev/null && grep -qF 'launchApp clicked' "$lf"; then
        found=1; break
      fi
      sleep 2
    done
    [ "$found" = "1" ] && { log_ok "已进入镜像面（CarShell launchApp 证据在位）"; return 0; }
    log_err "tap 后 12s 无 launchApp 证据（第 $i 个候选）"
  done
  return 1
}

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  local size W H x1 x2 x3 y i
  # 0) 进入镜像面（Home 态下 MirrorScreen 不可见，触摸不回传——try2 根因）。
  #    失败不 return 1（避免 ops_failed 直接判败），继续 tap 收证据。
  _s02_enter_mirror || log_err "进入镜像面失败（无 launchApp 证据）——后续 tap 可能无效，继续收证据"
  # 1) wm size 偶发空输出（2026-10-16 P0 S02 实证：adb 调用被沙箱/杀软干扰时单次
  # 失败，手动复现成功）——重试 3 次，仍失败才判错。
  for i in 1 2 3; do
    size=$(dcar wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
    [ -n "$size" ] && break
    log_step "A 的 wm size 第 $i 次无输出，重试…"
    sleep 2
  done
  W=${size%x*}; H=${size#*x}
  if [ -z "$W" ] || [ -z "$H" ]; then
    log_err "取不到 A 的屏幕尺寸（wm size，3 次重试后仍失败）"
    return 1
  fi
  # 2) 镜像区三组 tap（进 APP 面后 SurfaceView 全宽接收触摸）
  y=$((H * 55 / 100))
  x1=$((W * 25 / 100)); x2=$((W * 50 / 100)); x3=$((W * 75 / 100))
  for i in 1 2 3; do
    dcar input tap "$x1" "$y" > /dev/null 2>&1
    sleep 1
    dcar input tap "$x2" "$y" > /dev/null 2>&1
    sleep 1
    dcar input tap "$x3" "$y" > /dev/null 2>&1
    sleep 1
  done
  log_step "已在镜像面 tap 三组点（x=$x1/$x2/$x3 y=$y，屏 ${W}x${H}）"
  return 0
}

scenario_asserts() {
  local rc=0
  # 触摸注入链路证据 + 流未断
  assert_touch_echo || rc=1
  # 触摸后双端应仍在 STREAMING（触摸不应打断会话）
  wait_state_eq car   STREAMING 30 && ok "触摸后 car 仍 STREAMING"   || { bad "触摸后 car=$(car_state_now)"; rc=1; }
  wait_state_eq phone STREAMING 30 && ok "触摸后 phone 仍 STREAMING" || { bad "触摸后 phone=$(phone_state_now)"; rc=1; }
  # A 侧 screencap 非纯黑（VD 上屏正常）
  local cv
  cv=$(screencap_variance car "$E2E_RUN_DIR/$E2E_SCENARIO_ID/screencap-car" 1.0) \
    && ok "A 屏非纯黑（${cv:-}）" \
    || { bad "A 屏纯黑或截屏环境错（${cv:-}）"; rc=1; }
  return $rc
}

scenario_restore() {
  run_op neutral
}
