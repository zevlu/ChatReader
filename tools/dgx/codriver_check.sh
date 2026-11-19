#!/bin/bash
# CoDriver 健康檢查：A 台（遊戲）或 B 台（車機＋AI）執行都可以，每項印 ✅／❌，❌ 附一句怎麼修。
#   A 台執行：檢查本機的遊戲、轉送程式；再用 SSH 到 B 台（local.conf 的 RELAY_TO）跑 B 台的檢查
#   B 台執行：只檢查 B 台（橋接有收到遊戲資料 = A 台的遊戲和轉送都正常）
#   單機模式（同一台）：全部在本機檢查
# 用法：tools/dgx/codriver_check.sh            自動判斷
#       tools/dgx/codriver_check.sh --local    只檢查本機（A 台 SSH 到 B 台時用的就是這個）
HERE=$(dirname "$(readlink -f "$0")")
REPO=$(cd "$HERE/../.." && pwd)
FAIL=0
ok()  { echo "  ✅ $1"; }
bad() { echo "  ❌ $1"; echo "     → $2"; FAIL=$((FAIL + 1)); }
info(){ echo "  ℹ️  $1"; }

# ---------- A 台：遊戲、轉送 ----------
check_a() {
  echo "【A 台：遊戲】$(hostname)"
  if pgrep -f 'BinLinux/BeamNG\.drive\.x64' > /dev/null; then ok "BeamNG 在跑"
  else bad "BeamNG 沒在跑" "tools/dgx/start_codriver.sh（約 2 分鐘）"; return; fi
  local st; st=$("$HERE/bng" status 2>&1 | head -2)
  if echo "$st" | grep -q "OutGauge 開"; then ok "遊戲 OutGauge：$(echo "$st" | grep -o 'OutGauge 開 [0-9.:]*')"
  else bad "遊戲 OutGauge 沒開或 bng 沒回應（$(echo "$st" | head -1 | cut -c1-60)）" "bng outgauge 127.0.0.1（轉送模式）；bng 沒回應就 stop_codriver.sh → start_codriver.sh"; fi
  local fps; fps=$(echo "$st" | grep -o 'avg=[0-9.]*' | cut -d= -f2)
  [ -n "$fps" ] && { if awk "BEGIN{exit !($fps >= 30)}"; then ok "FPS $fps"; else bad "FPS 只有 $fps" "Ctrl+Alt+4 交通車重新分散；還是低就 bng traffic 8"; fi; }
  if [ -n "$RELAY_TO" ]; then
    if pgrep -f '[c]odriver_relay.py' > /dev/null; then
      local last; last=$(grep '30 秒內' "$HOME/codriver-dgx/logs/relay.log" 2>/dev/null | tail -1)
      local og pos; og=$(echo "$last" | grep -o 'OutGauge [0-9]*' | grep -o '[0-9]*'); pos=$(echo "$last" | grep -o '位置 [0-9]*' | grep -o '[0-9]*')
      if [ "${og:-0}" -gt 500 ] && [ "${pos:-0}" -gt 10 ]; then ok "轉送 → $RELAY_TO：30 秒內 OutGauge $og、位置 $pos"
      else bad "轉送在跑，但最近 30 秒 OutGauge ${og:-0}、位置 ${pos:-0}" "遊戲在選單或暫停？bng status 確認 OutGauge 開 127.0.0.1:4444"; fi
    else bad "轉送程式（codriver_relay.py）沒在跑" "重跑 tools/dgx/start_codriver.sh（遊戲開著也可以，它會補開轉送）"; fi
  else info "沒有設定 RELAY_TO（單機模式，遊戲直接送給本機的橋接）"; fi
}

