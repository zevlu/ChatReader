#!/bin/bash
# 啟動 BeamNG 原生 Linux 版（x86，經 Steam snap 內的 FEX 轉譯）。Steam 要先在背景執行（DRM 驗證）。
# 用法：run_beamng.sh [BeamNG 參數...]   例：run_beamng.sh -noui -nocrashreport
# 環境變數 CODRIVER_TASKSET=5-9,15-19 可以把遊戲綁在指定 CPU 核心（預設不綁）
HERE=$(dirname "$(readlink -f "$0")")
source "$HERE/codriver-dgx.conf"
export DISPLAY=${DISPLAY:-:0} XAUTHORITY=${XAUTHORITY:-/run/user/$(id -u)/gdm/Xauthority}
export PULSE_SERVER=unix:/run/user/$(id -u)/pulse/native
export SteamAppId=284160 SteamGameId=284160
cd ~ && exec snap run --shell steam -c "$HERE/in_steam_fex.sh /bin/bash -c 'cd \"$GAME_DIR\" && exec ${CODRIVER_TASKSET:+taskset -c $CODRIVER_TASKSET} ./BinLinux/BeamNG.drive.x64 $*'"
