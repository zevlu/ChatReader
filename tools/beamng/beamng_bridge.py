# -*- coding: utf-8 -*-
"""
BeamNG → CoDriver 橋接程式（2026-11-11 里程碑 2＋3；2026-11-17 加上位置／GPS）

做的事：
  1. 收 BeamNG.drive 的 OutGauge UDP（車速、油量）→ 每秒送一次給手機上的 CoDriver：
         POST http://127.0.0.1:8765/api   {"type":"vehicle","speed":45,"fuel":72,"game":true}
     手機再把車速、油量推給網頁車機（http://localhost:8765/?unit=1）顯示。
  2. 塞車：低於 --jam-kmh 持續 --jam-sec 秒 → POST {"type":"action","name":"jam"}，
           手機 CoDriver（導航中才理會）多算 10 分鐘，會遲到就主動問要不要通知約好的人。
           車速回到門檻以上 → {"type":"action","name":"jamClear"}（只記在紀錄裡）
  3. 位置／GPS（DGX 版，遊戲裡要有 tools/dgx/beamng_mod 的 codriverPose）：
     收遊戲送來的 UDP JSON（port = OutGauge port + 1）→ 轉給手機，網頁車機畫地圖、算真實到達時間：
         {"type":"pose","lat":..,"lon":..,"heading":度,"speedKmh":..,
          "gps":{"name":..,"lat":..,"lon":..,"remainingM":..,"etaS":..,"planning":false} 或 null,
          "arrivedSeq":抵達次數,"arrivedName":".."}                                  每秒一次
         {"type":"route","version":"..","distM":..,"durS":..,"points":[[lat,lon],...]}  路線改變時、之後每 30 秒
  4. 導航同步：每秒讀手機的 GET /state，看到新的導航（nav.cmd = plan）就把目的地名稱換成座標
     （先查 places.json，查不到才問 OpenStreetMap），叫遊戲把 BeamNG.Earth 的 GPS 設過去；
     nav.cmd = cancel／done 就取消遊戲 GPS。指令送到「位置封包的來源 IP」的 OutGauge port + 2。

事前準備：
  1. 手機用 USB 接電腦，CoDriver 開著，cmd 先執行：
       adb -s R5CY21RP81B forward tcp:8765 tcp:8765
  2. BeamNG：選項 → 進階 → 協定 → OutGauge UDP 協定 開，127.0.0.1、port 4444
     （DGX：tools/dgx/start_codriver.sh 會自動設定）

用法（Anaconda Prompt 或 cmd）：
    python beamng_bridge.py
    python beamng_bridge.py --port 4444 --phone http://127.0.0.1:8765

監聽位址（2026-11-16，DGX 三台展示）：
    單機（遊戲和橋接同一台）：預設 --listen 127.0.0.1，只收本機的 OutGauge
    雙機（A 台遊戲 → B 台橋接）：B 台用 --listen 0.0.0.0，A 台 BeamNG 的 OutGauge 位址設成 B 台的 IP
    參數也可以用環境變數給（docker-compose 用）：
        CODRIVER_OUTGAUGE_LISTEN、CODRIVER_OUTGAUGE_PORT、CODRIVER_PHONE、CODRIVER_JAM_KMH、CODRIVER_JAM_SEC
    命令列參數優先於環境變數。
按 Ctrl+C 結束。只用 Python 內建的套件，不用另外安裝。
"""
import argparse
import json
import os
import select
import socket
import struct
import threading
import time
import urllib.parse
import urllib.request

# LFS OutGauge 格式（92 bytes，後面可能多 4 bytes 的 ID）
FMT = "<I4sHBB7f2I3f16s16s"
SIZE = struct.calcsize(FMT)
HERE = os.path.dirname(os.path.abspath(__file__))


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


class Places:
    """目的地名稱 → (緯度, 經度, 用的名稱)。先查 places.json（keywords 全部出現就算符合），再問 OpenStreetMap。"""

    def __init__(self, path):
        self.path, self.mtime, self.items, self.cache = path, None, [], {}
        self.reload()

    def reload(self):
        """places.json 改過就重新讀（不用重啟橋接）"""
        try:
            m = os.path.getmtime(self.path)
        except OSError:
            m = None
        if m == self.mtime:
            return
        self.mtime, self.cache = m, {}
        try:
            self.items = json.load(open(self.path, encoding="utf-8")).get("places", [])
            print(f"📒 地點表：{len(self.items)} 個地點（{self.path}）")
        except (OSError, ValueError) as e:
            self.items = []
            print(f"⚠ 讀不到 {self.path}（{e}），只用 OpenStreetMap 查地名")

    def resolve(self, dest):
        if not dest:
            return None
        self.reload()
        if dest in self.cache:
            return self.cache[dest]
        hit = None
        for p in self.items:
            kws = p.get("keywords") or [p.get("name", "")]
            if all(k and k in dest for k in kws):
                hit = (float(p["lat"]), float(p["lon"]), p.get("name", dest))
                break
        if hit is None:
            hit = self.nominatim(dest)
        self.cache[dest] = hit
        return hit

    @staticmethod
    def nominatim(dest):
        q = dest if "高雄" in dest else dest + " 高雄"
        url = ("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=tw&q="
               + urllib.parse.quote(q))
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "CoDriver-bridge/1.0"})
            with urllib.request.urlopen(req, timeout=8) as r:
                res = json.loads(r.read().decode("utf-8"))
            if res:
                return float(res[0]["lat"]), float(res[0]["lon"]), dest
        except Exception as e:
            print(f"⚠ OpenStreetMap 查不到「{dest}」（{e}）")
        return None


