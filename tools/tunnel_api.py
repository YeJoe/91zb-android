#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tunnel_api.py — 走「明文长连接通道」调用天美 API（纯标准库）

背景（8.5.3 实测，2026-10-29）：
  App 内部有两类隧道通道：
    · /tunnel/open?z=1&appversion=..&token=..   ← 明文！帧是 JSON，响应体 zlib 压缩
    · /tunnel/<hash>?z=1&nobase64=1&token=..    ← 二进制密文
    · /go/<blob>?z=1&nobase64=1&e=1             ← 二进制密文

  /tunnel/open 上跑的是一个「HTTP 代理」协议：
    请求帧  {"body":{...},"random":N,"method":"POST","uri":"/xxx"}
    响应帧  {"opt":"http","data":{"status":200,"random":N,"body":"<json字符串>"}}
    推送帧  {"opt":"u","ctl":"room","data":{...}} / {"opt":"r","ctl":"config","data":"..."}

  因此像 /login/init 这种「仅支持长连接」的接口，必须走这里而不是普通 HTTP。

用法:
  python tools/tunnel_api.py init                  # 拉启动数据（相当于 App 冷启动首包）
  python tools/tunnel_api.py call /room/list '{"pageIndex":1,"menuId":0}'
  python tools/tunnel_api.py rooms                 # 在线房间
  python tools/tunnel_api.py live                  # 只列可拉流的真人直播间
  python tools/tunnel_api.py play <room_id>        # 打印 / 播放地址
