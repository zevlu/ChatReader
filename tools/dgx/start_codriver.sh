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

# 4. 3D 畫面（-noui 不會建）、1080p 無邊框全螢幕、垂直同步，載入 BeamNG.Earth 關卡
send 'local g=scenetree.findObject("OnlyGui") if not g then g=createObject("GameViewportCtrl") g.forceFOV=0 g:setField("position",0,"0 0") g:setField("extent",0,"1024 768") g:setField("horizSizing",0,"width") g:setField("vertSizing",0,"height") g:setField("profile",0,"GuiDefaultProfile") g.visible=1 g.active=1 g.isContainer=1 g.enabled=1 g:registerObject("OnlyGui") end scenetree.findObject("Canvas"):setContent(g) settings.setValue("vsync", true) settings.setValue("fpsLimitEnabled", false) settings.setValue("GraphicDisplayResolutions", "1920 1080") settings.setValue("GraphicDisplayModes", "Borderless") core_settings_graphic.applyGraphicsState() freeroam_freeroam.startFreeroamByName("beamng_earth") return "boot"'
say "載入 BeamNG.Earth 關卡（約 30 秒）..."
waitlog "$GLOG" "earthReady=true" 300 || { say "Earth 世界沒有就緒，請看 $GLOG"; exit 1; }

# 5. 傳送到起點
say "傳送到 $PLACE ..."
if [[ "$PLACE" =~ ^-?[0-9.]+\ +-?[0-9.]+$ ]]; then "$HERE/bng" tp $PLACE; else "$HERE/bng" place "$PLACE"; fi
sleep 15

# 6. OutGauge（codriver-dgx.conf 的 OUTGAUGE_HOST／PORT）
say "OutGauge 設定..."; "$HERE/bng" outgauge

# 7. 交通車（選用）
if [ "$TRAFFIC" -gt 0 ] 2>/dev/null; then say "叫出 $TRAFFIC 台交通車..."; "$HERE/bng" traffic "$TRAFFIC"; fi
say "完成！用 Alt+Tab 切到 BeamNG 視窗開車。其他操作：$HERE/bng（遊戲 log：$GLOG）"
