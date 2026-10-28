#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最小 WebSocket 客户端探测（纯标准库，无第三方依赖）

目的：验证隧道上的 /go/ 端点能否用抓到的 channel blob 建立 ws 连接。
      /go/<blob>?z=1&nobase64=1&e=1  +  authorization: Bearer <token>  +  cipher: <b64 iv>

用法:
  python tools/ws_probe.py handshake            # 全端口试握手，报 101 的端口
  python tools/ws_probe.py frames <port>        # 连上后读 30 秒二进制帧
"""
import base64
import json
import os
import socket
import ssl  # noqa: F401  (保持导入以便将来走 https)
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SESSION = os.path.join(ROOT, ".session.json")

# 2026-10-28 22:16 抓到的真实 ws 握手路径（32 字节 blob）
BLOB = "rVC3YFXZjFxA4DH10hbA8nnRJS3PLpM5irJt07ZosI0="
def _load_jwe():
    """JWE 属于会话凭证，不入库：运行时从根目录 .jwe.txt 读"""
    try:
        return open(os.path.join(ROOT, ".jwe.txt")).read().strip()
    except OSError:
        return ""


JWE = _load_jwe()


def ws_handshake(port, path, token, timeout=6.0):
    """返回 (状态行, 响应头dict, socket)；失败返回 (None, {...}, None)"""
    try:
        s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    except Exception as e:
        return None, {"error": "%s: %s" % (type(e).__name__, e)}, None
    key = base64.b64encode(os.urandom(16)).decode()
    iv = base64.b64encode(os.urandom(8)).decode()
    req = (
        "GET %s HTTP/1.1\r\n"
        "user-agent: Dart/3.10 (dart:io)\r\n"
        "connection: Upgrade\r\n"
        "cache-control: no-cache\r\n"
        "accept-encoding: gzip\r\n"
        "sec-websocket-version: 13\r\n"
        "host: 127.0.0.1:%d\r\n"
        "authorization: Bearer %s\r\n"
        "sec-websocket-extensions: permessage-deflate; client_max_window_bits\r\n"
        "sec-websocket-key: %s\r\n"
        "cipher: %s\r\n"
        "upgrade: websocket\r\n\r\n"
    ) % (path, port, token, key, iv)
    try:
        s.sendall(req.encode())
        buf = b""
        s.settimeout(timeout)
        while b"\r\n\r\n" not in buf:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
    except Exception as e:
        s.close()
        return None, {"error": "%s: %s" % (type(e).__name__, e)}, None
    head, _, rest = buf.partition(b"\r\n\r\n")
    lines = head.decode("latin1", "replace").split("\r\n")
    status = lines[0] if lines else ""
    hd = {}
    for ln in lines[1:]:
        if ":" in ln:
            k, v = ln.split(":", 1)
            hd[k.strip().lower()] = v.strip()
    hd["_leftover"] = len(rest)
    return status, hd, s


def mask_frame(payload, opcode=0x2):
    hdr = bytearray([0x80 | opcode])
    n = len(payload)
    if n < 126:
        hdr.append(0x80 | n)
    elif n < 65536:
        hdr.append(0x80 | 126)
        hdr += struct.pack(">H", n)
    else:
        hdr.append(0x80 | 127)
        hdr += struct.pack(">Q", n)
    m = os.urandom(4)
    hdr += m
    return bytes(hdr) + bytes(b ^ m[i % 4] for i, b in enumerate(payload))


def read_frame(s, timeout=8.0):
    s.settimeout(timeout)

    def recvn(n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise EOFError
            b += c
        return b

    h = recvn(2)
    op = h[0] & 0x0F
    masked = h[1] & 0x80
    ln = h[1] & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", recvn(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", recvn(8))[0]
    mk = recvn(4) if masked else None
    data = recvn(ln) if ln else b""
    if mk:
        data = bytes(b ^ mk[i % 4] for i, b in enumerate(data))
    return op, data


def cmd_handshake():
    ports = tm.listen_ports()
    print("端口", len(ports))
    ok = []
    for p in ports:
        tm.forward(p)
        for token, tname in ((JWE, "JWE"), ("", "无token")):
            st, hd, s = ws_handshake(p, "/go/%s?z=1&nobase64=1&e=1" % BLOB, token)
            if st and "101" in st:
                print(f"★ {p} [{tname}] {st}  server={hd.get('server','')}")
                ok.append((p, token))
            elif st:
                print(f"  {p} [{tname}] {st[:40]}")
            if s:
                s.close()
    print("可握手端口:", [p for p, _ in ok])
    return ok


def cmd_frames(port, token=None, seconds=25):
    token = token or JWE
    tm.forward(port)
    st, hd, s = ws_handshake(port, "/go/%s?z=1&nobase64=1&e=1" % BLOB, token)
    print("握手:", st, hd.get("server", ""))
    if not s or "101" not in (st or ""):
        print("失败，无法继续")
        return
    t0 = time.time()
    frames = []
    while time.time() - t0 < seconds:
        try:
            op, data = read_frame(s, timeout=max(1.0, seconds - (time.time() - t0)))
        except Exception as e:
            print("读帧结束:", type(e).__name__)
            break
        frames.append((op, data))
        print(f"  frame op={op} len={len(data)} {data[:60]!r}")
    s.close()
    out = os.path.join(ROOT, "ws_frames.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump([{"op": o, "len": len(d), "hex": d.hex()} for o, d in frames], f)
    print(f"{len(frames)} 帧 -> {out}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "handshake"
    if cmd == "handshake":
        cmd_handshake()
    elif cmd == "frames":
        cmd_frames(int(sys.argv[2]), sys.argv[3] if len(sys.argv) > 3 else None)
    else:
        print(__doc__)
