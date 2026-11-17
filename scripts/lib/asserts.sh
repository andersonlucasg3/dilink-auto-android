#!/bin/bash
# DiLink-Auto 双手机真机 E2E — 断言原语库（只读层）
#
# 设计：.plan/e2e-harness-design.md §1.2 + .plan/e2e-observability.md（断言串逐字来源）。
# 层契约：除 freeze_scene（取证需要，唯一副作用 = 双端各发一次 KEYCODE_WAKEUP）外，
#         任何函数不得改变设备状态。退出码：0=通过 1=断言失败 2=用法/环境错误。
# 记账：ok()/bad() 累加 E2E_PASS/E2E_FAIL（与 e2e-reconnect-cleanup.sh 范式一致）。
# 前置：logging.sh → devices.sh 已 source；PHONE_SERIAL/CAR_SERIAL/PHONE_IP 已填充。
#
# CLI probe 模式（explore.py 采集复合态的入口，与交互式 wait_* 同源）：
#   PHONE_SERIAL=.. CAR_SERIAL=.. bash asserts.sh probe <what>
#   what ∈ state | car_state | phone_state | engine | vd | sentinel | engine_pid
#   state 输出一行：car=STREAMING phone=STREAMING engine=present vd=present sentinel=on
#
# 断言串校准提示：所有日志串来自 .plan/e2e-observability.md 的源码逐字提取；
# 真机未命中时输出「pattern not found — 按 .plan/e2e-observability.md §F 校准」。

set -uo pipefail

E2E_PASS=0
E2E_FAIL=0
E2E_VDLOG="${E2E_VDLOG:-${E2E_RUN_DIR:-.}/vdlog.snapshot}"
E2E_CLIENTLOG="${E2E_CLIENTLOG:-${E2E_RUN_DIR:-.}/clientlog.snapshot}"

ok()  { log_ok "$*";  E2E_PASS=$((E2E_PASS + 1)); }
bad() { log_err "$*"; E2E_FAIL=$((E2E_FAIL + 1)); }

# ── 基础计数/轮询（范式：e2e-reconnect-cleanup.sh :61/:73-82）────────────
countF() { # <file> <fixed-str> → 计数（固定串 -F：日志含 | 等元字符）
  local n
  n=$(grep -cF "$2" "$1" 2>/dev/null | head -1)
  echo "${n:-0}"
}
countE() { # <file> <regex> → 计数（扩展正则，仅用于 ANR 类已知无元字符冲突的模式）
  local n
  n=$(grep -cE "$2" "$1" 2>/dev/null | head -1)
  echo "${n:-0}"
}
wait_count() { # <file> <fixed-str> <target> <timeout_s>（SECONDS 真实预算，禁迭代次数）
  local f=$1 pat=$2 target=$3 t=${4:-60} n
  local deadline=$((SECONDS + t))
  while [ "$SECONDS" -lt "$deadline" ]; do
    n=$(countF "$f" "$pat")
    [ "$n" -ge "$target" ] && return 0
    sleep 0.5
  done
  return 1
}

# ── 引擎进程（恒在发送端 B；方括号技巧防 pgrep 自匹配，禁 pkill -f）──────
engine_pid() { dphone 'pgrep -f "[P]ipelineServer" | head -1' 2>/dev/null | tr -d '\r\n '; }
# 引擎进程数。**tr -d 不能带 '\n'**：pgrep 多行输出（双开）会被粘成一个数字，
# grep -c . 恒为 1 → assert_engine_single 永远发现不了双开（2026-10-16 S17-S22
# subagent 静态走查抓到）。先 tr -d '\r' 只去回车，再用 grep -c 数行数。
engine_pid_all() {
  local n
  n=$(dphone 'pgrep -f "[P]ipelineServer"' 2>/dev/null | tr -d '\r' | grep -c '[0-9]')
  case "${n:-}" in ''|*[!0-9]*) echo 0 ;; *) echo "$n" ;; esac
}
wait_engine_gone() { # [timeout_s=30]（优雅退出实测 6.1~6.3s；12s 硬预算；30s 包抖动）
  local deadline=$((SECONDS + ${1:-30}))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ -z "$(engine_pid)" ] && return 0
    sleep 0.5
  done
  return 1
}
wait_engine_present() { # [timeout_s=60]
  local deadline=$((SECONDS + ${1:-60}))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ -n "$(engine_pid)" ] && return 0
    sleep 0.5
  done
  return 1
}
assert_engine_single() { # 会话中引擎恰 1 个（>1 = 双开，S08 判据）
  # 采样韧性（2026-10-17 try2 S03 实证）：黑屏误报重握手风暴的重部署空窗（旧引擎
  # 已 kill、新引擎未起，~10s）里单次采样会读到 0——瞬时误报，冻结快照反证
  # （state-snapshot engine_pid=24543 + 双端 STREAMING）。策略：15s 内轮询，任一
  # 采样=1 即 PASS；>1 立即判负（双开不需要等）；全 0 才判「没起来」。
  local n=0 deadline=$((SECONDS + 15))
  while [ "$SECONDS" -lt "$deadline" ]; do
    n=$(engine_pid_all)
    if [ "$n" = "1" ]; then ok "引擎恰 1 个（pid=$(engine_pid)）"; return 0; fi
    [ "$n" -gt 1 ] && break
    sleep 2
  done
  bad "引擎进程数=$n（期望 1；0=没起来，>1=双开；已轮询 15s）"
  return 1
}

