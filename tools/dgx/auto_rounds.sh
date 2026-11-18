#!/bin/bash
# 無人值守的穩定性測試：每一輪＝回起點 → Earth 自動駕駛 → 塞車 60 秒 → 解除 → 開到終點（抵達標記、橫幅）
#                     → 送一輪 LLM 請求（App 真正的指令判斷提示詞，12 句）。重複到時間到。
# 用法：auto_rounds.sh <分鐘>      例：auto_rounds.sh 40
# 量功耗／記憶體請另外開 power_mem_monitor.py；這支只負責「讓系統像展示時一樣動起來」。
HERE=$(dirname "$(readlink -f "$0")")
REPO=$(cd "$HERE/../.." && pwd)
MIN=${1:-40}
END=$(( $(date +%s) + MIN * 60 ))
B="$HERE/bng"
say() { echo "[$(date +%T)] $*"; }
round=0
while [ "$(date +%s)" -lt "$END" ]; do
  round=$((round + 1))
  say "第 $round 輪：回起點"
  "$B" start > /dev/null || { say "bng start 失敗，30 秒後再試"; sleep 30; continue; }
  "$B" lua "require('beamngEarth/gps').setAutodrive(true) return 'autodrive on'" > /dev/null
  sleep 10
  say "塞車：$("$B" jam on 450)"
  sleep 75                                   # 開到車陣、被擋住、橋接送出 jam
  say "解除：$("$B" jam off)"
  for i in $(seq 1 120); do                  # 最多 6 分鐘開到終點
    "$B" gps 2>/dev/null | grep -q "沒有 GPS" && break
    sleep 3
  done
  say "抵達（或逾時）：$("$B" gps)"
  "$B" lua "require('beamngEarth/gps').setAutodrive(false) return 'autodrive off'" > /dev/null
  say "LLM：$(cd "$REPO" && python3 tools/dgx/llm_bench.py --quiet 2>&1 | grep -E '意圖正確|延遲')" | tr '\n' ' '; echo
done
say "結束：共 $round 輪"
