#!/bin/bash
# @id: S25
# @name: VD 连续切换 4 次（4~5 个应用，S25 正式版）
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 360
# @priority: P1
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,switch_app
# @refs: #15 #106
#
# 同一会话连续切换 4 次应用（初始应用可能已在 4 个磁贴之一，因此共 4~5 个应用），
# 每次采样画面并核对 [Window] 的 visReq / n、Pipeline 的 tex / sendDrops。
# 结束时在车机屏顶部点一下，顺带留 #60 的触控证据。
# 本场景只覆盖「连续切换不崩、不黑、纹理持续增长」，不冒充 #55/#58 的真实长视频。

_s25_tap_tile() {
  local idx=$1 ui target f="/sdcard/DiLinkAuto/ui-s25.xml"
  ui=$(dcar "uiautomator dump $f >/dev/null 2>&1; cat $f" 2>/dev/null | tr -d '\r')
  target=$(_grid_tile_bounds "$ui" "$idx") || return 1
  [ -z "$target" ] && return 1
  _tap_bounds "$CAR_SERIAL" "$target"
  return 0
}

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  local i
  for i in 1 2 3 4; do
    log_step "S25 切换第 $i/4 次"
    if ! run_op switch_app index="$i"; then
      log_step "S25 switch_app index=$i 失败，改点磁贴"
      _s25_tap_tile "$i" || { log_err "S25 第 $i 个磁贴点击失败"; return 1; }
    fi
    sleep 4
  done
  # 第 4 次切换上屏后，在车机屏顶部点一下，顺带留触控证据（#60）
  local w
  w=$(dcar wm size 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | head -1)
  [ -n "$w" ] && dcar input tap $(( ${w%x*} / 2 )) 100 > /dev/null 2>&1 || true
  sleep 2
  return 0
}

scenario_asserts() {
  local d="$E2E_RUN_DIR/$E2E_SCENARIO_ID"
  mkdir -p "$d"
  local rc=0 vd="$d/vd-server.log" wins last false_count plines badline firsttex lasttex nval
  assert_streaming_healthy || rc=1
  pull_clientlog "$d/client.log" > /dev/null 2>&1 || true
  vdlog_snapshot "$vd" > /dev/null 2>&1 || true
  logcat_dump car "$d/logcat-car.txt" > /dev/null 2>&1 || true

  if grep -qE '\[BLACK\] decode still alive' "$d/client.log" 2>/dev/null; then
    bad "#15 复现：client.log 出现 [BLACK] decode still alive"
    rc=1
  else
    ok "#15 无 [BLACK] decode still alive"
  fi

  if [ ! -s "$vd" ]; then
    bad "S25 缺 vd-server.log，无法核对 [Window]/Pipeline"
    return 1
  fi

  wins=$(grep -c '\[Window\] display=' "$vd" 2>/dev/null || true)
  if [ "${wins:-0}" -ge 4 ]; then
    ok "S25 [Window] 共 $wins 行（≥ 初始 + 4 次切换中的有效行）"
  else
    bad "S25 [Window] 仅 ${wins:-0} 行，期望 ≥4"
    rc=1
  fi

  local pkg
  for pkg in com.bilibili.app.in com.xingin.xhs com.dragon.read com.baidu.BaiduMap; do
    if grep -q "comp=$pkg" "$vd" 2>/dev/null; then
      ok "S25 应用上屏：$pkg"
    else
      bad "S25 未在 [Window] 看到应用：$pkg"
      rc=1
    fi
  done

  last=$(grep '\[Window\] display=' "$vd" 2>/dev/null | tail -1 || true)
  nval=$(printf '%s\n' "$last" | sed -n 's/.* n=\([0-9]*\) .*/\1/p')
  if [ "${nval:-0}" -ge 4 ]; then
    ok "S25 末条 [Window] n=$nval（≥4）"
  else
    bad "S25 末条 [Window] n=${nval:-0}，期望 ≥4：$last"
    rc=1
  fi
  if printf '%s\n' "$last" | grep -q 'comp=com.baidu.BaiduMap'; then
    ok "S25 末条顶层为百度地图"
  else
    bad "S25 末条顶层不是百度地图：$last"
    rc=1
  fi
  if printf '%s\n' "$last" | grep -q 'visReq=true'; then
    ok "S25 末条顶层 visReq=true"
  else
    bad "S25 末条顶层 visReq!=true：$last"
    rc=1
  fi
  false_count=$(printf '%s\n' "$last" | grep -o 'visReq=false' | wc -l | tr -d ' \r\n')
  if [ "${false_count:-0}" -ge 3 ]; then
    ok "S25 末条旧应用 visReq=false 共 $false_count 个"
  else
    bad "S25 末条旧应用 visReq=false 仅 ${false_count:-0} 个，期望 ≥3"
    rc=1
  fi

  plines=$(grep '\[Pipeline\] Pipeline: .*frames ' "$vd" 2>/dev/null || true)
  if [ -z "$plines" ]; then
    bad "S25 缺 [Pipeline] 统计行，无法核对 tex/sendDrops"
    rc=1
  else
    badline=$(printf '%s\n' "$plines" | grep -vE 'swapFails=0 .*sendDrops=0 .*glErr=0' | head -1 || true)
    if [ -z "$badline" ]; then
      ok "S25 所有 [Pipeline] 统计行 swapFails=0 sendDrops=0 glErr=0（q 允许瞬时 1）"
    else
      bad "S25 [Pipeline] 异常行：$badline"
      rc=1
    fi
    if printf '%s\n' "$plines" | grep -qE 'tex=0([^0-9]|$)'; then
      bad "S25 [Pipeline] 出现 tex=0"
      rc=1
    else
      ok "S25 无 tex=0"
    fi
    firsttex=$(printf '%s\n' "$plines" | head -1 | sed -n 's/.* tex=\([0-9]*\) .*/\1/p')
    lasttex=$(printf '%s\n' "$plines" | tail -1 | sed -n 's/.* tex=\([0-9]*\) .*/\1/p')
    if [ -n "$firsttex" ] && [ -n "$lasttex" ] && [ "$lasttex" -gt "$firsttex" ]; then
      ok "S25 tex 增长：$firsttex → $lasttex"
    else
      bad "S25 tex 未增长：$firsttex → $lasttex"
      rc=1
    fi
  fi

  return $rc
}

scenario_restore() {
  run_op neutral
}
