#!/bin/bash
# @id: S23
# @name: 音频镜像（含熄屏后持续出帧）
# @pre: (IDLE, IDLE, absent, absent, off)
# @post: (STREAMING, STREAMING, present, present, on)
# @budget_s: 360
# @priority: P2
# @expect: pass
# @invariants: full
# @ops: neutral,launch_client,launch_server,connect,screen_off,screen_on
# @refs: .plan/issue-13-audio-implementation.md §4.5（S23 定义）；docs/e2e-scenarios.md §4（S23 行待补，见 issue #79）
#
# S01（reach_streaming）+ screen_off → sleep 10 → screen_on，把 issue #13 §4.5 的
# 五条音频判据固化成回归网。依赖 PA（PR #78：9640 监听 + vd-server 周期帧数行）与
# PB（车机 AudioPlayer 统计行），二者当前均未合并——本场景在缺任一前置时如实判 FAIL，
# 不假通过（S6 是「脚本先落地」，真机判据待两台设备回签，见 issue #79）。
#
# ── 五条判据（#13 §4.5）──────────────────────────────────────────────────
#   1. vd-server 侧音频帧计数 > 0 且窗口内增长（名义 ≈46.875 帧/s；判据 = 两点增长
#      + 速率下限 ≥20 帧/s，**非严格线性拟合**）
#   2. app-server 侧 decoded > 0、starved == 0（会话全程累计口径，含起播期）、
#      underrun 不随时间增长（delta 口径，容忍起播期恒定噪声）
#   3. 静音期补帧计数 > 0 且 starved == 0（F2 判据）
#   4. screen_off 后 10s 内音频帧仍增长（覆盖 R7）
#   5. 音频通道失败/降级时视频会话仍存活（降级判据：双端 STREAMING + 9638 ESTABLISHED）
#
# ── 组合判据的真实覆盖面：传输 + 解码链路失败（S2 第三轮评审 P0 机理是立项理由）──────
# `.agents/teamwork/reviewer_70_s2_r3/handoff.md` P0-1：采集线程曾漏掉
# `AudioRecord.startRecording()`，`read()` 恒返 0 → 采集线程只按 46.875 帧/s 节拍
# 补**静音帧**，`frameCount` **照样**增长。故判据 #1 **单独不足以判成功**。
#
# 本场景的「组合判据」= 判据 #1（vd 帧 >0 且增长）∧ 判据 #2（播放端 decoded >0），
# 二者同时成立才 ok。它**真正兜住的是传输 / 解码链路断裂**：9640 未绑 / 无接收端 /
# 连接断 / 解码器建不起来 → decoded==0 → 组合判据 FAIL。这有价值，但**不是所引 P0**。
#
# **它兜不住所引的 P0**：源侧只出静音帧时，AAC 照样编码出帧 → vd frames 增长（#1 过），
# 车机 feedDecoder 成功 → decodedFrames++（#2 也过）→ 组合判据**照绿**。
# PB 日志契约（decoded / starved / underrun / maxRecoverMs / queue）**不含静音/幅度字段**，
# **源侧静音（漏 `startRecording`）不可由本场景区分，需人工听觉回签**（issue #79）。
#
# 残留局限（诚实标注，不夸大覆盖）：唯一能直接区分静音的是 vd-server 终局行
# `Audio: capture stopped (frames=N, silence=M)` 的 silence 计数（silence≈frames =
# 纯静音），而该行只在会话结束（scenario_restore）时产生、asserts 窗口内取不到
# → 判据 #3 的「补帧计数 >0」降为观测项（见下方 judgement 3 说明与 issue #79）。
#
# ── 日志契约（正则集中在本常量区，禁止在断言里散落 —— S6 立项明确要求「避免猜测正则」）──
# PA 周期行（PR #78，vd-server/.../SocketAudioSink.kt:177，每 120 FRAME 一行 ≈2.56s）：
#   [Pipeline] HH:mm:ss.SSS Audio: sent frames=<累计FRAME> configs=<累计CONFIG> dropped=<累计丢弃> queued=<队列深度>
# PA 终局行（PipelineServer.kt:850）：Audio: sink closed (sent=.., configs=.., dropped=.., failed=..)
# PA 采集起（AudioPipeline.kt:291）：Audio: capture started (...)
# PA 采集停（AudioPipeline.kt:313）：Audio: capture stopped (frames=N, silence=M)
# PA 降级行（PipelineServer.kt:295/302）：
#   Audio: 9640 not bound this session, audio disabled
#   Audio: no receiver on 9640 within 3000ms, audio disabled (capture not started)
# PB 统计行（协调者定死，经 TCP 中继进 B 的 client.log，tag=CarLog）：
#   [AudioPlayer] decoded=<累计解码帧> starved=<累计欠载补静音> underrun=<AudioTrack underrunCount> maxRecoverMs=<最长恢复耗时> queue=<队列深度>
#
# isolated：入口恒为中立态，流由 setup 内部 reach_streaming 重建。

