#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_send2.py — 深挖 /websock/send_msg 的正确字段

已确认：/websock/send_msg 是发送弹幕的接口（其它 path 全 404）。
首轮报 `code=-1 content_type is null`，说明缺 content_type 字段。
这里枚举 content_type 的取值 + 消息体结构，直到拿到 code=0 或更明确的报错。

用法:
  python tools/probe_send2.py <room_id> [menu_id]
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import live_ws as lw  # noqa: E402


def main():
    rid = int(sys.argv[1])
    menu = int(sys.argv[2]) if len(sys.argv) > 2 else 101

    p, s = lw.connect(probe_rid=rid)
    if not s:
        raise SystemExit("握手失败")
    print("房间 %d 端口 %d" % (rid, p))

    # content_type 候选：从接收到的 room_msg 里，content_type 字段出现过 1 / 8 等值
    # （DmFilter 里 ct=1 普通聊天、ct=8 礼物/管理）。发送普通聊天大概率 ct=1。
    ct_values = [1, 2, 3, 4, 0, "1", "text", "chat"]

    # 消息体形状候选
    shapes = [
        lambda ct: {"room_id": rid, "menu_id": menu, "content_type": ct, "content": "test123"},
        lambda ct: {"room_id": rid, "menu_id": menu, "content_type": ct, "text": "test123"},
        lambda ct: {"room_id": rid, "menu_id": menu, "content_type": ct, "content": json.dumps({"text": "test123"})},
        lambda ct: {"room_id": rid, "menu_id": menu, "content_type": ct, "content": json.dumps({"typ": 32, "text": "test123"})},
        lambda ct: {"room_id": rid, "menu_id": menu, "content_type": ct, "msg": "test123", "text": "test123"},
    ]

    rnd = 9100
    for ct in ct_values:
        for si, mk in enumerate(shapes):
            b = mk(ct)
            lw.send(s, "/websock/send_msg", b, rnd)
            resp, _ = lw.pump(s, rnd, 3.0)
            st = resp.get('status') if resp else None
            body = resp.get('body') if resp else None
            if isinstance(body, dict):
                code = body.get('code')
                msg = body.get('msg') or body.get('message') or ''
            else:
                code = body; msg = str(body)
            flag = "★" if code == 0 else ("·" if code not in (None, -1) else " ")
            print("%s ct=%s shape#%d status=%s code=%s %s" % (flag, ct, si, st, code, str(msg)[:70]))
            if code == 0:
                print(">>> 命中：ct=%s shape#%d body=%s" % (ct, si, json.dumps(b, ensure_ascii=False)))
                s.close(); return
            rnd += 1
    s.close()
    print("\n未命中 code=0。下一步：根据更具体的报错（比如 -10000 bad token / 等级限制）调整。")


if __name__ == "__main__":
    main()
