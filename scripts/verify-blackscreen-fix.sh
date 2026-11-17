#!/bin/bash
# 验证「退出重连黑屏」修复是否生效。
#
# 用法：
#   1. 手机上打开 App → 「分享日志」，把 dilinkauto-logs.zip 发到电脑
#   2. unzip dilinkauto-logs.zip -d /tmp/vdcheck
#   3. bash scripts/verify-blackscreen-fix.sh /tmp/vdcheck
#
# 退出码 0 = 全部通过；1 = 有失败项。
set -uo pipefail

DIR="${1:-.}"
CLIENT="$DIR/client.log"
VDSRV="$DIR/vd-server.log"
FAIL=0

if [ ! -f "$CLIENT" ]; then
  # client.log 可能在轮转后的文件里，回退到最大的那个
  CLIENT=$(ls -S "$DIR"/client*.log 2>/dev/null | head -1)
fi
[ -z "$CLIENT" ] && { echo "✗ 找不到 client*.log（目录：$DIR）"; exit 1; }
[ -f "$VDSRV" ] || VDSRV=$(ls -S "$DIR"/*vd-server*.log 2>/dev/null | head -1)

hr() { printf '%s\n' "------------------------------------------------------------"; }
ok()   { echo "  ✓ $1"; }
bad()  { echo "  ✗ $1"; FAIL=1; }

count() {
  local n
  n=$(grep -cE "$2" "$1" 2>/dev/null | head -1)
  [ -z "$n" ] && n=0
  printf '%s' "$n"
}

echo "日志目录：$DIR"
echo "client  ：$(basename "$CLIENT")"
[ -n "$VDSRV" ] && echo "vdserver：$(basename "$VDSRV")"
hr

# ── V2: VD 启动次数必须等于 cleanup 完成次数 ────────────────────────────
if [ -n "$VDSRV" ]; then
  START=$(count "$VDSRV" 'Starting: VD=')
  CLEAN=$(count "$VDSRV" 'Cleanup complete')
  echo "【V2】VD 泄漏"
  echo "     Starting: VD= $START    Cleanup complete: $CLEAN"
  if [ "$START" -eq 0 ]; then
    bad "日志里没有 VD 启动记录，可能不是本次测试的日志"
  elif [ "$START" -eq "$CLEAN" ]; then
    ok "启动/清理次数相等（$START = $CLEAN），无泄漏"
  else
    bad "泄漏 $((START - CLEAN)) 个 VirtualDisplay（改前实测 4:2 / 3:1）"
  fi

  # 附赠：displayId 应当不复用增长
  IDS=$(grep -oE 'VD via DisplayManager: id=[0-9]+' "$VDSRV" | grep -oE '[0-9]+$' | tr '\n' ' ')
  echo "     displayId 序列: $IDS"

  # 屏幕超时设置是否被污染
  POLL=$(count "$VDSRV" "screen_off_timeout 2147483647")
  REST=$(grep -oE "settings put system screen_off_timeout [0-9]+" "$VDSRV" 2>/dev/null | tail -1)
  echo "     最后的超时设置: ${REST:-<未记录>}"
  hr
fi

# ── V4: 恢复次数应与断连次数大致 1:1 ─────────────────────────────────
DIS=$(count "$CLIENT" 'Car disconnected')
WAKE=$(count "$CLIENT" 'Force-waking physical display')
CANCEL=$(count "$CLIENT" 'Shizuku display restore failed: Job was cancelled')
echo "【V4】cleanupSession幂等"
echo "     Car disconnected=$DIS   Force-waking=$WAKE   被取消=$CANCEL"
if [ "$DIS" -eq 0 ]; then
  bad "没有断连记录，无法判断"
elif [ "$WAKE" -le $((DIS * 2)) ]; then
  ok "恢复次数≈断连次数（改前 164828 那份是 1:9）"
else
  bad "恢复被重复触发 ${WAKE}次（断连仅 ${DIS}次），幂等 guard 可能失效"
fi
if [ "$CANCEL" -gt 0 ]; then
  bad "有 $CANCEL 次恢复被协程取消（pkill 跑了但 power-on 可能没跑）"
else
  ok "无 'Job was cancelled'"
fi
hr

# ── V5: 黑屏自愈 ─────────────────────────────────────────────────────
echo "【V5】车机端黑屏自愈"
SUSPECT=$(count "$CLIENT" 'Suspected BLACK SCREEN')
SUSTAIN=$(count "$CLIENT" 'BLACK SCREEN sustained')
REBUILD=$(count "$CLIENT" '\[BLACK\] requesting VD rebuild')
CLEARED=$(count "$CLIENT" 'Black screen alert cleared')
echo "     疑似黑屏=$SUSPECT   持续黑屏=$SUSTAIN   触发重建=$REBUILD   告警清除=$CLEARED"
if [ "$SUSTAIN" -gt 0 ]; then
  if [ "$REBUILD" -gt 0 ]; then
    ok "持续黑屏后触发了 VD 重建（自愈路径打通）"
  else
    bad "检测到持续黑屏但没有触发重建，自愈回调没接上"
  fi
else
  ok "本次测试未出现持续黑屏（最好情况）"
fi
[ "$SUSPECT" -gt 0 ] && echo "     注：黑帧曾出现但已恢复（$CLEARED 次清除）"
hr

# ── 附加：优雅停机是否真的跑了 ───────────────────────────────────────
echo "【附加】停机路径"
echo "     CMD_STOP 成功=$(count "$CLIENT" 'Sent CMD_STOP to VD server')"
echo "     CMD_STOP 失败=$(count "$CLIENT" 'Failed to send CMD_STOP')"
echo "     vd-server 退出探测=$(count "$CLIENT" 'VD server exited')"
echo "     等待旧实例超时=$(count "$CLIENT" 'still alive after')"
hr

if [ "$FAIL" -eq 0 ]; then
  echo "结果：全部通过 ✓"
  exit 0
else
  echo "结果：有失败项 ✗ —— 把这个输出和日志一起发回给我"
  exit 1
fi
