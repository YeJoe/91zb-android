#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隧道端口 -> 上游服务 映射探测器

背景（实测）:
  App 把 HTTP 打到 127.0.0.1:<随机端口>, 由 libkiwi.so 加密后经 7001 转发。
  每个监听端口对应隧道里的**一个上游虚拟主机**, 用 adb forward 映射到 PC 后可直接访问,
  **不需要** /go/ 加密路径 —— 明文 REST 路径就能拿到 200。

用法:
  python tools/tunnel_map.py ports                     # 列出设备上的隧道端口
  python tools/tunnel_map.py scan                      # 逐端口探测, 输出端口->上游画像
  python tools/tunnel_map.py call <port> <method> <path> [json_body]
"""
import json
import os
import subprocess
import sys
import http.client

PKG = "com.l95c2450d7.f68bb4e2d5"


def _pick_serial():
    """自动挑第一台 online 设备。写死序列号在换模拟器/换真机后必然失效
    (之前固定成 127.0.0.1:16384, 设备变成 emulator-5554 后所有 adb 调用都空)。"""
    env = os.environ.get("ADB_SERIAL")
    if env:
        return env
    try:
        out = subprocess.run(["adb", "devices"], capture_output=True,
                             text=True, timeout=15).stdout
    except (OSError, subprocess.SubprocessError):
        return "127.0.0.1:16384"
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 2 and cols[1] == "device":
            return cols[0]
    return "127.0.0.1:16384"


DEVICE = _pick_serial()


def adb(*args):
    return subprocess.run(["adb", "-s", DEVICE] + list(args),
                          capture_output=True, text=True).stdout


def listen_ports():
    # 只让 su -c 负责 cat（引号不易被 adb 拆坏），过滤在 PC 端做。
    # 0A = TCP LISTEN 状态。
    out = adb("shell", "su -c 'cat /proc/net/tcp 2>/dev/null; cat /proc/net/tcp6 2>/dev/null'")
    ports = set()
    for line in out.splitlines():
        if " 0A " not in line:
            continue
        cols = line.split()
        if len(cols) < 2:
            continue
        hexport = cols[1].rsplit(":", 1)[-1]
        try:
            ports.add(int(hexport, 16))
        except ValueError:
            pass
    return sorted(ports)


def forward(port):
    subprocess.run(["adb", "-s", DEVICE, "forward",
                    "tcp:%d" % port, "tcp:%d" % port],
                   capture_output=True)


def call(port, method, path, body=None, headers=None, timeout=6):
    h = {"user-agent": "Dart/3.10 (dart:io)"}
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        h["content-type"] = "application/json"
    if headers:
        h.update(headers)
    try:
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        c.request(method, path, body=data, headers=h)
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r.status, dict(r.getheaders()), raw
    except Exception as e:
        return None, {}, ("%s: %s" % (type(e).__name__, e)).encode()


PROBES = [
    ("POST", "/room/list", {"pageIndex": 1, "menuId": 0}),
    ("POST", "/login/hello", {}),
    ("POST", "/login/init", {}),
    ("GET", "/game_api/get_game_info?token=x", None),
    ("GET", "/login/hello", None),
]


def scan():
    ports = listen_ports()
    print("隧道端口: %s" % ports)
    rows = []
    for p in ports:
        forward(p)
        prof = []
        for method, path, body in PROBES:
            st, hd, raw = call(p, method, path, body, timeout=4)
            prof.append(st)
        server = ""
        st, hd, raw = call(p, "POST", "/room/list", {"pageIndex": 1, "menuId": 0}, timeout=4)
        server = hd.get("Server", "")
        rows.append((p, prof, server, raw[:60]))
        print("  %-6d %s  %-24s %r" % (p, prof, server, raw[:60]))
    return rows


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "ports":
        print(listen_ports())
    elif cmd == "scan":
        scan()
    elif cmd == "call":
        port = int(sys.argv[2])
        method = sys.argv[3]
        path = sys.argv[4]
        body = json.loads(sys.argv[5]) if len(sys.argv) > 5 else None
        forward(port)
        st, hd, raw = call(port, method, path, body, timeout=10)
        print("HTTP", st)
        for k, v in hd.items():
            print("  %s: %s" % (k, v))
        print(raw.decode("utf-8", "replace"))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
