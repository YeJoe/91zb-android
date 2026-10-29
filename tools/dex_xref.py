#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跨 dex 的交叉引用扫描：谁调用了 / 谁读取了某个方法、字段、类或字符串。

用法:
    python tools/dex_xref.py meth  com.kiwi.sdk.Kiwi Init
    python tools/dex_xref.py fld   com.kiwi.sdk.Kiwi init_appkey
    python tools/dex_xref.py cls   com.kiwi.sdk.Kiwi
    python tools/dex_xref.py str   initEx

输出格式: dex名  调用方类->调用方方法  @指令地址
"""
import sys
import struct

from dex_inspect import load
from dalvik_dump import OP, LEN


def iter_methods(dx):
    """yield (class_desc, method_name, acc, code_off, insns_size)"""
    for ci in range(dx.cdef_sz):
        o = dx.cdef_off + ci * 32
        tidx = struct.unpack_from("<I", dx.d, o)[0]
        cdo = struct.unpack_from("<I", dx.d, o + 24)[0]
        if not cdo:
            continue
        cls = dx.type(tidx)
        cd = dx._parse_class_data(cdo)
        for mi, acc, code in cd["dmethods"] + cd["vmethods"]:
            if not code:
                continue
            c, mn, rt, ar = dx.method(mi)
            insns_size = struct.unpack_from("<I", dx.d, code + 12)[0]
            yield cls, mn, code + 16, insns_size


def scan(dx, hit):
    """hit(kind, index) -> str|None；返回 [(cls, meth, addr, text)]"""
    out = []
    d = dx.d
    for cls, mn, base, n in iter_methods(dx):
        i = 0
        while i < n:
            u = struct.unpack_from("<H", d, base + i * 2)[0]
            op = u & 0xFF
            name, fmt = OP.get(op, ("", "10x"))
            step = LEN.get(fmt, 1)
            if fmt in ("21c", "22c", "35c", "3rc") and i + 1 < n:
                idx = struct.unpack_from("<H", d, base + (i + 1) * 2)[0]
                kind = ("str" if name.startswith("const-string") else
                        "fld" if name.startswith(("iget", "iput", "sget", "sput")) else
                        "meth" if name.startswith(("invoke", "filled")) else
                        "cls" if name in ("const-class", "new-instance", "check-cast") else "")
                if kind:
                    try:
                        t = hit(kind, idx)
                    except Exception:
                        t = None
                    if t:
                        out.append((cls, mn, i * 2, "%s %s" % (name, t)))
            i += step
    return out


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    what = sys.argv[1]
    if what == "meth":
        cls, mn = sys.argv[2], sys.argv[3]
        desc = cls if cls.startswith("L") else "L%s;" % cls.replace(".", "/")
        want = (desc, mn)
        def hit(kind, idx):
            pass
    elif what == "fld":
        cls, fn = sys.argv[2], sys.argv[3]
        desc = cls if cls.startswith("L") else "L%s;" % cls.replace(".", "/")
        want = (desc, fn)
    elif what == "cls":
        desc = sys.argv[2] if sys.argv[2].startswith("L") else "L%s;" % sys.argv[2].replace(".", "/")
    elif what == "str":
        sub = sys.argv[2]
    else:
        print(__doc__)
        return

    total = 0
    for dn, dx in load():
        if what == "meth":
            def hit(kind, idx, dx=dx, want=want):
                if kind != "meth":
                    return None
                c, n, r, a = dx.method(idx)
                if c == want[0] and n == want[1]:
                    return "%s->%s(%s)%s" % (c, n, "".join(a), r)
                return None
        elif what == "fld":
            def hit(kind, idx, dx=dx, want=want):
                if kind != "fld":
                    return None
                c, n, t = dx.field(idx)
                if c == want[0] and n == want[1]:
                    return "%s->%s:%s" % (c, n, t)
                return None
        elif what == "cls":
            def hit(kind, idx, dx=dx, desc=desc):
                if kind not in ("cls", "meth", "fld"):
                    return None
                if kind == "cls":
                    t = dx.type(idx)
                    return t if t == desc else None
                return None
        else:
            def hit(kind, idx, dx=dx, sub=sub):
                if kind != "str":
                    return None
                s = dx.string(idx)
                return s if sub.lower() in s.lower() else None

        res = scan(dx, hit)
        for cls, mn, addr, txt in res:
            print("%-12s %s->%s  @%04x  %s" % (dn, cls, mn, addr, txt))
        total += len(res)
    print("--- total %d" % total)


if __name__ == "__main__":
    main()
