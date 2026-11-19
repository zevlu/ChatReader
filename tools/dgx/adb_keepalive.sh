#!/bin/bash
# 手機 USB 接在 B 台時：每 10 秒檢查一次，手機在、但 adb forward／reverse 少了就自動補上（2026-11-19：
# 實測時手機斷線兩次，重新插上後 forward／reverse 都不見了，要手動重設）。
#   forward tcp:8765 → 手機的 DashServer（橋接、車機網頁用）
#   reverse tcp:8300 → B 台的 LLM（App 問 AI 用）
# 手機沒接在這台（例如接筆電、走 SSH 通道）時什麼都不做，不會跟通道搶 8765。
# 用法：
#   tools/dgx/adb_keepalive.sh            前景執行（Ctrl+C 結束）
#   tools/dgx/adb_keepalive.sh --once     只檢查／補一次就結束（codriver_check.sh 也會用）
#   背景常駐：systemctl --user enable --now codriver-adb-keepalive（見 tools/dgx/README.md）
# log：~/codriver-dgx/logs/adb_keepalive.log（只記「補上」「斷線」「接上」這類變化，不會每 10 秒一行）
LOG=${ADB_KEEPALIVE_LOG:-$HOME/codriver-dgx/logs/adb_keepalive.log}
mkdir -p "$(dirname "$LOG")"
INTERVAL=${ADB_KEEPALIVE_SEC:-10}
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
command -v adb > /dev/null || { log "❌ 沒有 adb（B 台要先 sudo apt install adb）"; exit 1; }

last=""
check() {
  local state
  state=$(adb get-state 2>&1 | tr -d '\r')
  if [ "$state" != device ]; then
    case "$state" in
      *unauthorized*) s="手機要按「允許 USB 偵錯」" ;;
      *"no devices"*|*"not found"*) s="沒有手機" ;;
      *) s="$state" ;;
    esac
    [ "$last" != "$s" ] && log "📵 $s"
    last=$s; return 1
  fi
  [ "$last" != device ] && log "📱 手機接上：$(adb get-serialno 2>/dev/null)"
  last=device
  if ! adb forward --list 2>/dev/null | grep -q "tcp:8765 tcp:8765"; then
    if out=$(adb forward tcp:8765 tcp:8765 2>&1); then log "🔧 補上 adb forward tcp:8765 tcp:8765"
    else log "❌ adb forward 8765 失敗：$out（8765 被別的程式佔住？例如筆電的 SSH 通道）"; fi
  fi
  if ! adb reverse --list 2>/dev/null | grep -q "tcp:8300 tcp:8300"; then
    if out=$(adb reverse tcp:8300 tcp:8300 2>&1); then log "🔧 補上 adb reverse tcp:8300 tcp:8300"
    else log "❌ adb reverse 8300 失敗：$out"; fi
  fi
  return 0
}

if [ "$1" = --once ]; then check; exit $?; fi
log "開始：每 $INTERVAL 秒檢查一次 adb forward 8765／reverse 8300"
while true; do check > /dev/null; sleep "$INTERVAL"; done