# ── 端口（LISTEN 是瞬态！会话中用 port_established，见 observability §A0）─
wait_port_listening() { # <side> <port> [timeout_s=60]（WAITING 态判据）
  local side=$1 port=$2 sh deadline=$((SECONDS + ${3:-60}))
  case $side in phone) sh=listening_on_phone ;; car) sh=listening_on_car ;; *) return 2 ;; esac
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$($sh "$port")" = "Y" ] && return 0
    sleep 0.5
  done
  return 1
}
wait_port_established() { # <side> <port> [timeout_s=30]（会话中流存活判据）
  local side=$1 port=$2 sh deadline=$((SECONDS + ${3:-30}))
  case $side in phone) sh=established_on_phone ;; car) sh=established_on_car ;; *) return 2 ;; esac
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$($sh "$port")" = "Y" ] && return 0
    sleep 0.5
  done
  return 1
}
wait_port_free() { # <side> <port> [timeout_s=30]（收尾判据：LISTEN 与 ESTABLISHED 都没）
  local side=$1 port=$2 sh deadline=$((SECONDS + ${3:-30}))
  case $side in phone) sh=port_free_phone ;; car) sh=port_free_car ;; *) return 2 ;; esac
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$($sh "$port")" = "Y" ] && return 0
    sleep 0.5
  done
  return 1
}
wait_no_established() { # <side> <port> [timeout_s=30]（负向：ESTABLISHED 消失=数据面拆干净）
  # 用于 Eject/杀进程后的收尾：视频/触摸口的会话连接必须没了，但**不**要求
  # 9637 释放——client 活着时会回 WAITING 重新 LISTEN（真机行为，2026-10-16
  # 修：S03/S19 原等 port_free 9637 与"phone 回 WAITING"自相矛盾，永真机不过）。
  local side=$1 port=$2 sh deadline=$((SECONDS + ${3:-30}))
  case $side in phone) sh=established_on_phone ;; car) sh=established_on_car ;; *) return 2 ;; esac
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$($sh "$port")" != "Y" ] && return 0
    sleep 0.5
  done
  return 1
}
listening_on_phone()    { listening_on "$PHONE_SERIAL" "$1"; }
listening_on_car()      { listening_on "$CAR_SERIAL" "$1"; }
established_on_phone()  { port_established "$PHONE_SERIAL" "$1"; }
established_on_car()    { port_established "$CAR_SERIAL" "$1"; }
port_free_phone()       { [ "$(listening_on "$PHONE_SERIAL" "$1")" != "Y" ] && [ "$(port_established "$PHONE_SERIAL" "$1")" != "Y" ] && echo Y || echo N; }
port_free_car()         { [ "$(listening_on "$CAR_SERIAL" "$1")" != "Y" ] && [ "$(port_established "$CAR_SERIAL" "$1")" != "Y" ] && echo Y || echo N; }

