#!/bin/bash
# 在 Steam snap 的 FEX（x86 轉譯）環境裡執行指令（BeamNG 原生 Linux 版是 x86_64，要經過 FEX）。
# 用法：snap run --shell steam -c "tools/dgx/in_steam_fex.sh <指令...>"（run_beamng.sh 會幫你呼叫）
# 環境設定照抄 /snap/steam/current/usr/bin/fex_launcher.sh（不做 rootfs／驅動更新那些步驟，那些由 Steam 啟動時做）
export HOME="$SNAP_USER_COMMON"
export FEX_SERVERSOCKETPATH=$SNAP_USER_COMMON/.fex-emu/FEXServer.Socket
export FEX_APP_CONFIG_LOCATION=$SNAP_USER_COMMON/fex_config/
export FEX_ROOTFS=$SNAP_USER_COMMON/x86_rootfs/
export XKB_CONFIG_ROOT="$SNAP/usr/share/X11/xkb"
unset LD_LIBRARY_PATH LD_PRELOAD
shadow_common="${FEX_ROOTFS%/}$SNAP_USER_COMMON"
exec "$SNAP/usr/bin/bwrap" --bind / / --dev-bind /dev /dev --proc /proc \
  --bind "$SNAP_USER_COMMON" "$shadow_common" -- \
  "$SNAP/usr/bin/FEXBash" -c 'exec "$@"' bash "$@"
