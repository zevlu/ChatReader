#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CoDriver：本機 LLM（codriver-llm）連續請求測試（只用 Python 標準函式庫）

送出的請求跟 App 一樣：
  - 提示詞直接從 App 原始碼 DriveBrain.kt 讀 ROUTER_PROMPT（指令判斷器），使用者訊息格式同 DriveBrain.route()
  - POST {base}/chat/completions，model = 伺服器 /v1/models 的第一個 id（或 --model）、temperature 0.2、
    chat_template_kwargs.enable_thinking = false（同 LlmClient.kt）
每句話都有預期的意圖（intent），最後算正確率、延遲。

用法（在 repo 根目錄）：
  python3 tools/dgx/llm_bench.py                         # 每句送一次，印出回答與對錯
  python3 tools/dgx/llm_bench.py --duration 300 --quiet  # 量 llm 情境時，同時開 power_mem_monitor.py
  python3 tools/dgx/llm_bench.py --base http://127.0.0.1:8300/v1 --key 你的金鑰
"""
import argparse
import json
import os
import re
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DRIVE_BRAIN = os.path.join(HERE, "..", "..", "app", "src", "drive", "java", "com", "example", "chatreader", "DriveBrain.kt")

# (駕駛說的話, 可接受的意圖) —— 依 DriveBrain.kt ROUTER_PROMPT 的定義；目前行程：前往跟孟澤約的餐廳
CASES = [
    ("帶我去跟孟澤約的地方", {"appointment"}),
    ("跟孟澤說我會晚八分鐘", {"late"}),
    ("先去加油站再去高雄車站", {"stop"}),
    ("還要多久會到", {"eta"}),
    ("講個笑話", {"joke"}),
    ("跟澤說我到了", {"send"}),
    ("好想睡喔陪我聊聊", {"talk"}),
    ("導航到最近的停車場", {"go", "stop"}),
    ("記一下右邊那家咖啡廳", {"save_place"}),
    ("我到了，結束旅程", {"end_trip"}),
    ("回到車機", {"home"}),
    ("今天天氣怎麼樣", {"chat"}),
]


def load_router_prompt(path):
    """從 DriveBrain.kt 取出 ROUTER_PROMPT 的內容（Kotlin 的 \"\"\"...\"\"\".trimIndent()）。"""
    src = open(path, encoding="utf-8").read()
    m = re.search(r'ROUTER_PROMPT\s*=\s*"""(.*?)"""\.trimIndent\(\)', src, re.S)
    if not m:
        raise SystemExit("在 %s 找不到 ROUTER_PROMPT" % path)
    lines = m.group(1).split("\n")
    body = [l for l in lines if l.strip()]
    indent = min(len(l) - len(l.lstrip()) for l in body)          # 同 Kotlin trimIndent
    return "\n".join(l[indent:] for l in lines).strip("\n")


def ask(base, key, model, system, utterance, timeout):
    user = ("【現在時間】" + time.strftime("%Y/%m/%d（%a）%H:%M") + "\n"
            "【聯絡人】孟澤、澤、小美\n"
            "【目前行程】前往駁二大魯閣餐廳（跟孟澤約 19:00）\n"
            "【駕駛說】" + utterance)
    body = {
        "model": model,
        "temperature": 0.2,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "chat_template_kwargs": {"enable_thinking": False},
    }
    req = urllib.request.Request(base.rstrip("/") + "/chat/completions",
                                 data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json; charset=utf-8",
                                          "Authorization": "Bearer " + (key or "none")},
                                 method="POST")
    t = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read().decode("utf-8"))
    dt = time.time() - t
    content = data["choices"][0]["message"].get("content", "").strip()
    usage = data.get("usage") or {}
    intent = None
    s, e = content.find("{"), content.rfind("}")          # 同 LlmClient.extractJson：第一個 { 到最後一個 }
    if s >= 0 and e > s:
        try:
            intent = json.loads(content[s:e + 1]).get("intent")
        except ValueError:
            intent = None
    return dt, intent, content, usage.get("prompt_tokens"), usage.get("completion_tokens")


def main():
    ap = argparse.ArgumentParser(description="codriver-llm 連續請求測試（用 App 真正的提示詞）")
    ap.add_argument("--base", default="http://127.0.0.1:8300/v1", help="OpenAI 相容 API 位址")
    ap.add_argument("--key", default="", help="API 金鑰（codriver-llm 沒設金鑰就不用給）")
    ap.add_argument("--model", default="", help="模型 id（不給就用伺服器 /v1/models 的第一個）")
    ap.add_argument("--duration", type=int, default=0, help="連續送幾秒（0 = 每句送 --rounds 輪）")
    ap.add_argument("--rounds", type=int, default=1, help="--duration 為 0 時，全部句子送幾輪")
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--prompt-file", default=DRIVE_BRAIN, help="讀 ROUTER_PROMPT 的 Kotlin 檔")
    ap.add_argument("--quiet", action="store_true", help="不印每次的回答內容")
    a = ap.parse_args()

    if not a.model:
        req = urllib.request.Request(a.base.rstrip("/") + "/models", headers={"Authorization": "Bearer " + (a.key or "none")})
        with urllib.request.urlopen(req, timeout=10) as r:
            a.model = json.loads(r.read().decode("utf-8"))["data"][0]["id"]
    print("模型：%s（%s）" % (a.model, a.base))
    system = load_router_prompt(a.prompt_file)
    print("提示詞：DriveBrain.kt ROUTER_PROMPT（%d 字）" % len(system))

    lat, right, wrong, bad, errors, ptoks, ctoks = [], 0, 0, 0, 0, [], []
    t0, i = time.time(), 0
    while True:
        if a.duration and time.time() - t0 >= a.duration:
            break
        if not a.duration and i >= a.rounds * len(CASES):
            break
        u, want = CASES[i % len(CASES)]
        i += 1
        try:
            dt, intent, content, pt, ct = ask(a.base, a.key, a.model, system, u, a.timeout)
            lat.append(dt)
            if pt: ptoks.append(pt)
            if ct: ctoks.append(ct)
            if intent is None:
                bad += 1; mark = "⚠ 不是合法 JSON"
            elif intent in want:
                right += 1; mark = "✅ " + intent
            else:
                wrong += 1; mark = "❌ %s（應為 %s）" % (intent, "／".join(sorted(want)))
            print(f"[{time.strftime('%H:%M:%S')}] {dt:5.2f} 秒 {mark}  {u}" +
                  ("" if a.quiet else f"\n    → {content[:160]}"), flush=True)
        except Exception as e:
            errors += 1
            print(f"[{time.strftime('%H:%M:%S')}] ❌ 請求失敗 {u}：{e}", flush=True)
            time.sleep(2)

    if lat:
        s = sorted(lat)
        p95 = s[min(len(s) - 1, int(len(s) * 0.95))]
        n = right + wrong + bad
        print(f"\n共 {len(lat)} 次成功、{errors} 次失敗")
        print(f"意圖正確 {right}/{n}（{100.0 * right / n:.0f}%）、判錯 {wrong}、不是合法 JSON {bad}")
        print(f"延遲：平均 {sum(s) / len(s):.2f} 秒、最快 {s[0]:.2f}、P95 {p95:.2f}、最慢 {s[-1]:.2f}")
        if ptoks and ctoks:
            print(f"平均輸入 {sum(ptoks) / len(ptoks):.0f} tokens、輸出 {sum(ctoks) / len(ctoks):.0f} tokens"
                  f"，輸出速度約 {sum(ctoks) / sum(lat):.1f} tokens/秒（含預填）")


if __name__ == "__main__":
    main()
