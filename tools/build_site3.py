#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_site3.py — 按官方分类抓「真人直播间」，不预扫拉流

方法说明（为什么不用拉流探测）：
  拉流只能测「此刻是否在推流」，不能测「是不是真人」。
  实测反例：名字明显是真人（性瘾小野猫 / 极品静静 / 可爱的丽娜）但人气 40~177 的房，
  拉流稳定 I/O error —— 那是主播下播了，不是假房。
  反过来 on_air=1 的房里 109 个拉不到流，说明 on_air 字段不可信。

  真正的分类依据是 menu_id（平台自己的分类）：
      8=游戏（85% 是盘口广告）  101=星秀  102=女神  103=颜值
      104=交友  105=音乐  108=舞蹈  5=新人
  用 menu_id 做分类：9 次请求、约 1.5 秒。
  全量拉流探测：342 次连接、60~90 秒，而且结果是快照，十分钟后就过期。

产物：site/data.js
"""
import gzip
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm            # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(HERE, 'site')
JWE = open(os.path.join(HERE, '.jwe.txt')).read().strip()
TOK = open(os.path.join(HERE, '.apptoken.txt')).read().strip()
HEAD = {"authorization": "Bearer " + JWE}

# 只取 depth=2 的真人秀分类。8000~8013 是博彩盘口分类，明确不要。
CATS = [
    (104, '交友'),
    (101, '星秀'),
    (102, '女神'),
    (103, '颜值'),
    (108, '舞蹈'),
    (105, '音乐'),
    (5,   '新人'),
]

# 盘口/博彩推广词。有些房挂在真人分类里，但把房间名起成「XX百家乐」「回血快三王」拉客。
# 不删除、只打标记，页面默认隐藏、可一键显示——因为其中少数确实是真人主播在出镜。
AD_WORDS = ('快三', '百家乐', '百乐', '六合', '牛牛', '龙虎', '鱼虾蟹', '轮盘', '时时彩',
            'PC28', 'pc28', '三公', '回血', '倍投', '和值', '稳赢', '带飞', '赌神', '博彩',
            '彩票', '下注', '走势', '计划', '开元', '永利', '威尼斯', '银河', '财神', '发财',
            '印钞', '日富', '斗金')


def is_ad(name):
    return any(w in name for w in AD_WORDS)


_port = None


def api(path, body, timeout=7):
    global _port
    cands = [_port] if _port else []
    cands += [p for p in tm.listen_ports() if p not in cands]
    for p in cands:
        try:
            tm.forward(p)
        except Exception:
            continue
        try:
            st, hd, raw = tm.call(p, "POST", path + "?token=" + TOK,
                                  body, headers=HEAD, timeout=timeout)
        except Exception:
            continue
        if st != 200:
            continue
        if raw[:2] == b'\x1f\x8b':
            raw = gzip.decompress(raw)
        try:
            d = json.loads(raw)
        except Exception:
            continue
        if isinstance(d, dict) and d.get('code') == 0:
            _port = p
            return d
    return None


def main():
    t0 = time.time()
    seen = {}
    for mid, name in CATS:
        d = api('/room/list', {"menu_id": mid}) or {}
        ids = d.get('result') or []
        cnt = d.get('count_list') or []
        for i, rid in enumerate(ids):
            e = seen.setdefault(rid, {'cat': name, 'online': 0})
            if i < len(cnt):
                e['online'] = max(e['online'], cnt[i])
        print('  %-4s menu_id=%-4d %d 个' % (name, mid, len(ids)))

    ids = sorted(seen)
    rooms = {}
    for i in range(0, len(ids), 20):
        ch = ids[i:i + 20]
        d = api('/room/get_list_by_ids', {"ids": ",".join(map(str, ch))})
        for r in (d or {}).get('rooms') or []:
            rooms[r['id']] = r

    out = []
    for rid in ids:
        r = rooms.get(rid)
        if not r:
            continue
        e = seen[rid]
        name = (r.get('name') or '').strip() or ('房间 %d' % rid)
        out.append({
            'id': rid,
            'name': name,
            'cat': e['cat'],
            'online': max(e['online'], r.get('player_count') or 0),
            'coins': r.get('live_fee') or 0,
            'pay': r.get('live_pay_type') or 0,
            'sex': r.get('sex') or 0,
            'city': (r.get('city_show') or '').strip(),
            'chess': r.get('chess_id'),
            'avatar': r.get('attachment_url') or '',
            'battle': 1 if (r.get('battle_id') or 0) else 0,
            'title': r.get('title_id') or 0,
            'ad': 1 if is_ad(name) else 0,
        })
    out.sort(key=lambda x: (-x['online']))

    # 分类按 CATS 顺序，附上实际数量
    cats = [[n, sum(1 for x in out if x['cat'] == n)] for _, n in CATS]

    js = 'window.ROOMS=%s;\n' % json.dumps(out, ensure_ascii=False, separators=(',', ':'))
    js += 'window.CATS=%s;\n' % json.dumps(cats, ensure_ascii=False)
    js += 'window.BUILD_AT=%s;\n' % json.dumps(time.strftime('%Y-%m-%d %H:%M:%S'))
    # 页面拼播放源要用到 RTMP 域名，跟房间数据一起下发，免得前端再硬编码一份
    dom = ''
    fp = os.path.join(HERE, '.rtmp_domain.txt')
    if os.path.exists(fp):
        dom = open(fp).read().strip()
    js += 'window.RTMP_DOMAIN=%s;\n' % json.dumps(dom)
    os.makedirs(SITE, exist_ok=True)
    open(os.path.join(SITE, 'data.js'), 'w', encoding='utf-8').write(js)

    ad = sum(1 for x in out if x['ad'])
    print('\n%d 个真人直播间（其中 %d 个挂盘口名，页面默认隐藏），耗时 %.1fs'
          % (len(out), ad, time.time() - t0))
    print('分类:', cats)
    print('干净房间:', len(out) - ad)


if __name__ == '__main__':
    main()
