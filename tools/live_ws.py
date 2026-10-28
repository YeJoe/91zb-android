#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
live_ws.py — 直播间实时通道 /websock/* 客户端（纯标准库）

背景（2026-10-29 抓包 + 实测坐实）：
  直播间的弹幕、礼物、进场、在线人数、榜单一律走 /tunnel/open 这条明文长连接，
  路径是 /websock/xxx（**不是** 旧版的 /room_push/join）。

  三个硬性前提，缺一个就失败：
    1. 隧道 URL 的 ?token= 必须是 App 当前会话的 32 位 hex（存 .apptoken.txt）
       用自己的 quick-login token 会得到 {"code":-10000,"msg":"bad token, please login!"}
    2. authorization: Bearer <JWE>（.jwe.txt，App prefs 里那个 330 字符的）
    3. 端口必须是同时能跑 /index/hello 和 /websock/* 的那个 vhost
       （实测 1406 / 37549；其它端口 404 或 403）

用法:
  python tools/live_ws.py join  <room_id> [menu_id]
  python tools/live_ws.py hist  <room_id> [start_id]
  python tools/live_ws.py watch <room_id> [menu_id] [seconds]   # 进房并持续收推送
  python tools/live_ws.py leave <room_id>
  python tools/live_ws.py hello
"""
import json
import os
import sys
import time
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm          # noqa: E402
import ws_probe as wp            # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JWE_FILE = os.path.join(HERE, '.jwe.txt')
TOK_FILE = os.path.join(HERE, '.apptoken.txt')
PORT_FILE = os.path.join(HERE, '.ws_port')
VER = '20260226001'


def app_token():
    return open(TOK_FILE).read().strip()


def jwe():
    return open(JWE_FILE).read().strip()


def ws_vhosts():
    """筛出真正的业务 vhost

    隧道里同时挂着十几个上游站点，很多端口对 /index/hello 也回 200，
    单看 hello 会挑错。但只有业务 nginx（openresty/1.21.4.2）对 /websock/* 的
    **明文 POST** 回 405（它只认 WS 升级）；其它站点一律 403 / 404。
    """
    out = []
    for p in tm.listen_ports():
        try:
            tm.forward(p)
            st = tm.call(p, 'POST', '/websock/history_msg',
                         {'room_id': 0, 'start_id': 0}, timeout=2.5)[0]
        except Exception:
            continue
        if st == 405:
            out.append(p)
    return out


def connect(token=None, probe_rid=None):
    """连上能跑 /websock/* 的端口，返回 (port, socket)

    候选端口先试 .ws_port 缓存，再按 ws_vhosts() 逐个握手；
    probe_rid 非空时用一次 history_msg 确认 status=200（避开发错 vhost 拿到 404 的情况）。
    """
    t = token or app_token()
    cands = []
    if os.path.exists(PORT_FILE):
        try:
            cands.append(int(open(PORT_FILE).read().strip()))
        except ValueError:
            pass
    cands += [p for p in ws_vhosts() if p not in cands]
    for p in cands:
        try:
            tm.forward(p)
            st, hd, sk = wp.ws_handshake(
                p, "/tunnel/open?z=1&appversion=%s&token=%s" % (VER, t),
                jwe(), timeout=2.0)
        except Exception:
            continue
        if not (st and '101' in st):
            continue
        if probe_rid:
            send(sk, '/websock/history_msg', {"room_id": probe_rid, "start_id": 0}, 1001)
            resp, _ = pump(sk, 1001, 5.0)
            if (resp or {}).get('status') != 200:
                try:
                    sk.close()
                except Exception:
                    pass
                continue
        try:
            open(PORT_FILE, 'w').write(str(p))
        except Exception:
            pass
        return p, sk
    return None, None


def send(s, uri, body, rnd):
    raw = json.dumps({"body": body, "random": rnd, "method": "POST", "uri": uri},
                     separators=(',', ':')).encode()
    s.sendall(wp.mask_frame(raw, opcode=1))


def decode(d):
    try:
        return json.loads(d)
    except Exception:
        pass
    for fn in (zlib.decompress, lambda x: zlib.decompress(x, -15)):
        try:
            return json.loads(fn(d))
        except Exception:
            pass
    return None


def pump(s, want_random=None, seconds=4.0, on_push=None):
    """收帧。want_random 为 None 时把第一个 http 帧当响应（/websock/* 不带 random）"""
    resp, pushes = None, []
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            op, d = wp.read_frame(s, timeout=min(1.0, seconds))
        except Exception:
            break
        if op not in (1, 2) or not d:
            continue
        o = decode(d)
        if not isinstance(o, dict):
            continue
        if o.get('opt') == 'http':
            rnd = o['data'].get('random')
            if want_random is None or rnd == want_random:
                body = o['data'].get('body')
                try:
                    body = json.loads(body)
                except Exception:
                    pass
                resp = {'status': o['data'].get('status'), 'body': body}
                if want_random is None:
                    break
        elif o.get('opt') in ('r', 'u'):
            pushes.append(o)
            if on_push:
                on_push(o)
    return resp, pushes


def brief(o):
    """把推送压成一行可读文本"""
    ctl = o.get('ctl', '?')
    data = o.get('data')
    if ctl == 'room_msg':
        items = json.loads(data) if isinstance(data, str) else (data or [])
        out = []
        for it in items:
            c = it.get('content')
            try:
                c = json.loads(c)
            except Exception:
                pass
            txt = c.get('text') if isinstance(c, dict) and c.get('text') else (
                ('下注 %s 元 %s' % (c.get('total_amount'), c.get('cp_game_string')))
                if isinstance(c, dict) and c.get('optcode') == 19 else str(c)[:60])
            out.append('%s: %s' % (it.get('nickname'), txt))
        return 'room_msg  ' + ' | '.join(out)
    return '%s %s' % (ctl, str(data)[:160])


def cmd_join(rid, menu=1):
    p, s = connect(probe_rid=rid)
    if not s:
        raise SystemExit('握手失败')
    send(s, '/websock/join', {"room_id": rid, "menu_id": menu, "cp_type": 3}, 7001)
    resp, _ = pump(s, None, 6.0)
    s.close()
    print('端口', p, '状态', resp and resp['status'])
    b = (resp or {}).get('body') or {}
    if not isinstance(b, dict):
        print(b); return
    print('在场人数 player_count =', b.get('player_count'), ' code =', b.get('code'))
    res = b.get('result') or []
    print('返回 %d 个用户（按 weight 排序）' % len(res))
    for u in res[:10]:
        print('  %-12s lv%-4s vip%-2s 粉%d w=%-7s %s' % (
            u.get('nickname'), u.get('level'), u.get('vip_level'),
            u.get('user_fanclub_level', 0), u.get('weight'), u.get('signature', '')[:20]))
    return b


def cmd_hist(rid, start=0):
    p, s = connect(probe_rid=rid)
    if not s:
        raise SystemExit('握手失败')
    send(s, '/websock/history_msg', {"room_id": rid, "start_id": start}, 7002)
    resp, _ = pump(s, None, 6.0)
    s.close()
    b = (resp or {}).get('body') or {}
    for it in (b.get('result') or []):
        c = it.get('content')
        try:
            c = json.loads(c)
        except Exception:
            pass
        print('%-16s ct=%s opt=%s %s' % (it.get('nickname'), it.get('content_type'),
                                         it.get('optcode'), c if isinstance(c, dict) else c))


def cmd_watch(rid, menu=1, secs=30):
    p, s = connect(probe_rid=rid)
    if not s:
        raise SystemExit('握手失败')
    send(s, '/websock/join', {"room_id": rid, "menu_id": menu, "cp_type": 3}, 7003)
    print('进房 %s（端口 %s），监听 %ss…' % (rid, p, secs))
    seen = set()

    def on_push(o):
        k = json.dumps(o, sort_keys=True)[:200]
        if k in seen:
            return
        seen.add(k)
        ts = time.strftime('%H:%M:%S')
        print(ts, brief(o))

    pump(s, 7003, secs + 2, on_push)
    s.close()


def cmd_leave(rid):
    p, s = connect(probe_rid=rid)
    send(s, '/websock/leave', {"room_id": rid}, 7004)
    resp, _ = pump(s, None, 4.0)
    s.close()
    print('leave ->', resp)


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    c = sys.argv[1]
    if c == 'join':
        cmd_join(int(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else 1)
    elif c == 'hist':
        cmd_hist(int(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else 0)
    elif c == 'watch':
        cmd_watch(int(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else 1,
                  int(sys.argv[4]) if len(sys.argv) > 4 else 30)
    elif c == 'leave':
        cmd_leave(int(sys.argv[2]))
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
