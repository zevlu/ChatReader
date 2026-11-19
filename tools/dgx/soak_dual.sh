#!/bin/bash
# 雙機長時間穩定性測試（無人值守）：A 台（這台）跑遊戲＋轉送，B 台跑橋接＋LLM＋假手機。
#   - 兩台各開 power_mem_monitor（soak_A／soak_B）
#   - A 台每 5 秒記遊戲 FPS
#   - B 台用展示頻率（每 10 秒一句）連續發 LLM 請求（App 真正的提示詞）
#   - 一輪接一輪跑加油站版完整旅程（gas_story_round.sh）
# 事前：B 台的假手機（/tmp/fake_phone2.py）和 CODRIVER_PHONE=http://127.0.0.1:18765 的橋接容器要先開好
# 用法：tools/dgx/soak_dual.sh <B 台 IP> [分鐘=120]
B_HOST=$1; MIN=${2:-120}
HERE=$(dirname "$(readlink -f "$0")")
REPO=$(cd "$HERE/../.." && pwd)
OUT=$HOME/codriver-dgx/logs/soak_$(date +%m%d_%H%M); mkdir -p "$OUT"
SEC=$((MIN * 60)); END=$(( $(date +%s) + SEC ))
G=(--group 'beamng=BinLinux/BeamNG\.drive\.x64' --group 'codriver_llm=vllm serve|VLLM::EngineCore')
say() { echo "[$(date +%T)] $*"; }
say "輸出：$OUT（$MIN 分鐘）"

cd "$REPO"
python3 -u tools/dgx/power_mem_monitor.py --label soak_A --duration $((SEC + 600)) "${G[@]}" > "$OUT/pm_A.out" 2>&1 &
PM=$!
ssh -o BatchMode=yes "nkust@$B_HOST" "cd ~/ChatReader && setsid -f python3 -u tools/dgx/power_mem_monitor.py --label soak_B --duration $((SEC + 600)) --group 'codriver_llm=vllm serve|VLLM::EngineCore' > /tmp/pm_soak_B.out 2>&1 < /dev/null; setsid -f python3 -u tools/dgx/llm_bench.py --duration $SEC --interval 10 --quiet > /tmp/llm_soak.log 2>&1 < /dev/null"
( LOG=$(ls -t "$HOME"/codriver-dgx/logs/game_*.log | head -1); echo "time,fps,worst_ms" > "$OUT/fps.csv"
  while [ "$(date +%s)" -lt "$END" ]; do
    grep -a CODRIVER_FPS "$LOG" | tail -1 | sed -E "s/.*avg=([0-9.]+) worst_ms=([0-9.]+).*/$(date +%T),\1,\2/" >> "$OUT/fps.csv"; sleep 5
  done ) &
FPS=$!
cp "$HOME/codriver-dgx/logs/relay.log" "$OUT/relay_before.log" 2>/dev/null

r=0
while [ "$(date +%s)" -lt "$END" ]; do
  r=$((r + 1))
  "$HERE/gas_story_round.sh" "$B_HOST" "$r" >> "$OUT/rounds.log" 2>&1
  tail -1 "$OUT/rounds.log"
done
say "跑輪結束：共 $r 輪，等 LLM 測試結束..."
wait $FPS
sleep 60
kill -INT $PM 2>/dev/null; wait $PM 2>/dev/null
ssh -o BatchMode=yes "nkust@$B_HOST" 'for p in $(pgrep -f "[p]ower_mem_monitor.py --label soak_B"); do kill -INT $p; done; sleep 3; tail -5 /tmp/llm_soak.log; grep -E "gpu_power_w|mem_used_gb|rss_codriver_llm" $(ls -t ~/ChatReader/logs/power_mem/soak_B_*_summary.txt | head -1)' > "$OUT/B_summary.txt" 2>&1
ssh -o BatchMode=yes "nkust@$B_HOST" 'docker logs --since '"${MIN}"'m codriver-bridge 2>&1' > "$OUT/bridge.log"
cp "$HOME/codriver-dgx/logs/relay.log" "$OUT/relay_after.log"
{
  echo "== 輪數：$r（完成 $(grep -c '結束：沒有 GPS' "$OUT/rounds.log")）"
  echo "== 每輪秒數：$(grep -o '整輪 [0-9]* 秒' "$OUT/rounds.log" | grep -o '[0-9]*' | tr '\n' ' ')"
  echo "== 橋接：塞車 $(grep -c '【塞車】' "$OUT/bridge.log")、到加油站 $(grep -c '到了停靠點' "$OUT/bridge.log")、抵達 $(grep -c '🏁' "$OUT/bridge.log")"
  echo "== 轉送（每 30 秒）：錯誤合計 $(grep -o '錯誤 [0-9]*' "$OUT/relay_after.log" | awk '{s+=$2} END {print s+0}')、OutGauge 最少 $(grep -o 'OutGauge [0-9]*' "$OUT/relay_after.log" | awk 'NR>2{print $2}' | sort -n | head -1)、位置最少 $(grep -o '位置 [0-9]*' "$OUT/relay_after.log" | awk 'NR>2{print $2}' | sort -n | head -1)"
  python3 - "$OUT/fps.csv" <<'EOF'
import csv, sys
v = sorted(float(r['fps']) for r in csv.DictReader(open(sys.argv[1])) if r['fps'])
if v: print("== FPS：樣本 %d、平均 %.1f、P5 %.1f、最低 %.1f" % (len(v), sum(v) / len(v), v[int(len(v) * 0.05)], v[0]))
EOF
  echo "== A 台："; grep -E "gpu_power_w|mem_used_gb|rss_beamng|rss_earth" "$(ls -t "$REPO"/logs/power_mem/soak_A_*_summary.txt | head -1)"
  echo "== B 台（LLM 最後幾行＋量測）："; cat "$OUT/B_summary.txt"
} | tee "$OUT/summary.txt"
say "完成"
