#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""直播房间 API 客户端（明文 JSON，走 App 自己的隧道端口）

2026-10-28 实测结论（渠道包 8.5.3 / MuMu Android 15 / channel=ecy）：

  1. 详情接口在 8.5.3 里**改名了**：旧的 `/room/get_info_by_ids` 已是 404，
     现在叫 **`POST /room/get_list_by_ids`**，返回 `{"code":0,"rooms":[Room...]}`。
  2. `ids` 必须是**逗号分隔的字符串**，不是数组：
        {"ids": "25999068,208073602"}   -> 命中
        {"ids": [25999068]}             -> rooms 为空（服务端静默忽略）
        {"ids": "[25999068,208073602]"} -> 只解析出部分 id
     传错字段名（roomIds/room_ids/...）会明确报 `{"code":-1,"msg":"ids is null"}`。
  3. **详情只要 `?token=`**；但 `/room/list` 额外要求 `authorization: Bearer <JWE>`
     —— 缺了它不会说"未登录"，而是返回 `{"code":-2,"msg":"查询类型错误"}`（误导性报错）。
  4. 响应可能带 gzip（看 content-encoding），需要解压。
  5. `/room_push/join` 被服务端版本闸拦住：`{"code":-5003,"msg":"請前往系統設定 > 更新版本"}`。

用法:
  python tools/room_api.py ports
  python tools/room_api.py list --token <32hex> [--jwe <eyJ...>]
  python tools/room_api.py detail --token <32hex> --ids 25999068,208073602
  python tools/room_api.py raw --token <32hex> /room/list '{"pageIndex":1,"menuId":0}'
"""
import argparse
import gzip
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JWE_FILE = os.path.join(ROOT, ".jwe.txt")
SESSION_FILE = os.path.join(ROOT, ".session.json")

BASE_HDR = {
    "content-type": "application/json",
    "accept-encoding": "gzip",
    "user-agent": "Dart/3.10 (dart:io)",
}


def body_of(raw):
    return gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw


def post(port, path, body, token=None, jwe=None, timeout=8):
    hdr = dict(BASE_HDR)
    if jwe:
        hdr["authorization"] = "Bearer " + jwe
    sep = "&" if "?" in path else "?"
    url = path if token is None else path + sep + "token=" + token
    st, hd, raw = tm.call(port, "POST", url, body, headers=hdr, timeout=timeout)
    return st, body_of(raw)


def find_room_port(ports=None):
    """能找到 /room/get_list_by_ids 路由的端口（nginx 404 即不在此 vhost）"""
    ports = ports or tm.listen_ports()
    for p in ports:
        try:
            tm.forward(p)
            st, raw = post(p, "/room/get_list_by_ids", {"ids": ""}, timeout=4)
        except Exception:
            continue
        if st != 200:
            continue
        try:
            d = json.loads(raw)
        except Exception:
            continue
        if isinstance(d, dict) and "code" in d:
            return p
    return None


def load_jwe():
    if os.path.exists(JWE_FILE):
        return open(JWE_FILE, encoding="utf-8").read().strip() or None
    return None


def load_token():
    if os.path.exists(SESSION_FILE):
        try:
            return json.load(open(SESSION_FILE, encoding="utf-8")).get("token")
        except Exception:
            pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["ports", "list", "detail", "raw"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--token", default=load_token())
    ap.add_argument("--jwe", default=load_jwe())
    ap.add_argument("--port", type=int)
    ap.add_argument("--menu", type=int, default=0, help="menuId，决定大厅分类")
    ap.add_argument("--page", type=int, default=1)
    ap.add_argument("--ids", default="")
    a = ap.parse_args()

    if a.cmd == "ports":
        ports = tm.listen_ports()
        print("隧道端口 %d 个: %s" % (len(ports), ports))
        print("房间 API 端口:", find_room_port(ports))
        return

    port = a.port or find_room_port()
    if not port:
        print("没找到提供房间接口的隧道端口，确认 App 正在运行")
        return
    tm.forward(port)

    if a.cmd == "list":
        if not a.token:
            print("缺 token")
            return
        if not a.jwe:
            print("[警告] 没有 JWE，/room/list 很可能回 {\"code\":-2,\"msg\":\"查询类型错误\"}")
        st, raw = post(port, "/room/list", {"pageIndex": a.page, "menuId": a.menu},
                       token=a.token, jwe=a.jwe)
        d = json.loads(raw)
        ids = d.get("result") or []
        print("端口 %d  HTTP %s  code=%s  房间 %d 个  has_next=%s"
              % (port, st, d.get("code"), len(ids), d.get("has_next")))
        print("ids:", ",".join(map(str, ids[:20])) + ("..." if len(ids) > 20 else ""))
        print("在线人数前 10:", (d.get("count_list") or [])[:10])

    elif a.cmd == "detail":
        if not a.ids:
            print("需要 --ids a,b,c")
            return
        st, raw = post(port, "/room/get_list_by_ids", {"ids": a.ids}, token=a.token)
        d = json.loads(raw)
        rooms = d.get("rooms") or []
        print("HTTP %s  code=%s  命中 %d 个房间" % (st, d.get("code"), len(rooms)))
        for r in rooms:
            print("  %-12s %-16s menu=%-4s on_air=%s 在线=%-6s 城市=%s"
                  % (r.get("id"), (r.get("name") or "")[:16], r.get("menu_id"),
                     r.get("on_air"), r.get("player_count"), r.get("city_show")))
            print("     封面: %s" % r.get("attachment_url"))
        if rooms:
            print("\n完整字段:", ", ".join(sorted(rooms[0].keys())))

    elif a.cmd == "raw":
        if not a.args:
            print("需要 path")
            return
        path = a.args[0]
        body = json.loads(a.args[1]) if len(a.args) > 1 else {}
        st, raw = post(port, path, body, token=a.token, jwe=a.jwe)
        print("HTTP", st)
        print(raw.decode("utf-8", "replace")[:2000])


if __name__ == "__main__":
    main()
