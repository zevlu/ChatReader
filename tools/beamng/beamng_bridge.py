# -*- coding: utf-8 -*-
"""
BeamNG → CoDriver 橋接程式（2026-11-11，里程碑 2＋3）

做的事：
  收 BeamNG.drive 的 OutGauge UDP（車速、油量）→ 每秒送一次給手機上的 CoDriver：
      POST http://127.0.0.1:8765/api   {"type":"vehicle","speed":45,"fuel":72,"game":true}
  手機再把車速、油量推給網頁車機（http://localhost:8765/?unit=1）顯示。
  塞車：低於 10 km/h 持續 20 秒 → POST {"type":"action","name":"jam"}，
        手機 CoDriver（導航中才理會）多算 10 分鐘，會遲到就主動問要不要通知約好的人。
        車速回到 10 km/h 以上 → {"type":"action","name":"jamClear"}（只記在紀錄裡）

事前準備：
  1. 手機用 USB 接電腦，CoDriver 開著，cmd 先執行：
       adb -s R5CY21RP81B forward tcp:8765 tcp:8765
  2. BeamNG：選項 → 進階 → 協定 → OutGauge UDP 協定 開，127.0.0.1、port 4444

用法（Anaconda Prompt 或 cmd）：
    python beamng_bridge.py
    python beamng_bridge.py --port 4444 --phone http://127.0.0.1:8765
按 Ctrl+C 結束。只用 Python 內建的套件，不用另外安裝。
"""
import argparse
import json
import socket
import struct
import time
import urllib.request

# LFS OutGauge 格式（92 bytes，後面可能多 4 bytes 的 ID）
FMT = "<I4sHBB7f2I3f16s16s"
SIZE = struct.calcsize(FMT)


class FuelFilter:
    """
    BeamNG 按 R 修車／重生的瞬間，油量會亂跳（實測 99.9 → 14.9 → 70.8 → 99.9）。
    規則：跟目前的值差不到 2% 就直接接受（正常耗油）；
          差很多（例如在加油站加滿）要連續 2 秒都差不多（±1%）才接受。
    """

    def __init__(self):
        self.value = None
        self.cand = None
        self.cand_since = 0.0

    def feed(self, raw, now):
        if self.value is None or abs(raw - self.value) <= 2.0:
            self.value = raw
            self.cand = None
            return self.value
        if self.cand is None or abs(raw - self.cand) > 1.0:
            self.cand, self.cand_since = raw, now
        elif now - self.cand_since >= 2.0:
            self.value, self.cand = raw, None
        return self.value


def post(phone, obj, timeout=1.0):
    req = urllib.request.Request(
        phone.rstrip("/") + "/api",
        data=json.dumps(obj, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "text/plain"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status == 200


def main():
    ap = argparse.ArgumentParser(description="BeamNG OutGauge → CoDriver")
    ap.add_argument("--port", type=int, default=4444, help="BeamNG OutGauge 的 port（預設 4444）")
    ap.add_argument("--phone", default="http://127.0.0.1:8765", help="手機 CoDriver 的位址（adb forward 後是 127.0.0.1:8765）")
    ap.add_argument("--jam-kmh", type=float, default=10.0, help="低於這個時速算走走停停")
    ap.add_argument("--jam-sec", type=float, default=20.0, help="持續幾秒算塞車")
    a = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", a.port))
    sock.settimeout(1.0)
    print(f"橋接啟動：BeamNG OutGauge 127.0.0.1:{a.port} → 手機 {a.phone}（Ctrl+C 結束）")

    fuel = FuelFilter()
    speed = 0.0
    got_game = False
    last_packet = 0.0
    last_send = 0.0
    phone_ok = None          # None = 還沒試過
    slow_since = None
    jam = False

    while True:
        now = time.time()
        try:
            data, _ = sock.recvfrom(256)
            if len(data) >= SIZE:
                f = struct.unpack(FMT, data[:SIZE])
                speed = abs(f[5]) * 3.6                 # m/s → km/h
                fuel.feed(f[9] * 100.0, now)            # 0～1 → %
                got_game = True
                last_packet = now
        except socket.timeout:
            pass

        if now - last_send < 1.0:
            continue
        last_send = now

        if not got_game or now - last_packet > 3.0:
            print("…沒有收到 BeamNG 的資料（遊戲在選單／暫停中？OutGauge 有開嗎？port 對嗎？）")
            continue

        kmh, pct = int(round(speed)), int(round(fuel.value))

        # 塞車判斷 → 通知手機（手機只在導航中理會）
        event = None
        if kmh < a.jam_kmh:
            slow_since = slow_since or now
            if not jam and now - slow_since >= a.jam_sec:
                jam = True
                event = "jam"
                print(f"【塞車】已經 {now - slow_since:.0f} 秒低於 {a.jam_kmh:.0f} km/h → 通知副駕")
        else:
            if jam:
                event = "jamClear"
                print("【車流恢復】")
            slow_since, jam = None, False
        if event:
            try:
                post(a.phone, {"type": "action", "name": event})
            except Exception as e:
                print(f"❌ 塞車通知沒送到手機（{e}）")

        try:
            ok = post(a.phone, {"type": "vehicle", "speed": kmh, "fuel": pct, "game": True})
            if phone_ok is not True:
                print("✅ 手機收到了" if ok else "⚠ 手機回應錯誤")
            phone_ok = ok
            mark = "→ 手機"
        except Exception as e:
            if phone_ok is not False:
                print(f"❌ 連不到手機（{e}）：CoDriver 有開嗎？adb forward 做了嗎？")
            phone_ok = False
            mark = "✗ 手機沒連上"
        slow = f"  慢速 {now - slow_since:3.0f} 秒" if slow_since else ""
        print(f"車速 {kmh:4d} km/h  油量 {pct:3d} %  {mark}{slow}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("結束")
    except OSError as e:
        print(f"開不了 port：{e}（outgauge_test.py 還開著嗎？兩個程式不能同時聽同一個 port）")
