# -*- coding: utf-8 -*-
"""
生成房间列表网页：类别分页 + 内嵌播放器。

播放后端两选一（页面可切换）：
  1) WASM 直连（默认）：jessibuca 软解 HEVC，直接播 pullStreamAddr
     —— 与官方网页版同款方案，不需要服务端转码，画质无损
  2) MSE 转码：走 serve.py 的 /ts（ffmpeg 转 H.264），设备/网络异常时兜底

用法：
    python build_web.py [rooms.json] [输出目录]
"""
import json
import os
import re
import sys
from datetime import datetime, timezone, timedelta

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# 游戏/博彩推广号识别：这类「凤凰快三 / 百家乐 / 龙虎 / pc28」房间不是真人直播间，
# 而是拿直播间当广告位。判别字段是 anchorType：1=游戏/推广号，2=真人主播。
# 兜底：anchorType 缺失时再用玩法名称关键词判一次，避免漏网。
_AD_NAME_RE = re.compile(r"快三|百家乐|龙虎|pc28|PC28|六合彩|时时彩|彩票|博彩|娱乐城|棋牌|视讯")


def is_ad_room(r) -> bool:
    try:
        at = int(r.get("anchorType"))
    except (TypeError, ValueError):
        at = None
    if at == 1:
        return True
    if at is None:
        name = f"{r.get('userName', '')} {r.get('roomName', '')}"
        return bool(_AD_NAME_RE.search(name))
    return False


def main() -> None:
    src = sys.argv[1] if len(sys.argv) > 1 else "rooms_all.json"
    outdir = sys.argv[2] if len(sys.argv) > 2 else "web"

    with open(src, encoding="utf-8") as f:
        payload = json.load(f)

    if isinstance(payload, dict) and "rooms" in payload:
        categories = payload.get("categories") or []
        rooms = payload["rooms"]
        gen = payload.get("generated") or ""
    else:
        categories = []
        rooms = payload
        gen = ""

    cat_names = [c["name"] for c in categories] if categories else []
    for r in rooms.values():
        for cn in (r.get("_cats") or ["未分类"]):
            if cn not in cat_names:
                cat_names.append(cn)
    if not cat_names:
        cat_names = ["未分类"]

    def exp_of(url, pre=None):
        import re
        if pre:
            return int(pre)
        m = re.search(r"auth_key=(\d{9,})-", url or "")
        return int(m.group(1)) if m else 0

    slim = []
    skipped = 0
    for rid, r in rooms.items():
        if is_ad_room(r):          # 跳过「凤凰快三 / 百家乐」这类博彩/游戏广告直播间
            skipped += 1
            continue
        # 两套字段名都要兼容：列表用 pullStreamAddr*，进房用 pullAddress*
        u0 = str(r.get("pullStreamAddr") or r.get("pullAddress") or "")
        u3 = str(r.get("pullStreamAddr360") or r.get("pullAddress360") or "")
        u7 = str(r.get("pullStreamAddr720") or r.get("pullAddress720") or "")
        cats = r.get("_cats") or ["未分类"]
        mf = r.get("moneyFen") or 0
        try:
            mf = int(mf)
        except Exception:
            mf = 0
        slim.append({
            "roomId": str(r.get("roomId") or rid),
            "userId": str(r.get("userId") or ""),
            "userName": str(r.get("userName") or r.get("nickname") or "未知主播"),
            "roomName": str(r.get("roomName") or ""),
            "hot": int(r.get("currentHot") or 0) if str(r.get("currentHot") or "0").isdigit() else 0,
            "cover": str(r.get("roomCover") or ""),
            "liveType": r.get("liveType"),
            "payType": r.get("payType"),
            "moneyFen": mf,
            "oneToOneState": r.get("oneToOneState"),
            "url0": u0, "url360": u3, "url720": u7,
            "exp": exp_of(u0, r.get("_exp")),
            "cats": cats,
        })
    slim.sort(key=lambda x: -x["hot"])

    ts = gen or datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S")

    html = (TEMPLATE
            .replace("__DATA__", json.dumps(slim, ensure_ascii=False, separators=(",", ":")))
            .replace("__CATS__", json.dumps(cat_names, ensure_ascii=False, separators=(",", ":")))
            .replace("__BUILD__", ts))

    os.makedirs(outdir, exist_ok=True)
    path = os.path.join(outdir, "index.html")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(html)
    extra = f"，已过滤 {skipped} 个广告直播间" if skipped else ""
    print(f"已生成 {path}（{len(slim)} 个房间 / {len(cat_names)} 个类别{extra}）")
    need = ["jessibuca.js", "decoder.js", "decoder.wasm"]
    missing = [n for n in need if not os.path.isfile(os.path.join(outdir, n))]
    if missing:
        print("[警告] web 目录缺少播放器文件：" + ", ".join(missing))
    print("启动服务：双击根目录 start.bat（或 python tools/run.py --serve-only），然后访问 http://127.0.0.1:8899/")


TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>直播间列表 · 91直播</title>
<style>
  :root{
    --bg:#0e1117; --card:#161b24; --card2:#1d2430; --line:#252c3a;
    --fg:#e6e9ef; --dim:#8b95a7; --accent:#ff5c8a; --ok:#3ddc97; --warn:#ffb454;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--fg);
       font:14px/1.5 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif}

  header{position:sticky;top:0;z-index:20;background:rgba(14,17,23,.94);
         backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
  .bar{padding:13px 20px;display:flex;gap:14px;align-items:center;flex-wrap:wrap}
  h1{font-size:16px;margin:0;font-weight:600;letter-spacing:.3px}
  h1 span{color:var(--accent)}
  .meta{color:var(--dim);font-size:12px}
  .tools{margin-left:auto;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  input[type=search]{background:var(--card);border:1px solid var(--line);color:var(--fg);
        border-radius:8px;padding:7px 12px;width:210px;outline:none;font-family:inherit}
  input[type=search]:focus{border-color:var(--accent)}
  select{background:var(--card);border:1px solid var(--line);color:var(--fg);
        border-radius:8px;padding:7px 10px;outline:none;font-family:inherit}

  nav{display:flex;gap:7px;padding:0 20px 11px;overflow-x:auto;scrollbar-width:thin}
  nav::-webkit-scrollbar{height:5px}
  nav::-webkit-scrollbar-thumb{background:#2e3644;border-radius:3px}
  nav button{white-space:nowrap;background:var(--card);border:1px solid var(--line);
      color:var(--dim);border-radius:999px;padding:6px 15px;font-size:13px;
      cursor:pointer;transition:.15s;font-family:inherit}
  nav button:hover{color:var(--fg);border-color:#3a4457}
  nav button.on{background:linear-gradient(135deg,#ff5c8a,#ff8a5c);color:#fff;
      border-color:transparent;font-weight:600}
  nav button .n{opacity:.65;font-size:11px;margin-left:5px}

  .grid{display:grid;gap:14px;padding:18px 20px 34px;
        grid-template-columns:repeat(auto-fill,minmax(300px,1fr))}
  .card{background:var(--card);border:1px solid var(--line);border-radius:14px;
        overflow:hidden;display:flex;flex-direction:column;transition:.18s}
  .card:hover{border-color:#3a4457;transform:translateY(-2px)}
  .cover{position:relative;aspect-ratio:16/10;background:var(--card2);overflow:hidden;cursor:pointer}
  /* 封面图：绝对定位 + z-index 1 压在占位字母(.ph)之上；未加载完时自身透明，
     占位字母自然透出来，加载完成后覆盖占位，无需 JS 切换，也没有动画竞态 */
  .cover img{position:absolute;inset:0;z-index:1;width:100%;height:100%;object-fit:cover;
        display:block}
  .cover .ph{position:absolute;inset:0;z-index:0;display:flex;align-items:center;justify-content:center;
       font-size:40px;font-weight:700;color:#5b6376;
       background:radial-gradient(circle at 30% 25%,#2a3242,#151a23)}
  /* 顶部/底部压暗，保证角标与文字可读（不拦截点击） */
  .cover::after{content:'';position:absolute;inset:0;z-index:2;pointer-events:none;
       background:linear-gradient(180deg,rgba(6,8,12,.42) 0%,rgba(6,8,12,0) 34%,
                  rgba(6,8,12,0) 66%,rgba(6,8,12,.52) 100%)}
  .cover .playmask{position:absolute;inset:0;z-index:3;display:flex;align-items:center;justify-content:center;
       background:rgba(0,0,0,0);transition:.18s;font-size:34px;color:#fff;opacity:0}
  .cover:hover .playmask{background:rgba(0,0,0,.38);opacity:1}
  .badge{position:absolute;z-index:4;top:8px;left:8px;background:rgba(0,0,0,.62);
         border-radius:6px;padding:2px 8px;font-size:11px;color:#ffb3c7}
  .badge.pay{color:#ffd28a;background:rgba(120,70,0,.72)}
  .hot{position:absolute;z-index:4;top:8px;right:8px;background:rgba(0,0,0,.62);
       border-radius:6px;padding:2px 8px;font-size:11px;color:var(--ok)}
  .body{padding:11px 13px 13px;display:flex;flex-direction:column;gap:7px;flex:1}
  .name{font-weight:600;font-size:14px}
  .title{color:var(--dim);font-size:12.5px;min-height:19px;
         overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .row{display:flex;gap:6px;align-items:center;font-size:11.5px;color:var(--dim);flex-wrap:wrap}
  .row code{background:#0b0e14;border:1px solid var(--line);border-radius:5px;
            padding:1px 5px;font-size:11px;color:#9aa6ba}
  .cats{display:flex;gap:4px;flex-wrap:wrap}
  .cats i{font-style:normal;font-size:10.5px;background:#222b39;color:#9fb0c9;
          border-radius:4px;padding:1px 6px}
  .acts{display:flex;gap:7px;margin-top:auto;padding-top:6px}
  button.play,button.cp{flex:1;text-align:center;text-decoration:none;cursor:pointer;
     border-radius:9px;padding:8px 6px;font-size:12.5px;font-weight:600;
     border:1px solid transparent;transition:.15s;font-family:inherit}
  button.play{background:linear-gradient(135deg,#ff5c8a,#ff8a5c);color:#fff}
  button.play:hover{filter:brightness(1.12)}
  button.cp{background:var(--card2);color:var(--fg);border-color:var(--line);flex:0 0 70px}
  button.cp:hover{border-color:var(--accent);color:var(--accent)}
  .q{display:flex;gap:5px}
  .q button{flex:1;background:var(--card2);border:1px solid var(--line);color:var(--dim);
      border-radius:7px;padding:4px 0;font-size:11px;cursor:pointer;font-family:inherit}
  .q button.on{background:#2b3446;color:#fff;border-color:#48546b}

  .pager{display:flex;gap:6px;align-items:center;justify-content:center;
         padding:6px 20px 26px;flex-wrap:wrap}
  .pager button{background:var(--card);border:1px solid var(--line);color:var(--fg);
      border-radius:8px;min-width:36px;padding:7px 11px;font-size:13px;
      cursor:pointer;font-family:inherit;transition:.15s}
  .pager button:hover:not(:disabled){border-color:var(--accent);color:var(--accent)}
  .pager button.on{background:linear-gradient(135deg,#ff5c8a,#ff8a5c);color:#fff;
      border-color:transparent;font-weight:600}
  .pager button:disabled{opacity:.32;cursor:default}
  .pager .info{color:var(--dim);font-size:12.5px;margin:0 8px}
  .pager .gap{color:var(--dim);padding:0 2px}

  .empty{color:var(--dim);padding:60px 20px;text-align:center}
  .tip{color:var(--dim);font-size:11.5px;padding:0 20px 30px;line-height:1.7}
  .tip b{color:#c9d2e0}

  /* ---------- 播放器 ---------- */
  #modal{position:fixed;inset:0;z-index:100;display:none;
         background:rgba(6,8,12,.88);backdrop-filter:blur(6px);
         align-items:center;justify-content:center;padding:18px}
  #modal.on{display:flex}
  .player{background:#0b0e14;border:1px solid #2a3242;border-radius:16px;
          width:min(1180px,96vw);max-height:94vh;display:flex;flex-direction:column;overflow:hidden}
  .phead{display:flex;align-items:center;gap:12px;padding:11px 15px;border-bottom:1px solid var(--line)}
  .phead .t{font-weight:600;font-size:14px}
  .phead .sub{color:var(--dim);font-size:11.5px}
  .phead .close{margin-left:auto;background:var(--card2);border:1px solid var(--line);
       color:var(--fg);border-radius:8px;padding:6px 13px;cursor:pointer;font-family:inherit}
  .phead .close:hover{border-color:var(--accent);color:var(--accent)}
  .stage{background:#000;position:relative;aspect-ratio:16/9;width:100%;overflow:hidden}
  .stage .poster{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;
        filter:blur(18px) brightness(.45) saturate(1.1);opacity:0;transition:opacity .45s;
        transform:scale(1.06);pointer-events:none}
  .stage .poster.on{opacity:1}
  .stage #appPlayer,.stage video{position:absolute;inset:0;width:100%;height:100%;display:block}
  .stage video{background:#000}
  .stage .overlay{position:absolute;inset:0;display:flex;flex-direction:column;
       align-items:center;justify-content:center;gap:10px;color:#cfd7e6;font-size:13px;
       background:rgba(0,0,0,.55);text-align:center;padding:20px;z-index:5;white-space:pre-wrap}
  .stage .overlay.hide{display:none}
  .spin{width:26px;height:26px;border:3px solid #2c3648;border-top-color:var(--accent);
        border-radius:50%;animation:sp .8s linear infinite}
  @keyframes sp{to{transform:rotate(360deg)}}
  .pfoot{padding:10px 15px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;
         border-top:1px solid var(--line);font-size:12px;color:var(--dim)}
  .pfoot .stat{font-variant-numeric:tabular-nums}
  .pfoot .stat b{color:var(--ok);font-weight:600}
  .pfoot .stat i{color:var(--warn);font-style:normal}
  .pfoot .sp{flex:1}
  .pfoot button,.pfoot a{background:var(--card2);border:1px solid var(--line);color:var(--fg);
       border-radius:8px;padding:6px 12px;cursor:pointer;text-decoration:none;
       font-family:inherit;font-size:12px}
  .pfoot button:hover,.pfoot a:hover{border-color:var(--accent);color:var(--accent)}
  .pfoot .q button.on{background:#2b3446;color:#fff;border-color:#48546b}
  .pfoot .m{display:flex;gap:4px}
  .pfoot .m button{font-size:11.5px;padding:5px 10px}
  .pfoot .m button.on{background:linear-gradient(135deg,#ff5c8a,#ff8a5c);color:#fff;
       border-color:transparent;font-weight:600}
  .pfoot .m button.bad{opacity:.4;cursor:not-allowed}

  @media (max-width: 760px){
    .bar{padding:10px 12px;gap:8px}
    input[type=search]{width:100%}
    .tools{width:100%}
    .grid{grid-template-columns:repeat(auto-fill,minmax(160px,1fr));
          gap:10px;padding:12px 12px 26px}
    .body{padding:9px 10px 10px;gap:6px}
    .name{font-size:13px}
    .title{font-size:11.5px}
    nav{padding:0 12px 9px}
    #modal{padding:0}
    .player{width:100vw;max-height:100vh;border-radius:0;border:none}
    .phead{padding:10px 12px}
    .stage{aspect-ratio:9/16;max-height:68vh}
    .pfoot{padding:9px 12px;gap:7px;font-size:11.5px}
    .pfoot .q button,.pfoot .m button{padding:7px 10px;font-size:11.5px}
    .pfoot button,.pfoot a{padding:8px 12px}
    .tip{font-size:11px;padding:0 12px 24px}
    .pager{padding:6px 12px 20px}
  }
  @media (hover:none){
    .cover .playmask{opacity:1;background:rgba(0,0,0,.28)}
  }

  /* ---------- 令牌对话框 ---------- */
  .tk-btn{background:var(--card2);border:1px solid var(--line);color:var(--fg);
      border-radius:8px;padding:7px 12px;cursor:pointer;font-family:inherit;font-size:12.5px}
  .tk-btn:hover{border-color:var(--accent);color:var(--accent)}
  .tk-btn.warn{border-color:#7a5a1e;color:#ffc76a}
  #tkDlg{position:fixed;inset:0;z-index:120;display:none;align-items:center;justify-content:center;
        background:rgba(6,8,12,.86);backdrop-filter:blur(6px);padding:18px}
  #tkDlg.on{display:flex}
  .tk-card{background:#0b0e14;border:1px solid #2a3242;border-radius:14px;
      width:min(620px,96vw);padding:18px 18px 14px;display:flex;flex-direction:column;gap:10px}
  .tk-h{font-weight:600;font-size:14px}
  .tk-status{font-size:12.5px;color:var(--dim);line-height:1.6}
  .tk-status.ok{color:var(--ok)}
  .tk-status.bad{color:#ff8a8a}
  #tk-input{width:100%;height:92px;resize:vertical;background:var(--card);
      border:1px solid var(--line);border-radius:9px;color:var(--fg);
      padding:9px 11px;font-family:ui-monospace,Consolas,monospace;font-size:12px;outline:none}
  #tk-input:focus{border-color:var(--accent)}
  .tk-row{display:flex;gap:8px;flex-wrap:wrap}
  .tk-row button{background:var(--card2);border:1px solid var(--line);color:var(--fg);
      border-radius:9px;padding:8px 14px;cursor:pointer;font-family:inherit;font-size:12.5px}
  .tk-row button:hover{border-color:var(--accent);color:var(--accent)}
  #tk-save{background:linear-gradient(135deg,#ff5c8a,#ff8a5c);color:#fff;border-color:transparent;font-weight:600}
  .tk-tip{font-size:11.5px;color:var(--dim);line-height:1.6}
</style>
</head>
<body>
<header>
  <div class="bar">
    <div>
      <h1>直播间列表 <span>·</span> 91直播</h1>
      <div class="meta" id="meta"></div>
    </div>
    <div class="tools">
      <button id="tk-btn" class="tk-btn">令牌</button>
      <input type="search" id="q" placeholder="搜索主播 / 标题 / 房间ID">
      <select id="sort">
        <option value="hot">按热度</option>
        <option value="free">免费优先</option>
        <option value="name">按主播名</option>
        <option value="new">按房间ID倒序</option>
      </select>
      <select id="ps">
        <option value="12">每页 12</option>
        <option value="24" selected>每页 24</option>
        <option value="48">每页 48</option>
        <option value="96">每页 96</option>
      </select>
    </div>
  </div>
  <nav id="cats"></nav>
</header>

<div class="grid" id="grid"></div>
<div class="empty" id="empty" style="display:none">没有匹配的房间</div>
<div class="pager" id="pager"></div>

<div class="tip">
  <b>播放</b>：jessibuca <b>WASM 软解</b>直连源站 FLV（与官方网页版同款），不转码、画质无损。<br>
  <b>不受 30 秒试看限制</b>——该限制是客户端读 <code>previewTime=30</code> 后自行计时停播，网页不经过这段逻辑。<br>
  <b>令牌</b>：点右上角「令牌」可粘贴或更新 <code>authorization</code>，保存后自动刷新房间数据；
  失效时页面会自动弹窗提醒。服务端若下发新令牌会<b>自动续期</b>。<br>
  <b>必须用本地服务器打开</b>（双击 <code>start.bat</code> → <code>http://127.0.0.1:8899/</code>）。
  <code>auth_key</code> 有效期约 5 小时，过期后在「令牌」里点保存即可重新抓取。
</div>

<div id="tkDlg">
  <div class="tk-card">
    <div class="tk-h">令牌（authorization）</div>
    <div class="tk-status" id="tk-status">检测中…</div>
    <textarea id="tk-input" placeholder="粘贴 authorization（很长的一串十六进制）后点「保存并刷新数据」"></textarea>
    <div class="tk-row">
      <button id="tk-save">保存并刷新数据</button>
      <button id="tk-check">仅校验</button>
      <button id="tk-close">关闭</button>
    </div>
    <div class="tk-tip">
      令牌来自 App / 网页登录后的请求头 <code>authorization</code>，有效期有限。
      失效后在此重新粘贴即可，无需改文件或重启服务。<br>
      若服务端在响应头下发了新令牌，系统会自动保存（自动续期）。
    </div>
  </div>
</div>

<div id="modal">
  <div class="player">
    <div class="phead">
      <div>
        <div class="t" id="p-title">—</div>
        <div class="sub" id="p-sub"></div>
      </div>
      <button class="close" id="p-close">关闭 ✕</button>
    </div>
    <div class="stage">
      <img class="poster" id="p-poster" alt="">
      <div id="appPlayer"></div>
      <video id="video" controls playsinline webkit-playsinline preload="none" style="display:none"></video>
      <div class="overlay" id="p-overlay">
        <div class="spin"></div>
        <div id="p-msg">正在连接直播流…</div>
      </div>
    </div>
    <div class="pfoot">
      <div class="q" id="p-q"></div>
      <div class="stat" id="p-stat"></div>
      <div class="sp"></div>
      <button id="p-reload">重新连接</button>
      <a id="p-pot" href="#" target="_blank">PotPlayer</a>
      <button id="p-copy">复制地址</button>
    </div>
  </div>
</div>

<script src="./jessibuca.js"></script>
<script>
const ROOMS = __DATA__;
const CATS  = __CATS__;
const BUILD = "__BUILD__";
const IMG_BASES = [
  "https://rgf.rucqnij.xyz",
  "https://utp.opuxrcm.com",
  "https://ytt.jvjzmc.com"
];

/* 封面图地址链路
   源站图床（imgEncryDomain = rgf.rucqnij.xyz）在真实 JPEG 前面多塞了 1 个字节做防盗链，
   浏览器直接 <img src> 解码失败 = 页面上看不到任何封面。
   因此优先走本地服务的 /img 代理（剥掉前缀字节后下发，带磁盘缓存），
   代理不可用时再退回各图床直连（大概率仍然解码失败，仅作兜底）。*/
function coverSources(path){
  if(!path) return [];
  if(/^https?:\/\//i.test(path)) return [path];
  const list = [];
  if(location.protocol === 'http:' || location.protocol === 'https:'){
    list.push('/img?u=' + encodeURIComponent(path));
  }
  IMG_BASES.forEach(b => list.push(b + path));
  return list;
}

const CAT_COUNT = {};
CATS.forEach(c => CAT_COUNT[c] = 0);
ROOMS.forEach(r => (r.cats || []).forEach(c => {
  if (CAT_COUNT[c] === undefined) CAT_COUNT[c] = 0;
  CAT_COUNT[c]++;
}));

let curCat  = "全部";
let curPage = 1;
let pageSize = 24;
const quality = {};

const $grid  = document.getElementById('grid');
const $empty = document.getElementById('empty');
const $pager = document.getElementById('pager');
const $cats  = document.getElementById('cats');

document.getElementById('meta').textContent =
  `共 ${ROOMS.length} 个在线房间 · ${CATS.length} 个类别 · 数据时间 ${BUILD}`;

function el(tag, cls, txt){
  const e = document.createElement(tag);
  if(cls) e.className = cls;
  if(txt !== undefined) e.textContent = txt;
  return e;
}

/* 1V1 常见：原画位为空，只有 360/720（_one_ 前缀） */
function defaultQuality(r){
  if(r.url0) return 'url0';
  if(r.url720) return 'url720';
  if(r.url360) return 'url360';
  return 'url0';
}
function pickUrl(r){
  const want = quality[r.roomId] || defaultQuality(r);
  return r[want] || r.url0 || r.url720 || r.url360 || '';
}
function isOneToOne(r){
  if((r.cats || []).includes('1V1表演')) return true;
  const u = (r.url0 || '') + (r.url360 || '') + (r.url720 || '');
  return u.indexOf('_one_') >= 0;
}

/* ============ 播放器 ============ */
const modal    = document.getElementById('modal');
const video    = document.getElementById('video');
const appPlayer= document.getElementById('appPlayer');
const overlay  = document.getElementById('p-overlay');
const pMsg     = document.getElementById('p-msg');
const pTitle   = document.getElementById('p-title');
const pSub     = document.getElementById('p-sub');
const pStat    = document.getElementById('p-stat');
const pQ       = document.getElementById('p-q');
const pPot     = document.getElementById('p-pot');
const pCopy    = document.getElementById('p-copy');
const pReload  = document.getElementById('p-reload');
const pPoster  = document.getElementById('p-poster');

/* 播放器背景：连接期间先铺一张模糊封面，避免黑屏 */
function setPoster(r){
  const srcs = coverSources(r && r.cover);
  pPoster.classList.remove('on');
  if(!srcs.length){ pPoster.removeAttribute('src'); return; }
  let i = 0;
  pPoster.onload  = () => pPoster.classList.add('on');
  pPoster.onerror = () => { i++; if(i < srcs.length) pPoster.src = srcs[i];
                            else pPoster.classList.remove('on'); };
  pPoster.src = srcs[0];
}

let jess   = null;      // jessibuca
let curRoom = null;
let statTimer = null;
let playStart = 0;
let lastBytes = 0;
let curBitrate = 0;

function destroyAll(){
  if(jess){
    try{ jess.destroy(); }catch(_){}
    jess = null;
  }
  try{
    video.pause();
    video.removeAttribute('src');
    video.load();
  }catch(_){}
  if(statTimer){ clearInterval(statTimer); statTimer = null; }
}

function showMsg(text, spinning){
  overlay.classList.remove('hide');
  pMsg.textContent = text;
  const sp = overlay.querySelector('.spin');
  if(sp) sp.style.display = spinning ? 'block' : 'none';
}
function hideMsg(){ overlay.classList.add('hide'); }

function fmtBytes(n){
  if(n > 1048576) return (n/1048576).toFixed(1) + ' MB';
  if(n > 1024) return (n/1024).toFixed(0) + ' KB';
  return n + ' B';
}

function updateStat(){
  const sec = Math.round((Date.now() - playStart)/1000);
  const over = sec >= 30;
  pStat.innerHTML =
    `已播 <b>${sec}s</b>` +
    (over ? ' <i>已越过 30s 限制</i>' : '') +
    (lastBytes ? ` · ${fmtBytes(lastBytes)}` : '') +
    (curBitrate ? ` · ${(curBitrate/1000).toFixed(0)} KB/s` : '') +
    ` · WASM`;
}

function playRoom(r){
  curRoom = r;
  pTitle.textContent = r.userName + (r.roomName ? ' · ' + r.roomName : '');
  pSub.textContent = `${r.roomId} · payType=${r.payType ?? '-'} · moneyFen=${r.moneyFen || 0}` +
                     (r.moneyFen > 0 ? '（收费房）' : '（免费）');
  setPoster(r);

  pQ.textContent = '';
  [['url0','原画'],['url720','720P'],['url360','360P']].forEach(([k,label])=>{
    const b = el('button', null, label);
    if((quality[r.roomId]||defaultQuality(r)) === k) b.classList.add('on');
    if(!r[k]){ b.disabled = true; b.style.opacity = .3; b.style.cursor='default'; }
    b.onclick = () => {
      quality[r.roomId] = k;
      pQ.querySelectorAll('button').forEach(x=>x.classList.remove('on'));
      b.classList.add('on');
      startPlay();
    };
    pQ.appendChild(b);
  });

  modal.classList.add('on');
  startPlay();
}

function startPlay(){
  const r = curRoom;
  if(!r) return;
  const rawUrl = pickUrl(r);
  pPot.href = 'potplayer://' + rawUrl;

  destroyAll();
  lastBytes = 0; curBitrate = 0;
  playStart = Date.now();
  updateStat();
  statTimer = setInterval(updateStat, 500);

  if(r.oneToOneState === 2){
    showMsg('主播正在 1V1 表演中。\n服务端此时不下发视频流，需先购买表演项目才能观看。', false);
    return;
  }
  if(!rawUrl){
    showMsg('该房间当前没有可播放的推流地址（可能未开播或已下播）', false);
    return;
  }

  startWasm(rawUrl);
}
/* ---------- WASM 直连（jessibuca，软解 HEVC） ---------- */
function startWasm(rawUrl){
  if(!window.Jessibuca){
    showMsg('jessibuca.js 未加载，请确认 web 目录下有 jessibuca.js / decoder.js / decoder.wasm', false);
    return;
  }
  appPlayer.style.display = 'block';
  video.style.display = 'none';
  showMsg('正在连接直播流（WASM 软解）…', true);

  if(!jess){
    jess = new window.Jessibuca({
      container: appPlayer,
      decoder: './decoder.js',
      videoBuffer: 0.15,
      loadingText: '',
      debug: false,
      isNotMute: true,
      hasVideo: true,
      hasAudio: true,
      keepScreenOn: true,
      forceNoOffscreen: false,
      useWCS: true,
      autoWasm: true,
      heartTimeoutReplay: true,
      heartTimeoutReplayTimes: 2,
      text: ''
    });
    jess.on('error', (e) => {
      console.warn('jessibuca error', e);
      showMsg('WASM 播放出错：' + e + '\n可点「重新连接」重试，或检查网络。', false);
    });
    jess.on('timeout', () => showMsg('拉流超时。可点「重新连接」重试。', false));
    jess.on('videoInfo', (info) => {
      try{
        if(info && info.width) pSub.textContent += ` · ${info.width}x${info.height}`;
      }catch(_){}
    });
  }
  const doPlay = () => {
    try{
      const p = jess.play(rawUrl);
      if(p && p.then) p.then(()=>{ hideMsg(); updateStat(); }).catch(err=>showMsg('播放失败：'+err, false));
      else setTimeout(hideMsg, 800);
    }catch(e){ showMsg('播放失败：' + e.message, false); }
  };
  let loaded = false;
  try{ loaded = jess.hasLoaded && jess.hasLoaded(); }catch(_){ loaded = false; }
  if(loaded) doPlay();
  else jess.on('load', doPlay);
}

function closePlayer(){
  destroyAll();
  modal.classList.remove('on');
  curRoom = null;
}
document.getElementById('p-close').onclick = closePlayer;
pReload.onclick = () => { if(curRoom) startPlay(); };
modal.addEventListener('click', (e) => { if(e.target === modal) closePlayer(); });
document.addEventListener('keydown', (e) => {
  if(e.key === 'Escape' && modal.classList.contains('on')) closePlayer();
});

pCopy.onclick = async () => {
  const u = curRoom ? pickUrl(curRoom) : '';
  try{ await navigator.clipboard.writeText(u); pCopy.textContent = '已复制'; }
  catch(_){
    const ta = document.createElement('textarea');
    ta.value = u; document.body.appendChild(ta); ta.select();
    document.execCommand('copy'); ta.remove(); pCopy.textContent = '已复制';
  }
  setTimeout(()=>pCopy.textContent='复制地址', 1200);
};

/* ============ 令牌管理 ============ */
const tkDlg    = document.getElementById('tkDlg');
const tkStatus = document.getElementById('tk-status');
const tkInput  = document.getElementById('tk-input');
const tkBtn    = document.getElementById('tk-btn');

function setTkStatus(txt, cls){
  tkStatus.textContent = txt;
  tkStatus.className = 'tk-status' + (cls ? ' ' + cls : '');
}

function renderToken(j){
  if(!j) return;
  if(!j.has_token){
    setTkStatus('未设置令牌，请粘贴后再试', 'bad');
    tkBtn.classList.add('warn');
    return;
  }
  if(j.valid){
    setTkStatus('令牌有效 · ' + j.preview + (j.source === 'rotated' ? ' · 服务端已自动续期' : ''), 'ok');
    tkBtn.classList.remove('warn');
  }else{
    setTkStatus('令牌已失效：' + (j.msg || '未知原因') + ' · ' + j.preview, 'bad');
    tkBtn.classList.add('warn');
  }
}

async function checkToken(force){
  try{
    const r = await fetch('/api/token' + (force ? '?force=1' : ''), {cache:'no-store'});
    const j = await r.json();
    renderToken(j);
    return j;
  }catch(e){
    setTkStatus('无法连接本地服务：' + e.message, 'bad');
    return null;
  }
}

tkBtn.onclick = () => { tkDlg.classList.add('on'); checkToken(false); };
document.getElementById('tk-close').onclick = () => tkDlg.classList.remove('on');
document.getElementById('tk-check').onclick = () => { setTkStatus('校验中…'); checkToken(true); };

document.getElementById('tk-save').onclick = async () => {
  const t = tkInput.value.trim();
  if(!t){ setTkStatus('请先粘贴令牌', 'bad'); return; }
  setTkStatus('校验令牌中…');
  try{
    const r = await fetch('/api/token', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({token: t})
    });
    const j = await r.json();
    if(!j.ok){ setTkStatus('保存失败：' + (j.msg || ''), 'bad'); return; }
    setTkStatus('令牌已保存，正在刷新房间数据（约 30 秒）…');
    const r2 = await fetch('/api/refresh', {method:'POST'});
    const j2 = await r2.json();
    if(j2.ok){
      setTkStatus('完成，即将重新加载页面', 'ok');
      setTimeout(()=>location.reload(), 800);
    }else{
      setTkStatus('刷新失败：' + (j2.msg || ''), 'bad');
    }
  }catch(e){
    setTkStatus('请求失败：' + e.message, 'bad');
  }
};

/* 启动时自检：令牌缺失/失效就自动弹窗 */
(async () => {
  const j = await checkToken(false);
  if(j && (!j.has_token || !j.valid)) tkDlg.classList.add('on');
})();

/* ============ 列表 ============ */
function renderCats(){
  $cats.textContent = '';
  const all = el('button', null, '全部');
  all.appendChild(el('span','n', String(ROOMS.length)));
  if(curCat === '全部') all.classList.add('on');
  all.onclick = () => { curCat = '全部'; curPage = 1; renderCats(); render(); };
  $cats.appendChild(all);

  CATS.forEach(c => {
    const b = el('button', null, c);
    b.appendChild(el('span','n', String(CAT_COUNT[c] || 0)));
    if(curCat === c) b.classList.add('on');
    b.onclick = () => { curCat = c; curPage = 1; renderCats(); render(); };
    $cats.appendChild(b);
  });
}

function makeCover(r){
  const wrap = el('div','cover');
  const ph = el('div','ph', (r.userName||'?').slice(0,1));
  wrap.appendChild(ph);

  // 按 coverSources() 的顺序逐个尝试：本地代理 → 各图床直连
  const srcs = coverSources(r.cover);
  if(srcs.length){
    let i = 0;
    const img = document.createElement('img');
    img.alt = r.userName || '';
    img.loading = 'lazy';
    img.referrerPolicy = 'no-referrer';
    img.onerror = () => {
      i++;
      if(i < srcs.length) img.src = srcs[i];
      else img.remove();   // 全部失败 → 只留占位字母
    };
    img.src = srcs[0];
    wrap.appendChild(img);
  }
  const isPay = (r.moneyFen || 0) > 0;
  const performing = r.oneToOneState === 2;
  let t = r.liveType === 1 ? '视频直播' : (r.liveType === 2 ? '语音房' : '直播');
  if(performing) t = '1V1表演中';
  else if(isOneToOne(r)) t = '1V1 · ' + t;
  if(isPay && !performing) t += ' · 收费';
  wrap.appendChild(el('div', 'badge' + ((isPay || performing) ? ' pay' : ''), t));
  wrap.appendChild(el('div','hot', '🔥 ' + r.hot));
  wrap.appendChild(el('div','playmask', '▶'));
  wrap.onclick = () => playRoom(r);
  return wrap;
}

function makeCard(r){
  const card = el('div','card');
  card.appendChild(makeCover(r));

  const body = el('div','body');
  body.appendChild(el('div','name', r.userName));
  body.appendChild(el('div','title', r.roomName || '（无标题）'));

  const row = el('div','row');
  row.appendChild(el('code', null, r.roomId));
  if(r.exp){
    const left = Math.round((r.exp*1000 - Date.now())/60000);
    const tag = el('span', null, left > 0 ? `有效 ${left} 分钟` : '已过期');
    tag.style.color = left > 0 ? 'var(--ok)' : '#ff7a7a';
    row.appendChild(tag);
  }
  if((r.moneyFen || 0) > 0){
    const tag = el('span', null, `门票 ¥${(r.moneyFen/100).toFixed(0)}`);
    tag.style.color = 'var(--warn)';
    row.appendChild(tag);
  }
  body.appendChild(row);

  if(r.cats && r.cats.length){
    const cs = el('div','cats');
    r.cats.forEach(c => cs.appendChild(el('i', null, c)));
    body.appendChild(cs);
  }

  const q = el('div','q');
  [['url0','原画'],['url720','720P'],['url360','360P']].forEach(([k,label])=>{
    const b = el('button', null, label);
    if((quality[r.roomId]||defaultQuality(r))===k) b.classList.add('on');
    if(!r[k]) { b.disabled = true; b.style.opacity = .35; b.style.cursor='default'; }
    b.onclick = () => {
      quality[r.roomId] = k;
      q.querySelectorAll('button').forEach(x=>x.classList.remove('on'));
      b.classList.add('on');
    };
    q.appendChild(b);
  });
  body.appendChild(q);

  const acts = el('div','acts');
  const play = el('button','play','▶ 播放');
  play.style.flex = '1';
  play.onclick = () => playRoom(r);
  acts.appendChild(play);

  // 复制当前所选清晰度的播放地址
  const cp = el('button','cp','复制');
  cp.style.flex = '0 0 52px';
  cp.title = '复制播放地址';
  cp.onclick = async (ev) => {
    ev.stopPropagation();
    const u = pickUrl(r);
    if(!u) return;
    try{ await navigator.clipboard.writeText(u); }
    catch(_){
      const ta = document.createElement('textarea');
      ta.value = u; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); ta.remove();
    }
    cp.textContent = '已复制';
    setTimeout(()=>{ cp.textContent = '复制'; }, 1200);
  };
  acts.appendChild(cp);

  const pp = el('button','cp','Pot');
  pp.style.flex = '0 0 46px';
  pp.onclick = (ev) => {
    ev.stopPropagation();
    const u = pickUrl(r);
    if(u) window.open('potplayer://' + u, '_blank');
  };
  acts.appendChild(pp);
  body.appendChild(acts);

  card.appendChild(body);
  return card;
}

function renderPager(total, totalPages){
  $pager.textContent = '';
  if(totalPages <= 1){
    if(total) $pager.appendChild(el('span','info', `共 ${total} 个房间`));
    return;
  }
  const mk = (label, page, opts={}) => {
    const b = el('button', null, label);
    if(page === curPage && !opts.nav) b.classList.add('on');
    if(opts.disabled) b.disabled = true;
    if(!opts.disabled) b.onclick = () => {
      curPage = page; render();
      document.querySelector('header').scrollIntoView({behavior:'smooth', block:'start'});
    };
    return b;
  };
  $pager.appendChild(mk('‹ 上一页', Math.max(1, curPage-1), {nav:true, disabled:curPage===1}));
  const win = new Set([1, totalPages, curPage, curPage-1, curPage+1, curPage-2, curPage+2]);
  const pages = [...win].filter(p => p >= 1 && p <= totalPages).sort((a,b)=>a-b);
  let prev = 0;
  for(const p of pages){
    if(prev && p - prev > 1) $pager.appendChild(el('span','gap','…'));
    $pager.appendChild(mk(String(p), p));
    prev = p;
  }
  $pager.appendChild(mk('下一页 ›', Math.min(totalPages, curPage+1), {nav:true, disabled:curPage===totalPages}));
  $pager.appendChild(el('span','info', `第 ${curPage}/${totalPages} 页 · 共 ${total} 个`));
}

function render(){
  const q = document.getElementById('q').value.trim().toLowerCase();
  const sort = document.getElementById('sort').value;
  pageSize = parseInt(document.getElementById('ps').value, 10) || 24;

  let list = ROOMS.filter(r => {
    if(curCat !== '全部' && !(r.cats || []).includes(curCat)) return false;
    if(!q) return true;
    return r.userName.toLowerCase().includes(q)
        || r.roomName.toLowerCase().includes(q)
        || r.roomId.includes(q);
  });

  if(sort === 'hot')  list.sort((a,b)=> b.hot - a.hot);
  if(sort === 'free') list.sort((a,b)=> (b.moneyFen>0?1:0) - (a.moneyFen>0?1:0) || b.hot - a.hot);
  if(sort === 'name') list.sort((a,b)=> a.userName.localeCompare(b.userName,'zh'));
  if(sort === 'new')  list.sort((a,b)=> b.roomId.localeCompare(a.roomId));

  const totalPages = Math.max(1, Math.ceil(list.length / pageSize));
  if(curPage > totalPages) curPage = totalPages;
  const start = (curPage - 1) * pageSize;
  const pageItems = list.slice(start, start + pageSize);

  $grid.textContent = '';
  pageItems.forEach(r => $grid.appendChild(makeCard(r)));

  $empty.style.display = list.length ? 'none' : 'block';
  $empty.textContent = curCat === '全部' ? '没有匹配的房间' : `「${curCat}」下没有匹配的房间`;

  renderPager(list.length, totalPages);
}

document.getElementById('q').addEventListener('input', () => { curPage = 1; render(); });
document.getElementById('sort').addEventListener('change', () => { curPage = 1; render(); });
document.getElementById('ps').addEventListener('change', () => { curPage = 1; render(); });

renderCats();
render();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()
