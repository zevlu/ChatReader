#!/bin/bash
# 啟動 BeamNG.Earth 世界伺服器（ARM 原生 Python venv，不經轉譯）＋安裝／更新 Earth mod。
# 遊戲另外用 run_beamng.sh 啟動（--no-game）。BeamMP、自動更新檢查都關掉。
HERE=$(dirname "$(readlink -f "$0")")
source "$HERE/codriver-dgx.conf"
cd "$EARTH_DIR" || { echo "找不到 $EARTH_DIR（先執行 tools/dgx/install_host.sh）"; exit 1; }
export BNE_TELEMETRY=off      # 匿名統計：預設關閉（要開再拿掉這行）
exec "$EARTH_VENV/bin/python" launcher.py --no-game --no-beammp --no-update-check --no-terminal \
  --user-dir "$BEAMNG_USER" "$@"
