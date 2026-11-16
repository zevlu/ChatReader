#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CoDriver：DGX Spark 功耗與記憶體量測腳本（只用 Python 標準函式庫）

用法（在 repo 根目錄）：
  python3 tools/dgx/power_mem_monitor.py --label idle   --duration 300
  python3 tools/dgx/power_mem_monitor.py --label game10 --duration 600
  python3 tools/dgx/power_mem_monitor.py --label full   --duration 3600   # 順便當 60 分鐘穩定測試
  （不給 --duration 就一直量，按 Ctrl+C 結束並印出摘要）

輸出：
  logs/power_mem/<label>_<時間>.csv          每秒一筆原始資料
  logs/power_mem/<label>_<時間>_summary.txt  最大值／平均值摘要
  （logs/ 請不要 commit）

注意：
  1. DGX Spark 的 nvidia-smi power.draw 只量「GPU」功耗，不是整台機器。
     整台的最大瓦數請用插座型電表量（DGX Spark 原廠變壓器額定 240 W）。
  2. DGX Spark 是統一記憶體，nvidia-smi 的 Memory-Usage 會顯示 Not Supported（官方說明的正常現象）。
     所以記憶體以 /proc/meminfo 的「MemTotal - MemAvailable」為準（CPU＋GPU 共用的 128 GB）。
  3. 這台 DGX 也有別組（ai-truth-lab）的容器在跑，請先量一次 idle 當基準，
     「我們用掉的量」= 各情境的值 - idle 的值。
