#!/bin/bash
# 验证「应用并重连 / 关窗」时设备侧引擎的收尾清理是否完整（纯日志断言，不需要设备）。
#
# 背景：2026-10-10 修复了「重连清理补全」的 5 个缺陷（哨兵等待越界、teardown 截断、
# 桌面端杀 adb 锚点截断 cleanup、6s 预算太紧、pkill -f 自匹配）。本脚本把那轮实机
# 测试用到的判据固化成断言，随时可以复跑。
#
# 用法：
#   1. 跑一次实机会话（推荐直接跑 scripts/e2e-reconnect-cleanup.sh 全自动）；
#      或手工把两份日志放到同一目录：
#        desktop.log   桌面端日志（默认 %APPDATA%\DiLinkAuto\desktop.log，UTF-8）
#        vd-server.log 设备日志（adb pull /sdcard/DiLinkAuto/vd-server.log）
#   2. bash scripts/verify-reconnect-cleanup.sh <日志目录>
#
# 判据（都来自 2026-10-10 MI 9 实机实测）：
#   D1  每次「优雅停止」都等到了引擎消失（探针 GONE 次数 == 停止次数 >= 1）
#   D2  全部是「引擎已优雅退出」——不得出现「强制 kill」
#   D3  每次收尾耗时 < 12s 预算（实测 6.1~6.6s；出现 >= 10s 会警告逼近预算）
#   D4  vd-server 侧 cleanup 真跑完整：__DILINK_SYNC__ 排水回显 + Cleanup complete
#   D5  超时设置已恢复落盘（最后一次 screen_off_timeout 不是 2147483647 哨兵）
#   D6  会话确实建立过（handshake accepted + state -> STREAMING）；重连场景要求 >= 2 次握手
#
# 退出码 0 = 全部通过；1 = 有失败项。
set -uo pipefail

DIR="${1:-.}"
DESK="$DIR/desktop.log"
VDSRV="$DIR/vd-server.log"
PASS=0
BADN=0

if [ ! -f "$DESK" ]; then
  DESK=$(ls -S "$DIR"/desktop*.log 2>/dev/null | head -1)
