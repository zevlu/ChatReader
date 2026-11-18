# CoDriver（只在 BeamNG.Earth 的 earth-venv 裡生效，由 codriver_netfix.pth 載入）：導航路線的「錄製／重播」快取。
#
# BeamNG.Earth 的 GPS 路線是世界伺服器（earthd.py 的 route() → routing.plan()）向線上 OSRM
# （routing.openstreetmap.de）要的；沒有網路時 mod 只能畫一條直線指向目的地。這裡包住 routing.plan：
#   - 有網路：照常問 OSRM，成功的結果存一份到 ~/.cache/beamng-earth/codriver-routes/（排練時開過一次就有）
#   - 失敗（--offline、斷線、OSRM 掛掉）：找「目的地相同（30 公尺內）、路線經過車子附近（150 公尺內）」的快取，
#     從離車子最近的點截斷，距離和時間按比例縮短後回傳。真人駕駛稍微偏離後重新規劃也接得上。
# 只在失敗時才用快取，有網路時行為完全不變。不改 BeamNG.Earth 的原始碼：earthd 呼叫時才去讀 routingmod.plan，
# 所以在 routing 模組載入後替換 plan 就會生效。
import importlib.abc
import json
import logging
import math
import os
import sys
import time

CACHE_DIR = os.environ.get("CODRIVER_ROUTE_CACHE") or os.path.expanduser("~/.cache/beamng-earth/codriver-routes")
DEST_M = 30.0        # 目的地要在這個距離內才算同一個
NEAR_M = 150.0       # 車子要在快取路線這個距離內才能用
log = logging.getLogger("earthd.codriver_routecache")


def _m(a, b):
    """兩個 (lat, lon) 的距離（公尺，近距離用平面近似就夠）"""
    k = 111320.0
    dx = (b[1] - a[1]) * k * math.cos(math.radians((a[0] + b[0]) / 2))
    dy = (b[0] - a[0]) * k
    return math.hypot(dx, dy)


def _length(points):
    return sum(_m(points[i], points[i + 1]) for i in range(len(points) - 1))


def _save(lat0, lon0, lat1, lon1, bearing, r):
    try:
        if not r.get("points"):
            return
        os.makedirs(CACHE_DIR, exist_ok=True)
        name = "%.5f_%.5f__%.4f_%.4f.json" % (lat1, lon1, lat0, lon0)
        tmp = os.path.join(CACHE_DIR, name + ".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"start": [lat0, lon0], "dest": [lat1, lon1], "bearing": bearing,
                       "saved": time.strftime("%Y-%m-%d %H:%M:%S"), "result": r}, f)
        os.replace(tmp, os.path.join(CACHE_DIR, name))
    except Exception as e:                      # 存不了不影響導航
        log.debug("route cache save: %s", e)


def lookup(lat0, lon0, lat1, lon1):
    """快取裡能用的路線 {points, dist, dur}（從車子附近開始），沒有就 None"""
    try:
        names = os.listdir(CACHE_DIR)
    except OSError:
        return None
    best = None                                 # (車子離路線的距離, 截斷後的路線)
    for name in names:
        if not name.endswith(".json"):
            continue
        try:
            with open(os.path.join(CACHE_DIR, name), encoding="utf-8") as f:
                c = json.load(f)
            if _m(c["dest"], (lat1, lon1)) > DEST_M:
                continue
            r = c["result"]
            pts = [tuple(p) for p in r["points"]]
            i = min(range(len(pts)), key=lambda k: _m(pts[k], (lat0, lon0)))
            d = _m(pts[i], (lat0, lon0))
            if d > NEAR_M or (best and d >= best[0]):
                continue
            rest = [(lat0, lon0)] + pts[i + 1:] if d > 5 else pts[i:]
            if len(rest) < 2:
                rest = [(lat0, lon0), pts[-1]]
            full = float(r.get("dist") or _length(pts)) or 1.0
            dist = _length(rest)
            best = (d, {"points": [[round(a, 6), round(b, 6)] for a, b in rest], "dist": dist,
                        "dur": float(r.get("dur") or 0) * dist / full})
        except Exception as e:
            log.debug("route cache read %s: %s", name, e)
    return best[1] if best else None


def _wrap(mod):
    orig = mod.plan
    if getattr(orig, "_codriver", False):
        return

    def plan(lat0, lon0, lat1, lon1, net=None, bearing=None):
        try:
            r = orig(lat0, lon0, lat1, lon1, net, bearing)
        except Exception as e:
            c = lookup(float(lat0), float(lon0), float(lat1), float(lon1))
            if c is None:
                log.info("CODRIVER_ROUTE miss (%s): no cached route to %.5f, %.5f near the car", type(e).__name__, lat1, lon1)
                raise
            log.warning("CODRIVER_ROUTE replay (%s): cached route, %d points, %.2f km", type(e).__name__,
                        len(c["points"]), c["dist"] / 1000)
            return c
        _save(float(lat0), float(lon0), float(lat1), float(lon1), bearing, r)
        return r

    plan._codriver = True
    mod.plan = plan
    log.info("CODRIVER_ROUTE cache on (%s)", CACHE_DIR)


class _Hook(importlib.abc.MetaPathFinder):
    """BeamNG.Earth 的 server/routing.py 一載入就包住它的 plan()"""

    def find_spec(self, name, path, target=None):
        if name != "routing":
            return None
        for f in sys.meta_path:
            if f is self or not hasattr(f, "find_spec"):
                continue
            spec = f.find_spec(name, path, target)
            if spec is not None:
                break
        else:
            return None
        loader = spec.loader
        if not (spec.origin or "").replace("\\", "/").endswith("/server/routing.py") or not hasattr(loader, "exec_module"):
            return spec
        orig_exec = loader.exec_module

        def exec_module(module):
            orig_exec(module)
            if hasattr(module, "plan") and hasattr(module, "OSRM_URL"):
                _wrap(module)

        loader.exec_module = exec_module
        return spec


if not any(isinstance(f, _Hook) for f in sys.meta_path):
    sys.meta_path.insert(0, _Hook())
