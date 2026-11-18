#!/bin/bash
# 依展示分工量功耗／記憶體（無人值守，約 90 分鐘）：
#   1. full   單機全開（遊戲＋10 台交通車＋塞車＋35B LLM＋橋接），自動跑輪   30 分鐘
#   2. roleA  A 台角色：只有遊戲（LLM 容器停掉），自動跑輪                   15 分鐘
#   3. idle   基準：遊戲、Earth、Steam、LLM 都關（別組容器照常）              3 分鐘
#   4. roleB  B 台角色：只有 LLM＋橋接；前 20 分鐘每 10 秒一句（展示頻率），
#             後 10 分鐘 4 個連線連續發（壓力測試）                         30 分鐘
#   最後重新開遊戲（start_codriver.sh），回到平常的狀態。
# 用法（repo 根目錄）：tools/dgx/measure_roles.sh [full分鐘] [roleA分鐘] [idle分鐘] [roleB展示分鐘] [roleB壓力分鐘]
# 輸出：logs/power_mem/<情境>_*_summary.txt、~/codriver-dgx/logs/roles_<時間>/（跑輪、LLM、FPS）
HERE=$(dirname "$(readlink -f "$0")")
REPO=$(cd "$HERE/../.." && pwd)
cd "$REPO"
FULL=${1:-30}; ROLEA=${2:-15}; IDLE=${3:-3}; BDEMO=${4:-20}; BSTRESS=${5:-10}
OUT=$HOME/codriver-dgx/logs/roles_$(date +%m%d_%H%M); mkdir -p "$OUT"
MGROUPS=(--group 'beamng=BinLinux/BeamNG\.drive\.x64' --group 'codriver_llm=vllm serve|VLLM::EngineCore')
say() { echo "[$(date +%T)] $*"; }

# 遊戲 FPS 取樣：每 5 秒取遊戲 log 最新一行 CODRIVER_FPS，寫成「時間,平均 FPS,最差一格毫秒」
fps_sampler() {   # $1=輸出檔 $2=秒數
  local end=$(( $(date +%s) + $2 )) log
  log=$(ls -t "$HOME"/codriver-dgx/logs/game_*.log 2>/dev/null | head -1)
  echo "time,fps,worst_ms" > "$1"
  while [ "$(date +%s)" -lt "$end" ]; do
    grep -a CODRIVER_FPS "$log" | tail -1 | sed -E "s/.*avg=([0-9.]+) worst_ms=([0-9.]+).*/$(date +%T),\1,\2/" >> "$1"
    sleep 5
  done
}
fps_stats() { python3 - "$1" <<'EOF'
import csv,sys
v=sorted(float(r['fps']) for r in csv.DictReader(open(sys.argv[1])) if r['fps'])
if v: print("FPS 樣本 %d：平均 %.1f、P5 %.1f、最低 %.1f、最高 %.1f"%(len(v),sum(v)/len(v),v[int(len(v)*0.05)],v[0],v[-1]))
EOF
}
monitor() {       # $1=label $2=秒數
  python3 -u tools/dgx/power_mem_monitor.py --label "$1" --duration "$2" "${MGROUPS[@]}" > "$OUT/pm_$1.out" 2>&1
}
wait_llm() {
  for i in $(seq 1 120); do curl -sf -m 3 http://127.0.0.1:8300/v1/models > /dev/null && return 0; sleep 5; done; return 1
}

say "輸出資料夾：$OUT"

# ---- 1. full ----
pgrep -f '[B]inLinux/BeamNG.drive.x64' > /dev/null || { say "遊戲沒開，先 start_codriver.sh"; "$HERE/start_codriver.sh" >> "$OUT/start.log" 2>&1; }
docker compose start codriver-llm > /dev/null 2>&1; wait_llm || say "⚠ LLM 沒有回應"
say "1. full（$FULL 分鐘）"
monitor full $((FULL*60)) & M=$!
fps_sampler "$OUT/fps_full.csv" $((FULL*60)) & F=$!
"$HERE/auto_rounds.sh" "$FULL" > "$OUT/rounds_full.log" 2>&1
wait $M $F; say "full：$(fps_stats "$OUT/fps_full.csv")"

# ---- 2. roleA ----
say "2. roleA：停 LLM 容器（$ROLEA 分鐘）"
docker compose stop codriver-llm > /dev/null 2>&1; sleep 30
monitor roleA $((ROLEA*60)) & M=$!
fps_sampler "$OUT/fps_roleA.csv" $((ROLEA*60)) & F=$!
"$HERE/auto_rounds.sh" "$ROLEA" > "$OUT/rounds_roleA.log" 2>&1
wait $M $F; say "roleA：$(fps_stats "$OUT/fps_roleA.csv")"

# ---- 3. idle ----
say "3. idle：關遊戲、Earth、Steam（$IDLE 分鐘）"
"$HERE/stop_codriver.sh" > /dev/null 2>&1
(cd ~ && timeout 60 snap run steam -shutdown > /dev/null 2>&1)
for i in $(seq 1 30); do pgrep -f '[s]teamrt64/steam' > /dev/null || break; sleep 2; done
p=$(pgrep -f '[s]teamrt64/steam'); [ -n "$p" ] && kill $p
sleep 60
monitor idle $((IDLE*60))

# ---- 4. roleB ----
say "4. roleB：啟動 LLM（約 5 分鐘）"
docker compose start codriver-llm > /dev/null 2>&1
wait_llm || say "⚠ LLM 沒有起來，請看 docker compose logs codriver-llm"
sleep 20
say "roleB：展示頻率 $BDEMO 分鐘＋壓力 $BSTRESS 分鐘"
monitor roleB $(((BDEMO+BSTRESS)*60)) & M=$!
python3 -u tools/dgx/llm_bench.py --duration $((BDEMO*60)) --interval 10 --quiet > "$OUT/llm_roleB_demo.log" 2>&1
python3 -u tools/dgx/llm_bench.py --duration $((BSTRESS*60)) --concurrency 4 --quiet > "$OUT/llm_roleB_stress.log" 2>&1
wait $M
say "roleB 展示頻率：$(tail -4 "$OUT/llm_roleB_demo.log" | tr '\n' ' ')"
say "roleB 壓力：$(tail -4 "$OUT/llm_roleB_stress.log" | tr '\n' ' ')"

# ---- 回到平常狀態 ----
say "重新開遊戲..."
"$HERE/start_codriver.sh" >> "$OUT/start.log" 2>&1
say "全部完成"
