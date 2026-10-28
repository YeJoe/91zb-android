# -*- coding: utf-8 -*-
"""
按类别枚举 91直播(4.3.3) 全部在线直播间。

类别来源：/home/recommend/cls  ->  8 个分类，字段 fixedType 即 type 参数
房间来源：/home/default/cls/query  {type, pageNo, pageSize}   pageSize 上限 20

用法:
  python jj_rooms.py --token <authorization>
  python jj_rooms.py --token <authorization> --out rooms_all.json --quiet
"""
import argparse
import json
import re
import sys
import time

sys.path.insert(0, ".")
from jj_client import JJClient

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PAGE_SIZE_MAX = 20          # 服务端硬限制，超过会静默返回空
CLS_API = "/home/recommend/cls"
ROOM_API = "/home/default/cls/query"
REC_API = "/home/rec"

TOKEN_RE = re.compile(r"[0-9a-fA-F]{32,}")


def load_token(path="token.txt"):
    """从 token.txt 读令牌。

    兼容三种情况：
      * 正常 UTF-8 文本
      * 被 cmd 用 GBK 写进来的提示文字（如 “ECHO 处于关闭状态。”）
      * 带引号、换行、前后空白的粘贴
    做法是逐个编码尝试解码，再从文本里提取第一个十六进制令牌。
    """
    import os
    p = path if os.path.isabs(path) else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), path)
    if not os.path.isfile(p):
        return None
    try:
        raw = open(p, "rb").read()
    except Exception:
        return None
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            txt = raw.decode(enc)
        except Exception:
            continue
        m = TOKEN_RE.search(txt)
        if m:
            return m.group(0)
    return None


def clean(o):
    """清洗 xorCode 可能产生的孤立代理字符，保证可写盘。"""
    if isinstance(o, str):
        return o.encode("utf-8", "replace").decode("utf-8")
    if isinstance(o, list):
        return [clean(v) for v in o]
    if isinstance(o, dict):
        return {k: clean(v) for k, v in o.items()}
    return o


def find_rooms(js):
    """递归找出响应里所有含 roomId 的对象。"""
    out = []

    def walk(o):
        if isinstance(o, dict):
            if o.get("roomId"):
                out.append(o)
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(js)
    return out


def exp_of(url):
    m = re.search(r"auth_key=(\d{9,})-", url or "")
    return int(m.group(1)) if m else 0


def fetch_categories(cli):
    """拉取分类列表；失败时退回内置默认表。"""
    try:
        js = cli.call(CLS_API, {}).get("json") or {}
        cats = js.get("data")
        if isinstance(cats, list) and cats:
            out = []
            for c in cats:
                if not isinstance(c, dict):
                    continue
                t = c.get("fixedType")
                if t is None:
                    continue
                out.append({
                    "id": t,
                    "name": str(c.get("clsName") or f"类别{t}"),
                    "fixedType": t,
                    "isHot": c.get("isHot"),
                })
            if out:
                return out
    except Exception as e:
        print(f"  [警告] 拉取分类失败: {e}")
    return [{"id": 1, "name": "关注", "fixedType": 1},
            {"id": 2, "name": "热门", "fixedType": 2},
            {"id": 3, "name": "颜值", "fixedType": 0},
            {"id": 4, "name": "新人", "fixedType": 4},
            {"id": 5, "name": "收费", "fixedType": 5},
            {"id": 6, "name": "游戏", "fixedType": 6},
            {"id": 9, "name": "帖子", "fixedType": 9},
            {"id": 10, "name": "1V1表演", "fixedType": 10}]


