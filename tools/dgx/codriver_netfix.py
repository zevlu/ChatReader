# CoDriver（只在 BeamNG.Earth 的 earth-venv 裡生效）：
# 學校網路固定連不上 GitHub 的部分 IP（185.199.x.x 其中幾個），Python 的連線會在壞掉的 IP 上乾等到逾時（約 2 分鐘）。
# 這裡改寫 socket.create_connection：每個 IP 最多等 4 秒就換下一個，並記住連不上的 IP，之後排到最後。
import socket as _socket
import threading as _threading

_CAP = 4.0
_bad = set()
_lock = _threading.Lock()
_orig = _socket.create_connection
_DEFAULT = getattr(_socket, "_GLOBAL_DEFAULT_TIMEOUT", object())


def _create_connection(address, timeout=_DEFAULT, source_address=None, *args, **kwargs):
    host, port = address[0], address[1]
    try:
        infos = _socket.getaddrinfo(host, port, 0, _socket.SOCK_STREAM)
    except OSError:
        return _orig(address, timeout, source_address, *args, **kwargs)
    with _lock:
        infos.sort(key=lambda i: i[4][0] in _bad)          # 已知連不上的 IP 排到最後
    want = None if timeout is _DEFAULT else timeout
    err = None
    for family, socktype, proto, _, sa in infos:
        s = None
        try:
            s = _socket.socket(family, socktype, proto)
            s.settimeout(_CAP if want is None else min(want, _CAP))
            if source_address:
                s.bind(source_address)
            s.connect(sa)
            s.settimeout(want if timeout is not _DEFAULT else _socket.getdefaulttimeout())
            with _lock:
                _bad.discard(sa[0])
            return s
        except OSError as e:
            err = e
            with _lock:
                _bad.add(sa[0])
            if s is not None:
                s.close()
    raise err if err else OSError("getaddrinfo returns an empty list")


_socket.create_connection = _create_connection