# ── 状态面（双端状态都从 B 的 client.log 读：A 的日志经 TCP 中继进去，tag=CarLog） ──
# B · app-client（observability B-2）：
#   WAITING  = "Listening for car connection on port 9637..."
#   CONNECTED= "Car connected (control, session #N), waiting for handshake"
#   STREAMING= "VD server lifecycle connected (displayId=N)"
# A · app-server 中继行（observability B-3）：
#   CONNECTING="Control connected to ..."  CONNECTED="All connections ready — connected"
#   STREAMING ="Video ready — streaming"    IDLE      ="handleDisconnect"/"User disconnect"
clientlog_snapshot() { # [out_file] → 落盘 client.log 尾部快照（轮询/扫描共用，免反复拉）
  local out=${1:-$E2E_CLIENTLOG}
  dphone "tail -c 300000 /sdcard/DiLinkAuto/client.log" 2>/dev/null | tr -d '\r' > "$out"
  [ -s "$out" ] || { log_step "client.log 快照为空（B 的 client 未起过/FileLog 未开？）"; return 1; }
  return 0
}
pull_clientlog() { # <out_file> 正式归档件
  pull_phone /sdcard/DiLinkAuto/client.log "$1" > /dev/null 2>&1
}
_last_state_line() { # <file> <pattern>... → 最后命中的行号（0=未命中）
  local f=$1; shift
  grep -nF -e "$1" "$f" 2>/dev/null | tail -1 | cut -d: -f1
}
phone_state_now() { # → IDLE|WAITING|CONNECTED|STREAMING|UNKNOWN
  # client.log 是持久文件：client 被 force-stop 后日志不再增长，末条状态行会
  # 一直停在旧值——所以先用进程存亡判 IDLE（方括号技巧防 pgrep 匹配到
  # adb shell 自己的 cmdline；同 engine_pid 的 [P] 技巧）。
  if [ -z "$(dphone 'pgrep -f "[c]om.dilinkauto.client"' 2>/dev/null | tr -d '\r\n ')" ]; then
    echo IDLE; return 0
  fi
  local f ST; f=$(mktemp)
  clientlog_snapshot "$f" > /dev/null 2>&1
  local s=0 n
  n=$(_last_state_line "$f" 'VD server lifecycle connected (displayId=');   [ "${n:-0}" -gt "$s" ] && { s=$n; ST=STREAMING; }
  n=$(_last_state_line "$f" 'Car connected (control, session #');            [ "${n:-0}" -gt "$s" ] && { s=$n; ST=CONNECTED; }
  n=$(_last_state_line "$f" 'Listening for car connection on port 9637');    [ "${n:-0}" -gt "$s" ] && { s=$n; ST=WAITING; }
  rm -f "$f"
  [ "$s" -eq 0 ] && ST=UNKNOWN
  echo "${ST:-UNKNOWN}"
}
car_state_now() { # → IDLE|CONNECTING|CONNECTED|STREAMING|UNKNOWN
  # 可注入接缝（issue #31 回归钉子）：测试用 CAR_STATE_SAMPLER 注入假采样器，
  # 绕过真实 adb 采样，单测 car_state_now 的状态判定边界。生产路径留空走原逻辑。
  if [ -n "${CAR_STATE_SAMPLER:-}" ]; then "$CAR_STATE_SAMPLER"; return $?; fi
  # A 的日志经 TCP 中继进 B 的 client.log（tag=CarLog）；server 进程不在 → IDLE。
  if [ -z "$(dcar 'pgrep -f "[c]om.dilinkauto.server"' 2>/dev/null | tr -d '\r\n ')" ]; then
    echo IDLE; return 0
  fi
  # 【2026-10-16 P1 预判新增 → issue #31 修正】B 端 client 进程不在 = 日志源已死
  # （client.log 不再增长，最后状态行是死前残留）且会话的一端已消失——既非「会话
  # 已收敛到 IDLE」也非任何已知态。返回 UNKNOWN，让调用方（S21 ops 循环、
  # wait_state_eq）继续轮询而非早退判 FAIL。S09 kill_client 后改为期望 car 回
  # UNKNOWN（s09:39）；S11 client 同死子支路放宽接受 car=UNKNOWN（s11:57）。
  # IDLE 只由「双端进程在 + 明确断开/空闲状态行」定义（下方 :194-200 的
  # handleDisconnect / User disconnect / Car disconnected (control)）。
  # A 端 server 缺失仍走上方 :179 返回 IDLE（中立态 / S10 kill_server 依赖此路；
  # 改 :179 会让 _common.sh neutral_verified 在中立态 FAIL——全套 setup 崩）。
  if [ -z "$(dphone 'pgrep -f "[c]om.dilinkauto.client"' 2>/dev/null | tr -d '\r\n ')" ]; then
    echo UNKNOWN; return 0
  fi
  local f ST; f=$(mktemp)
  clientlog_snapshot "$f" > /dev/null 2>&1
  local s=0 n
  n=$(_last_state_line "$f" 'Video ready — streaming');            [ "${n:-0}" -gt "$s" ] && { s=$n; ST=STREAMING; }
  n=$(_last_state_line "$f" 'All connections ready — connected');  [ "${n:-0}" -gt "$s" ] && { s=$n; ST=CONNECTED; }
  n=$(_last_state_line "$f" 'Control connected to');               [ "${n:-0}" -gt "$s" ] && { s=$n; ST=CONNECTING; }
  n=$(_last_state_line "$f" 'handleDisconnect');                   [ "${n:-0}" -gt "$s" ] && { s=$n; ST=IDLE; }
  n=$(_last_state_line "$f" 'User disconnect — idle');             [ "${n:-0}" -gt "$s" ] && { s=$n; ST=IDLE; }
  # 【2026-10-16 P0 S03 实证新增】B 端本地行「Car disconnected (control)」也判 IDLE：
  # A 端 Eject 后中继随连接断开，A 端自己的 handleDisconnect/「User disconnect — idle」
  # **永远到不了 B**（logWriter 出口即 9637）——而这行是 B 自己打的，不受中继影响。
  # 重连场景安全：重连后会有行号更大的新「Video ready」行覆盖判定。
  n=$(_last_state_line "$f" 'Car disconnected (control)');         [ "${n:-0}" -gt "$s" ] && { s=$n; ST=IDLE; }
  rm -f "$f"
  [ "$s" -eq 0 ] && ST=UNKNOWN
  echo "${ST:-UNKNOWN}"
}
wait_state_eq() { # <side> <STATE> [timeout_s=60]（软等待；超时转硬失败并打印实测末值）
  local side=$1 want=$2 deadline=$((SECONDS + ${3:-60})) cur
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$side" = "car" ]; then cur=$(car_state_now); else cur=$(phone_state_now); fi
    [ "$cur" = "$want" ] && { ok "$side 状态收敛 $want"; return 0; }
    sleep 1
  done
  bad "状态未收敛：$side 期望 $want，实测 $cur（pattern not found — 按 .plan/e2e-observability.md §F 校准）"
  return 1
}

