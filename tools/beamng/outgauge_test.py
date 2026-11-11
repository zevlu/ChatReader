# -*- coding: utf-8 -*-
"""
BeamNG OutGauge 測試程式（CoDriver 用）
- 收 BeamNG.drive 送出的 OutGauge UDP 封包，每秒印一行：車速、油量、檔位
- 車速低於 JAM_KMH 持續 JAM_SECONDS 秒 → 印出【塞車】（之後橋接程式會改成通知 CoDriver）

用法（Windows 命令提示字元）：
    python outgauge_test.py            # 預設聽 127.0.0.1:4444
    python outgauge_test.py 5555       # 遊戲裡設的 port 不是 4444 時，在後面加上 port
遊戲設定：Esc → 系統 → 選項 → Other（其他）→ Protocols → 打開 OutGauge，IP 127.0.0.1，port 跟這裡一樣
按 Ctrl+C 結束。
"""
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 4444
JAM_KMH = 10.0      # 低於這個時速算「走走停停」
JAM_SECONDS = 20.0  # 持續這麼久才算塞車

# LFS OutGauge 格式（92 bytes，後面可能多 4 bytes 的 ID）
# Time, Car[4], Flags, Gear, PLID, Speed, RPM, Turbo, EngTemp, Fuel, OilPressure, OilTemp,
# DashLights, ShowLights, Throttle, Brake, Clutch, Display1[16], Display2[16]
FMT = "<I4sHBB7f2I3f16s16s"
SIZE = struct.calcsize(FMT)  # 92


def gear_text(g):
    # OutGauge：0 = 倒車、1 = 空檔、2 = 1 檔 ...
    if g == 0:
        return "R"
    if g == 1:
        return "N"
    return str(g - 1)


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((HOST, PORT))
    sock.settimeout(2.0)
    print(f"等待 BeamNG OutGauge 資料：{HOST}:{PORT}（Ctrl+C 結束）")

    slow_since = None   # 開始變慢的時間
    jam_reported = False
    last_print = 0.0
    got_any = False

    while True:
        try:
            data, _ = sock.recvfrom(256)
        except socket.timeout:
            if not got_any:
                print("…還沒收到資料：遊戲有沒有開 OutGauge？port 對嗎？車子要在開（不能停在選單）")
            else:
                print("…資料中斷（遊戲暫停或在選單中）")
            continue
        except KeyboardInterrupt:
            break

        if len(data) < SIZE:
            print(f"封包長度 {len(data)} bytes，不是 OutGauge 格式，略過")
            continue
        got_any = True
        f = struct.unpack(FMT, data[:SIZE])
        gear = f[3]
        speed_kmh = f[5] * 3.6       # m/s → km/h
        rpm = f[6]
        fuel_pct = f[9] * 100.0      # 0～1 → %

        now = time.time()
        # 塞車判斷
        if speed_kmh < JAM_KMH:
            if slow_since is None:
                slow_since = now
            slow_for = now - slow_since
            if slow_for >= JAM_SECONDS and not jam_reported:
                print(f"【塞車】已經 {slow_for:.0f} 秒低於 {JAM_KMH:.0f} km/h → 之後會通知 CoDriver：「有點塞車，要不要傳訊息？」")
                jam_reported = True
        else:
            if jam_reported:
                print("【車流恢復】")
            slow_since = None
            jam_reported = False

        if now - last_print >= 1.0:
            last_print = now
            slow = f"  慢速 {now - slow_since:4.0f} 秒" if slow_since else ""
            print(f"車速 {speed_kmh:6.1f} km/h  油量 {fuel_pct:5.1f} %  檔位 {gear_text(gear):>2}  轉速 {rpm:6.0f}{slow}")

    sock.close()
    print("結束")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("結束")
    except OSError as e:
        print(f"開不了 port {PORT}：{e}（可能有別的程式在用，換一個 port）")
