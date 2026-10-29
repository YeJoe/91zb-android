#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
danmaku_probe.py — 采集弹幕原始样本（用于分类与过滤规则推导）

每个房间做两件事：
  1. /websock/history_msg  拉最近一屏历史消息（密、类型全，最适合做分类样本）
  2. /websock/join + 持续收 room_msg 推送 N 秒（拿实时增量）
落盘保留服务端原始 content_type / optcode / content，不做任何过滤。

用法:
  python tools/danmaku_probe.py --rooms 40 --seconds 5 --out assets/data/dm.jsonl
  python tools/danmaku_probe.py --ids 55143589,1006656015 --seconds 20
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import live_ws as lw          # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rooms_from_site(limit):
    """从 site/data.js 取房间 id（跳过盘口广告房，按人气降序）"""
    p = os.path.join(HERE, 'site', 'data.js')
    if not os.path.exists(p):
        return []
    s = open(p, encoding='utf-8').read()
    m = re.search(r'window\.ROOMS=(\[.*?\]);', s, re.S)
    if not m:
        return []
    try:
        rooms = json.loads(m.group(1))
    except Exception:
        return []
    rooms = [r for r in rooms if not r.get('ad')]
    rooms.sort(key=lambda r: -(r.get('online') or 0))
    return [int(r['id']) for r in rooms[:limit]]


def norm_item(it, room_id, src):
    raw = it.get('content')
    obj = None
    if isinstance(raw, str):
        try:
            obj = json.loads(raw)
        except Exception:
            obj = None
    elif isinstance(raw, dict):
        obj = raw
    o = obj if isinstance(obj, dict) else {}
    return {
        'src': src,
        'room_id': room_id,
        'id': it.get('id'),
        'ts': it.get('create_time'),
        'user_id': it.get('user_id'),
        'nickname': it.get('nickname'),
        'content_type': it.get('content_type'),
        'optcode': it.get('optcode'),
        'optcode_ref_id': it.get('optcode_ref_id'),
        'typ': o.get('typ'),
        'text': o.get('text'),
        'content': raw if isinstance(raw, str) else json.dumps(raw, ensure_ascii=False),
    }


def collect(ids, seconds, out_path, gap=1.0):
    p, sk = lw.connect(probe_rid=ids[0])
    if not sk:
        raise SystemExit('隧道握手失败：确认 App 在跑、adb 在线、token 未过期')
    print('隧道端口', p, '| 房间', len(ids), '| 实时窗口', seconds, 's/房')
    os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
    fh = open(out_path, 'a', encoding='utf-8')
    total = {'hist': 0, 'push': 0}

    def write(it, rid, src):
        row = norm_item(it, rid, src)
        fh.write(json.dumps(row, ensure_ascii=False) + '\n')
        fh.flush()
        total[src] += 1

    for i, rid in enumerate(ids, 1):
        before = dict(total)
        try:
            lw.send(sk, '/websock/history_msg', {"room_id": rid, "start_id": 0}, 30000 + i)
            resp, _ = lw.pump(sk, 30000 + i, 5.0)
            body = (resp or {}).get('body') or {}
            for it in (body.get('result') or []) if isinstance(body, dict) else []:
                if isinstance(it, dict):
                    write(it, rid, 'hist')

            lw.send(sk, '/websock/join', {"room_id": rid, "menu_id": 1, "cp_type": 3}, 40000 + i)

            def on_push(o, rid=rid):
                if o.get('ctl') != 'room_msg':
                    return
                data = o.get('data')
                try:
                    items = json.loads(data) if isinstance(data, str) else (data or [])
                except Exception:
                    return
                for it in items:
                    if isinstance(it, dict):
                        write(it, rid, 'push')

            lw.pump(sk, 40000 + i, seconds, on_push)
        except (ConnectionError, OSError) as e:
            # 隧道会被 App 侧主动断开（切后台 / 心跳超时），换一条连接继续，不要整批丢掉
            print('  [%2d/%2d] %-12s 连接中断(%s)，重连…' % (i, len(ids), rid, type(e).__name__))
            try:
                sk.close()
            except Exception:
                pass
            time.sleep(1.0)
            _, sk = lw.connect(probe_rid=rid)
            if not sk:
                raise SystemExit('重连失败，先确认 App 还在前台跑')
        print('  [%2d/%2d] %-12s hist+%d push+%d' % (
            i, len(ids), rid, total['hist'] - before['hist'], total['push'] - before['push']))
        time.sleep(gap)

    try:
        lw.send(sk, '/websock/leave', {"room_id": ids[-1]}, 59999)
    except Exception:
        pass
    sk.close()
    fh.close()
    print('共采集 hist=%d push=%d -> %s' % (total['hist'], total['push'], out_path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rooms', type=int, default=20, help='从 site/data.js 取前 N 个真人房')
    ap.add_argument('--ids', default='', help='逗号分隔的房间号')
    ap.add_argument('--seconds', type=int, default=8, help='每房实时监听秒数')
    ap.add_argument('--out', default=os.path.join(HERE, 'assets', 'data', 'dm.jsonl'))
    a = ap.parse_args()
    ids = [int(x) for x in a.ids.split(',') if x.strip()] if a.ids else rooms_from_site(a.rooms)
    if not ids:
        raise SystemExit('没有房间可采，先跑 tools/build_site3.py 或传 --ids')

    # 断点续采：已经采过的房间不再重复拉，方便分多次攒样本
    if os.path.exists(a.out):
        done = set()
        for line in open(a.out, encoding='utf-8'):
            try:
                done.add(json.loads(line)['room_id'])
            except Exception:
                pass
        left = [i for i in ids if i not in done]
        if len(left) != len(ids):
            print('已采过 %d 个房间，跳过；本次采 %d 个' % (len(ids) - len(left), len(left)))
        ids = left
    if not ids:
        raise SystemExit('候选房间都采过了，加大 --rooms 或换 --ids')

    collect(ids, a.seconds, a.out)


if __name__ == '__main__':
    main()