# ── 日志正则常量（唯一出处）────────────────────────────────────────────
S23_VD_SENT_RE='Audio: sent frames=[0-9]+ configs=[0-9]+ dropped=[0-9]+ queued=[0-9]+'
S23_VD_CAPTURE_STARTED='Audio: capture started'
S23_VD_CAPTURE_STOPPED_RE='Audio: capture stopped \(frames=[0-9]+, silence=[0-9]+\)'
S23_VD_AUDIO_DISABLED_RE='Audio: (no receiver on 9640 within [0-9]+ms, audio disabled \(capture not started\)|9640 not bound this session, audio disabled)'
S23_PLAYER_STAT_RE='\[AudioPlayer\] decoded=[0-9]+ starved=[0-9]+ underrun=[0-9]+ maxRecoverMs=[0-9]+ queue=[0-9]+'

# ── 采样夹具（跨 setup/ops/asserts 的全局量；scenario 文件被 source 进编排同一进程）──
S23_T0=0; S23_T1=0
S23_FRAMES_T0=""; S23_FRAMES_T1=""
S23_FRAMES_OFF_START=""; S23_FRAMES_OFF_END=""
S23_PLAYER_T0=""; S23_PLAYER_T1=""

_s23_num() { # <v> → 打印纯整数；非纯数字则空（set -u 友好的数字护栏）
  case "${1:-}" in ''|*[!0-9]*) echo "" ;; *) echo "$1" ;; esac
}

_s23_vd_snapshot() { vdlog_snapshot > /dev/null 2>&1 || true; }

_s23_vd_sent_line() { # → vdlog 末条 `Audio: sent frames=...` 行（无则空）
  _s23_vd_snapshot
  grep -oE "$S23_VD_SENT_RE" "$E2E_VDLOG" 2>/dev/null | tail -1
}

_s23_vd_frames_now() { # → 末条周期行的累计 FRAME 数（无则空）
  _s23_vd_sent_line | grep -oE 'frames=[0-9]+' | tail -1 | grep -oE '[0-9]+'
}

_s23_vd_audio_state() { # → started | disabled | absent（判据 #5 的降级诊断）
  _s23_vd_snapshot
  if grep -qE "$S23_VD_AUDIO_DISABLED_RE" "$E2E_VDLOG" 2>/dev/null; then echo disabled; return 0; fi
  if grep -qF "$S23_VD_CAPTURE_STARTED" "$E2E_VDLOG" 2>/dev/null; then echo started; return 0; fi
  echo absent
}

_s23_vd_silence_now() { # → 末条采集停行的 silence=M 的 M（无则空）
  _s23_vd_snapshot
  grep -oE "$S23_VD_CAPTURE_STOPPED_RE" "$E2E_VDLOG" 2>/dev/null | tail -1 | grep -oE 'silence=[0-9]+' | grep -oE '[0-9]+'
}

_s23_player_line() { # → client.log 末条 `[AudioPlayer] ...` 统计行（无则空）
  clientlog_snapshot > /dev/null 2>&1 || true
  grep -oE "$S23_PLAYER_STAT_RE" "$E2E_CLIENTLOG" 2>/dev/null | tail -1
}

