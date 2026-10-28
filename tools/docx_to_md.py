# -*- coding: utf-8 -*-
"""
把 docx 转成 Markdown（保留标题层级、表格、代码块、列表）。

代码块判定：整段 run 的字体为 Consolas（本项目生成文档时的约定）。
用法：python docx_to_md.py <input.docx> [output.md]
"""
import sys

from docx import Document
from docx.oxml.ns import qn
from docx.table import Table
from docx.text.paragraph import Paragraph

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

MONO = "Consolas"
HEAD_MAP = {"Heading 1": "##", "Heading 2": "###", "Heading 3": "####", "Heading 4": "#####"}


def iter_blocks(doc):
    """按文档真实顺序产出 Paragraph / Table。"""
    for child in doc.element.body.iterchildren():
        if child.tag == qn("w:p"):
            yield Paragraph(child, doc)
        elif child.tag == qn("w:tbl"):
            yield Table(child, doc)


def is_code_para(p: Paragraph) -> bool:
    runs = [r for r in p.runs if r.text.strip()]
    if not runs:
        return False
    return all((r.font.name or "") == MONO for r in runs)


def cell_text(cell) -> str:
    return " ".join(cell.text.split()).replace("|", "\\|")


def table_to_md(t: Table) -> str:
    rows = [[cell_text(c) for c in row.cells] for row in t.rows]
    if not rows:
        return ""
    ncol = max(len(r) for r in rows)
    rows = [r + [""] * (ncol - len(r)) for r in rows]
    out = ["| " + " | ".join(rows[0]) + " |",
           "|" + "|".join([" --- "] * ncol) + "|"]
    for r in rows[1:]:
        out.append("| " + " | ".join(r) + " |")
    return "\n".join(out)


def convert(src: str, dst: str) -> None:
    doc = Document(src)
    lines = []
    code_buf = []
    list_open = False

    def flush_code():
        if code_buf:
            lines.append("```")
            lines.extend(code_buf)
            lines.append("```")
            lines.append("")
            code_buf.clear()

    for blk in iter_blocks(doc):
        if isinstance(blk, Table):
            flush_code()
            md = table_to_md(blk)
            if md:
                lines.append(md)
                lines.append("")
            continue

        p = blk
        text = p.text.strip()

        # 代码块聚合
        if is_code_para(p):
            code_buf.extend(p.text.rstrip("\n").split("\n"))
            continue
        flush_code()

        if not text:
            continue

        style = p.style.name if p.style is not None else ""
        if style in HEAD_MAP:
            lines.append(f"{HEAD_MAP[style]} {text}")
            lines.append("")
        elif style in ("List Bullet", "List Number"):
            marker = "-" if style == "List Bullet" else "1."
            lines.append(f"{marker} {text}")
            list_open = True
            continue
        elif style == "Title":
            lines.append(f"# {text}")
            lines.append("")
        else:
            lines.append(text)
            lines.append("")
        list_open = False

    flush_code()

    # 列表结束处补空行（Markdown 需要）
    out_lines = []
    for i, ln in enumerate(lines):
        out_lines.append(ln)
        if ln.startswith(("- ", "1. ")) and i + 1 < len(lines):
            nxt = lines[i + 1]
            if not nxt.startswith(("- ", "1. ")) and nxt != "":
                out_lines.append("")
    lines = out_lines

    body = "\n".join(lines)
    # 压缩多余空行
    while "\n\n\n" in body:
        body = body.replace("\n\n\n", "\n\n")

    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    print(f"已生成 {dst}")
    print(f"  行数 {body.count(chr(10)) + 1}，表格 {body.count(chr(124)) and sum(1 for l in lines if l.startswith('| '))} 行")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("用法: python docx_to_md.py <input.docx> [output.md]")
    s = sys.argv[1]
    d = sys.argv[2] if len(sys.argv) > 2 else s.rsplit(".", 1)[0] + ".md"
    convert(s, d)
