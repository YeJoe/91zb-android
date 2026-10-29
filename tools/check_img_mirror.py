#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 CDN「加密路径 ⇄ 明文路径」的映射是否普遍成立。

结论(2026-10-30 实测):
  .../images/<宽>/u/xx/yy/zz/head/a.jpg   → 加密流
  .../static/upload/u/xx/yy/zz/head/a.jpg → 明文原图
  .../files/admin_res/<p>/pic/a.png       → 加密流
  .../static/upload/admin_res/<p>/pic/a.png → 明文原图
"""
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "http://drdue73ptt7o.cloudfront.net"
MAGIC = {b"\x89PNG": "PNG", b"\xff\xd8\xff": "JPEG", b"<mode": "ENC", b"GIF8": "GIF"}


def kind(url, n=16):
    try:
        r = urllib.request.urlopen(url, timeout=15)
        head = r.read(n)
        cl = r.headers.get("Content-Length") or "?"
        for m, name in MAGIC.items():
            if head.startswith(m):
                return "%s(%s)" % (name, cl)
        return "?" + head[:8].hex()
    except Exception as e:
        return "ERR:%s" % str(e)[:30]


def to_plain(url):
    """加密 CDN 路径 → 明文镜像路径"""
    u = url
    m = re.search(r"/images/\d+/", u)
    if m:
        return u[: m.start()] + "/static/upload/" + u[m.end():]
    if "/files/admin_res/" in u:
        return u.replace("/files/admin_res/", "/static/upload/admin_res/", 1)
    return u


def main():
    data_js = os.path.join(ROOT, "site", "data.js")
    print("repo:", ROOT, "data.js exists:", os.path.exists(data_js))
    cases = []
    if os.path.exists(data_js):
        d = open(data_js, encoding="utf-8", errors="replace").read()
        rels = list(dict.fromkeys(re.findall(r'"(/static/upload/[^"]+\.(?:jpg|png|jpeg))"', d)))
        for rel in rels[:5]:
            # 用加密形态当输入: /static/upload/x -> /images/256/x
            cases.append((BASE + rel.replace("/static/upload/", "/images/256/", 1), BASE + rel))
    cases += [
        (BASE + "/files/admin_res/2026021007/pic/dec63fabb16021d24bd4579d331948c5.png",
         BASE + "/static/upload/admin_res/2026021007/pic/dec63fabb16021d24bd4579d331948c5.png"),
        (BASE + "/files/admin_res/2026021007/pic/ef30f970786179d89003a88adeaf1fa4.png",
         BASE + "/static/upload/admin_res/2026021007/pic/ef30f970786179d89003a88adeaf1fa4.png"),
    ]

    ok = bad = 0
    for enc, plain in cases:
        kp = kind(plain)
        ke = kind(enc)
        good = kp.startswith(("PNG", "JPEG", "GIF")) and ke.startswith("ENC")
        print("\n enc    %-90s -> %s\n mirror %-90s -> %s  %s" % (enc[-90:], ke, plain[-90:], kp, "OK" if good else "MISMATCH"))
        ok += 1 if good else 0
        bad += 0 if good else 1
    print("\n一致=%d 异常=%d" % (ok, bad))


if __name__ == "__main__":
    main()
