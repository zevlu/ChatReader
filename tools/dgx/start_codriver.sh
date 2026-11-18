#!/bin/bash
# CoDriver A 台一鍵啟動：Steam → BeamNG.Earth 世界伺服器 → BeamNG（-noui）→ 3D 畫面 → Earth 關卡 → 傳送到起點 → OutGauge
# 用法：start_codriver.sh [地點名稱或「緯度 經度」] [交通車台數]（不給就用 codriver-dgx.conf 的 START_PLACE／START_TRAFFIC）
HERE=$(dirname "$(readlink -f "$0")")
source "$HERE/codriver-dgx.conf"
PLACE=${1:-$START_PLACE}; TRAFFIC=${2:-$START_TRAFFIC}
U=$BEAMNG_USER
SLOG=$HOME/.cache/beamng-earth/logs/launcher.log
mkdir -p "$CODRIVER_HOME/logs"; GLOG=$CODRIVER_HOME/logs/game_$(date +%m%d_%H%M).log
export DISPLAY=:0 XAUTHORITY=/run/user/$(id -u)/gdm/Xauthority
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/$(id -u)/bus XDG_RUNTIME_DIR=/run/user/$(id -u)
say() { echo "[$(date +%T)] $*"; }
waitlog() { for i in $(seq 1 $3); do grep -a -q -E "$2" "$1" 2>/dev/null && return 0; sleep 2; done; return 1; }
send() { printf '%s\n' "$1" > $U/codriver_cmd.tmp && mv $U/codriver_cmd.tmp $U/codriver_cmd.lua; }

# 0. 已經在跑就不重複開
if pgrep -f '[B]inLinux/BeamNG.drive.x64' > /dev/null; then
  say "BeamNG 已經在執行。換地點：$HERE/bng place <名稱>；要重開先執行 $HERE/stop_codriver.sh"; exit 0
fi

# 1. Steam（BeamNG 要 Steam 在背景執行才能通過驗證；不用按 Play）
if ! pgrep -f '[s]teamrt64/steam' > /dev/null; then
  say "啟動 Steam..."
  (cd ~ && setsid -f snap run steam > "$CODRIVER_HOME/logs/steam.log" 2>&1 < /dev/null)
  for i in $(seq 1 90); do pgrep -f '[s]teamwebhelper' > /dev/null && break; sleep 2; done
  sleep 10
  say "Steam 已啟動"
fi

# 2. BeamNG.Earth 世界伺服器（ARM 原生）
if ! ss -ltn | grep -q ':47613 '; then
  say "啟動 BeamNG.Earth 世界伺服器..."
  rm -f "$SLOG"; setsid -f "$HERE/run_earth_server.sh" > /dev/null 2>&1 < /dev/null
  waitlog "$SLOG" "World server +running|did not start" 90
  grep -q -E "World server +running" "$SLOG" || { say "世界伺服器啟動失敗，請看 $SLOG"; exit 1; }
fi
say "世界伺服器 OK"

# 3. BeamNG（-noui：CEF 介面在 FEX 下會當掉）
say "啟動 BeamNG..."
rm -f $U/codriver_cmd.lua
setsid -f "$HERE/run_beamng.sh" -noui -nocrashreport > "$GLOG" 2>&1 < /dev/null
waitlog "$GLOG" "CODRIVER_FPS" 90 || { say "遊戲沒有正常啟動（codriver mod 有裝嗎？），請看 $GLOG"; exit 1; }