# ── 画面非黑（PIL 灰度标准差；observability A2：>1.0 判非黑）────────────
_png_magic() { # <file> → 0=PNG 魔数有效（前 8 字节）
  # screencap 失败时会把错误文本写进 stdout（实测 56B："Failed to take screenshot.
  # Status: -2\nCapturing failed.\n"），文件"非空"骗过 [ -s ] → 必须校验魔数
  # （2026-10-16 try8 根因：PIL.UnidentifiedImageError）。
  [ -f "$1" ] && printf '\x89PNG\r\n\x1a\n' | cmp -s -n 8 - "$1"
}
_png_complete() { # <file> → 0=PNG 有完整 IEND 尾部（issue #47：截断 PNG 不是纯黑）
  # 网络 adb 的 exec-out 偶发丢尾部，文件仍带魔数却能骗过只查魔数的判据；PNG 以
  # IEND 块结尾（末 8 字节 49 45 4E 44 AE 42 60 82），缺失即视为传输截断。
  [ -s "$1" ] || return 1
  tail -c 8 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n' | grep -qi '^49454e44ae426082$'
}
_png_ok() { # <file> → 0=PNG 魔数有效且尾部完整（issue #47：截断 PNG 不能当纯黑）
  _png_magic "$1" && _png_complete "$1"
}

png_variance() { # <png> <min_sd> → stdout `stddev=X.XXX`；0=非纯黑 1=纯黑 2=解码失败
  python - "$1" "$2" <<'PY'
import sys
from PIL import Image, ImageStat
try:
    im = Image.open(sys.argv[1]).convert("L")
    sd = ImageStat.Stat(im).stddev[0]
except Exception as e:  # 解码/截断错误属于环境错，不能与纯黑混为一谈（issue #47）
    print(f"png 解码失败: {e}", file=sys.stderr)
    sys.exit(2)
print(f"stddev={sd:.3f}")
sys.exit(0 if sd > float(sys.argv[2]) else 1)
PY
}

screencap_variance() { # <side> <prefix> [min_sd=1.0] [sf_display_id] → 0=非纯黑 1=纯黑 2=环境错
  # sf_display_id 是 screencap -d 的 uint64 编码值（**不是** logical id；语义见
  # vd_phys_display_id）。缺省截默认屏。会话中引擎会熄物理屏（dumpsys display 实证
  # displayId=0 isActive=false，2026-10-16 try4 冻结件）——对 display 0 截图恒黑，
  # 验 VD 画面必须传 vd_phys_display_id（observability §C-4）。
  # stdout 契约：恰一行 `stddev=X.XXX`（s15/s16 用 $( ) 捕获解析）；诊断一律走 stderr。
  # 证据策略：判败且 PNG 有效 → 保留 <prefix>.png；PNG 无效 → 删除。
  local side=$1 prefix=$2 min_sd=${3:-1.0} disp=${4:-}
  local serial; case $side in phone) serial=$PHONE_SERIAL ;; car) serial=$CAR_SERIAL ;; *) return 2 ;; esac
  local png="$prefix.png"
  local TO=""; command -v timeout > /dev/null 2>&1 && TO="timeout 15"
  local dflag=""; [ -n "$disp" ] && dflag="-d $disp"
  local attempt
  for attempt in 1 2; do  # 传输截断重试一次（issue #47）
    $TO adb -s "$serial" exec-out screencap $dflag -p > "$png" 2>/dev/null
    _png_ok "$png" && break
    rm -f "$png"
  done
  if [ ! -f "$png" ] || ! _png_ok "$png"; then
    local sz=""; [ -f "$png" ] && sz=$(wc -c < "$png" 2>/dev/null | tr -d ' ')
    echo "screencap 未产出完整 PNG（side=$side${dflag:+ $dflag}，${sz:-0}B，已尝试 $attempt 次）" >&2
    rm -f "$png"; return 2
  fi
  png_variance "$png" "$min_sd"
  local rc=$?
  if [ "$rc" = "0" ]; then
    rm -f "$png"
  else
    echo "画面非黑判据未过：rc=$rc side=$side${dflag:+ $dflag} min_sd=$min_sd；PNG 留证：$png" >&2
  fi
  return "$rc"
}
# VD 截屏（会话中物理屏被引擎熄掉，-d 0 恒黑；须 -d <SF DisplayId 大数>，observability §C-4）
screencap_vd() { # <label> <outdir> [min_sd=1.0] → stdout 打印 jpg 路径；0=有效截图
  local id; id=$(vd_phys_display_id)
  [ -n "$id" ] || { log_step "VD SF DisplayId 未知，跳过 VD 截屏"; return 2; }
  # 拆开写：local 一条语句内的多个赋值先全部展开再赋值，$outdir 会 unbound（set -u）
  local outdir=${2:?outdir}
  local png jpg
  outdir=$2
  png="$outdir/vdshot-$1.png"
  jpg="$outdir/vdshot-$1.jpg"
  mkdir -p "$outdir"
  local TO=""; command -v timeout > /dev/null 2>&1 && TO="timeout 15"
  $TO adb -s "$PHONE_SERIAL" exec-out screencap -d "$id" -p > "$png" 2>/dev/null
  _png_ok "$png" || { rm -f "$png"; log_err "VD 截屏未产出有效 PNG（-d $id，魔数/完整性校验未过，可能传输截断）；跳过"; return 2; }
  python - "$png" "$jpg" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1])
