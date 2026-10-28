#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
danmaku_filter.py — 弹幕分类 + 广告/系统消息过滤（纯标准库）

分类依据是服务端字段，不靠猜文本：
  content 里的 `typ`（等价于 `optcode`）是消息类型码，`content_type` 是大类（1 文本 / 8 结构化）。

实测类型码（2026-10-29 采 70 个真人直播间，见 docs/弹幕分类与过滤.md）：
  32  普通聊天      ← 唯一可以直接当弹幕显示的内容
  19  下注          ← 博彩
  20  结算/中奖      ← 博彩（"恭喜…收入囊中"就是这一类）
  23  开奖          ← 博彩（pre_draw_code / dan_shuang）
  10  博彩中奖播报   ← "恭喜…在东方彩票大厅…赢得 34438 元" + app://quick_login_chess 跳转
  5   关注          ← "xx 关注了 yy" 系统通知
  3   礼物
  1   管理操作       ← 禁言/踢人/设管理
  26  心愿达成       ← 平台运营通知，带 app://live_room 跨房引流
  18  待定          ← 只带 short_id，暂按系统消息丢弃

类型码之外还有一类**纯文本系统广播**（完成心愿、引流广告、开奖公告），
它们没有可靠的类型码，只能用关键词兜底，因此本模块把两者合起来判：
  先看类型码定 kind，再用关键词对文本二次打分（只对非 chat 生效，避免误杀真人聊天）。

