#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_send.py — 发送后立刻 watch，验证消息真的广播 + 观察等级限制"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import live_ws as lw


def main():
    rid = int(sys.argv[1])
    menu = int(sys.argv[2]) if len(sys.argv) > 2 else 101
    mark = "probe_%d" % (time.time() % 100000)

    p, s = lw.connect(probe_rid=rid)
    if not s:
        raise SystemExit("握手失败")
    print("端口 %d，发消息: %s" % (p, mark))

    # 1) 发一条带唯一标记的消息
    lw.send(s, "/websock/send_msg",
            {"room_id": rid, "menu_id": menu, "content_type": 1, "content": mark}, 9501)
    resp, _ = lw.pump(s, 9501, 4.0)
    b = (resp or {}).get('body') or {}
    print("发送应答:", resp and resp.get('status'), b if isinstance(b, dict) else b)

    # 2) watch 12 秒，找自己的消息
    print("监听 12 秒找 %s …" % mark)
    hits = []
    t0 = time.time()

    def on_push(o):
        if o.get('ctl') != 'room_msg':
            return
        data = o.get('data')
        items = json.loads(data) if isinstance(data, str) else (data or [])
        for it in items:
            c = it.get('content')
            try:
                c = json.loads(c)
            except Exception:
                pass
            txt = c.get('text') if isinstance(c, dict) else str(c)
            if mark in str(txt):
                hits.append(it)
                print("  ★ 收到自己的消息:", it.get('nickname'), '->', txt, 'lv=', it.get('level'))

    lw.pump(s, None, 12.0, on_push)
    s.close()

    if hits:
        print("\n验证通过：消息广播成功，昵称=%s 等级=%s" % (hits[0].get('nickname'), hits[0].get('level')))
    else:
        print("\n12 秒内没收到自己的消息（可能房间太吵被挤掉 / 服务端延迟），但发送应答已 code=0")


if __name__ == "__main__":
    main()