"""
import json
import os
import re
import sys
import time
import subprocess
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm          # noqa: E402
import ws_probe as wp            # noqa: E402
import ws_capture as wc          # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SESS = os.path.join(HERE, '.session.json')
JWE_FILE = os.path.join(HERE, '.jwe.txt')
DOMAIN_FILE = os.path.join(HERE, '.rtmp_domain.txt')


def token():
    return json.load(open(SESS, encoding='utf-8'))['token']


def jwe():
    return open(JWE_FILE).read().strip() if os.path.exists(JWE_FILE) else ''


def unmask(payload_zlib):
    n, out = wc.decodings(payload_zlib)
    return out if out is not None else payload_zlib


def open_channel(timeout=3.5):
    """找一个能对 /tunnel/open 握手成功的端口，返回 (port, socket)"""
    t = token()
    for p in tm.listen_ports():
        try:
            tm.forward(p)
            st, hd, s = wp.ws_handshake(
                p, "/tunnel/open?z=1&appversion=20260226001&token=" + t,
                jwe(), timeout=timeout)
        except Exception:
            continue
        if st and '101' in st:
            return p, s
    return None, None


def send(s, uri, body, rnd):
    frame = json.dumps({"body": body, "random": rnd, "method": "POST",
                        "uri": uri}, separators=(',', ':')).encode()
    s.sendall(wp.mask_frame(frame, opcode=1))


def collect(s, rnd, wait=4.0):
    """收集响应；返回 (http_body_json | None, [ctl 帧...])"""
    resp, ctl = None, []
    t0 = time.time()
    while time.time() - t0 < wait:
        try:
            op, d = wp.read_frame(s, timeout=max(0.4, wait - (time.time() - t0)))
        except Exception:
            break
        if op not in (1, 2) or not d:
            continue
        try:
            obj = json.loads(unmask(d))
        except Exception:
            continue
        if isinstance(obj, dict) and obj.get('opt') == 'http':
            data = obj.get('data', {})
            if data.get('random') == rnd:
                try:
                    resp = json.loads(data.get('body', 'null'))
                except Exception:
                    resp = data.get('body')
        elif isinstance(obj, dict) and obj.get('opt') in ('r', 'u'):
            ctl.append(obj)
    return resp, ctl


def call(uri, body=None, wait=4.0, port=None):
    p, s = open_channel()
    if not s:
        raise SystemExit('没有端口能对 /tunnel/open 握手（App 没跑？token 过期？）')
    try:
        send(s, uri, body or {}, 71)
        return collect(s, 71, wait)
    finally:
        s.close()


def cmd_init():
    p, s = open_channel()
    if not s:
        raise SystemExit('握手失败')
    print('通道端口', p)
    send(s, '/login/init', {}, 91)
    resp, ctl = collect(s, 91, wait=6)
    s.close()
    out = os.path.join(HERE, 'login_init.json')
    blob = json.dumps({'http': resp, 'ctl': ctl}, ensure_ascii=False)
    open(out, 'w', encoding='utf-8').write(blob)
    print('已保存', out, '(%d 字节)' % len(blob))
    if resp:
        print('顶层键:', list(resp.keys()))
    for c in ctl:
        ct = c.get('ctl', '?')
        d = c.get('data')
        n = len(d) if isinstance(d, (list, dict)) else '?'
        print('  ctl=%-14s 条目=%s' % (ct, n))


def _rooms():
    t = token()
    import gzip
    p = None
    # room/list 走普通 HTTP 更快
    for q in tm.listen_ports():
        try:
            tm.forward(q)
            H = {"content-type": "application/json", "accept-encoding": "gzip",
                 "user-agent": "Dart/3.10 (dart:io)", "authorization": "Bearer " + jwe()}
            st, hd, raw = tm.call(q, "POST", "/room/list?token=" + t,
                                  {"pageIndex": 1, "menuId": 0}, headers=H, timeout=5)
            if raw[:2] == b'\x1f\x8b':
                raw = gzip.decompress(raw)
            d = json.loads(raw)
            if d.get('code') == 0:
                p = q
                ids = ','.join(str(x) for x in d['result'][:50])
                st, hd, raw = tm.call(q, "POST", "/room/get_list_by_ids?token=" + t,
                                      {"ids": ids}, headers=H, timeout=10)
                if raw[:2] == b'\x1f\x8b':
                    raw = gzip.decompress(raw)
                return json.loads(raw).get('rooms', [])
        except Exception:
            continue
    return []


def cmd_rooms():
    rs = _rooms()
    print('%-12s %-16s %-5s %-6s %-6s %s' % ('id', 'name', 'menu', 'chess', 'live', '可拉流'))
    for r in rs:
        ok = r.get('chess_id') == 3 and (r.get('menu_id') or 0) >= 100
        print('%-12s %-16s %-5s %-6s %-6s %s'
              % (r['id'], r['name'][:14], r.get('menu_id'), r.get('chess_id'),
                 r.get('live_in'), 'YES' if ok else '-'))


def cmd_live():
    rs = [r for r in _rooms()
          if r.get('chess_id') == 3 and (r.get('menu_id') or 0) >= 100
          and r.get('on_air') == 1]
    dom = open(DOMAIN_FILE).read().strip() if os.path.exists(DOMAIN_FILE) else '<域名>'
    print('可拉流直播间 %d 个：' % len(rs))
    for r in rs:
        print('  %-12s %-16s 在线=%-6s rtmp://%s/live/%s'
              % (r['id'], r['name'][:14], r.get('player_count'), dom, r['id']))


def cmd_play(rid):
    dom = open(DOMAIN_FILE).read().strip()
    print('rtmp://%s/live/%s' % (dom, rid))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    c = sys.argv[1]
    if c == 'init':
        cmd_init()
    elif c == 'call':
        body = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        r, ctl = call(sys.argv[2], body)
        print(json.dumps(r, ensure_ascii=False, indent=1)[:3000])
        for x in ctl:
            print('  ctl=', x.get('ctl'), str(x.get('data'))[:200])
    elif c == 'rooms':
        cmd_rooms()
    elif c == 'live':
        cmd_live()
    elif c == 'play':
        cmd_play(sys.argv[2])
    else:
        print(__doc__)
