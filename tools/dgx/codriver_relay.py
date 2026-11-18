#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CoDriver A 台（遊戲台）UDP 轉送：遊戲 → 127.0.0.1 → 這支程式 → B 台（車機台）

為什麼要有它：BeamNG 內建的 LuaSocket 只准送到私有位址（127.x、10.x、172.16～31.x、192.168.x），
送到公開 IP 會回 "restricted"（2026-11-18 在 DGX 上實測）。B 台在學校公開網段時，遊戲就要先送到本機，
再由這支程式轉出去。會場如果兩台都在私有網段，遊戲可以直接送，不需要它。

轉送內容（只用 Python 標準函式庫）：
  127.0.0.1:4444（OutGauge：車速、油量）        → B 台:4444
  127.0.0.1:4445（位置／路線，從遊戲的 4446 送出） → B 台:4445
  B 台:4445 送回來的 GPS 指令                      → 遊戲（最後一個位置封包的來源，通常是 127.0.0.1:4446）
送往 B 台:4445 的封包都從同一個 socket 出去，B 台的橋接回到它的來源位址和 port，所以 A 台在 NAT 後面也收得到；
位置每秒一次，NAT 的對應不會過期。

用法：python3 tools/dgx/codriver_relay.py --to 163.18.10.131
     （start_codriver.sh 看到 codriver-dgx.conf／local.conf 有 RELAY_TO 就會自動開）
"""
import argparse
import select
import socket
import time


def main():
    ap = argparse.ArgumentParser(description="CoDriver：遊戲 UDP 轉送到 B 台")
    ap.add_argument("--to", required=True, help="B 台（車機台）的 IP")
    ap.add_argument("--port", type=int, default=4444, help="OutGauge port（位置 +1、GPS 指令 +2），預設 4444")
    ap.add_argument("--listen", default="127.0.0.1", help="收遊戲封包的位址（預設只收本機）")
    a = ap.parse_args()

    og_in = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    og_in.bind((a.listen, a.port))
    pose_in = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    pose_in.bind((a.listen, a.port + 1))
    og_out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    pose_out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)   # 位置出去、GPS 指令回來都走這個
    b_og, b_pose = (a.to, a.port), (a.to, a.port + 1)
    for s in (og_in, pose_in, og_out, pose_out):
        s.setblocking(False)

    print(f"轉送啟動：{a.listen}:{a.port}/{a.port + 1} → {a.to}:{a.port}/{a.port + 1}；"
          f"{a.to} 回來的 GPS 指令 → 遊戲", flush=True)
    game = None                    # 遊戲送位置封包的來源（GPS 指令要送回這裡）
    n = {"og": 0, "pose": 0, "cmd": 0, "err": 0}
    last_report = time.time()

    def drain(s):
        out = []
        while True:
            try:
                out.append(s.recvfrom(65535))
            except (BlockingIOError, InterruptedError):
                return out
            except OSError:         # ICMP port unreachable 等：B 台還沒開，略過
                n["err"] += 1
                return out

    while True:
        select.select([og_in, pose_in, pose_out], [], [], 1.0)
        for data, _ in drain(og_in):
            try:
                og_out.sendto(data, b_og); n["og"] += 1
            except OSError:
                n["err"] += 1
        for data, src in drain(pose_in):
            if game != src:
                game = src
                print(f"📍 遊戲的位置封包來自 {src[0]}:{src[1]}（GPS 指令會轉回這裡）", flush=True)
            try:
                pose_out.sendto(data, b_pose); n["pose"] += 1
            except OSError:
                n["err"] += 1
        for data, src in drain(pose_out):
            if src[0] != a.to:
                print(f"⚠ 忽略 {src[0]} 送來的封包", flush=True)
                continue
            if game:
                pose_in.sendto(data, game); n["cmd"] += 1
                print(f"🧭 轉回遊戲：{data[:120].decode('utf-8', 'replace')}", flush=True)
        now = time.time()
        if now - last_report >= 30:
            print(f"[{time.strftime('%H:%M:%S')}] 30 秒內：OutGauge {n['og']}、位置 {n['pose']}、"
                  f"GPS 指令 {n['cmd']}、錯誤 {n['err']}", flush=True)
            n = dict.fromkeys(n, 0)
            last_report = now


if __name__ == "__main__":
    main()
