#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""明文 JSON 接口客户端（绕过 /go/ 路径加密）

实测结论（2026-10-28, MuMu Android15 / 渠道包 8.5.3）:
  1. App 把 HTTP 打到 127.0.0.1:<随机端口>, 由 libkiwi.so 加密后经 121.204.228.227:7001 转发;
     隧道另一端是**普通 nginx/openresty 反向代理**, 明文路径可直接访问(按路径分流到不同 vhost)。
  2. 游客登录走明文 JSON:
         POST /login/quick   {"apns_code":..., "app_name":..., "package_name":...,
                              "channel_id":..., "device_type":"android", "version":...}
     返回 {"code":0,"token":"<32位hex>", "data":{...用户资料...}}
     —— 与 8.1.7 protobuf LoginQuick 的 apnsCode 字段完全对应, 不需要 /go/ 加密路径。
  3. 后续接口带 ?token=<32hex> 即可:
         POST /room/list?token=...   {}  -> {"code":0,"has_next":1,"result":[roomId,...]}

用法:
  python tools/json_api.py ports
  python tools/json_api.py login [--device UUID] [--pkg 包名] [--channel 渠道] [--version 版本]
  python tools/json_api.py rooms [--page 1] [--size 20]
  python tools/json_api.py call <path> [json_body] [--method POST]
"""
import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm  # noqa: E402

DEFAULT_DEVICE = "29e9eb7c-b442-350b-b325-3427cd26f85b"   # prefs 里的 machine id
DEFAULT_PKG = "com.l95c2450d7.f68bb4e2d5"
DEFAULT_CHANNEL = "ecy"
DEFAULT_VERSION = "8.5.3"
CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     ".session.json")


def find_api_port(ports=None, timeout=3):
    """找到能正常应答 /login/quick 的端口（该 vhost 就是明文 JSON API 网关）"""
    ports = ports or tm.listen_ports()
    for p in ports:
        tm.forward(p)
        st, hd, raw = tm.call(p, "POST", "/login/quick", {"probe": 1}, timeout=timeout)
        if st == 200 and b"apns_code" in raw:
            return p
    return None


def login(device=DEFAULT_DEVICE, pkg=DEFAULT_PKG, channel=DEFAULT_CHANNEL,
          version=DEFAULT_VERSION, port=None):
    port = port or find_api_port()
    if not port:
        raise RuntimeError("没找到可用的隧道端口，请确认 App 正在运行")
    body = {
        "apns_code": device,
        "app_name": "live3",
        "package_name": pkg,
        "channel_id": channel,
        "device_type": "android",
        "version": version,
    }
    st, hd, raw = tm.call(port, "POST", "/login/quick", body, timeout=10)
    data = json.loads(raw)
    sess = {"port": port, "device": device, "package": pkg, "channel": channel,
            "version": version, "token": data.get("token"), "login": data}
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(sess, f, ensure_ascii=False, indent=1)
    return sess


def load_session():
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            return json.load(f)
    return None


def api(path, body=None, method="POST", token=None, port=None):
    sess = load_session() or {}
    token = token or sess.get("token")
    port = port or sess.get("port") or find_api_port()
    tm.forward(port)
    sep = "&" if "?" in path else "?"
    url = path if token is None else "%s%stoken=%s" % (path, sep, token)
    return tm.call(port, method, url, body, timeout=10)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["ports", "login", "rooms", "call", "session"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--device", default=DEFAULT_DEVICE)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--channel", default=DEFAULT_CHANNEL)
    ap.add_argument("--version", default=DEFAULT_VERSION)
    ap.add_argument("--page", type=int, default=1)
    ap.add_argument("--size", type=int, default=20)
    ap.add_argument("--method", default="POST")
    a = ap.parse_args()

    if a.cmd == "ports":
        ports = tm.listen_ports()
        print("隧道端口 %d 个: %s" % (len(ports), ports))
        api_port = find_api_port(ports)
        print("明文 JSON API 端口:", api_port)
    elif a.cmd == "login":
        sess = login(a.device, a.pkg, a.channel, a.version)
        d = sess["login"].get("data", {})
        print("端口 :", sess["port"])
        print("TOKEN:", sess["token"])
        print("昵称 :", d.get("nickname"))
        print("ID   :", d.get("id") or d.get("user_id"))
        print("渠道 :", d.get("bloc_id") or d.get("hosting_channel"))
        print("缓存 :", CACHE)
    elif a.cmd == "rooms":
        st, hd, raw = api("/room/list", {"page": a.page, "page_size": a.size})
        print("HTTP", st)
        try:
            print(json.dumps(json.loads(raw), ensure_ascii=False, indent=1)[:1200])
        except Exception:
            print(raw[:400])
    elif a.cmd == "session":
        print(json.dumps(load_session(), ensure_ascii=False, indent=1)[:2000])
    elif a.cmd == "call":
        if not a.args:
            print("需要 path")
            return
        path = a.args[0]
        body = json.loads(a.args[1]) if len(a.args) > 1 else None
        st, hd, raw = api(path, body, a.method)
        print("HTTP", st)
        print(raw.decode("utf-8", "replace")[:2000])


if __name__ == "__main__":
    main()
