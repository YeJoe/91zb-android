/* DmFilter — 弹幕分类与广告过滤（浏览器端，与 tools/danmaku_filter.py 同规则）
 *
 * 分类先看服务端类型码（content.typ / optcode），类型码缺失或为未知系统消息时，
 * 再用关键词兜底。真人聊天（typ=32）只拦链接/联系方式，不做博彩词过滤，
 * 否则观众正常聊"下注"也会被误杀。
 */
(function (root) {
  'use strict';

  var TYP_KIND = {
    1: 'admin',    // 禁言/踢人/设管理
    3: 'gift',     // 送礼物
    4: 'chat',     // @提及，仍是聊天
    5: 'follow',   // 关注
    10: 'ad',      // 博彩中奖播报 + 棋牌大厅跳转
    18: 'sys',     // 待定，只带 short_id
    19: 'bet',     // 下注
    20: 'win',     // 结算/中奖
    23: 'draw',    // 开奖
    26: 'sys',     // 心愿达成，跨房引流
    32: 'chat',    // 普通聊天
    33: 'order',   // 商城下单通知
    34: 'order'    // 心愿单下单
  };

  var KIND_LABEL = {
    chat: '聊天', gift: '礼物', enter: '进场', bet: '下注', win: '结算中奖',
    draw: '开奖', follow: '关注', admin: '管理', ad: '广告', sys: '系统',
    order: '下单', other: '其它'
  };

  var BET_WORDS = ['下注', '押注', '投注', '结算', '开奖', '派奖', '彩金', '返水', '首充',
    '百家乐', '牛牛', '快三', '时时彩', '六合彩', '棋牌', '电子游艺', '真人视讯',
    '七星', '财神', '提款', '收入囊中', '盘口', '赔率', '跟单', '上分', '下分',
    '东方彩票', '彩票大厅', '赢得', '大廳', '大厅'];

  var AD_WORDS = ['速来围观', '已濒临崩溃', '下一秒会怎样', '私聊', '加微', '加V', '加v', '微信',
    'QQ群', 'qq群', '电报', '飞机', '邀请码', '注册送', '代理', '包赢', '稳赚',
    '上岸', '计划群', '网址', '点击链接', '复制打开', '下载APP', '下载app'];

  var SYS_WORDS = ['完成了主播', '本场心愿', '心愿已达成', '升级为', '开通了', '续费了',
    '欢迎来到', '感谢关注', '排行榜', '活动期间'];

  var SPAM_PATTERNS = ['http://', 'https://', '.com', '.cn', '.net', '＋v', '+v'];

  // 伪装成聊天（typ=32）的引流文案：真人不会一口气刷 60 字外加一串表情
  var CHAT_AD_WORDS = ['加我', '私聊', '加私人', '福利视频', '解锁', '一对一', '包本人',
    '可约', '上门', '秒发', '心愿单下单', '听指挥'];
  var CHAT_WORD_MIN = 25, CHAT_LONG = 60, CHAT_TOO_LONG = 110;
  var EMOJI_HINT = ['💖', '💞', '💦', '🌊', '🛌', '👗', '👍', '🉑', '🌃'];

  function textOf(m) {
    if (m.t) return m.t;
    if (m.text) return m.text;
    var c = m.content;
    if (c && typeof c === 'object') return c.text || c.desc || '';
    return '';
  }

  function objOf(m) {
    return (m.content && typeof m.content === 'object') ? m.content : {};
  }

  function typOf(m) {
    var c = objOf(m), t = m.typ;
    if (t == null) t = c.typ;
    if (t == null) t = m.optcode;
    if (t == null) t = c.optcode;
    t = parseInt(t, 10);
    return isNaN(t) ? null : t;
  }

  function kindOf(m) {
    var c = objOf(m), typ = typOf(m);
    if (typ != null && TYP_KIND[typ]) return TYP_KIND[typ];
    if (c.bet_list || c.total_amount != null) return 'bet';
    if (c.pay_amount != null || c.cp_name) return 'win';
    if (c.item_id) return 'gift';
    if (c.operator_id && c.user_nickname) return 'follow';
    if (m.content_type === 1) return 'chat';
    return m.k || 'other';           // 已归一化过的样本自带 k
  }

  function hit(list, s) {
    for (var i = 0; i < list.length; i++) if (s.indexOf(list[i]) >= 0) return list[i];
    return null;
  }

  /* 返回 {kind, typ, keep, reason, text, nick} */
  function classify(m, keepKinds) {
    keepKinds = keepKinds || { chat: 1, gift: 1 };
    var kind = kindOf(m), typ = typOf(m), t = textOf(m) || '', reason = '';

    if (!t.trim()) {
      return { kind: kind, typ: typ, keep: !!keepKinds[kind], reason: '无文本',
        text: '', nick: m.n || m.nickname || '' };
    }

    if (kind === 'chat') {
      var sp = hit(SPAM_PATTERNS, t.toLowerCase());
      if (sp) return { kind: 'ad', typ: typ, keep: false, reason: '链接/联系方式',
        text: t, nick: m.n || m.nickname || '' };
      var cw = t.length >= CHAT_WORD_MIN ? hit(CHAT_AD_WORDS, t) : null;
      if (cw) return { kind: 'ad', typ: typ, keep: false, reason: '引流词 ' + cw,
        text: t, nick: m.n || m.nickname || '' };
      if (t.length >= CHAT_TOO_LONG) return { kind: 'ad', typ: typ, keep: false,
        reason: '超长刷屏', text: t, nick: m.n || m.nickname || '' };
      var emo = 0;
      for (var i = 0; i < EMOJI_HINT.length; i++) {
        var e = EMOJI_HINT[i], p = 0;
        while ((p = t.indexOf(e, p)) >= 0) { emo++; p += e.length; }
      }
      if (t.length >= CHAT_LONG && emo >= 2) return { kind: 'ad', typ: typ, keep: false,
        reason: '长文+表情轰炸', text: t, nick: m.n || m.nickname || '' };
      return { kind: 'chat', typ: typ, keep: true, reason: '', text: t,
        nick: m.n || m.nickname || '' };
    }

    var w = hit(AD_WORDS, t);
    if (w) return { kind: 'ad', typ: typ, keep: false, reason: '引流词 ' + w, text: t,
      nick: m.n || m.nickname || '' };
    w = hit(BET_WORDS, t);
    if (w) return { kind: 'bet', typ: typ, keep: false, reason: '博彩词 ' + w, text: t,
      nick: m.n || m.nickname || '' };
    w = hit(SYS_WORDS, t);
    if (w) return { kind: 'sys', typ: typ, keep: false, reason: '系统词 ' + w, text: t,
      nick: m.n || m.nickname || '' };

    return { kind: kind, typ: typ, keep: !!keepKinds[kind], reason: '', text: t,
      nick: m.n || m.nickname || '' };
  }

  /* 批量过滤，返回 {kept, stats}；stats[kind] = 该类型出现次数 */
  function filter(list, opts) {
    opts = opts || {};
    var keepKinds = opts.keepKinds || { chat: 1, gift: 1 };
    var kept = [], stats = {}, dropped = 0;
    for (var i = 0; i < list.length; i++) {
      var r = classify(list[i], keepKinds);
      stats[r.kind] = (stats[r.kind] || 0) + 1;
      if (r.keep) kept.push(list[i]); else dropped++;
    }
    return { kept: kept, stats: stats, dropped: dropped };
  }

  var api = { classify: classify, filter: filter, kindOf: kindOf, typOf: typOf,
    textOf: textOf, KIND_LABEL: KIND_LABEL, TYP_KIND: TYP_KIND };
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.DmFilter = api;
})(typeof window !== 'undefined' ? window : this);
