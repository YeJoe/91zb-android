#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_danmaku.py — 把采集到的弹幕样本压成网页可用的 site/dm_samples.js

输入 assets/data/dm.jsonl（tools/danmaku_probe.py 的产物），输出：
  window.DANMAKU   页面聊天区的样本池（聊天占多数，另掺少量各类系统消息供"全部"模式对照）
  window.DM_STATS  分类统计，页头那行"样本 N 条 / 已过滤 M 条"就是它
  window.DM_AT     采样时间

用法:
  python tools/build_danmaku.py
  python tools/build_danmaku.py --in assets/data/dm.jsonl --chat 140 --each 6
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import danmaku_filter as f          # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(HERE, 'site')


def readable(kind, r):
    """结构化消息没有 text/desc 时，用模板拼一句人话（只为在"全部"模式里看得懂）"""
    try:
        c = json.loads(r.get('content') or '{}')
    except Exception:
        c = {}
    if kind == 'bet':
        return '下注 %s 元 %s' % (c.get('total_amount', '?'), c.get('cp_type_string') or '')
    if kind == 'win':
        return '%s 结算 %s 元' % (c.get('cp_name') or '彩票', c.get('pay_amount', '?'))
    if kind == 'draw':
        return '%s 开奖 %s' % (c.get('cp_type') or '', c.get('pre_draw_code') or '')
    if kind == 'follow':
        return '关注了主播'
    if kind == 'admin':
        return '管理操作'
    if kind == 'gift':
        return '送出礼物 ×%s' % c.get('count', 1)
    if kind == 'order':
        return '商城下单'
    if kind == 'ad':
        return '博彩中奖播报'
    return '系统通知'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--in', dest='inp',
                    default=os.path.join(HERE, 'assets', 'data', 'dm.jsonl'))
    ap.add_argument('--out', default=os.path.join(SITE, 'dm_samples.js'))
    ap.add_argument('--chat', type=int, default=140, help='最多取多少条聊天')
    ap.add_argument('--each', type=int, default=6, help='每个非聊天类别取几条')
    a = ap.parse_args()

    rows, seen_id, seen_txt = [], set(), set()
    for line in open(a.inp, encoding='utf-8'):
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
        except Exception:
            continue
        key = r.get('id')
        if key and key in seen_id:
            continue
        if key:
            seen_id.add(key)
        rows.append(r)

    buckets, stats = {}, {}
    for r in rows:
        c = f.classify(r)
        stats[c['kind']] = stats.get(c['kind'], 0) + 1
        buckets.setdefault(c['kind'], []).append((c, r))

    out = []

    def push(c, r, limit_tag):
        nick = (r.get('nickname') or '').strip() or '观众'
        text = (c['text'] or '').strip()
        if not text:
            # 结构化消息没有文本，用模板还原一句可读的话，够"全部"模式演示
            text = limit_tag
        out.append({'k': c['kind'], 'typ': c['typ'], 'n': nick, 't': text[:120]})

    for c, r in buckets.get('chat', [])[:a.chat]:
        t = (c['text'] or '').strip()
        if not t or t in seen_txt:
            continue
        seen_txt.add(t)
        push(c, r, '')

    for kind, items in buckets.items():
        if kind == 'chat':
            continue
        for c, r in items[:a.each]:
            text = (c['text'] or '').strip() or readable(kind, r)
            out.append({'k': kind, 'typ': c['typ'], 'n': (r.get('nickname') or '系统')[:20],
                        't': text[:120]})

    js = 'window.DANMAKU=%s;\n' % json.dumps(out, ensure_ascii=False, separators=(',', ':'))
    js += 'window.DM_STATS=%s;\n' % json.dumps(stats, ensure_ascii=False)
    js += 'window.DM_AT=%s;\n' % json.dumps(time.strftime('%Y-%m-%d %H:%M:%S'))
    os.makedirs(SITE, exist_ok=True)
    open(a.out, 'w', encoding='utf-8').write(js)

    total = sum(stats.values())
    keep = stats.get('chat', 0) + stats.get('gift', 0)
    print('%d 条样本 / %d 个房间 -> %s' % (total, len(set(r['room_id'] for r in rows)), a.out))
    print('分类:', json.dumps(stats, ensure_ascii=False))
    print('可直接显示 %d 条（%.0f%%），过滤 %d 条' % (keep, keep * 100.0 / max(total, 1), total - keep))


if __name__ == '__main__':
    main()