class NavSync(threading.Thread):
    """每秒讀手機 /state 的 nav，新導航 → 叫遊戲設 GPS；取消／完成 → 取消遊戲 GPS。"""

    def __init__(self, phone, places, game):
        super().__init__(daemon=True)
        self.phone, self.places, self.game = phone, places, game
        self.key = None          # (nav.id, cmd) 處理過的

    def run(self):
        while True:
            time.sleep(1.0)
            try:
                with urllib.request.urlopen(self.phone.rstrip("/") + "/state", timeout=2) as r:
                    nav = json.loads(r.read().decode("utf-8")).get("nav")
            except Exception:
                continue
            if not isinstance(nav, dict):
                continue
            cmd, nid = nav.get("cmd"), nav.get("id")
            key = (nid, cmd)
            if key == self.key:
                continue
            self.key = key
            if cmd == "plan":
                dest = nav.get("dest") or ""
                hit = self.places.resolve(dest)
                if hit:
                    lat, lon, name = hit
                    print(f"🧭 App 導航到「{dest}」→ 遊戲 GPS 設到 {name}（{lat:.5f}, {lon:.5f}）")
                    self.game.command({"cmd": "gps", "lat": lat, "lon": lon, "name": name})
                else:
                    print(f"⚠ 找不到「{dest}」的座標，遊戲 GPS 沒有設定（可在 places.json 加上這個地點）")
            elif cmd in ("cancel", "done"):
                print(f"🧭 App 導航{'取消' if cmd == 'cancel' else '結束'} → 取消遊戲 GPS")
                self.game.command({"cmd": "gpsClear"})


class Game:
    """送指令給遊戲裡的 codriverPose（位置封包的來源 IP，OutGauge port + 2）。"""

    def __init__(self, cmd_port):
        self.addr, self.cmd_port, self.pending = None, cmd_port, None
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def seen(self, ip):
        if self.addr != ip:
            self.addr = ip
            print(f"📍 收到 {ip} 送來的位置／GPS（遊戲指令會送回 {ip}:{self.cmd_port}）")
        if self.pending:
            self.command(self.pending)

    def command(self, obj):
        if not self.addr:
            self.pending = obj          # 還不知道遊戲在哪台：等收到第一個位置封包再送
            return
        self.pending = None
        self.sock.sendto(json.dumps(obj, ensure_ascii=False).encode("utf-8"), (self.addr, self.cmd_port))


