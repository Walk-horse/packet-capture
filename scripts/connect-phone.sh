#!/usr/bin/env bash
# 一键恢复手机同步链路：adb forward + 手机端 App 存活检查 + API 探活
#
# 背景：adb forward 规则不跨 USB 断连持久化，插拔一次就丢，
#       表现为「桌面端连不上手机 / 列表没数据」。
#
# 用法：
#   ./scripts/connect-phone.sh              # 恢复转发并探活
#   ./scripts/connect-phone.sh --start-app  # 顺带用调试通道拉起 App 并开抓包/同步
#   ./scripts/connect-phone.sh --full       # 探活时用 ?full=1（注意：会消费 synced 标志位）
#
# 注意：普通探活是「增量」请求，会消费手机端 synced 标志位，
#       会影响桌面端增量同步，非必要别随便跑探活。

set -uo pipefail

SERIAL="${SERIAL:-126504f1}"
PORT="${PORT:-17890}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.ht.stream.debug"

START_APP=0
PROBE="incremental"
for arg in "$@"; do
  case "$arg" in
    --start-app) START_APP=1 ;;
    --full) PROBE="full" ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

if [ ! -x "$ADB" ]; then
  echo "[x] 找不到 adb: $ADB（可用 ADB=/path/to/adb 覆盖）"
  exit 1
fi

echo "== 1. 设备 =="
DEV="$("$ADB" devices | awk -v s="$SERIAL" '$1==s && $2=="device" {print $1}')"
if [ -z "$DEV" ]; then
  echo "[x] 设备 $SERIAL 未连接或未授权"
  "$ADB" devices -l
  echo "    → 插好 USB / 手机上允许调试后重试"
  exit 1
fi
echo "[ok] $SERIAL 已连接"

echo "== 2. 手机端 App =="
if "$ADB" -s "$SERIAL" shell "ps -A | grep -q $PKG_DEBUG" 2>/dev/null; then
  echo "[ok] $PKG_DEBUG 在运行"
elif [ "$START_APP" = "1" ]; then
  echo "[..] 未运行，拉起并开启抓包/同步"
  "$ADB" -s "$SERIAL" shell am start -n "$PKG_DEBUG/com.ht.stream.MainActivity" \
    --ez autostart true --ez sync_on true >/dev/null 2>&1
  sleep 3
else
  echo "[!] $PKG_DEBUG 未运行（加 --start-app 自动拉起）"
fi

echo "== 3. adb forward =="
"$ADB" -s "$SERIAL" forward --remove "tcp:$PORT" >/dev/null 2>&1
OUT="$("$ADB" -s "$SERIAL" forward "tcp:$PORT" "tcp:$PORT" 2>&1)"
if [ "$OUT" = "$PORT" ]; then
  echo "[ok] tcp:$PORT -> tcp:$PORT"
else
  echo "[x] 转发失败: $OUT"
  exit 1
fi

echo "== 4. 探活 =="
URL="http://127.0.0.1:$PORT/api/state"
[ "$PROBE" = "full" ] && URL="$URL?full=1"
CODE="$(curl -s --max-time 10 "$URL" -o /tmp/connect-phone-state.json -w '%{http_code}' || echo 000)"
if [ "$CODE" != "200" ]; then
  echo "[x] HTTP $CODE —— 手机端同步服务没响应（App 是否在跑？抓包是否开启？）"
  exit 1
fi
python3 - <<'PY'
import json
try:
    d = json.load(open('/tmp/connect-phone-state.json'))
except Exception as e:
    print('[!] 返回体非 JSON:', e); raise SystemExit(0)
print('[ok] capturing=%s sessions=%d exchanges=%d' % (
    d.get('capturing'), len(d.get('sessions') or []), len(d.get('exchanges') or [])))
PY

echo
echo "链路就绪。桌面端地址填 127.0.0.1:$PORT"
