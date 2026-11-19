#!/bin/bash
# 加油站版完整旅程（無人值守測試用）：B 台跑「假手機」（/tmp/fake_phone2.py，127.0.0.1:18765），
# 橋接容器用 CODRIVER_PHONE=http://127.0.0.1:18765 啟動。一輪：
#   回起點（油 12%）→ 假手機導航「楠梓車站旁的麥當勞」＋viaGas → 自動駕駛 → 第一段塞車 75 秒 → 解除
#   → 楠都加油站（自動加滿、GPS 換成麥當勞）→ 抵達麥當勞
# 用法：gas_story_round.sh <B 台 IP> <輪次>      結果一行一行印出來（時間、事件）
B_HOST=$1; R=${2:-1}
HERE=$(dirname "$(readlink -f "$0")")
BNG=$HERE/bng
say() { echo "[$(date +%T)] 第 $R 輪：$*"; }
bstate() { ssh -o BatchMode=yes "nkust@$B_HOST" "$@"; }
t0=$(date +%s)
"$BNG" start > /dev/null || { say "回起點失敗"; exit 1; }
"$BNG" gps off > /dev/null
bstate "echo '{\"id\":$((1000 + R)),\"cmd\":\"plan\",\"dest\":\"楠梓車站旁的麥當勞\",\"viaGas\":true,\"via\":\"\",\"start\":true}' > /tmp/nav.json"
for i in $(seq 1 30); do "$BNG" gps 2>/dev/null | grep -q "加油站" && ! "$BNG" gps | grep -q 規劃中 && break; sleep 2; done
say "第一段 GPS：$("$BNG" gps)"
"$BNG" face > /dev/null; sleep 2
"$BNG" auto on > /dev/null
sleep 10; say "$("$BNG" jam on 450 | cut -c1-40)"
sleep 75; "$BNG" jam off > /dev/null; say "塞車解除"
"$BNG" auto on > /dev/null
for i in $(seq 1 200); do "$BNG" gps 2>/dev/null | grep -q "麥當勞" && break; sleep 3; done
say "到加油站 → 第二段 GPS：$("$BNG" gps)（出發後 $(( $(date +%s) - t0 )) 秒）"
sleep 3; "$BNG" auto on > /dev/null
for i in $(seq 1 120); do "$BNG" gps 2>/dev/null | grep -q "沒有 GPS" && break; sleep 3; done
"$BNG" auto off > /dev/null
say "結束：$("$BNG" gps)；整輪 $(( $(date +%s) - t0 )) 秒；位置 $("$BNG" status | head -1 | cut -d'|' -f1)"
bstate "echo '{\"id\":$((1000 + R)),\"cmd\":\"done\"}' > /tmp/nav.json"