w, h = im.size
im.resize((max(1, w // 3), max(1, h // 3))).save(sys.argv[2], "JPEG", quality=40)
PY
  rm -f "$png"
  echo "$jpg"
}

# ── logcat ──────────────────────────────────────────────────────────────
logcat_dump() { # <side> <out_file>（-d 不阻塞不清缓冲）
  local serial; case $1 in phone) serial=$PHONE_SERIAL ;; car) serial=$CAR_SERIAL ;; *) return 2 ;; esac
  adb -s "$serial" logcat -d > "$2" 2>/dev/null
}
logcat_clear() { # <side>（轮次要日志面清零，防跨轮假阳性）
  local serial; case $1 in phone) serial=$PHONE_SERIAL ;; car) serial=$CAR_SERIAL ;; *) return 2 ;; esac
  adb -s "$serial" logcat -c > /dev/null 2>&1
}
logcat_scan() { # <side> <pat> [--expect-zero|--min N]（固定串；扫描永远基于留证文件）
  local side=$1 pat=$2; shift 2
  local mode=expect-zero want=1
  while [ $# -gt 0 ]; do case "$1" in
    --expect-zero) mode=expect-zero ;;
    --min) mode=min; want=${2:?}; shift ;;
    *) return 2 ;; esac; shift; done
  local f="${E2E_RUN_DIR:-.}/scan-$side-$(date +%H%M%S).log"
  logcat_dump "$side" "$f" || return 2
  local c; c=$(countF "$f" "$pat")
  case $mode in
    expect-zero) [ "$c" -eq 0 ] && { ok "$side 无「$pat」"; return 0; } ;;
    min)         [ "$c" -ge "$want" ] && { ok "$side「$pat」×$c ≥ $want"; return 0; } ;;
  esac
  bad "$side「$pat」×$c 不符（mode=$mode want=$want）"
  return 1
}
assert_no_anr() { # <logcat_file>...（双端 0 命中；硬断言禁重试）
  local f n total=0
  for f in "$@"; do
    # 只把自家进程的 ANR/崩溃算作失败：B 上装的第三方 app 的 ANR 是环境噪声
    # （2026-10-17 S05 实测：hello.litiaotiao.app 的 Input dispatching timeout
    # 被旧模式「ANR in」误判为产品失败，ledger 凭空多 2 处 ✗）。
    # FATAL EXCEPTION 保持全量——任何进程的 FATAL 都值得追查，不静默放过。
    n=$(countE "$f" 'ANR in com\.dilinkauto\.|FATAL EXCEPTION|Force Closed: com\.dilinkauto\.')
    total=$((total + n))
  done
  if [ "$total" -eq 0 ]; then ok "无 ANR/崩溃（$# 个日志面）"; return 0; fi
  bad "发现 $total 处 ANR/崩溃：$*"
  return 1
}