"""

import argparse
import csv
import os
import re
import signal
import subprocess
import sys
import time
from datetime import datetime

# 程序分組：用 cmdline 的正規表示式比對，第一個符合的組優先。可用 --group 名稱=正規表示式 追加或覆寫。
DEFAULT_GROUPS = [
    ("beamng", r"BeamNG"),
    ("earth", r"(?i)earth"),
    ("llm", r"(?i)(vllm|ollama|llama[-_.]?server|sglang|tritonserver)"),
    ("bridge", r"(?i)beamng_bridge"),
    ("steam", r"(?i)steam"),
    ("sunshine", r"(?i)sunshine"),
    ("chrome", r"(?i)(chrome|chromium)"),
]

GPU_FIELDS = ["power.draw", "utilization.gpu", "temperature.gpu", "clocks.sm"]


def num(s):
    """把 nvidia-smi / sysfs 的字串轉成數字；[N/A]、Not Supported 之類回傳 None。"""
    try:
        return float(str(s).strip())
    except (TypeError, ValueError):
        return None


def read_gpu():
    """回傳 {'gpu_power_w':..., 'gpu_util':..., 'gpu_temp_c':..., 'gpu_sm_mhz':...}，失敗回 None 值。"""
    keys = ["gpu_power_w", "gpu_util_pct", "gpu_temp_c", "gpu_sm_mhz"]
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=" + ",".join(GPU_FIELDS),
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5).stdout.strip().splitlines()
        vals = [num(v) for v in out[0].split(",")] if out else []
    except Exception:
        vals = []
    vals += [None] * (len(keys) - len(vals))
    return dict(zip(keys, vals))


def read_gpu_apps():
    """nvidia-smi 列出的各程序 GPU 記憶體（MiB）。回傳 {pid: MiB}。圖形程式可能不會列出。"""
    res = {}
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-compute-apps=pid,used_memory",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5).stdout.strip().splitlines()
        for line in out:
            parts = [p.strip() for p in line.split(",")]
            if len(parts) >= 2 and parts[0].isdigit():
                v = num(parts[1])
                if v is not None:
                    res[int(parts[0])] = v
    except Exception:
        pass
    return res


def find_hwmon_power():
    """掃 /sys/class/hwmon 裡所有 power*_input（微瓦），回傳 [(欄位名, 路徑)]。"""
    sensors = []
    base = "/sys/class/hwmon"
    if not os.path.isdir(base):
        return sensors
    for hw in sorted(os.listdir(base)):
        d = os.path.join(base, hw)
        try:
            name = open(os.path.join(d, "name")).read().strip()
        except OSError:
            name = hw
        try:
            files = os.listdir(d)
        except OSError:
            continue
        for f in sorted(files):
            m = re.match(r"(power\d+)_(input|average)$", f)
            if not m:
                continue
            label = m.group(1)
            lf = os.path.join(d, m.group(1) + "_label")
            if os.path.exists(lf):
                try:
                    label = open(lf).read().strip() or label
                except OSError:
                    pass
            col = "hwmon_%s_%s_%s_w" % (name, label, m.group(2))
            col = re.sub(r"[^A-Za-z0-9_]+", "_", col)
            sensors.append((col, os.path.join(d, f)))
    return sensors


def read_hwmon(sensors):
    vals = {}
    for col, path in sensors:
        try:
            v = num(open(path).read())
            vals[col] = round(v / 1e6, 2) if v is not None else None   # 微瓦 → 瓦
        except OSError:
            vals[col] = None
    return vals


def read_meminfo():
    """回傳系統記憶體（GB）：已用、可用、總量、swap 已用。"""
    info = {}
    try:
        for line in open("/proc/meminfo"):
            k, v = line.split(":", 1)
            info[k] = float(v.split()[0]) / 1024 / 1024   # kB → GB
    except OSError:
        return {}
    total = info.get("MemTotal", 0)
    avail = info.get("MemAvailable", 0)
    return {
        "mem_used_gb": round(total - avail, 2),
        "mem_avail_gb": round(avail, 2),
        "mem_total_gb": round(total, 2),
        "swap_used_gb": round(info.get("SwapTotal", 0) - info.get("SwapFree", 0), 2),
    }


def read_cpu_times():
    try:
        parts = open("/proc/stat").readline().split()[1:]
        vals = [int(x) for x in parts]
        idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
        return sum(vals), idle
    except OSError:
        return None


def read_groups(groups, gpu_apps):
    """依 cmdline 分組加總 RSS（GB）與 nvidia-smi 列出的 GPU 記憶體（GB）。"""
    rss = {g: 0.0 for g, _ in groups}
    gmem = {g: 0.0 for g, _ in groups}
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            cmd = open("/proc/%s/cmdline" % pid, "rb").read().replace(b"\0", b" ").decode("utf-8", "replace")
        except OSError:
            continue
        if not cmd:
            continue
        grp = None
        for g, rx in groups:
            if rx.search(cmd):
                grp = g
                break
        if grp is None:
            continue
        try:
            for line in open("/proc/%s/status" % pid):
                if line.startswith("VmRSS:"):
                    rss[grp] += float(line.split()[1]) / 1024 / 1024
                    break
        except OSError:
            continue
        if int(pid) in gpu_apps:
            gmem[grp] += gpu_apps[int(pid)] / 1024
    out = {}
    for g, _ in groups:
        out["rss_%s_gb" % g] = round(rss[g], 2)
        out["gpumem_%s_gb" % g] = round(gmem[g], 2)
    return out


def summarize(rows, cols, label, started, path_txt):
    lines = []
    lines.append("CoDriver 功耗／記憶體量測摘要")
    lines.append("情境：%s" % label)
    lines.append("開始：%s　樣本數：%d（約 %d 秒）" % (started, len(rows), len(rows)))
    lines.append("")
    lines.append("%-40s %10s %10s %10s %s" % ("項目", "最大", "平均", "P95", "最大值時間"))
    for c in cols:
        if c in ("time", "elapsed_s"):
            continue
        vals = [(r[c], r["time"]) for r in rows if isinstance(r.get(c), (int, float))]
        if not vals:
            continue
        nums = sorted(v for v, _ in vals)
        if max(nums) == 0 and (c.startswith("rss_") or c.startswith("gpumem_")):
            continue   # 這組沒有程序在跑，不列
        mx, t = max(vals, key=lambda x: x[0])
        avg = sum(nums) / len(nums)
        p95 = nums[min(len(nums) - 1, int(len(nums) * 0.95))]
        lines.append("%-40s %10.2f %10.2f %10.2f %s" % (c, mx, avg, p95, t))
    lines.append("")
    lines.append("說明：gpu_power_w 只是 GPU 功耗；整台最大瓦數請用插座型電表。")
    lines.append("　　　mem_used_gb 是整台已用記憶體（含別組容器），請減掉 idle 情境的值。")
    lines.append("　　　rss_* 是各組程序常駐記憶體加總，共用頁面可能重複計算，僅供參考。")
    text = "\n".join(lines)
    with open(path_txt, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    return text


def main():
    ap = argparse.ArgumentParser(description="DGX Spark 功耗與記憶體量測")
    ap.add_argument("--label", default="test", help="情境名稱，例如 idle、game0、game10、llm、full")
    ap.add_argument("--duration", type=int, default=0, help="量幾秒；0 = 直到 Ctrl+C")
    ap.add_argument("--interval", type=float, default=1.0, help="取樣間隔秒數（預設 1）")
    ap.add_argument("--outdir", default="logs/power_mem", help="輸出資料夾")
    ap.add_argument("--group", action="append", default=[],
                    help="追加或覆寫程序分組：名稱=正規表示式（可重複）")
    args = ap.parse_args()

    groups = list(DEFAULT_GROUPS)
    for g in args.group:
        if "=" not in g:
            sys.exit("--group 格式要是 名稱=正規表示式")
        name, rx = g.split("=", 1)
        groups = [(n, r) for n, r in groups if n != name]
        groups.insert(0, (name, rx))
    groups = [(n, re.compile(r)) for n, r in groups]

    os.makedirs(args.outdir, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe = re.sub(r"[^A-Za-z0-9_-]+", "_", args.label)
    path_csv = os.path.join(args.outdir, "%s_%s.csv" % (safe, stamp))
    path_txt = os.path.join(args.outdir, "%s_%s_summary.txt" % (safe, stamp))

    sensors = find_hwmon_power()
    if sensors:
        print("找到 hwmon 功耗感測器：%s" % ", ".join(c for c, _ in sensors))
    else:
        print("沒有找到 hwmon 功耗感測器（只記錄 nvidia-smi 的 GPU 功耗）")

    stop = {"flag": False}
    signal.signal(signal.SIGINT, lambda *_: stop.update(flag=True))
    signal.signal(signal.SIGTERM, lambda *_: stop.update(flag=True))

    rows, cols, writer, fcsv = [], None, None, None
    prev_cpu = read_cpu_times()
    t0 = time.time()
    started = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print("開始量測「%s」，輸出到 %s（Ctrl+C 結束）" % (args.label, path_csv))

    try:
        while not stop["flag"]:
            tick = time.time()
            row = {"time": datetime.now().strftime("%H:%M:%S"),
                   "elapsed_s": round(tick - t0, 1)}
            row.update(read_gpu())
            row.update(read_hwmon(sensors))
            row.update(read_meminfo())
            cur = read_cpu_times()
            if prev_cpu and cur and cur[0] > prev_cpu[0]:
                dt, di = cur[0] - prev_cpu[0], cur[1] - prev_cpu[1]
                row["cpu_util_pct"] = round(100.0 * (dt - di) / dt, 1)
            else:
                row["cpu_util_pct"] = None
            prev_cpu = cur
            row.update(read_groups(groups, read_gpu_apps()))

            if writer is None:
                cols = list(row.keys())
                fcsv = open(path_csv, "w", newline="", encoding="utf-8")
                writer = csv.DictWriter(fcsv, fieldnames=cols, extrasaction="ignore")
                writer.writeheader()
            writer.writerow(row)
            fcsv.flush()
            rows.append(row)

            if len(rows) % 5 == 1:
                print("[%s] GPU %s W  GPU %s%%  CPU %s%%  記憶體已用 %s GB  BeamNG %s GB  LLM %s GB" % (
                    row["time"], row.get("gpu_power_w"), row.get("gpu_util_pct"),
                    row.get("cpu_util_pct"), row.get("mem_used_gb"),
                    row.get("rss_beamng_gb"), row.get("rss_llm_gb")))

            if args.duration and tick - t0 >= args.duration:
                break
            time.sleep(max(0.0, args.interval - (time.time() - tick)))
    finally:
        if fcsv:
            fcsv.close()

    if rows:
        print()
        print(summarize(rows, cols, args.label, started, path_txt))
        print("\n摘要已存到 %s" % path_txt)


if __name__ == "__main__":
    main()
