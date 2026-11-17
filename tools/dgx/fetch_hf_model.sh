#!/bin/bash
# 從 Hugging Face 下載模型（給學校這種會斷線、DNS 不穩的網路用）
#   - 每個檔案用 curl -C - 續傳：斷線後從斷掉的位置接著下載，不會從頭來
#   - DNS 失敗、逾時都自動重試（每個檔案最多 100 次，間隔 10 秒）
#   - 大檔案同時下載，下載完比對檔案大小
# 用法：fetch_hf_model.sh <Hugging Face 模型名> <目的資料夾>
#   例：tools/dgx/fetch_hf_model.sh Qwen/Qwen3-8B ~/codriver-models/Qwen3-8B
# 只下載公開模型（不需要 Hugging Face 帳號）。
set -u
REPO=$1; DEST=$2
mkdir -p "$DEST"
api="https://huggingface.co/api/models/$REPO?blobs=true"
list=$(curl -sf --retry 30 --retry-all-errors --retry-delay 10 --max-time 60 "$api" | python3 -I -c "
import json, sys
d = json.load(sys.stdin)
print(d['sha'])
for s in d['siblings']:
    print(s['rfilename'], s.get('size') or 0)") || { echo "取不到 $REPO 的檔案清單"; exit 1; }
REV=$(echo "$list" | head -1)
echo "$REPO 版本 $REV → $DEST"

get() {   # 檔名 大小
  local f=$1 size=$2 out="$DEST/$1"
  mkdir -p "$(dirname "$out")"
  if [ -f "$out" ] && [ "$(stat -c %s "$out")" = "$size" ]; then echo "已完成：$f"; return 0; fi
  curl -sL -C - --retry 100 --retry-all-errors --retry-delay 10 --connect-timeout 20 --speed-limit 10240 --speed-time 60 \
       -o "$out" "https://huggingface.co/$REPO/resolve/$REV/$f"
  if [ "$(stat -c %s "$out" 2>/dev/null)" = "$size" ]; then echo "✅ $f"; else echo "❌ $f（大小不符，重跑這個腳本會接著下載）"; return 1; fi
}

pids=()
while read -r f size; do
  get "$f" "$size" &
  pids+=($!)
done <<< "$(echo "$list" | tail -n +2)"
fail=0; for p in "${pids[@]}"; do wait "$p" || fail=1; done
[ $fail = 0 ] && echo "全部下載完成：$(du -sh "$DEST" | cut -f1)" || { echo "有檔案沒下載完，請重跑"; exit 1; }
