# -*- coding: utf-8 -*-
"""
从 4.3.3 的 pp.txt（对象池转储）中提取全部接口路径。

pp.txt 形如：  [pp+0x2fa40] String: "/home/rec"
字符串常量未参与混淆，因此可完整枚举 App 的全部 API。

用法：python extract_endpoints.py <pp.txt> [输出前缀]
"""
import os
import re
import sys
from collections import defaultdict

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# [pp+0x2fa40] String: "/home/rec"
LINE_RE = re.compile(r'\[pp\+(0x[0-9a-fA-F]+)\]\s+String:\s+"([^"]*)"')
# 合法接口路径：以 / 开头，至少两段，只含路径字符
PATH_RE = re.compile(r"^/[A-Za-z0-9_\-/{}.]+$")


def extract(pp_path: str):
    raw = open(pp_path, encoding="utf-8", errors="replace").read().split("\n")
    found = {}
    for ln in raw:
        m = LINE_RE.search(ln)
        if not m:
            continue
        off, s = m.group(1), m.group(2)
        if not s.startswith("/") or len(s) > 90 or " " in s:
            continue
        if not PATH_RE.match(s):
            continue
        # 过滤静态资源（图片/文件后缀）
        if re.search(r"\.(png|jpg|jpeg|webp|gif|apk|mobileconfig|html|css|js|json|mp4)$", s, re.I):
            continue
        found.setdefault(s, off)
    return found


def main():
    pp = sys.argv[1] if len(sys.argv) > 1 else r"D:\WorkSpace\Android\91直播_4.3.3\out_dir\pp.txt"
    prefix = sys.argv[2] if len(sys.argv) > 2 else "api_endpoints"

    eps = extract(pp)
    print(f"从 pp.txt 提取到接口路径: {len(eps)} 个")

    groups = defaultdict(list)
    for p in sorted(eps):
        parts = p.strip("/").split("/")
        key = "/" + parts[0] + ("/" + parts[1] if len(parts) > 1 else "")
        groups[key].append(p)

    with open(prefix + ".txt", "w", encoding="utf-8", newline="\n") as f:
        for p in sorted(eps):
            f.write(p + "\n")
    print(f"已保存 {prefix}.txt")

    lines = ["# 4.3.3 全量接口清单（自 pp.txt 提取）", "",
             f"共 **{len(eps)}** 个接口路径，按一级/二级前缀分组。", ""]
    for key in sorted(groups):
        lines.append(f"## `{key}` （{len(groups[key])}）")
        lines.append("")
        for p in groups[key]:
            lines.append(f"- `{p}`")
        lines.append("")
    with open(prefix + ".md", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"已保存 {prefix}.md")

    print("\n=== 分组统计 ===")
    for key in sorted(groups, key=lambda k: -len(groups[k])):
        print(f"  {key:<28} {len(groups[key]):>4}")


if __name__ == "__main__":
    main()