_s23_player_field() { # <stat行> <字段名> → 数字（无则空）
  printf '%s' "${1:-}" | grep -oE "${2:-}=[0-9]+" | tail -1 | grep -oE '[0-9]+'
}

scenario_setup() {
  run_op neutral || return 1
  reach_streaming || return 1
  return 0
}

scenario_ops() {
  # 软等待 PA 第一条周期行（建流后 ≈2.56s 出现；setup 的 wait_stream_settled 已过去）
  local d=$((SECONDS + 30))
  while [ "$SECONDS" -lt "$d" ]; do
    [ -n "$(_s23_vd_frames_now)" ] && break
    sleep 2
  done
  log_step "音频采集状态：$(_s23_vd_audio_state)（sent 末条：$(_s23_vd_sent_line)）"

  S23_T0=$SECONDS
  S23_FRAMES_T0=$(_s23_vd_frames_now)
  S23_PLAYER_T0=$(_s23_player_line)
  log_step "熄灭前采样：frames=${S23_FRAMES_T0:-无} player=${S23_PLAYER_T0:-无}"

  run_op screen_off || { log_err "screen_off op 失败"; return 1; }
  S23_FRAMES_OFF_START=$(_s23_vd_frames_now)
  sleep 10
  S23_FRAMES_OFF_END=$(_s23_vd_frames_now)
  log_step "熄灭 10s 窗口：frames ${S23_FRAMES_OFF_START:-?} → ${S23_FRAMES_OFF_END:-?}（判据 #4 窗口；E8 已证链路不易断）"
  run_op screen_on || { log_err "screen_on op 失败"; return 1; }

  S23_T1=$SECONDS
  S23_FRAMES_T1=$(_s23_vd_frames_now)
  S23_PLAYER_T1=$(_s23_player_line)
  log_step "点亮后采样：frames=${S23_FRAMES_T1:-无} player=${S23_PLAYER_T1:-无}"

  # 判据 #2 的 200ms 突发子条：无既有 op 可构造（既有 id 里没有流量/抖动注入），
  # 如实记录为「本轮回退到无突发子条」，不假造也不静默略过。
  log_step "200ms 突发注入：既有 op 无流量注入能力，本轮回退为「无突发」子条（starved==0 / underrun 不增长）"
  return 0
}