# ── vd-server.log（恒开，[Pipeline] 前缀） ──────────────────────────────
vdlog_snapshot() { # [out_file]（轮询用快照，免反复 pull）
  local out=${1:-$E2E_VDLOG}
  dphone "tail -c 300000 /sdcard/DiLinkAuto/vd-server.log" 2>/dev/null | tr -d '\r' > "$out"
  [ -s "$out" ] || return 1
  return 0
}
pull_vdlog() { # <out_file> 正式归档件（MSYS_NO_PATHCONV=1，范式 :473-474）
  pull_phone /sdcard/DiLinkAuto/vd-server.log "$1" > /dev/null 2>&1
}
vdlog_scan() { # <pat> [--expect-zero|--min N] [--file F]（默认对 $E2E_VDLOG）
  local pat=$1; shift
  local mode=expect-zero want=1 f="$E2E_VDLOG"
  while [ $# -gt 0 ]; do case "$1" in
    --expect-zero) mode=expect-zero ;;
    --min) mode=min; want=${2:?}; shift ;;
    --file) f=${2:?}; shift ;;
    *) return 2 ;; esac; shift; done
  [ -s "$f" ] || vdlog_snapshot "$f" || { bad "vdlog 快照为空"; return 2; }
  local c; c=$(countF "$f" "$pat")
  case $mode in
    expect-zero) [ "$c" -eq 0 ] && { ok "vdlog 无「$pat」"; return 0; } ;;
    min)         [ "$c" -ge "$want" ] && { ok "vdlog「$pat」×$c ≥ $want"; return 0; } ;;
  esac
  bad "vdlog「$pat」×$c 不符（mode=$mode want=$want）"
  return 1
}
vdlog_wait() { # <pat> <target> <timeout_s>
  local pat=$1 target=$2 t=${3:-60} deadline=$((SECONDS + t)) n
  while [ "$SECONDS" -lt "$deadline" ]; do
    vdlog_snapshot > /dev/null 2>&1 || true
    n=$(countF "$E2E_VDLOG" "$pat")
    [ "$n" -ge "$target" ] && return 0
    sleep 1
  done
  return 1
}
# 强制 kill 三串（observability D 表：手机端等价串，中文「强制 kill」只存在于桌面端）
assert_no_force_kill() { # <file>...（出现任一即回归；硬断言禁重试）
  local f n total=0
  for f in "$@"; do
    n=$(countF "$f" 'Previous VD server did not exit in time — forced kill used'); total=$((total + n))
    n=$(countF "$f" 'VD server did not exit within 12000ms — force kill');       total=$((total + n))
    n=$(countF "$f" 'Watchdog: cleanup did not complete within 3000ms');         total=$((total + n))
  done
  if [ "$total" -eq 0 ]; then ok "无强制 kill（$# 个日志面）"; return 0; fi
  bad "发现 $total 次强制 kill：$*"
  return 1
}
# ── vdlog 会话分段（issue #46：帧计数必须按「当前会话」取值）──────────────
# vd-server.log 是追加文件，一个文件里可能叠着多段会话（引擎重启/重部署）。分段
# 起点取「最后一个 `Video on :9638`（新会话建流）」与「最后一个 `Pipeline exited`
# （上一会话结束）」行号中的较大者：
#   - 只认 Video on：引擎刚退出、新引擎还没建流时会退回上一会话，把它的停机汇总
#     行（`Pipeline: 120 frames ... tex=0`）当成基线 → 误判「视频流静止」（S11/S13
#     2026-11-03 首跑 120→120 的根因）；
#   - 只认 Pipeline exited：行号可能落在 Video on 之前（2026-11-03 快照实测汇总行
#     先于 exited 行），会放行上一会话的帧行。
_vdlog_session_start() { # <file> → 当前会话分段起点行号（0=无任何分段标记）
  local f=$1 l1 l2
  l1=$(grep -nF 'Video on :9638' "$f" 2>/dev/null | tail -1 | cut -d: -f1)
  l2=$(grep -nF 'Pipeline exited' "$f" 2>/dev/null | tail -1 | cut -d: -f1)
  l1=${l1:-0}; l2=${l2:-0}
  if [ "$l1" -ge "$l2" ]; then echo "$l1"; else echo "$l2"; fi
}
vdlog_frames_current() { # <file> → 当前会话最后一条 Pipeline: N frames 的 N（空=当前会话还没有帧行）
  local f=$1 from n
  from=$(_vdlog_session_start "$f")
  n=$(tail -n +"$((from + 1))" "$f" 2>/dev/null | grep -oE 'Pipeline: [0-9]+ frames' | tail -1 | grep -oE '[0-9]+')
  echo "${n:-}"
}
wait_stream_alive() { # [growth_window=20] [first_frame_budget=40]（当前会话帧计数在窗口内增长）
  # 分段语义（issue #46）：基线只取**当前会话**的帧行，不把上一会话的停机汇总当基线。
  #   1) 当前会话已有帧行 → 要求 growth_window（默认 20s）内继续增长（主判据）；
  #   2) 当前会话没有帧行（新会话刚建流 / 上一会话已 exited 但新引擎未建流）→ 先给
  #      first_frame_budget（默认 40s，覆盖黑屏误报重握手风暴 ~40s 的重部署窗口）等首帧；
  #      首帧出现后再给 growth_window 确认增长；
  #   3) 轮询中分段起点后移（引擎重启）→ 上一会话帧数作废，按新会话重置基线与预算。
  # 失败文案按「当前会话没有帧行」与「有帧行但不增长」分开，避免再把重部署期误读成
  # 「陈旧纹理黑屏回归」。
  local win=${1:-20} ff=${2:-40}
  local f=$E2E_VDLOG
  vdlog_snapshot > /dev/null 2>&1 || true
  [ -s "$f" ] || { bad "vdlog 快照为空（取不到引擎日志）"; return 2; }
  local seg0 seg a b deadline
  seg0=$(_vdlog_session_start "$f")
  a=$(vdlog_frames_current "$f")
  if [ -z "$a" ]; then deadline=$((SECONDS + ff)); else deadline=$((SECONDS + win)); fi
  while [ "$SECONDS" -lt "$deadline" ]; do
    sleep 2
    vdlog_snapshot > /dev/null 2>&1 || true
    seg=$(_vdlog_session_start "$f")
    if [ "$seg" != "$seg0" ]; then
      # 新分段（引擎已重启）：上一会话帧数作废，按新会话重算预算
      seg0=$seg; a=$(vdlog_frames_current "$f")
      if [ -z "$a" ]; then deadline=$((SECONDS + ff)); else deadline=$((SECONDS + win)); fi
      continue
    fi
    b=$(vdlog_frames_current "$f")
    if [ -z "$a" ]; then
      [ -n "$b" ] || continue                     # 仍在等首帧
      a=$b; deadline=$((SECONDS + win)); continue  # 首帧出现，转入增长窗口
    fi
    if [ -n "$b" ] && [ "$b" -gt "$a" ]; then
      ok "视频流存活（frames $a→$b）"; return 0
    fi
  done
  if [ -z "$a" ]; then
    bad "视频流未出帧（${ff}s 内当前会话无 Pipeline: N frames 行；引擎未起或未出帧）"
  else
    bad "视频流静止（frames $a→${b:-$a}，tex 停止增长 = 陈旧纹理黑屏回归信号）"
  fi
  return 1
}