def main():
    env = os.environ.get
    ap = argparse.ArgumentParser(description="BeamNG OutGauge → CoDriver")
    ap.add_argument("--listen", default=env("CODRIVER_OUTGAUGE_LISTEN", "127.0.0.1"),
                    help="收 OutGauge 的位址：127.0.0.1 = 只收本機（預設）；0.0.0.0 = 也收別台電腦送來的（雙機模式）")
    ap.add_argument("--port", type=int, default=int(env("CODRIVER_OUTGAUGE_PORT", "4444")),
                    help="BeamNG OutGauge 的 port（預設 4444；位置／GPS 用 +1，遊戲指令用 +2）")
    ap.add_argument("--phone", default=env("CODRIVER_PHONE", "http://127.0.0.1:8765"),
                    help="手機 CoDriver 的位址（adb forward 後是 127.0.0.1:8765）")
    ap.add_argument("--jam-kmh", type=float, default=float(env("CODRIVER_JAM_KMH", "10")),
                    help="低於這個時速算走走停停（預設 10，環境變數 CODRIVER_JAM_KMH）")
    ap.add_argument("--jam-sec", type=float, default=float(env("CODRIVER_JAM_SEC", "20")),
                    help="持續幾秒算塞車（預設 20，環境變數 CODRIVER_JAM_SEC）")
    ap.add_argument("--places", default=env("CODRIVER_PLACES", os.path.join(HERE, "places.json")),
                    help="地名 → 座標對照表（導航同步用）")
    a = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((a.listen, a.port))
    psock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)       # 位置／GPS
    psock.bind((a.listen, a.port + 1))
    print(f"橋接啟動：BeamNG OutGauge {a.listen}:{a.port}、位置 {a.listen}:{a.port + 1} → 手機 {a.phone}（Ctrl+C 結束）")
    print(f"塞車判斷：低於 {a.jam_kmh:.0f} km/h 持續 {a.jam_sec:.0f} 秒")
    sources = set()          # 收過 OutGauge 的來源 IP（雙機模式時確認是 A 台送來的）

    game = Game(a.port + 2)
    NavSync(a.phone, Places(a.places), game).start()

    fuel = FuelFilter()
    speed = 0.0
    got_game = False
    last_packet = 0.0
    last_send = 0.0
    phone_ok = None          # None = 還沒試過
    slow_since = None
    jam = False
    pose, route = None, None
    route_sent_ver, route_sent_at = None, 0.0

    sock.setblocking(False)
    psock.setblocking(False)

    def drain(s, size):
        """把佇列裡的封包全部讀出來（OutGauge 每秒 60 個；手機逾時時迴圈會變慢，只讀一個會越積越舊）"""
        out = []
        while True:
            try:
                out.append(s.recvfrom(size))
            except (BlockingIOError, InterruptedError):
                return out

    while True:
        now = time.time()
        select.select([sock, psock], [], [], 0.2)
        for data, addr in drain(sock, 256):
            if addr[0] not in sources:
                sources.add(addr[0])
                print(f"📥 收到 {addr[0]} 送來的 OutGauge")
            if len(data) >= SIZE:
                f = struct.unpack(FMT, data[:SIZE])
                speed = abs(f[5]) * 3.6                 # m/s → km/h
                fuel.feed(f[9] * 100.0, now)            # 0～1 → %
                got_game = True
                last_packet = now
        for data, addr in drain(psock, 65535):
            try:
                j = json.loads(data.decode("utf-8"))
                game.seen(addr[0])
                if j.get("t") == "pose":
                    g = j.get("gps")
                    pose = {"type": "pose", "lat": j.get("lat"), "lon": j.get("lon"), "heading": j.get("heading"),
                            "speedKmh": round((j.get("speed") or 0) * 3.6, 1),
                            "gps": {"name": g.get("name"), "lat": g.get("lat"), "lon": g.get("lon"),
                                    "remainingM": g.get("remaining"), "etaS": g.get("eta"),
                                    "planning": bool(g.get("planning"))} if isinstance(g, dict) else None,
                            "arrivedSeq": j.get("arrivedSeq", 0), "arrivedName": j.get("arrivedName", ""),
                            "_at": now}
                    if pose["arrivedSeq"] and pose["arrivedSeq"] != getattr(game, "arrived_seen", 0):
                        game.arrived_seen = pose["arrivedSeq"]
                        print(f"🏁 遊戲 GPS 抵達「{pose['arrivedName']}」")
                elif j.get("t") == "route":
                    route = {"type": "route", "version": j.get("version"), "distM": j.get("dist"),
                             "durS": j.get("dur"), "points": j.get("points") or []}
            except (ValueError, AttributeError) as e:
                print(f"⚠ 看不懂位置封包（{e}）")

        if now - last_send < 1.0:
            continue
        last_send = now

        # 位置／路線 → 手機（跟 OutGauge 分開：遊戲沒裝 codriverPose 也不影響車速油量）
        if pose and now - pose["_at"] < 3.0:
            try:
                post(a.phone, {k: v for k, v in pose.items() if k != "_at"})
                if route and (route["version"] != route_sent_ver or now - route_sent_at > 30):
                    post(a.phone, route, timeout=2.0)
                    if route["version"] != route_sent_ver:
                        print(f"🗺 路線 {route['version']}：{len(route['points'])} 點、"
                              f"{(route['distM'] or 0) / 1000:.1f} 公里 → 手機")
                    route_sent_ver, route_sent_at = route["version"], now
            except Exception:
                pass            # 手機沒連上的訊息由下面的車速區塊印

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
        gps = ""
        if pose and pose.get("gps") and pose["gps"].get("remainingM") is not None:
            gps = f"  GPS 剩 {pose['gps']['remainingM'] / 1000:.1f} 公里"
        print(f"車速 {kmh:4d} km/h  油量 {pct:3d} %  {mark}{slow}{gps}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("結束")
    except OSError as e:
        print(f"開不了 port：{e}（outgauge_test.py 或另一個橋接還開著嗎？兩個程式不能同時聽同一個 port；"
              f"--listen 的位址是這台電腦的嗎？）")