scenario_asserts() {
  local rc=0

  # ── 判据 #5 前置：会话在音频交互全程存活（判据 #5 的基础）──
  # wait_state_eq 自带 ok/bad 记账 → 用裸 `|| rc=1`（禁再包一层 ok/bad 以免重复记账）
  wait_state_eq car   STREAMING 90 || rc=1
  wait_state_eq phone STREAMING 90 || rc=1

  # ── 判据 #1：vd-server 侧音频帧计数 > 0 且窗口内增长 ──
  local f0 f1 w
  f0=$(_s23_num "${S23_FRAMES_T0:-}"); f1=$(_s23_num "${S23_FRAMES_T1:-}")
  if [ -z "$f0" ] && [ -z "$f1" ]; then
    bad "判据1 未取到 vd-server 音频周期行（Audio: sent frames=）——PA 未合并 / 9640 无接收端 / 采集未启动（audio=$(_s23_vd_audio_state)）"
    rc=1
  elif [ -z "$f1" ] || [ "$f1" -le 0 ]; then
    bad "判据1 音频帧计数未增长到 >0（f0=${f0:-无} f1=${f1:-无}）"
    rc=1
  else
    ok "判据1 vd-server 音频帧计数 >0（frames=$f1）"
    if [ -n "$f0" ] && [ "$f1" -gt "$f0" ]; then
      ok "判据1 窗口内帧计数增长（$f0 → $f1，Δ=$((f1 - f0))）"
    else
      bad "判据1 窗口内帧计数未增长（f0=${f0:-无} → f1=$f1）"
      rc=1
    fi
    # 速率下限（保守 20 帧/s；名义 46.875 —— 日志每 120 帧一行，tail 值最多滞后 ≈2.56s）。
    # 阈值取名义值的 ~43%，只抓「接近停摆」型回归，不做紧配合（避免真机抖动假红）。
    w=$(( ${S23_T1:-0} - ${S23_T0:-0} )); [ "$w" -ge 5 ] || w=5
    local fps10=$(( (f1 - ${f0:-0}) * 10 / w ))
    if [ "$fps10" -ge 200 ]; then
      ok "判据1 隐含速率 ≥20 帧/s（$((fps10 / 10)).$((fps10 % 10)) 帧/s，窗口 ${w}s）"
    else
      bad "判据1 隐含速率偏低（Δ=$((f1 - ${f0:-0})) / 窗口 ${w}s → $((fps10 / 10)).$((fps10 % 10)) 帧/s < 保守下限 20）"
      rc=1
    fi
  fi

  # ── 判据 #4：screen_off 后 10s 内音频帧仍增长（覆盖 R7）──
  local o0 o1
  o0=$(_s23_num "${S23_FRAMES_OFF_START:-}"); o1=$(_s23_num "${S23_FRAMES_OFF_END:-}")
  if [ -n "$o0" ] && [ -n "$o1" ] && [ "$o1" -gt "$o0" ]; then
    ok "判据4 熄屏后 10s 内音频帧仍增长（$o0 → $o1，覆盖 R7）"
  else
    bad "判据4 熄屏后音频帧未增长（${o0:-无} → ${o1:-无}）——R7 熄屏断流回归信号"
    rc=1
  fi

  # ── 判据 #2：app-server 侧 decoded > 0、无突发 starved == 0、underrun 不增长 ──
  local p0 p1 dec star un0 un1
  p0="${S23_PLAYER_T0:-}"; p1="${S23_PLAYER_T1:-}"
  if [ -z "$p1" ]; then
    bad "判据2 client.log 无 [AudioPlayer] 统计行——PB 未合并 / CarLog 中继未开（见脚本头日志契约）"
    rc=1
  else
    dec=$(_s23_player_field "$p1" decoded); dec=$(_s23_num "$dec")
    star=$(_s23_player_field "$p1" starved); star=$(_s23_num "$star")
    un1=$(_s23_player_field "$p1" underrun); un1=$(_s23_num "$un1")
    if [ -n "$dec" ] && [ "$dec" -gt 0 ]; then
      ok "判据2 播放端 decoded >0（decoded=$dec）"
    else
      bad "判据2 播放端 decoded 非 >0（decoded=${dec:-无}）——解码器未出帧"
      rc=1
    fi
    # 判据 #2 的 starved 口径（**显式声明，不是 delta**）：`starved` 是 PB 会话内累计值
    # （AudioPlayer.kt:303 `starvedFrames.incrementAndGet`，仅 start() 时清零），**不回零**。
    # 本判据取绝对值 ⇒ **要求会话全程零欠载**（含起播期），非只看窗口增量。
    # 依据：§4.5 判据 #2 原文即「无突发时 starved == 0」，且 §2.5 A1/A2/A3 无突发组
    # 实测 starved 均为 0（起播期噪声只出现在 `underrun`=3，非 starved）。故此处与
    # `underrun` 的 delta 口径**有意不对称**：`underrun` 容忍起播期恒定噪声，`starved`
    # 不容忍。若真机首跑因起播期 starved 假红（FLAKY 复跑仍红），按实测校准为 delta
    # 口径——**校准须留证据**，不得为过而静默放宽。
    if [ "$star" = "0" ]; then
      ok "判据2 会话全程 starved == 0（无突发且无起播欠载；starved=$star）"
    else
      bad "判据2 会话累计 starved ≠ 0（starved=${star:-无}）——播放端欠载（含起播期，见上口径说明）"
      rc=1
    fi
    if [ -n "$p0" ]; then
      un0=$(_s23_player_field "$p0" underrun); un0=$(_s23_num "$un0")
      # 严格口径（宁缺勿假绿）：`underrun` 不得随时间增长。§2.5 A3 组的「起播期噪声」
      # （起播后恒定 3、不再增长）发生在本窗口之前（setup 的 wait_stream_settled 已静默），
      # 故窗口内应为平。若真机首跑因起播噪声假红（FLAKY 复跑仍红），按实测校准阈值——
      # 校准须留证据，不得为过而放宽成「只查 >0」。
      if [ -n "$un0" ] && [ -n "$un1" ] && [ "$un1" -le "$un0" ]; then
        ok "判据2 underrun 不随时间增长（$un0 → $un1）"
      else
        bad "判据2 underrun 随时间增长（${un0:-无} → ${un1:-无}）"
        rc=1
      fi
    else
      log_step "判据2 underrun 增长判据：熄灭前无播放端统计行（PB 未出/未连接），跳过对照（非通过，见上）"
    fi
    # 「组合判据」——覆盖**传输 + 解码链路失败**：vd 帧增长 **且** 播放端已解码，两条
    # 同时成立才 ok。它兜的是 9640 未绑 / 无接收端 / 连接断 / 解码器未起（decoded==0）；
    # **它兜不住源侧只出静音帧**（漏 `startRecording`）——那种情况 decoded 照样 >0，
    # 需人工听觉回签（详见脚本头）。**不宣称防静音假绿。**
    if [ -n "$f1" ] && [ -n "$dec" ] && [ "$dec" -gt 0 ]; then
      ok "组合判据 传输+解码链路成立：vd 帧增长 且 播放端 decoded>0（#1 ∧ #2）"
    else
      bad "组合判据 传输/解码链路未成立：vd 帧=${f1:-无} / decoded=${dec:-无}（帧发出但未达/未解码）"
      rc=1
    fi
  fi

  # ── 判据 #3：静音期补帧计数 > 0 且 starved == 0（F2 判据）──
  # 「starved == 0」的硬断言在判据 #2 已记账；此处补「补帧计数 > 0」——但它只出现在
  # 终局行 `Audio: capture stopped (frames=N, silence=M)`（会话结束时才打），asserts
  # 窗口内通常取不到 → 记为观测项。**不假通过**：终局行的 silence 计数由 resolve 后
  # 的回签核对（issue #79），本场景不宣称已覆盖该半边。
  local sil
  sil=$(_s23_vd_silence_now); sil=$(_s23_num "$sil")
  if [ -n "$sil" ]; then
    if [ "$sil" -gt 0 ]; then
      ok "判据3 静音补帧计数 >0（silence=$sil，取自终局行）"
    else
      bad "判据3 静音补帧计数为 0（silence=0）——静音期未补帧（F2 判据未达）"
      rc=1
    fi
  else
    log_step "判据3 静音补帧计数：终局行 Audio: capture stopped (frames=, silence=) 尚未产生（会话未结束），记为观测项；「starved == 0」半边已在判据 #2 硬断言"
  fi

  # ── 判据 #5：音频降级时视频会话仍存活 ──
  # 音频路径状态（started/disabled/absent）**只作诊断，不产 ok**：无论哪种状态视频
  # 会话都可能存活，把它记成 ok 是恒真记账（两分支恒 ok，`ast=absent` 也被误称「降级
  # 判据成立」）。本场景也没有构造音频降级的 op（既有 op 无音频降级注入），故降级路径
  # **本轮未被构造**，如实记为观测项。判据 #5 的可自动断言半边 = 音频链路异常时视频轨
  # 不被拖垮：下面直接核 9638 ESTABLISHED（音频与视频同会话，音频在跑就是同场检验）。
  local ast
  ast=$(_s23_vd_audio_state)
  log_step "判据5 音频状态诊断：$ast（started=采集中；disabled=9640 无接收端/未绑；absent=无音频行）；降级路径无 op 可构造，本轮未触发"
  if [ "$(port_established "$PHONE_SERIAL" 9638)" = "Y" ]; then
    ok "判据5 9638 视频轨 ESTABLISHED（音频未拖垮视频；音频状态=$ast，见上诊断）"
  else
    bad "判据5 9638 无 ESTABLISHED——视频轨受影响（音频状态=$ast）"
    rc=1
  fi

  # ── 末段健康不变量（ANR/崩溃 + 强制 kill）──
  assert_no_regression_markers || rc=1

  return $rc
}

scenario_restore() {
  run_op neutral
}