与网页端 site/dm_filter.js 规则完全一致，改规则时两边都要动。
"""

# 类型码 -> 语义
TYP_KIND = {
    1: 'admin',    # 管理操作：禁言/踢人/设管理
    3: 'gift',     # 送礼物
    4: 'chat',     # @提及，仍是聊天
    5: 'follow',   # 关注
    10: 'ad',      # 博彩中奖播报 + 棋牌大厅跳转
    18: 'sys',     # 待定：只带 short_id
    19: 'bet',     # 下注
    20: 'win',     # 结算/中奖
    23: 'draw',    # 开奖
    26: 'sys',     # 心愿达成（跨房引流）
    32: 'chat',    # 普通聊天
    33: 'order',   # 商城下单通知
    34: 'order',   # 心愿单下单
}

# 默认保留策略：只有真人聊天（可选礼物/进场）留下
KEEP_KINDS = {'chat', 'gift', 'enter'}

KIND_LABEL = {
    'chat': '聊天',
    'gift': '礼物',
    'enter': '进场',
    'bet': '下注',
    'win': '结算中奖',
    'draw': '开奖',
    'follow': '关注',
    'admin': '管理',
    'ad': '广告',
    'order': '下单',
    'sys': '系统',
    'other': '其它',
}

# 博彩/盘口语汇：命中即判为博彩类系统消息
BET_WORDS = (
    '下注', '押注', '投注', '结算', '开奖', '派奖', '彩金', '返水', '首充',
    '百家乐', '牛牛', '快三', '时时彩', '六合彩', '棋牌', '电子游艺', '真人视讯',
    '七星', '财神', '提款', '收入囊中', '盘口', '赔率', '跟单', '上分', '下分',
    '东方彩票', '彩票大厅', '赢得', '大廳', '大厅',
)

# 引流/广告语汇：这类是多房间互推或站外拉人
AD_WORDS = (
    '速来围观', '已濒临崩溃', '下一秒会怎样', '私聊', '加微', '加V', '加v', '微信',
    'QQ群', 'qq群', '电报', '飞机', '邀请码', '注册送', '代理', '包赢', '稳赚',
    '上岸', '计划群', '网址', '点击链接', '复制打开', '下载APP', '下载app',
)

# 系统广播语汇：平台自己的运营通知，不是观众在聊天
SYS_WORDS = (
    '完成了主播', '本场心愿', '心愿已达成', '升级为', '开通了', '续费了',
    '欢迎来到', '感谢关注', '排行榜', '活动期间',
)

# 真人聊天里几乎不会出现、一出现就是机器刷屏的硬广告特征
SPAM_PATTERNS = (
    'http://', 'https://', '.com', '.cn', '.net', '＋v', '+v', '+V',
)

# 伪装成聊天（typ=32）的引流文案：真人不会一口气刷 60 字外加一串表情
CHAT_AD_WORDS = (
    '加我', '私聊', '加私人', '福利视频', '解锁', '一对一', '包本人', '可约',
    '上门', '秒发', '心愿单下单', '听指挥',
)
CHAT_WORD_MIN = 25    # 引流词只在长句里判，避免把"一对一多久"这种追问误杀
CHAT_LONG = 60        # 超过这个长度且带 2 个以上表情 -> 引流
CHAT_TOO_LONG = 110   # 超长一律视为刷屏
EMOJI_HINT = ('💖', '💞', '💦', '🌊', '🛌', '👗', '👍', '🉑', '🌃')


def _text_of(item):
    """从归一化 item 里取可读文本（聊天用 text，系统类多用 desc）"""
    if item.get('text'):
        return item['text']
    c = item.get('content')
    if isinstance(c, dict):
        return c.get('text') or c.get('desc') or ''
    return ''


def _content_obj(item):
    c = item.get('content')
    return c if isinstance(c, dict) else {}


def kind_of(item):
    """按类型码定 kind；类型码缺失时退化为按 content 结构猜"""
    c = _content_obj(item)
    typ = item.get('typ')
    if typ is None:
        typ = c.get('typ')
    if typ is None:
        typ = item.get('optcode')
    if typ is None:
        typ = c.get('optcode')
    try:
        typ = int(typ)
    except (TypeError, ValueError):
        typ = None

    if typ in TYP_KIND:
        return TYP_KIND[typ], typ

    # 没有类型码：靠字段指纹认人
    if c.get('bet_list') or c.get('total_amount'):
        return 'bet', typ
    if c.get('pay_amount') or c.get('cp_name'):
        return 'win', typ
    if c.get('item_id'):
        return 'gift', typ
    if c.get('operator_id') and c.get('user_nickname'):
        return 'follow', typ
    if item.get('content_type') == 1:
        return 'chat', typ
    return 'other', typ


def text_verdict(text, kind='other'):
    """关键词兜底：返回 (kind 修正值, 是否丢弃, 原因)"""
    t = text or ''
    if not t.strip():
        return kind, kind not in KEEP_KINDS, ''

    if kind == 'chat':
        # 真人聊天不做博彩词过滤（观众自己会聊"下注"），只拦刷屏引流
        for p in SPAM_PATTERNS:
            if p in t.lower():
                return 'ad', True, '聊天含链接/联系方式'
        if len(t) >= CHAT_WORD_MIN:      # 短句多数是观众在追问，不判
            for w in CHAT_AD_WORDS:
                if w in t:
                    return 'ad', True, '聊天含引流词:' + w
        if len(t) >= CHAT_TOO_LONG:
            return 'ad', True, '超长刷屏(%d字)' % len(t)
        if len(t) >= CHAT_LONG and sum(t.count(e) for e in EMOJI_HINT) >= 2:
            return 'ad', True, '长文+表情轰炸'
        return kind, False, ''

    # 类型码已经能定性的消息，不被关键词改写 kind（只有 other/sys 才让关键词接管）
    known = kind in TYP_KIND.values() and kind != 'sys'
    for w in AD_WORDS:
        if w in t:
            return ('ad' if not known else kind), True, '引流词:' + w
    for w in BET_WORDS:
        if w in t:
            return ('bet' if not known else kind), True, '博彩词:' + w
    for w in SYS_WORDS:
        if w in t:
            return ('sys' if not known else kind), True, '系统词:' + w

    return kind, kind not in KEEP_KINDS, ''


def classify(item, keep=('chat', 'gift')):
    """返回 {'kind','verdict','reason','text','nick'}；verdict ∈ keep/drop"""
    kind, typ = kind_of(item)
    text = _text_of(item)
    kind2, drop, reason = text_verdict(text, kind)
    if kind2 != kind:
        kind = kind2
    if not drop and kind not in keep:
        drop, reason = True, '类型码 %s' % typ
    return {
        'kind': kind,
        'typ': typ,
        'verdict': 'drop' if drop else 'keep',
        'reason': reason,
        'text': text,
        'nick': item.get('nickname') or '',
    }


def filter_items(items, keep=('chat', 'gift'), stats=None):
    """批量过滤；stats 传 dict 会累加各类计数"""
    out = []
    for it in items:
        r = classify(it, keep)
        if stats is not None:
            stats[r['kind']] = stats.get(r['kind'], 0) + 1
        if r['verdict'] == 'keep':
            out.append(it)
    return out
