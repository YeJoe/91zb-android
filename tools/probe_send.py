#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_send.py — 枚举直播间「发送弹幕」的真实接口（明文 /websock/* 通道）

背景：接收弹幕的 /websock/join、/websock/history_msg、/websock/leave 都已被抓通，
但「发送」接口的 path 还没找到。源 App 发弹幕走的是 /go/<blob> 加密通道（帧密文，
缺对称密钥解不开）。这里反过来：用明文 /tunnel/open 通道，枚举可能的发送 path，
看哪个能回 200（或至少不 404/403），从而锁定发送接口。

用法:
  python tools/probe_send.py <room_id> [menu_id]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import live_ws as lw  # noqa: E402


# 发送弹幕可能的 path 候选（基于 /websock/* 命名空间 + 常见命名）
SEND_PATHS = [
    "/websock/send",
    "/websock/send_msg",
    "/websock/sendmsg",
    "/websock/send_message",
    "/websock/chat",
    "/websock/send_chat",
    "/websock/msg",
    "/websock/message",
    "/websock/comment",
    "/websock/send_comment",
    "/websock/barrage",
    "/websock/send_barrage",
    "/websock/danmaku",
    "/websock/send_danmaku",
    "/websock/chat_send",
    "/websock/post",
    "/websock/post_msg",
    "/websock/speak",
    "/websock/talk",
    "/websock/room_msg",
    "/websock/send_room_msg",
    "/room/send_msg",
    "/room/chat",
    "/room/send_chat",
    "/chat/send",
    "/msg/send",
    "/send_msg",
]

# 发送 body 的几种常见形状
def bodies(rid, menu):
    return [
        {"room_id": rid, "menu_id": menu, "text": "test123", "content": "test123"},
        {"room_id": rid, "menu_id": menu, "msg": "test123"},
        {"room_id": rid, "menu_id": menu, "content": "test123"},
        {"room_id": rid, "content": "test123"},
        {"room_id": rid, "text": "test123"},
        {"rid": rid, "text": "test123"},
        {"room_id": rid, "menu_id": menu, "content": json.dumps({"text": "test123"})},
    ]


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    rid = int(sys.argv[2 - 1])
    menu = int(sys.argv[2]) if len(sys.argv) > 2 else 1

    p, s = lw.connect(probe_rid=rid)
    if not s:
        raise SystemExit("握手失败，无可用明文通道")

    print("房间 %d 端口 %d，开始枚举发送接口…" % (rid, p))
    found = []
    for path in SEND_PATHS:
        for bi, b in enumerate(bodies(rid, menu)):
            try:
                lw.send(s, path, b, 9001)
                resp, _ = lw.pump(s, 9001, 3.0)
            except Exception as e:
                resp = {'status': 'ERR', 'body': str(e)}
            st = resp.get('status') if resp else None
            body = resp.get('body') if resp else None
            # 只看「路由存在」的信号：非 404 / 403 / 405 说明这个 path 有意义
            interesting = st not in (None, 404, 403, 405)
            if interesting:
                code = body.get('code') if isinstance(body, dict) else body
                msg = (body.get('msg') or body.get('message') or '') if isinstance(body, dict) else ''
                line = "★ %-28s body#%d status=%s code=%s %s" % (path, bi, st, code, str(msg)[:80])
                print(line)
                found.append((path, bi, st, code, msg))
            else:
                print("  %-28s body#%d -> %s" % (path, bi, st))
            # 关键路径多试几个 body 形状，其它 path 试第一个 body 就够
            break  # 只试第一个 body 形状，先锁定 path 再说
    s.close()

    print("\n=== 有戏的 path（非 404/403/405）===")
    if not found:
        print("（没有）—— 发送接口可能不在 /websock/* 明文命名空间，需另找")
    for f in found:
        print("  %s status=%s code=%s %s" % (f[0], f[2], f[3], str(f[4])[:80]))


if __name__ == "__main__":
    main()