# 4. 3D 畫面（-noui 不會建）、畫面模式（codriver-dgx.conf 的 GAME_MODE／GAME_RES）、垂直同步，載入 BeamNG.Earth 關卡
send 'local g=scenetree.findObject("OnlyGui") if not g then g=createObject("GameViewportCtrl") g.forceFOV=0 g:setField("position",0,"0 0") g:setField("extent",0,"1024 768") g:setField("horizSizing",0,"width") g:setField("vertSizing",0,"height") g:setField("profile",0,"GuiDefaultProfile") g.visible=1 g.active=1 g.isContainer=1 g.enabled=1 g:registerObject("OnlyGui") end scenetree.findObject("Canvas"):setContent(g) settings.setValue("vsync", true) settings.setValue("fpsLimitEnabled", false) settings.setValue("GraphicDisplayResolutions", "'"$GAME_RES"'") settings.setValue("GraphicDisplayModes", "'"$GAME_MODE"'") core_settings_graphic.applyGraphicsState() freeroam_freeroam.startFreeroamByName("beamng_earth") return "boot"'
say "載入 BeamNG.Earth 關卡（約 30 秒）..."
waitlog "$GLOG" "earthReady=true" 300 || { say "Earth 世界沒有就緒，請看 $GLOG"; exit 1; }

# 5. 傳送到起點，等那裡的地圖載好
say "傳送到 $PLACE ..."
if [[ "$PLACE" =~ ^-?[0-9.]+\ +-?[0-9.]+$ ]]; then "$HERE/bng" tp $PLACE; else "$HERE/bng" place "$PLACE"; fi
sleep 5
for i in $(seq 1 30); do "$HERE/bng" lua "return tostring(beamngEarth_api.ready())" 2>/dev/null | grep -q true && break; sleep 2; done

# 6. 遊戲 GPS 先導航到預設目的地，等路線規劃好，車頭轉向路線（傳送後方向是隨機的）
if [ -n "$START_GPS" ]; then
  say "遊戲 GPS → $START_GPS ..."; "$HERE/bng" gps "$START_GPS"
  # 要等路線規劃好（規劃中時只是一條指向目的地的直線，方向會錯）
  for i in $(seq 1 30); do "$HERE/bng" gps 2>/dev/null | grep -q "規劃中\|沒有 GPS" || break; sleep 2; done
  for i in $(seq 1 10); do "$HERE/bng" face 2>/dev/null | grep -q "車頭轉向" && { say "車頭已轉向導航路線"; break; }; sleep 2; done
fi

# 展示畫面乾淨：關掉 Earth 的除錯面板（要用時 bng panel）；固定白天（Earth 預設跟真實時間，晚上全黑）
"$HERE/bng" panel off > /dev/null
"$HERE/bng" day "${GAME_HOUR:-13}" > /dev/null
# 操作員快速鍵（Ctrl+Alt+1～4）用的起點／目的地
"$HERE/bng" hotkeys > /dev/null

# 7. OutGauge（codriver-dgx.conf 的 OUTGAUGE_HOST／PORT）
#    RELAY_TO 有設定（B 台是公開 IP）：遊戲送到本機，codriver_relay.py 轉送到 B 台（遊戲只准送私有位址）
if [ -n "$RELAY_TO" ]; then
  if ! pgrep -f '[c]odriver_relay.py' > /dev/null; then
    setsid -f python3 -u "$HERE/codriver_relay.py" --to "$RELAY_TO" --port "${OUTGAUGE_PORT:-4444}" \
      > "$CODRIVER_HOME/logs/relay.log" 2>&1 < /dev/null
    sleep 1
  fi
  say "轉送 → $RELAY_TO（log：$CODRIVER_HOME/logs/relay.log）"
  "$HERE/bng" outgauge 127.0.0.1 "${OUTGAUGE_PORT:-4444}"
else
  say "OutGauge 設定..."; "$HERE/bng" outgauge
fi

# 8. 交通車：剛生成的 30～60 秒會掉格，所以在出發前就叫好、等它穩定
if [ "$TRAFFIC" -gt 0 ] 2>/dev/null; then
  say "叫出 $TRAFFIC 台交通車（約 60 秒）..."; "$HERE/bng" traffic "$TRAFFIC"; sleep 60
fi
say "完成！用 Alt+Tab 切到 BeamNG 視窗開車。快速鍵：Ctrl+Alt+1 塞車開關、2 回起點、3 車放回路上、4 交通車重生；其他操作：$HERE/bng（遊戲 log：$GLOG）"
