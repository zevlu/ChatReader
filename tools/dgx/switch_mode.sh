#!/bin/bash
# 雙機 ⇄ 單機 切換（在 A 台＝遊戲台執行）：會場兩台連不起來時，一個指令切回單機（A 台也跑 LLM＋橋接）。
#   tools/dgx/switch_mode.sh single   單機：停轉送 → A 台 docker compose up -d（LLM 約 3.5～5 分鐘載入）→ 遊戲直接送本機橋接
#   tools/dgx/switch_mode.sh dual     雙機：停 A 台的 LLM＋橋接 → 開轉送到 B 台（local.conf 的 RELAY_TO）
#   tools/dgx/switch_mode.sh          只顯示現在是哪個模式
# 遊戲不用重開。切完會跑 codriver_check.sh。
# 手機：單機時接 A 台。A 台沒有 adb，所以用筆電的 SSH 通道（手機插筆電）：
#   ssh -N -L 8300:127.0.0.1:8300 -R 8765:127.0.0.1:8765 nkust@<A 台 IP>
HERE=$(dirname "$(readlink -f "$0")")
REPO=$(cd "$HERE/../.." && pwd)
LOCAL=$HOME/codriver-dgx/local.conf
LOG=$HOME/codriver-dgx/logs
say() { echo "[$(date +%T)] $*"; }
relay_to() { grep -E '^#? *RELAY_TO=' "$LOCAL" 2>/dev/null | tail -1 | sed -E 's/^#? *RELAY_TO=//'; }
mode_now() {
  if pgrep -f '[c]odriver_relay.py' > /dev/null; then echo "雙機（轉送 → $(relay_to)）"
  elif [ "$(docker inspect -f '{{.State.Status}}' codriver-bridge 2>/dev/null)" = running ]; then echo "單機（LLM＋橋接在這台）"
  else echo "不明（沒有轉送，也沒有本機橋接）"; fi
}
cd "$REPO" || exit 1

case "$1" in
  single)
    say "切到單機：A 台也跑 LLM＋橋接"
    # local.conf 的 RELAY_TO 改成註解（保留 B 台 IP，切回雙機時用；start_codriver.sh 才不會又開轉送）
    if grep -qE '^RELAY_TO=' "$LOCAL" 2>/dev/null; then sed -i -E 's/^RELAY_TO=/#RELAY_TO=/' "$LOCAL"; say "local.conf：RELAY_TO 改成註解（$(relay_to)）"; fi
    pkill -f '[c]odriver_relay.py' && say "轉送程式已停"
    "$HERE/bng" outgauge 127.0.0.1 > /dev/null 2>&1 && say "遊戲 OutGauge → 127.0.0.1:4444（本機橋接直接收）"
    [ -f .env ] || { say "❌ 沒有 .env：cp .env.example .env 再重跑"; exit 1; }
    docker compose up -d 2>&1 | tail -2
    say "等 LLM 載入（最多 8 分鐘）…"
    for i in $(seq 1 96); do
      [ "$(docker inspect -f '{{.State.Health.Status}}' codriver-llm 2>/dev/null)" = healthy ] && break; sleep 5
    done
    say "codriver-llm：$(docker inspect -f '{{.State.Health.Status}}' codriver-llm 2>/dev/null)"
    echo
    echo "手機：插筆電 → 筆電 PowerShell 開通道到「這台」："
    echo "  ssh -N -L 8300:127.0.0.1:8300 -R 8765:127.0.0.1:8765 nkust@$(hostname -I | awk '{print $1}')"
    echo "  （筆電也要 adb forward tcp:8765 tcp:8765、adb reverse tcp:8300 tcp:8300，或開 adb_keepalive.ps1）"
    echo "車機網頁：這台的 Chromium／瀏覽器開 http://localhost:8765/?unit=1（或筆電 http://localhost:8765/?unit=1）"
    ;;
  dual)
    B=$(relay_to)
    [ -n "$B" ] || { say "❌ local.conf 沒有 RELAY_TO（B 台 IP）：echo \"RELAY_TO=<B 台 IP>\" >> $LOCAL"; exit 1; }
    say "切到雙機：遊戲 → 轉送 → B 台 $B"
    docker compose stop codriver-bridge codriver-llm 2>&1 | tail -2
    sed -i -E 's/^# *RELAY_TO=/RELAY_TO=/' "$LOCAL"
    "$HERE/bng" outgauge 127.0.0.1 > /dev/null 2>&1
    if ! pgrep -f '[c]odriver_relay.py' > /dev/null; then
      setsid -f python3 -u "$HERE/codriver_relay.py" --to "$B" --port "${OUTGAUGE_PORT:-4444}" >> "$LOG/relay.log" 2>&1 < /dev/null
    fi
    sleep 2; pgrep -f '[c]odriver_relay.py' > /dev/null && say "轉送 → $B 已開" || say "❌ 轉送沒開起來，看 $LOG/relay.log"
    echo
    echo "B 台要開著：cd ~/ChatReader && docker compose up -d；手機接 B 台（或筆電通道連 B 台 $B）"
    ;;
  "") echo "現在：$(mode_now)"; exit 0 ;;
  *) echo "用法：$0 single|dual"; exit 1 ;;
esac
echo
say "現在：$(mode_now)；健康檢查（等 35 秒讓轉送／橋接有新資料）…"
sleep 35
"$HERE/codriver_check.sh"
