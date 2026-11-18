#!/bin/bash
# 啟動 BeamNG.Earth 世界伺服器（ARM 原生 Python venv，不經轉譯）＋安裝／更新 Earth mod。
# 遊戲另外用 run_beamng.sh 啟動（--no-game）。BeamMP、自動更新檢查都關掉。
# 版本固定 1.9.1-beta：launcher 的 --no-update-check 只管它自己；遊戲左上角「1.10.1-beta is available」是世界伺服器
# 檢查的，用 -- 後面的 --update-check off 關掉。其他伺服器參數接在後面，例如：run_earth_server.sh --offline
HERE=$(dirname "$(readlink -f "$0")")
source "$HERE/codriver-dgx.conf"
cd "$EARTH_DIR" || { echo "找不到 $EARTH_DIR（先執行 tools/dgx/install_host.sh）"; exit 1; }
export BNE_TELEMETRY=off      # 匿名統計：預設關閉（要開再拿掉這行）
exec "$EARTH_VENV/bin/python" launcher.py --no-game --no-beammp --no-update-check --no-terminal \
  --user-dir "$BEAMNG_USER" -- --update-check off "$@"
