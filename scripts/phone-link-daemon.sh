#!/usr/bin/env bash
# 手机同步链路守护进程
#
# 解决的问题：adb forward 规则不跨 USB 断连持久化，手机插拔/锁屏重连后规则丢失，
#             表现为「桌面端连不上手机」。本脚本每 5 秒巡检一次，规则丢了就自动重建。
#
# 由 LaunchAgent com.ht.streamdesk.phonelink 拉起（登录自启 + 崩溃自动重启）。
# 日志：/tmp/phone-link.log
#
# 手动跑：./scripts/phone-link-daemon.sh
# 临时改参数：SERIAL=xxx PORT=17891 ./scripts/phone-link-daemon.sh

set -uo pipefail

SERIAL="${SERIAL:-126504f1}"
PORT="${PORT:-17890}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.ht.stream.debug"
INTERVAL="${INTERVAL:-5}"

log() { echo "[$(date '+%F %T')] $*"; }

if [ ! -x "$ADB" ]; then
  log "[x] 找不到 adb: $ADB（可用 ADB=/path/to/adb 覆盖）"
  exit 1
fi

# 幂等：规则已在则静默返回
ensure_forward() {
  if "$ADB" forward --list 2>/dev/null | awk -v s="$SERIAL" -v p="tcp:$PORT" '$1==s && $2==p {found=1} END{exit !found}'; then
    return 0
  fi
  "$ADB" -s "$SERIAL" forward --remove "tcp:$PORT" >/dev/null 2>&1
  local out
  out="$("$ADB" -s "$SERIAL" forward "tcp:$PORT" "tcp:$PORT" 2>&1)"
  if [ "$out" = "$PORT" ]; then
    log "[ok] 重建 adb forward tcp:$PORT -> tcp:$PORT"
  else
    log "[x] 重建 forward 失败: $out"
  fi
}

log "守护启动 serial=$SERIAL port=$PORT interval=${INTERVAL}s"

dev_state=""
app_state=""

while true; do
  if "$ADB" devices 2>/dev/null | awk -v s="$SERIAL" '$1==s && $2=="device" {found=1} END{exit !found}'; then
    if [ "$dev_state" != "up" ]; then
      log "[..] 设备 $SERIAL 上线"
      dev_state="up"
    fi
    ensure_forward

    # 手机端 App 存活提示（只记状态变化，避免刷屏）
    if "$ADB" -s "$SERIAL" shell "ps -A | grep -q $PKG_DEBUG" >/dev/null 2>&1; then
      if [ "$app_state" != "up" ]; then
        log "[ok] 手机端 $PKG_DEBUG 在运行"
        app_state="up"
      fi
    else
      if [ "$app_state" != "down" ]; then
        log "[!] 手机端 $PKG_DEBUG 未运行（桌面端连不上，需手动打开 App）"
        app_state="down"
      fi
    fi
  else
    if [ "$dev_state" != "down" ]; then
      log "[..] 设备离线，等待重连"
      dev_state="down"
    fi
  fi

  sleep "$INTERVAL"
done