# ── 哨兵 / VD 尺寸 ──────────────────────────────────────────────────────
assert_sentinel() { # <side> <want> [timeout_s=30]（2147483647=会话中；基线=收尾后）
  local side=$1 want=$2 sh cur deadline=$((SECONDS + ${3:-30}))
  case $side in phone) sh=dphone ;; car) sh=dcar ;; *) return 2 ;; esac
  while [ "$SECONDS" -lt "$deadline" ]; do
    cur=$($sh 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')
    [ "$cur" = "$want" ] && { ok "$side screen_off_timeout=$want"; return 0; }
    sleep 0.5
  done
  bad "$side screen_off_timeout=$cur（期望 $want）"
  return 1
}
vd_display_id() { # vdlog 末条 VD via DisplayManager[:Global]: id=N —— **logical id**
  # 用途：wm size -d / am start --display 等 DisplayManager 接口。screencap -d **不收**
  # logical id（实测报 "Failed to take screenshot. Status: -2"）；截 VD 用 vd_phys_display_id。
  vdlog_snapshot > /dev/null 2>&1 || true
  grep -oE 'VD via DisplayManager(Global)?: id=[0-9]+' "$E2E_VDLOG" 2>/dev/null | tail -1 | grep -oE '[0-9]+$'
}
vd_phys_display_id() { # VD 的 SF DisplayId 大数（screencap -d 要的 uint64 编码值）
  # 语义（AOSP Android 15 screencap.cpp DisplayId::fromValue + 2026-10-16 真机实证）：
  #   - `-d` 收编码值而非 logical id；物理屏 = (model<<8)|port；
  #     VD = (0xA0<<56)|低56位 的大数，**每次会话变化**（run4=…47279880629 / v5=…49289396546）；
  #   - `SurfaceFlinger --display-id` 只列物理屏（v1 探测失败根因），正确来源是本段：
  #       Virtual Display <id>
  #           name="DiLinkAutoVD"
  # 离线单测：run3/run4 真实 dump、空 dump、无 VD、双 VD（取最后一个）五例全过。
  local dump; dump=$(dphone 'dumpsys SurfaceFlinger' 2>/dev/null | tr -d '\r')
  [ -n "$dump" ] || return 1
  printf '%s\n' "$dump" | awk '
    /^Virtual Display [0-9]+/ { vid=$3; next }
    /name="DiLinkAutoVD"/ && vid != "" { last=vid; vid="" }
    END { if (last != "") print last }'
}
vd_handshake_size() { # vdlog Starting: VD=WxH → "W H"
  vdlog_snapshot > /dev/null 2>&1 || true
  grep -oE 'Starting: VD=[0-9]+x[0-9]+' "$E2E_VDLOG" 2>/dev/null | tail -1 | grep -oE '[0-9]+x[0-9]+' | tr 'x' ' '
}
assert_vd_size_eq() { # <W> <H> [timeout_s=15]（wm size -d <vdId> 的 Physical size）
  local want_w=$1 want_h=$2 deadline=$((SECONDS + ${3:-15})) id cur
  while [ "$SECONDS" -lt "$deadline" ]; do
    id=$(vd_display_id)
    if [ -n "$id" ]; then
      cur=$(dphone "wm size -d $id" 2>/dev/null | tr -d '\r' | grep -oE 'Physical size: [0-9]+x[0-9]+' | grep -oE '[0-9]+x[0-9]+')
      if [ "$cur" = "${want_w}x${want_h}" ]; then ok "VD 尺寸 $cur（display $id）"; return 0; fi
    fi
    sleep 1
  done
  bad "VD 尺寸=$cur（期望 ${want_w}x${want_h}，display ${id:-?}）"
  return 1
}

# ── 预期失败 / 失败证据 ─────────────────────────────────────────────────
expect_fail() { # <desc> <cmd...>（rc≠0→ok；rc=0→UNEXPECTED-PASS）
  local desc=$1; shift
  if "$@"; then
    bad "UNEXPECTED-PASS：$desc（按设计应失败却成功——预期过期或产品缺陷）"
    return 1
  fi
  ok "按预期失败：$desc"
  return 0
}
assert_failure_evidence() { # <file> <pat>（失败必须明确：有错误/引导证据；静默失败=产品缺陷）
  local c; c=$(countF "$1" "$2")
  if [ "$c" -ge 1 ]; then ok "失败证据在位（「$2」×$c）"; return 0; fi
  bad "静默失败：未见「$2」——不给明确错误/引导是产品缺陷"
  return 1
}

