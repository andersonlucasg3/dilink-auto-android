#!/bin/bash
# E2E 判据单测（issue #46 / #47 回归钉子）
#
# 覆盖两处判据缺陷，不需要真机：
#   #46 wait_stream_alive 按「当前会话分段」取帧计数，不把上一会话停机汇总当基线；
#   #47 PNG 完整性（IEND 尾）与解码失败（rc=2）不再被当成「纯黑（rc=1）」。
#
# 跑法：bash scripts/lib/test-asserts.sh
# 挂进 run-mock-test.sh 的层①语法检查之后（见 run-mock-test.sh step 1/4 末尾）。

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/scripts/lib/logging.sh"
. "$ROOT/scripts/lib/asserts.sh"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0
ck() { # <got> <want> <desc>
  if [ "$1" = "$2" ]; then echo "  ok: $3"; pass=$((pass + 1));
  else echo "  FAIL: $3 — got '$1' want '$2'"; fail=$((fail + 1)); fi
}
ckrc() { # <rc> <want> <desc>
  if [ "$1" = "$2" ]; then echo "  ok: $3（rc=$1）"; pass=$((pass + 1));
  else echo "  FAIL: $3 — rc=$1 want $2"; fail=$((fail + 1)); fi
}

# ── #46：vdlog_frames_current 的分段语义 ────────────────────────────────
# 单会话：只有一段 Video on，帧 720 → 840。
cat > "$TMP/single.txt" <<'EOF'
[Pipeline] Video on :9638
[Pipeline] Pipeline: 720 frames 2Mbps keys=12 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=1 glErr=0
[Pipeline] Pipeline: 840 frames 2Mbps keys=14 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=2 glErr=0
EOF
ck "$(vdlog_frames_current "$TMP/single.txt")" "840" "单会话取最后一帧计数 840"

# 旧会话 1320 → 停机汇总 120（tex=0）→ Pipeline exited → 新会话 Video on + 120
# （run-20261103-184040 首轮 S11 的真实日志形态，截断处即误判 120→120 的现场）
cat > "$TMP/rollover.txt" <<'EOF'
[Pipeline] Video on :9638
[Pipeline] Pipeline: 1320 frames 2Mbps keys=26 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=1ms ts=3 glErr=0
[Pipeline] Pipeline: 120 frames 2Mbps keys=2 tex=0 cb=0 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=0 glErr=0
[Pipeline] Pipeline exited: 1320 frames
[Pipeline] Pipeline exited
[Pipeline] Starting: VD=1080x2152 @400dpi, encode=542x1080, phoneHost=127.0.0.1, fps=20, bitrate=2M, carHost=ANY
[Pipeline] Video on :9638
[Pipeline] Pipeline: 120 frames 2Mbps keys=2 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=4 glErr=0
EOF
ck "$(vdlog_frames_current "$TMP/rollover.txt")" "120" "旧会话 1320→停机汇总 120→exited→新会话 120：只取新会话（不是 1320/120 汇总）"
# 分段起点必须落在最后一条 Video on（新会话），而不是上一会话的汇总行之前
_last_video_on=$(grep -nF 'Video on :9638' "$TMP/rollover.txt" | tail -1 | cut -d: -f1)
ck "$(_vdlog_session_start "$TMP/rollover.txt")" "$_last_video_on" "分段起点 = 最后一条 Video on 行号"

# 只有停机汇总 + Pipeline exited（新引擎还没建流）：当前会话没有帧行 → 空
cat > "$TMP/stopped.txt" <<'EOF'
[Pipeline] Video on :9638
[Pipeline] Pipeline: 120 frames 2Mbps keys=2 tex=0 cb=0 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=0 glErr=0
[Pipeline] Pipeline exited
EOF
ck "$(vdlog_frames_current "$TMP/stopped.txt")" "" "只有停机汇总：当前会话无帧行（不返回 120）"

# 新会话已建流但还没产首帧：空
cat > "$TMP/born.txt" <<'EOF'
[Pipeline] Pipeline exited
[Pipeline] Video on :9638
EOF
ck "$(vdlog_frames_current "$TMP/born.txt")" "" "新会话刚建流：无帧行返回空"

# ── #47：PNG 完整性 / 解码失败 / 纯黑 三态 ──────────────────────────────
python - "$TMP" <<'PY'
import os, sys
from PIL import Image
d = sys.argv[1]
im = Image.new("RGB", (64, 64))
px = im.load()
for y in range(64):
    for x in range(64):
        px[x, y] = ((x * 4) % 256, (y * 4) % 256, ((x + y) * 2) % 256)
im.save(os.path.join(d, "ok.png"))
Image.new("RGB", (64, 64), (0, 0, 0)).save(os.path.join(d, "black.png"))
raw = open(os.path.join(d, "ok.png"), "rb").read()
# 保留魔数、砍掉尾部 40 字节：模拟网络 adb exec-out 传输截断（issue #47 冻结件同形态）
open(os.path.join(d, "trunc.png"), "wb").write(raw[:-40])
PY
_png_ok "$TMP/ok.png";            ckrc "$?" "0" "完整 PNG 通过完整性校验"
_png_ok "$TMP/trunc.png";         ckrc "$?" "1" "截断 PNG（有魔数无 IEND）被判无效"
_png_ok "$TMP/black.png";         ckrc "$?" "0" "纯黑 PNG 仍是完整文件（判黑由方差决定）"

out=$(png_variance "$TMP/ok.png" 1.0); ckrc "$?" "0" "完整非纯黑 PNG → rc=0（$out）"
out=$(png_variance "$TMP/black.png" 1.0); ckrc "$?" "1" "纯黑 PNG → rc=1（纯黑，非环境错）"
out=$(png_variance "$TMP/trunc.png" 1.0) 2>/dev/null; ckrc "$?" "2" "截断 PNG 解码失败 → rc=2（环境错，不是纯黑）"