fi
if [ ! -f "$VDSRV" ]; then
  VDSRV=$(ls -S "$DIR"/*vd-server*.log 2>/dev/null | head -1)
fi
if [ -z "${DESK:-}" ] || [ ! -f "$DESK" ]; then
  echo "✗ 找不到 desktop*.log（目录：$DIR）"
  exit 1
fi
if [ -z "${VDSRV:-}" ] || [ ! -f "$VDSRV" ]; then
  echo "✗ 找不到 *vd-server*.log（目录：$DIR）"
  exit 1
fi

hr() { printf '%s\n' "------------------------------------------------------------"; }
ok()   { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $1"; BADN=$((BADN + 1)); }
# 固定字符串计数（-F）：日志里有 | 等正则元字符，固定串最稳。
countF() { local n; n=$(grep -cF "$2" "$1" 2>/dev/null); echo "${n:-0}"; }

echo "日志目录：$DIR"
echo "desktop ：$(basename "$DESK")"
echo "vdserver：$(basename "$VDSRV")"
hr

STOPS=$(countF "$DESK" '优雅停止设备侧引擎')
ALIVE=$(countF "$DESK" '探针 ALIVE @+')
GONE=$(countF "$DESK" '探针 GONE @+')
GRACE=$(countF "$DESK" '引擎已优雅退出（耗时')
FORCED=$(countF "$DESK" '强制 kill')

# ── D1: 每次停止都等到引擎消失 ────────────────────────────────────────
echo "【D1】每次停止都确认引擎退出"
echo "     优雅停止=$STOPS   探针ALIVE=$ALIVE   探针GONE=$GONE"
if [ "$STOPS" -eq 0 ]; then
  bad "没有「优雅停止设备侧引擎」记录——这不是一次收尾测试的日志？"
elif [ "$GONE" -eq "$STOPS" ]; then
  ok "GONE 次数 == 停止次数（$STOPS），每次都等到引擎消失"
else
  bad "GONE($GONE) != 停止次数($STOPS)：有收尾没等到引擎退出"
fi
if [ "$ALIVE" -lt "$GONE" ]; then
  echo "     注：ALIVE < GONE 说明有引擎在首次探测前就已退出（退得快，正常）"
fi
hr

# ── D2: 必须优雅退出，不得强杀 ───────────────────────────────────────
echo "【D2】必须优雅退出（不得强杀）"
echo "     引擎已优雅退出=$GRACE   强制kill=$FORCED"
if [ "$FORCED" -gt 0 ]; then
  bad "出现 $FORCED 次「强制 kill」= 等待预算不足或退出路径被截断，cleanup 可能没跑完"
else
  ok "没有强制 kill"
fi
if [ "$STOPS" -gt 0 ] && [ "$GRACE" -eq "$STOPS" ]; then
  ok "每次停止都以「引擎已优雅退出」收尾（$GRACE/$STOPS）"
elif [ "$STOPS" -gt 0 ]; then
  bad "优雅退出次数($GRACE) != 停止次数($STOPS)"
fi
hr

# ── D3: 收尾耗时 ─────────────────────────────────────────────────────
echo "【D3】收尾耗时（预算 12s；实测 6.1~6.6s）"
TIMES=$(grep -oE '引擎已优雅退出（耗时 [0-9]+ms）' "$DESK" | grep -oE '[0-9]+')
if [ -z "$TIMES" ]; then
  echo "     <无耗时记录>"
else
  echo "     各次耗时(ms)：$(echo "$TIMES" | tr '\n' ' ')"
  TOOSLOW=$(echo "$TIMES" | awk '$1 >= 12000' | wc -l | tr -d ' ')
  NEAR=$(echo "$TIMES" | awk '$1 >= 10000' | wc -l | tr -d ' ')
  if [ "$TOOSLOW" -gt 0 ]; then
    bad "有 $TOOSLOW 次耗时 >= 12s（超出 GRACEFUL 预算，随时会退化成强制 kill）"
  else
    ok "全部 < 12s 预算"
  fi
  if [ "$NEAR" -gt 0 ]; then
    echo "     注：有 $NEAR 次 >= 10s，逼近预算，建议观察"
  fi
fi
hr

# ── D4: vd-server cleanup 完整性 ─────────────────────────────────────
echo "【D4】vd-server cleanup 完整性"
SYNC_CMD=$(countF "$VDSRV" 'sh> echo __DILINK_SYNC__')
SYNC_ECHO=$(countF "$VDSRV" 'sh<out| __DILINK_SYNC__')
CLEAN=$(countF "$VDSRV" 'Cleanup complete')
echo "     同步屏障下发=$SYNC_CMD   回显=$SYNC_ECHO   Cleanup complete=$CLEAN"
if [ "$SYNC_CMD" -ge 1 ] && [ "$SYNC_ECHO" -ge 1 ]; then
  ok "__DILINK_SYNC__ 屏障发出且回显（排水确已排空前序命令）"
else
  bad "缺少 __DILINK_SYNC__ 回显：teardown 可能在恢复命令执行完之前就关掉 shell"
fi
if [ "$CLEAN" -ge 1 ]; then
  ok "cleanup 跑到收尾（Cleanup complete x$CLEAN）"
else
  bad "没有 Cleanup complete——cleanup 被截断（例如被 -9 杀在半路）"
fi
hr

# ── D5: 超时设置恢复落盘 ─────────────────────────────────────────────
echo "【D5】screen_off_timeout 恢复落盘"
LAST=$(grep -oE 'settings put system screen_off_timeout [0-9]+' "$VDSRV" | tail -1 | grep -oE '[0-9]+$')
if [ -z "$LAST" ]; then
  bad "vd-server.log 里没有 screen_off_timeout 恢复命令"
elif [ "$LAST" = "2147483647" ]; then
  bad "最后一次仍是哨兵 2147483647——恢复是假的"
else
  ok "最后一次恢复值 = $LAST（非哨兵）"
fi
hr

# ── D6: 会话证据 ─────────────────────────────────────────────────────
echo "【D6】会话证据"
HS=$(countF "$DESK" 'handshake accepted:')
ST=$(countF "$DESK" 'state -> STREAMING')
DIS=$(countF "$DESK" 'state -> DISCONNECTED')
echo "     handshake accepted=$HS   STREAMING=$ST   DISCONNECTED=$DIS"
if [ "$HS" -ge 1 ] && [ "$ST" -ge 1 ]; then
  ok "会话建立过"
else
  bad "缺少会话建立证据（handshake/STREAMING）"
fi
if [ "$STOPS" -ge 2 ] && [ "$HS" -lt 2 ]; then
  bad "停止 $STOPS 次（含重连）但只有 $HS 次握手——重连没有建立新会话？"
elif [ "$STOPS" -ge 2 ]; then
  ok "重连场景：停止 $STOPS 次、握手 $HS 次，二次会话已建立"
fi
hr

echo "通过 $PASS 项；失败 $BADN 项"
if [ "$BADN" -eq 0 ]; then
  echo "结果：全部通过 ✓"
  exit 0
else
  echo "结果：有失败项 ✗ —— 把这个输出和日志一起发回给我"
  exit 1
fi
