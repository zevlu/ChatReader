#!/bin/bash
# 加油站版完整旅程（無人值守測試用）：B 台跑「假手機」（/tmp/fake_phone2.py，127.0.0.1:18765），
# 橋接容器用 CODRIVER_PHONE=http://127.0.0.1:18765 啟動。一輪（2026-11-19 新路線、自動塞車）：
#   回起點（朝新路，油 12%，GPS 指向加油站）→ 假手機導航「楠梓車站旁的麥當勞」＋viaGas → 自動駕駛
#   → 楠都加油站（自動加滿、GPS 換成麥當勞）→ 遊戲自動塞車（AUTO_JAM_SEC，預設 90 秒後自動解除）
#   → 抵達麥當勞
# 不手動放塞車：要驗證的就是「加完油自動塞車」。
# 用法：gas_story_round.sh <B 台 IP｜local> <輪次>      結果一行一行印出來（時間、事件）
B_HOST=$1; R=${2:-1}
HERE=$(dirname "$(readlink -f "$0")")
BNG=$HERE/bng
say() { echo "[$(date +%T)] 第 $R 輪：$*"; }
# B 台 IP 寫 local = 單機模式（假手機也在這台）
bstate() { if [ "$B_HOST" = local ]; then bash -c "$*"; else ssh -o BatchMode=yes "nkust@$B_HOST" "$@"; fi; }
jam() { "$BNG" lua "return tostring(codriverJam and codriverJam.isActive())" 2>/dev/null | tail -1; }
t0=$(date +%s)
"$BNG" start > /dev/null || { say "回起點失敗"; exit 1; }
bstate "echo '{\"id\":$((1000 + R)),\"cmd\":\"plan\",\"dest\":\"楠梓車站旁的麥當勞\",\"viaGas\":true,\"via\":\"\",\"start\":true}' > /tmp/nav.json"
sleep 4
for i in $(seq 1 30); do "$BNG" gps 2>/dev/null | grep -q "加油站" && ! "$BNG" gps | grep -q 規劃中 && break; sleep 2; done
say "第一段 GPS：$("$BNG" gps)"
"$BNG" face > /dev/null; sleep 2
"$BNG" auto on > /dev/null
for i in $(seq 1 100); do "$BNG" gps 2>/dev/null | grep -q "麥當勞" && break; sleep 3; done
say "到加油站 → 第二段 GPS：$("$BNG" gps)（出發後 $(( $(date +%s) - t0 )) 秒）"
sleep 3; "$BNG" auto on > /dev/null
# 等自動塞車（加完油、GPS 換到第二段後約 3～10 秒）
t1=$(date +%s); jon=""
for i in $(seq 1 20); do [ "$(jam)" = true ] && { jon=$(( $(date +%s) - t1 )); break; }; sleep 2; done
if [ -n "$jon" ]; then say "自動塞車 開（第二段 GPS 後 ${jon} 秒）"; else say "❌ 40 秒內沒有自動塞車"; fi
if [ -n "$jon" ]; then
  for i in $(seq 1 70); do [ "$(jam)" = true ] || break; sleep 2; done
  say "自動塞車 解除（開了約 $(( $(date +%s) - t1 - jon )) 秒）"
fi
"$BNG" auto on > /dev/null
for i in $(seq 1 120); do "$BNG" gps 2>/dev/null | grep -q "沒有 GPS" && break; sleep 3; done
"$BNG" auto off > /dev/null
say "結束：$("$BNG" gps)；整輪 $(( $(date +%s) - t0 )) 秒；位置 $("$BNG" status | head -1 | cut -d'|' -f1)"
bstate "echo '{\"id\":$((1000 + R)),\"cmd\":\"done\"}' > /tmp/nav.json"