# ── #46：wait_stream_alive 的分段等待行为（注入 vdlog_snapshot 回放 fixture）──
vdlog_snapshot() { # 覆盖断言库的落盘函数：按调用次序回放 fixture（可注入假日志）
  _seq_n=$((_seq_n + 1))
  local out=${1:-$E2E_VDLOG}
  local src="$_seq_dir/$_seq_n.txt"
  [ -f "$src" ] || src="$_seq_dir/last.txt"
  cp "$src" "$out" 2>/dev/null || return 1
}
_seq_n=0
_seq_dir=""

seq_case() { # <seq_dir> <win> <ff> → 设好回放目录并跑 wait_stream_alive
  _seq_dir=$1
  _seq_n=0
  E2E_VDLOG="$_seq_dir/out.txt"
  wait_stream_alive "$2" "$3"
}

# 用例①：正常增长 720→840（单会话），窗口内应过
cat > "$TMP/seq-720.txt" <<'EOF'
[Pipeline] Video on :9638
[Pipeline] Pipeline: 720 frames 2Mbps keys=12 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=1 glErr=0
EOF
cat > "$TMP/seq-840.txt" <<'EOF'
[Pipeline] Video on :9638
[Pipeline] Pipeline: 720 frames 2Mbps keys=12 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=1 glErr=0
[Pipeline] Pipeline: 840 frames 2Mbps keys=14 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=2 glErr=0
EOF
mkdir -p "$TMP/seq-grow"
cp "$TMP/seq-720.txt" "$TMP/seq-grow/1.txt"
cp "$TMP/seq-840.txt" "$TMP/seq-grow/2.txt"
cp "$TMP/seq-grow/2.txt" "$TMP/seq-grow/last.txt"
out=$(seq_case "$TMP/seq-grow" 4 4); ckrc "$?" "0" "正常增长过判据（${out:-空}）"
case "$out" in *"frames 720→840"*) ck 1 1 "输出含 frames 720→840" ;; *) ck "$out" "frames 720→840" "输出含 frames 720→840" ;; esac

# 用例②：旧会话→停机汇总→exited→新会话首帧→增长（S11/S13 首轮误判形态），应过
mkdir -p "$TMP/seq-rollover"
cp "$TMP/stopped.txt" "$TMP/seq-rollover/1.txt"
cp "$TMP/rollover.txt" "$TMP/seq-rollover/2.txt"
sed 's/Pipeline: 120 frames 2Mbps keys=2 tex=30/Pipeline: 240 frames 2Mbps keys=4 tex=30/' \
  "$TMP/rollover.txt" > "$TMP/seq-rollover/3.txt"
cp "$TMP/seq-rollover/3.txt" "$TMP/seq-rollover/last.txt"
out=$(seq_case "$TMP/seq-rollover" 4 6); ckrc "$?" "0" "重部署窗口：等新会话首帧后增长过判据（${out:-空}）"
case "$out" in *"frames 120→240"*) ck 1 1 "输出含 frames 120→240（基线来自新会话）" ;;
  *) ck "$out" "frames 120→240" "输出含 frames 120→240（基线来自新会话）" ;; esac

# 用例③：帧计数停滞（720→720）应判败，且文案是「静止」不是「未出帧」
mkdir -p "$TMP/seq-still"
cp "$TMP/seq-720.txt" "$TMP/seq-still/1.txt"
cp "$TMP/seq-720.txt" "$TMP/seq-still/2.txt"
cp "$TMP/seq-still/2.txt" "$TMP/seq-still/last.txt"
out=$(seq_case "$TMP/seq-still" 4 4); ckrc "$?" "1" "帧计数停滞判败（${out:-空}）"
case "$out" in *"视频流静止"*) ck 1 1 "失败文案=视频流静止" ;; *) ck "$out" "视频流静止" "失败文案=视频流静止" ;; esac

# 用例④：旧会话停机汇总 120 + exited + 新会话刚建流但未产首帧（S11 首轮的真实形态：
# 24s 内新引擎没追上帧行）应判败，文案是「未出帧」而不是「120→120 静止」
mkdir -p "$TMP/seq-noframe"
cat > "$TMP/seq-noframe/1.txt" <<'EOF'
[Pipeline] Video on :9638
[Pipeline] Pipeline: 1320 frames 2Mbps keys=26 tex=30 cb=30 swapFails=0 q=0 sendDrops=0 maxW=1ms ts=3 glErr=0
[Pipeline] Pipeline: 120 frames 2Mbps keys=2 tex=0 cb=0 swapFails=0 q=0 sendDrops=0 maxW=0ms ts=0 glErr=0
[Pipeline] Pipeline exited: 1320 frames
[Pipeline] Pipeline exited
[Pipeline] Video on :9638
EOF
cp "$TMP/seq-noframe/1.txt" "$TMP/seq-noframe/last.txt"
out=$(seq_case "$TMP/seq-noframe" 4 6); ckrc "$?" "1" "新会话无帧行判败（${out:-空}）"
case "$out" in *"视频流未出帧"*) ck 1 1 "失败文案=视频流未出帧（不是把停机汇总当静止）" ;;
  *) ck "$out" "视频流未出帧" "失败文案=视频流未出帧（不是把停机汇总当静止）" ;; esac

echo
if [ "$fail" = "0" ]; then
  echo "E2E 判据单测全过（$pass）"
  exit 0
fi
echo "E2E 判据单测失败 $fail/$((pass + fail))"
exit 1