# ---------- B 台：橋接、LLM、手機 ----------
check_b() {
  echo "【B 台：車機＋AI】$(hostname)"
  cd "$REPO" || return
  # LLM
  local h; h=$(docker inspect -f '{{.State.Health.Status}}' codriver-llm 2>/dev/null)
  local port; port=$(grep -s '^CODRIVER_LLM_PORT=' .env | cut -d= -f2); port=${port:-8300}
  local models; models=$(curl -s -m 5 "http://127.0.0.1:$port/v1/models" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ "$h" = healthy ] && [ -n "$models" ]; then ok "codriver-llm healthy，模型 $models"
  elif [ -z "$h" ]; then bad "codriver-llm 容器不存在" "docker compose up -d（第一次啟動約 5 分鐘）"
  else bad "codriver-llm 狀態 $h，/v1/models 回應：${models:-沒有}" "剛啟動就等 5 分鐘；不然 docker compose restart codriver-llm（約 3.5 分鐘）"; fi
  # 橋接
  local bs; bs=$(docker inspect -f '{{.State.Status}}' codriver-bridge 2>/dev/null)
  if [ "$bs" != running ]; then bad "codriver-bridge 沒在跑（${bs:-不存在}）" "docker compose up -d codriver-bridge"
  else
    local tail; tail=$(docker logs --tail 4 codriver-bridge 2>&1)
    local line; line=$(echo "$tail" | grep '^車速' | tail -1)
    if [ -n "$line" ] && ! echo "$tail" | tail -2 | grep -q '沒有收到 BeamNG'; then ok "橋接有收到 OutGauge：$(echo "$line" | grep -o '油量 *[0-9]* %' | tr -s ' ')"
    else bad "橋接沒收到遊戲的 OutGauge" "A 台跑 codriver_check.sh 看轉送；轉送有數字但這裡沒有 → B 台防火牆的來源 IP（部署清單 2.6）"; fi
    if echo "$line" | grep -q '前車'; then ok "橋接有收到位置（pose）"
    else bad "橋接沒收到位置（pose）" "A 台遊戲的 codriverPose 沒在送：bng status；或 A 台重跑 start_codriver.sh"; fi
    if echo "$line" | grep -q '手機沒連上'; then bad "橋接連不到手機" "看下面 adb／手機的檢查"; else ok "橋接連得到手機"; fi
  fi
  # adb：手機接這台 → 檢查 forward／reverse；沒接 → 看 8765 是不是 SSH 通道
  if command -v adb > /dev/null && [ "$(adb get-state 2>/dev/null)" = device ]; then
    ok "手機 USB 接在這台（$(adb get-serialno 2>/dev/null)）"
    adb forward --list | grep -q 'tcp:8765 tcp:8765' && ok "adb forward tcp:8765" \
      || bad "少了 adb forward tcp:8765" "adb forward tcp:8765 tcp:8765（或開著 tools/dgx/adb_keepalive.sh 會自動補）"
    adb reverse --list 2>/dev/null | grep -q 'tcp:8300 tcp:8300' && ok "adb reverse tcp:8300" \
      || bad "少了 adb reverse tcp:8300" "adb reverse tcp:8300 tcp:8300（或開著 tools/dgx/adb_keepalive.sh 會自動補）"
  elif ss -ltn 2>/dev/null | grep -q '127.0.0.1:8765 '; then
    info "手機沒接在這台；8765 有人在聽（應該是筆電的 SSH 通道 -R 8765），adb 在筆電那邊設"
  else
    bad "手機沒接在這台，也沒有 SSH 通道" "手機 USB 插這台 → adb forward tcp:8765 tcp:8765、adb reverse tcp:8300 tcp:8300；或筆電開通道 ssh -N -L 8300:127.0.0.1:8300 -R 8765:127.0.0.1:8765 nkust@<B 台 IP>"
  fi
  # 手機 App
  local state; state=$(curl -s -m 3 http://127.0.0.1:8765/state)
  if [ -n "$state" ]; then ok "手機 App GET /state 有回應"
  else bad "手機 App 沒回應（GET http://127.0.0.1:8765/state）" "手機上打開 CoDriver App；還是不行就重插 USB、重做 adb forward"; fi
}

[ -f "$HERE/codriver-dgx.conf" ] && . "$HERE/codriver-dgx.conf" 2>/dev/null
here_a=0; here_b=0
pgrep -f 'BinLinux/BeamNG\.drive\.x64' > /dev/null && here_a=1
docker inspect codriver-bridge > /dev/null 2>&1 && here_b=1
[ -n "$RELAY_TO" ] && here_a=1        # A 台（有設定轉送）：遊戲沒開也要檢查到

echo "CoDriver 健康檢查 $(date '+%F %T')"
[ $here_a = 1 ] && check_a
if [ $here_b = 1 ] && [ -z "$RELAY_TO" ]; then check_b
elif [ "$1" != --local ] && [ -n "$RELAY_TO" ]; then
  echo "（SSH 到 B 台 $RELAY_TO 檢查…）"
  if ! ssh -o BatchMode=yes -o ConnectTimeout=5 "nkust@$RELAY_TO" "cd ~/ChatReader && tools/dgx/codriver_check.sh --local"; then
    FAIL=$((FAIL + 1)); echo "  ❌ B 台有項目沒過，或 SSH 連不上 $RELAY_TO"; echo "     → 到 B 台直接執行 tools/dgx/codriver_check.sh"
  fi
elif [ $here_b = 1 ]; then check_b
fi
[ $here_a = 0 ] && [ $here_b = 0 ] && { echo "這台看起來不是 A 台也不是 B 台（沒有遊戲、沒有 codriver-bridge 容器）"; exit 1; }
echo
if [ $FAIL = 0 ]; then echo "全部正常 ✅"; else echo "有 $FAIL 項要處理 ❌"; fi
exit $FAIL