def main() -> None:
    ap = argparse.ArgumentParser(description="按类别枚举全部在线直播间")
    ap.add_argument("--host", default="https://utp.opuxrcm.com")
    ap.add_argument("--token", default=None, help="authorization 令牌；省略则读 token.txt")
    ap.add_argument("--token-file", default="token.txt")
    ap.add_argument("--device", default="1bbd0f822e77afd060cd5e8cc1ac147c")
    ap.add_argument("--page-size", type=int, default=PAGE_SIZE_MAX)
    ap.add_argument("--max-pages", type=int, default=40)
    ap.add_argument("--sleep", type=float, default=0.1)
    ap.add_argument("--out", default="rooms_all.json")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--no-rec", action="store_true", help="跳过 /home/rec 兜底")
    a = ap.parse_args()

    if not a.token:
        a.token = load_token(a.token_file)
    if not a.token:
        print("[错误] 没有可用令牌。")
        print("  方式一： python jj_rooms.py --token <authorization>")
        print("  方式二： 把 authorization 写入 token.txt")
        sys.exit(1)

    page_size = min(a.page_size, PAGE_SIZE_MAX)
    cli = JJClient(a.host, a.token, device_uid=a.device)

    print(f"目标域名: {a.host}")

    # 先探一次，令牌失效时避免"成功"写出 0 个房间
    probe = cli.call(CLS_API, {})
    pj = probe.get("json") or {}
    if probe.get("status") not in (200, None) or pj.get("code") not in (0, None):
        print(f"[错误] 令牌无效或已过期（HTTP {probe.get('status')}, "
              f"code={pj.get('code')}, msg={str(pj.get('msg'))[:60]}）")
        sys.exit(2)

    cats = fetch_categories(cli)
    print(f"分类共 {len(cats)} 个: " + " / ".join(c["name"] for c in cats))
    print(f"每页 {page_size} 条\n")

    rooms = {}          # roomId -> 房间对象
    room_cat = {}       # roomId -> 出现过的类别名列表

    print("=== 按类别分页抓取 ===")
    for c in cats:
        t = c["fixedType"]
        got, total = 0, None
        for pn in range(1, a.max_pages + 1):
            try:
                js = cli.call(ROOM_API,
                              {"type": t, "pageNo": pn, "pageSize": page_size}).get("json") or {}
            except Exception as e:
                print(f"  {c['name']:<8} pageNo={pn} 异常: {e}")
                break
            if js.get("code") != 0:
                break
            rs = find_rooms(js)
            if not rs:
                break
            total = js.get("count")
            for r in rs:
                rid = str(r["roomId"])
                rooms.setdefault(rid, r)
                room_cat.setdefault(rid, [])
                if c["name"] not in room_cat[rid]:
                    room_cat[rid].append(c["name"])
            got += len(rs)
            if len(rs) < page_size:
                break
            if a.sleep:
                time.sleep(a.sleep)

        print(f"  {c['name']:<8} type={t:<3} 返回={got:<5} 服务端总数={total}")
        if a.sleep:
            time.sleep(a.sleep)

    n_cls = len(rooms)

    # ── 兜底：/home/rec（未标记类别的房间） ───────────────────
    if not a.no_rec:
        added = 0
        for c in cats:
            try:
                js = cli.call(REC_API, {"type": c["fixedType"]}).get("json") or {}
            except Exception:
                continue
            for r in find_rooms(js):
                rid = str(r["roomId"])
                if rid not in rooms:
                    rooms[rid] = r
                    room_cat[rid] = ["未分类"]
                    added += 1
            if a.sleep:
                time.sleep(a.sleep)
        if added:
            print(f"\n/home/rec 兜底新增: {added}")

    print(f"\ncls/query 得到 {n_cls} 个，最终去重合计 {len(rooms)} 个直播间")

    if not rooms:
        print("[错误] 没有获取到任何房间，未生成数据文件。")
        print("        常见原因：令牌过期、接口限流、或域名不可达。")
        sys.exit(3)

    # ── 组装输出：房间数据 + 类别归属 ────────────────────────
    out_rooms = {}
    for rid, r in rooms.items():
        r = clean(r)
        r["_cats"] = room_cat.get(rid, [])
        r["_exp"] = exp_of(str(r.get("pullStreamAddr") or ""))
        out_rooms[rid] = r

    payload = {
        "generated": time.strftime("%Y-%m-%d %H:%M:%S"),
        "host": a.host,
        "categories": cats,
        "count": len(out_rooms),
        "rooms": out_rooms,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=True, indent=1)
    print(f"已保存 {a.out}")

    print("\n=== 各类别房间数 ===")
    for c in cats:
        n = sum(1 for r in out_rooms.values() if c["name"] in r["_cats"])
        print(f"  {c['name']:<10} {n:>4}")

    if not a.quiet:
        print(f"\n{'roomId':<24}{'主播':<14}{'热度':<8}类别")
        for rid, x in list(out_rooms.items())[:30]:
            print(f"{rid:<24}{str(x.get('userName'))[:12]:<14}"
                  f"{str(x.get('currentHot')):<8}{'/'.join(x['_cats'])}")


if __name__ == "__main__":
    main()
