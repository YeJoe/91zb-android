#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 site/data.js 转成 App 用的数据源 app-data/rooms.json。

为什么要有这一步：
    网页版的 data.js 带了 216 个房间的完整接口字段（avatar/title/chess/battle/pay…），
    其中 avatar 是相对路径、挂在打不通的接口域名上，App 用不到。
    App 只需要"能列出房间 + 能拼出 rtmp 地址"的最小集合，所以这里做一次瘦身。

用法：
    python tools/build_app_data.py                    # 默认读 site/data.js → app-data/rooms.json
    python tools/build_app_data.py --pretty           # 带缩进，便于人肉 diff
    python tools/build_app_data.py -i x.js -o y.json

输出结构（App 端 RoomSource 直接反序列化）：
    {
      "v": 1,
      "buildAt": "2026-10-29 01:33:26",
      "rtmpDomain": "xxx.hebeisaixin.com",
      "cats": [{"name": "交友", "count": 50}, ...],
      "rooms": [
        {"id": 64378161, "name": "今晚12点见哦", "cat": "颜值",
         "online": 48580, "city": "黃浦區", "ad": 0, "coins": 10}
      ]
    }

字段约定：
    id      房间号，同时就是 rtmp 流名（rtmp://<domain>/live/<id>）
    ad      1 = 推广/博彩位，App 端默认过滤
    coins   礼物钻数，0 = 无付费，用来标记"高价值房"
    city    归属地，接口给的是繁体地名，原样保留
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEF_IN = ROOT / "site" / "data.js"
DEF_OUT = ROOT / "app-data" / "rooms.json"

# App 端只保留这些字段，顺序即输出顺序（短键名省体积）
KEEP = ("id", "name", "cat", "online", "city", "ad", "coins")


def load_js(path: Path) -> dict:
    """从 data.js 里抠出 ROOMS / CATS / BUILD_AT / RTMP_DOMAIN。"""
    src = path.read_text(encoding="utf-8")

    def grab(name: str):
        m = re.search(r"window\.%s\s*=\s*(.+?);\s*$" % name, src, re.M | re.S)
        if not m:
            return None
        return json.loads(m.group(1))

    rooms = grab("ROOMS")
    if not rooms:
        raise SystemExit("没在 %s 里找到 window.ROOMS" % path)

    cats_raw = grab("CATS") or []
    build_at = grab("BUILD_AT") or ""
    domain = grab("RTMP_DOMAIN") or ""
    if isinstance(build_at, str):
        pass
    return {
        "rooms": rooms,
        "cats": [{"name": c[0], "count": c[1]} for c in cats_raw if c],
        "buildAt": build_at,
        "rtmpDomain": domain,
    }


def slim(rooms: list) -> list:
    out = []
    for r in rooms:
        item = {}
        for k in KEEP:
            v = r.get(k)
            if k == "name":
                v = (v or "").strip()
            elif k in ("online", "ad", "coins"):
                v = int(v or 0)
            elif k == "cat":
                v = v or "其他"
            elif k == "city":
                v = v or ""
                if v == "火星":
                    v = ""
            item[k] = v
        if not item["id"]:
            continue
        out.append(item)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-i", "--input", default=str(DEF_IN))
    ap.add_argument("-o", "--output", default=str(DEF_OUT))
    ap.add_argument("--pretty", action="store_true", help="缩进输出，便于 review")
    a = ap.parse_args()

    data = load_js(Path(a.input))
    rooms = slim(data["rooms"])

    # 分类统计以实际房间为准，避免 CATS 里的 count 是抓取时的分页上限
    counts: dict[str, int] = {}
    for r in rooms:
        counts[r["cat"]] = counts.get(r["cat"], 0) + 1
    cats = sorted(({"name": k, "count": v} for k, v in counts.items()),
                  key=lambda x: -x["count"])

    payload = {
        "v": 1,
        "buildAt": data["buildAt"],
        "rtmpDomain": data["rtmpDomain"],
        "cats": cats,
        "rooms": rooms,
    }

    out = Path(a.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=1 if a.pretty else None,
                      separators=None if a.pretty else (",", ":"))
    out.write_text(text + "\n", encoding="utf-8")

    real = [r for r in rooms if not r["ad"]]
    print("写入 %s" % out.relative_to(ROOT) if out.is_relative_to(ROOT) else out)
    print("  房间 %d 个（推广位 %d 个）· 分类 %d 类 · %.1f KB"
          % (len(rooms), len(rooms) - len(real), len(cats), out.stat().st_size / 1024))
    print("  构建时间 %s · 域名 %s" % (data["buildAt"] or "?", data["rtmpDomain"] or "?"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
