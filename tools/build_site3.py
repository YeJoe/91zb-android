#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_site3.py — 用房间号池 + /room/get_list_by_ids 重建 site/data.js

## 为什么要改成号池

2026-10-29 复核：**`/room/list` 在 8.5.3 已经下线**，17 个本地监听端口一律 404，
源 App 冷启动全程也不调用它。服务端现在只认「你直接告诉我房间号」——
唯一可用的房间接口是 `POST /room/get_list_by_ids {"ids":"a,b,c"}`。

所以本脚本取数的两步变成：
  1. **号池**：`app/src/main/assets/room_ids.txt`（1972 个种子号，与安卓 App 同一份），
     再加上 `site/data.js` 里已经存在的号，两处并起来；
  2. **批量刷新**：每 250 个一批打 `get_list_by_ids`，只留 `on_air=1`，
     分类直接用返回的 `menu_id`（平台自己的分类，无需再请求分类列表接口）。

## 为什么不用拉流探测判真人（历史结论，仍然成立）

拉流只能测「此刻是否在推流」，不能测「是不是真人」。
实测反例：名字明显是真人（性瘾小野猫 / 极品静静 / 可爱的丽娜）但人气 40~177 的房，
拉流稳定 I/O error —— 那是主播下播了，不是假房。

真正的分类依据是 menu_id：
    8=游戏（85% 是盘口广告）  101=星秀  102=女神  103=颜值
    104=交友  105=音乐  108=舞蹈  5=新人

产物：site/data.js
"""
import gzip
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel_map as tm            # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(HERE, 'site')
POOL_FILE = os.path.join(HERE, 'app', 'src', 'main', 'assets', 'room_ids.txt')


def load_cred():
    """凭证：优先 .apptoken.json（token_export.py 的产物），再退回老的 txt 两件套。"""
    tok = jwe = ''
    p = os.path.join(HERE, '.apptoken.json')
    if os.path.exists(p):
        try:
            d = json.load(open(p, encoding='utf-8'))
            tok = (d.get('token') or '').strip()
            jwe = (d.get('jwe') or '').strip()
        except Exception:
            pass
    if not tok:
        q = os.path.join(HERE, '.apptoken.txt')
        if os.path.exists(q):
            tok = open(q).read().strip()
    if not jwe:
        q = os.path.join(HERE, '.jwe.txt')
        if os.path.exists(q):
            jwe = open(q).read().strip()
    return tok, jwe


TOK, JWE = load_cred()
HEAD = {"authorization": "Bearer " + JWE} if JWE else {}

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
REAL = {mid: name for mid, name in CATS}

# 一批塞多少个 id：实测 250 → 约 0.5s，响应 100KB 上下
BATCH = 250

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


def load_ids():
    """号池：assets 里的种子 + 上次生成的 data.js 里已有的号，取并集（只增不减）。"""
    ids = set()
    if os.path.exists(POOL_FILE):
        for line in open(POOL_FILE, encoding='utf-8'):
            s = line.strip()
            if s.isdigit() and int(s) > 0:
                ids.add(int(s))
    dj = os.path.join(SITE, 'data.js')
    if os.path.exists(dj):
        try:
            txt = open(dj, encoding='utf-8').read()
            for m in re.finditer(r'"id":(\d+)', txt):
                v = int(m.group(1))
                if v > 0:
                    ids.add(v)
        except Exception:
            pass
    return sorted(ids)


def fetch_rooms(ids):
    """分批拉详情。第一批就失败 → 直接退出并给排查提示，别跑出个空 data.js。"""
    rooms = {}
    for i in range(0, len(ids), BATCH):
        ch = ids[i:i + BATCH]
        d = api('/room/get_list_by_ids', {"ids": ",".join(map(str, ch))})
        if d is None and not rooms:
            sys.exit('[x] 接口没打通。依次检查：\n'
                     '    1) python tools/token_export.py 重新导出 token/JWE\n'
                     '    2) 源 App 得在后台跑着（隧道端口由它提供）\n'
                     '    3) 端口自动探测失败时，用 --port 指定')
        for r in (d or {}).get('rooms') or []:
            rooms[r['id']] = r
        print('  详情 %d/%d' % (min(i + BATCH, len(ids)), len(ids)), end='\r')
    print()
    return rooms


def main():
    t0 = time.time()
    if not TOK:
        sys.exit('[x] 没有会话 token。先跑 python tools/token_export.py 导出（需 root + 冷启动抓 lo）')
    ids = load_ids()
    if not ids:
        sys.exit('[x] 号池为空：缺 %s' % POOL_FILE)

    print('号池 %d 个房间号，开始批量刷新（%d/批）…' % (len(ids), BATCH))
    rooms = fetch_rooms(ids)

    out = []
    for rid in ids:
        r = rooms.get(rid)
        if not r or r.get('on_air') != 1:
            continue
        cat = REAL.get(r.get('menu_id') or 0)
        if not cat:
            continue
        name = (r.get('name') or '').strip() or ('房间 %d' % rid)
        chess = r.get('chess_id')
        out.append({
            'id': rid,
            'name': name,
            'cat': cat,
            'online': r.get('player_count') or 0,
            'coins': r.get('live_fee') or 0,
            'pay': r.get('live_pay_type') or 0,
            'sex': r.get('sex') or 0,
            'city': (r.get('city_show') or '').strip(),
            'chess': chess,
            'avatar': r.get('attachment_url') or '',
            'battle': 1 if (r.get('battle_id') or 0) else 0,
            'title': r.get('title_id') or 0,
            # 名字带盘口语，或 chess_id 存在且不是 3（棋牌/推广房）
            'ad': 1 if (is_ad(name) or (chess is not None and chess != 3)) else 0,
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
