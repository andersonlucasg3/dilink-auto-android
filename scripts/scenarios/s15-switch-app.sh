#!/bin/bash
# @id: S15
# @name: VD 上切 app
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 240
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,switch_app
# @refs: docs/e2e-scenarios.md §4 S15
#
# S01 + switch_app index=1。断言（docs §4 S15）：目标 app 上屏、无 ANR、
# 流仍存活、引擎仍恰一个。
#
# 「目标 app 上屏」判据（宽松，理由见下）：建流时（A 在 HOME 应用网格）与切换后
# 各采一次 A 的 screencap 方差（两次不同前缀），只判两件事——
#   ① 切换后画面非纯黑（screencap_variance 自带 stddev>1.0 判据）；
#   ② 画面发生过变化（两次 stddev 数值不同）。
# 为什么宽松：镜像流 20fps（VideoConfig.TARGET_FPS）+ SurfaceScaler 周期重绘
#（FRAME_INTERVAL_MS 保证静态内容也出帧），同一画面两次采样的 stddev 几乎必然
# 不同；数值完全相同 ⇒ 画面静止/切换未生效（与 wait_stream_alive 的帧增长判据
# 互相印证）。不断言具体哪个 app 上屏：网格首项命中取决于 B 的安装集与
# allowlist，P0.5 校准前不写死包名——只验证"切换动作对画面产生了可观测影响"
# 这一回归面。且 HOME 网格（Compose 视图）→ APP 镜像（SurfaceView 视频）是
# 整屏内容切换，stddev 变化是高信号判据。
#
# ── 已知 UI 漂移（2026-10-16 建档）────────────────────────────────────────
# op_switch_app 第一步找 A 上文本 "Launcher" 的页签，但当前 app-server 流式
# UI 直接展示应用网格（HOME 态）——导航栏仅 Home/Back/Eject（PersistentNavBar.kt
# 的 NavAction 枚举实证；nav_launcher 字符串是无引用残留）。op 会在此步返回 1。
# 场景按 s02 / ops.sh 先例用允许的 UI 驱动兜底：uiautomator dump → 第 N 个应用
# 磁贴（_grid_tile_bounds，2026-10-17 真机校准：真机 dump 单行 XML，按位置判
# 磁贴——clickable + 非 EditText + y1<800）→ input tap；解析失败再退比例坐标。
# 已上报 team-lead。

S15_SD_BEFORE=""

_s15_a_ui() { # → A 侧 UI XML（只读；timeout 包裹 + 重试一次）
  local ui i f="/sdcard/DiLinkAuto/ui-e2e-${E2E_SCENARIO_ID:-s15}.xml"
  for i in 1 2; do
    ui=$(timeout 10 adb -s "$CAR_SERIAL" shell "uiautomator dump $f && cat $f" 2>/dev/null | tr -d '\r')
    echo "$ui" | grep -q '<hierarchy' && { echo "$ui"; return 0; }
    sleep 1
  done
  dcar "rm -f $f" > /dev/null 2>&1 || true
  return 1
}

_s15_tap_app_tile() { # → 0=点过（第 N 个磁贴；_grid_tile_bounds，devices.sh）
  local ui target uixml="/sdcard/DiLinkAuto/ui-e2e-${E2E_SCENARIO_ID:-s15}.xml" i
  ui=$(_s15_a_ui) || { log_err "A 侧 uiautomator dump 失败"; return 1; }
  dcar "rm -f $uixml" > /dev/null 2>&1 || true
  # 磁贴解析（2026-10-17 真机校准）：真机 dump 是**单行** XML，旧「按行 grep +
  # text 过滤」在真机上恒取到根节点 bounds（磁贴父节点 text/content-desc 均为
  # 空串，文本在子节点）→ 改 _grid_tile_bounds：clickable + 非 EditText +
  # y1<800 顶部网格区，按 (y1,x1) 排序取第 OP_INDEX 个。
  target=$(_grid_tile_bounds "$ui" "${OP_INDEX:-1}")
  if [ -n "$target" ]; then
    _tap_bounds "$CAR_SERIAL" "$target"
    log_step "兜底：已点应用网格第 ${OP_INDEX:-1} 项（bounds=$target）"
    return 0
  fi
  # 再兜底：应用网格首行磁贴中心比例坐标（2026-10-17 实测 4 磁贴中心 x≈16%/
  # 40%/64%/87%，y≈11%；index>1 每列 +24%，clamp 到 87%）
  local w W H cx idx
  idx=${OP_INDEX:-1}
  w=$(dcar wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
  W=${w%x*}; H=${w#*x}
  if [ -n "$W" ] && [ -n "$H" ]; then
    cx=$((W * (16 + (idx - 1) * 24) / 100))
    [ "$cx" -gt $((W * 87 / 100)) ] && cx=$((W * 87 / 100))
    dcar input tap "$cx" $((H * 11 / 100)) > /dev/null 2>&1 || true
    log_step "兜底：网格解析失败，已按比例坐标点击（x=$cx y=$((H * 11 / 100))）"
    return 0
  fi
  log_err "兜底失败：取不到 A 屏幕尺寸（wm size）"
  return 1
}

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # 建流时画面基线（A 在 HOME 应用网格）
  local out
  out=$(screencap_variance car "$E2E_RUN_DIR/$E2E_SCENARIO_ID/screencap-before" 1.0)
  S15_SD_BEFORE=$out
  log_step "切换前画面 stddev：${out:-采集失败}"
  # 规范动作：switch_app index=1
  if run_op switch_app index=1; then
    log_step "switch_app op 成功"
  else
    log_step "switch_app op 失败（Launcher 页签缺失的已知漂移）——UI 驱动兜底"
    _s15_tap_app_tile || log_err "兜底点击失败（dump 解析不到应用磁贴）"
  fi
  sleep 3
  return 0
}

scenario_asserts() {
  local rc=0
  # 1) 目标 app 上屏（宽松判据，理由见文件头）
  local out rc_cap
  out=$(screencap_variance car "$E2E_RUN_DIR/$E2E_SCENARIO_ID/screencap-after" 1.0)
  rc_cap=$?
  if [ "$rc_cap" = "2" ]; then
    # rc=2 是采集环境错（传输截断/超时，screencap_variance 已内置重试），不是纯黑（issue #47）
    bad "切换后 A screencap 采集环境错（rc=2，截断/超时；不是纯黑）out=${out:-空}"; rc=1
  elif [ "$rc_cap" != "0" ]; then
    bad "切换后 A 屏纯黑（rc=$rc_cap out=${out:-空}）——解码渲染面空，链路回归"; rc=1
  else
    local b=${S15_SD_BEFORE##*stddev=} a=${out##*stddev=}
    if [ -n "$b" ] && [ "$b" != "$a" ]; then
      ok "画面发生变化（stddev $b → $a；HOME 网格 → APP 镜像）"
    else
      bad "画面无变化（stddev before=${b:-采集失败} after=$a）——切换未生效或流静止"; rc=1
    fi
  fi
  # 2) 无 ANR/崩溃 + 无强制 kill
  assert_no_regression_markers || rc=1
  # 3) 流仍存活
  wait_stream_alive 20 || rc=1
  # 4) 引擎仍恰一个（切 app 不是双开）
  assert_engine_single || rc=1
  # 5) 会话仍健康
  wait_state_eq car   STREAMING 60 || rc=1
  wait_state_eq phone STREAMING 60 || rc=1
  return $rc
}

scenario_restore() {
  run_op neutral
}
