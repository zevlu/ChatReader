#!/bin/bash
# 關掉 BeamNG 和 BeamNG.Earth 世界伺服器（Steam 保持開著）。不要用 Steam 裡的 STOP 按鈕。
p=$(pgrep -f '[B]inLinux/BeamNG.drive.x64')
[ -n "$p" ] && { echo "關閉 BeamNG..."; kill $p; sleep 8; p=$(pgrep -f '[B]inLinux/BeamNG.drive.x64'); [ -n "$p" ] && kill -9 $p; }
p="$(pgrep -f '[l]auncher.py --no-game') $(pgrep -f '[l]auncher.py --server')"
[ -n "${p// /}" ] && { echo "關閉世界伺服器..."; kill $p 2>/dev/null; sleep 3; }
p=$(pgrep -f '[c]odriver_relay.py'); [ -n "$p" ] && { echo "關閉轉送..."; kill $p; }
echo "已關閉（Steam 還開著）"