# ── 现场冻结（P5：必须在 restore 之前；唯一副作用 = 双端 WAKEUP）─────────
freeze_scene() { # <tag> [run_dir=${E2E_RUN_DIR}]
  # 注意：local 一条语句里的多个赋值是**先全部展开再赋值**——`local run=X dir="$run/..."`
  # 会在 set -u 下报 run unbound（2026-10-16 mock 回放实测）。必须拆开写。
  local tag=${1:?freeze_scene 需要 tag}
  local run=${2:-${E2E_RUN_DIR:-}}
  local dir i=2
  [ -n "$run" ] || run="debug-logs/e2e/local"
  dir="$run/freeze-$tag"
  while [ -d "$dir" ]; do dir="$run/freeze-$tag-$i"; i=$((i + 1)); done
  mkdir -p "$dir"
  dphone input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  dcar   input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  logcat_dump phone "$dir/logcat-phone.txt" || true
  logcat_dump car   "$dir/logcat-car.txt"   || true
  pull_vdlog    "$dir/vd-server.log"        || true
  pull_clientlog "$dir/client.log"          || true
  # 签名：screencap_<side> <label> <outdir>。2026-10-16 修：旧调用把完整路径塞进 label 位
  # （screencap_car "$dir/shot-car"），生成 "shot-<dir>/shot-car.png" 畸形路径 → 历史 run
  # 的 freeze 目录从未有过截图（静默失败被 || true 吞掉）。
  screencap_phone phone "$dir" > /dev/null 2>&1 || true
  screencap_car   car   "$dir" > /dev/null 2>&1 || true
  # VD 截屏证据（内部用 SF DisplayId 大数；vdlog 无 VD 记录时早退）
  [ -n "$(vd_display_id)" ] && screencap_vd "$tag" "$dir" > /dev/null 2>&1 || true
  dphone 'dumpsys display | grep -A3 DiLinkAutoVD' > "$dir/dumpsys-display-phone.txt" 2>&1 || true
  dphone 'dumpsys window | grep -E "isKeyguardShowing|mCurrentFocus"' > "$dir/dumpsys-window-phone.txt" 2>&1 || true
  dcar   'dumpsys window | grep -E "isKeyguardShowing|mCurrentFocus"' > "$dir/dumpsys-window-car.txt"   2>&1 || true
  { echo "tag=$tag t=${SECONDS}s sid=${E2E_SCENARIO_ID:-} scenario=${E2E_SCENARIO_NAME:-}"
    echo "phone: $(dphone getprop ro.product.model 2>/dev/null) android=$(dphone getprop ro.build.version.release 2>/dev/null)"
    echo "car:   $(dcar   getprop ro.product.model 2>/dev/null) android=$(dcar   getprop ro.build.version.release 2>/dev/null)"
    echo "engine_pid=$(engine_pid)"
    echo "car_state=$(car_state_now) phone_state=$(phone_state_now)"
    echo "screen_off_timeout=$(dphone 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')"
    echo "phone_wifi_iface=$(active_wifi_iface "$PHONE_SERIAL" 2>/dev/null) phone_ip=$(active_wifi_ip "$PHONE_SERIAL" 2>/dev/null)"
    echo "car_wifi_iface=$(active_wifi_iface "$CAR_SERIAL" 2>/dev/null) car_ip=$(active_wifi_ip "$CAR_SERIAL" 2>/dev/null)"
    dphone 'ip -4 addr show' 2>/dev/null; dcar 'ip -4 addr show' 2>/dev/null
  } > "$dir/state-snapshot.txt" 2>&1 || true
  [ -f "${E2E_SEQ_LOG:-/dev/null}" ] && cp "$E2E_SEQ_LOG" "$dir/sequence-slice.jsonl" 2>/dev/null
  log_ok "现场已冻结：$dir"
}

# ── CLI probe 模式（explore.py 入口） ───────────────────────────────────
# 用法：bash asserts.sh [probe] <what>   （"probe" 一词可省略，兼容两种写法）
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  . "$(dirname "$0")/logging.sh"; . "$(dirname "$0")/devices.sh"
  : "${PHONE_SERIAL:?export PHONE_SERIAL}" "${CAR_SERIAL:?export CAR_SERIAL}"
  [ "${1:-}" = "probe" ] && shift
  case "${1:-}" in
    state)
      echo "car=$(car_state_now) phone=$(phone_state_now) engine=$([ -n "$(engine_pid)" ] && echo present || echo absent) vd=$([ -n "$(vd_display_id)" ] && echo present || echo absent) sentinel=$([ "$(dphone 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')" = "2147483647" ] && echo on || echo off)" ;;
    car_state)   car_state_now ;;
    phone_state) phone_state_now ;;
    engine)      [ -n "$(engine_pid)" ] && echo present || echo absent ;;
    engine_pid)  engine_pid ;;
    vd)          [ -n "$(vd_display_id)" ] && echo present || echo absent ;;
    sentinel)    [ "$(dphone 'settings get system screen_off_timeout' 2>/dev/null | tr -d '\r\n ')" = "2147483647" ] && echo on || echo off ;;
    *) echo "unknown probe: ${1:-}" >&2; exit 2 ;;
  esac
  exit 0
fi
