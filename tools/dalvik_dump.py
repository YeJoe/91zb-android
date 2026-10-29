#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""迷你 dalvik 反汇编器：把指定类的方法打成 smali 风格的可读文本。

只为逆向 kiwi SDK 而生，不追求完备（不做 try/catch 展开、不做 switch payload 展开）。

用法:
    python tools/dalvik_dump.py com.kiwi.sdk.Kiwi
    python tools/dalvik_dump.py com.kiwi.sdk.Kiwi --meth Init
"""
import sys
import struct

from dex_inspect import Dex, APK, load

# opcode -> (name, format)
OP = {}
def _o(code, name, fmt):
    OP[code] = (name, fmt)

_o(0x00, "nop", "10x")
for i, n in enumerate(["move", "move-wide", "move-object"]):
    _o(0x01 + i * 3, n, "12x")
    _o(0x02 + i * 3, n + "/from16", "22x")
    _o(0x03 + i * 3, n + "/16", "32x")
_o(0x0a, "move-result", "11x")
_o(0x0b, "move-result-wide", "11x")
_o(0x0c, "move-result-object", "11x")
_o(0x0d, "move-exception", "11x")
_o(0x0e, "return-void", "10x")
_o(0x0f, "return", "11x")
_o(0x10, "return-wide", "11x")
_o(0x11, "return-object", "11x")
_o(0x12, "const/4", "11n")
_o(0x13, "const/16", "21s")
_o(0x14, "const", "31i")
_o(0x15, "const/high16", "21h")
_o(0x16, "const-wide/16", "21s")
_o(0x17, "const-wide/32", "31i")
_o(0x18, "const-wide", "51l")
_o(0x19, "const-wide/high16", "21h")
_o(0x1a, "const-string", "21c")
_o(0x1b, "const-string/jumbo", "31c")
_o(0x1c, "const-class", "21c")
_o(0x1d, "monitor-enter", "11x")
_o(0x1e, "monitor-exit", "11x")
_o(0x1f, "check-cast", "21c")
_o(0x20, "instance-of", "22c")
_o(0x21, "array-length", "12x")
_o(0x22, "new-instance", "21c")
_o(0x23, "new-array", "22c")
_o(0x24, "filled-new-array", "35c")
_o(0x25, "filled-new-array/range", "3rc")
_o(0x26, "fill-array-data", "31t")
_o(0x27, "throw", "11x")
_o(0x28, "goto", "10t")
_o(0x29, "goto/16", "20t")
_o(0x2a, "goto/32", "30t")
_o(0x2b, "packed-switch", "31t")
_o(0x2c, "sparse-switch", "31t")
for i, n in enumerate(["cmpl-float", "cmpg-float", "cmpl-double", "cmpg-double", "cmp-long"]):
    _o(0x2d + i, n, "23x")
for i, n in enumerate(["if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le"]):
    _o(0x32 + i, n, "22t")
for i, n in enumerate(["if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez"]):
    _o(0x38 + i, n, "21t")
for i, n in enumerate(["aget", "aget-wide", "aget-object", "aget-boolean",
                       "aget-byte", "aget-char", "aget-short"]):
    _o(0x44 + i, n, "23x")
for i, n in enumerate(["aput", "aput-wide", "aput-object", "aput-boolean",
                       "aput-byte", "aput-char", "aput-short"]):
    _o(0x4b + i, n, "23x")
for i, n in enumerate(["iget", "iget-wide", "iget-object", "iget-boolean",
                       "iget-byte", "iget-char", "iget-short"]):
    _o(0x52 + i, n, "22c")
for i, n in enumerate(["iput", "iput-wide", "iput-object", "iput-boolean",
                       "iput-byte", "iput-char", "iput-short"]):
    _o(0x59 + i, n, "22c")
for i, n in enumerate(["sget", "sget-wide", "sget-object", "sget-boolean",
                       "sget-byte", "sget-char", "sget-short"]):
    _o(0x60 + i, n, "21c")
for i, n in enumerate(["sput", "sput-wide", "sput-object", "sput-boolean",
                       "sput-byte", "sput-char", "sput-short"]):
    _o(0x67 + i, n, "21c")
for i, n in enumerate(["invoke-virtual", "invoke-super", "invoke-direct",
                       "invoke-static", "invoke-interface"]):
    _o(0x6e + i, n, "35c")
    _o(0x74 + i, n + "/range", "3rc")
for i, n in enumerate(["neg-int", "not-int", "neg-long", "not-long", "neg-float",
                       "neg-double", "int-to-long", "int-to-float", "int-to-double",
                       "long-to-int", "long-to-float", "long-to-double",
                       "float-to-int", "float-to-long", "float-to-double",
                       "double-to-int", "double-to-long", "double-to-float",
                       "int-to-byte", "int-to-char", "int-to-short"]):
    _o(0x7b + i, n, "12x")
_bin = ["add", "sub", "mul", "div", "rem", "and", "or", "xor", "shl", "shr", "ushr"]
for t, off in (("int", 0x90), ("long", 0xb0), ("float", 0x98), ("double", 0xa0)):
    for i, n in enumerate(_bin):
        _o(off + i, "%s-%s" % (n, t), "23x")
        _o(off + 0x20 + i, "%s-%s/2addr" % (n, t), "12x")
for i, n in enumerate(["add-int/lit16", "rsub-int", "mul-int/lit16", "div-int/lit16",
                       "rem-int/lit16", "and-int/lit16", "or-int/lit16", "xor-int/lit16"]):
    _o(0xd0 + i, n, "22s")
for i, n in enumerate(["add-int/lit8", "rsub-int/lit8", "mul-int/lit8", "div-int/lit8",
                       "rem-int/lit8", "and-int/lit8", "or-int/lit8", "xor-int/lit8",
                       "shl-int/lit8", "shr-int/lit8", "ushr-int/lit8"]):
    _o(0xd8 + i, n, "22b")
_o(0xfa, "invoke-polymorphic", "45cc")
_o(0xfb, "invoke-polymorphic/range", "4rcc")
_o(0xfc, "invoke-custom", "35c")
_o(0xfd, "invoke-custom/range", "3rc")
_o(0xfe, "const-method-handle", "21c")
_o(0xff, "const-method-type", "21c")

LEN = {"10x": 1, "12x": 1, "11n": 1, "11x": 1, "10t": 1,
       "20t": 2, "22x": 2, "21t": 2, "21s": 2, "21h": 2, "21c": 2,
       "23x": 2, "22b": 2, "22t": 2, "22s": 2, "22c": 2, "20bc": 2,
       "30t": 3, "32x": 3, "31i": 3, "31t": 3, "31c": 3, "35c": 3, "3rc": 3,
       "45cc": 4, "4rcc": 4, "51l": 5}


def s16(v):
    return v - 0x10000 if v & 0x8000 else v


def s32(v):
    return v - 0x100000000 if v & 0x80000000 else v


def dump_method(dx, mname, ret, args, code_off, acc):
    d = dx.d
    regs, ins, outs, tries, dbg, insns_size = struct.unpack_from("<HHHHII", d, code_off)
    print("  .method %s %s(%s)%s" % (
        "native" if acc & 0x100 else ("static" if acc & 0x8 else "public"),
        mname, "".join(args), ret))
    print("    ; regs=%d ins=%d outs=%d code=0x%x" % (regs, ins, outs, code_off))
    base = code_off + 16
    i = 0
    while i < insns_size:
        u = struct.unpack_from("<H", d, base + i * 2)[0]
        op = u & 0xFF
        name, fmt = OP.get(op, ("op_%02x" % op, "10x"))
        n = LEN.get(fmt, 1)
        w = [struct.unpack_from("<H", d, base + (i + k) * 2)[0] for k in range(n)]

        if fmt == "10x":
            txt = ""
        elif fmt in ("11x", "10t"):
            a = (u >> 8) & 0xFF
            txt = "v%d" % a if fmt == "11x" else ":l%+d" % (a - 256 if a > 127 else a)
        elif fmt == "12x":
            txt = "v%d, v%d" % ((u >> 8) & 0xF, (u >> 12) & 0xF)
        elif fmt == "11n":
            a = (u >> 8) & 0xF
            b = (u >> 12) & 0xF
            txt = "v%d, #%d" % (a, b - 16 if b > 7 else b)
        elif fmt == "22x":
            txt = "v%d, v%d" % ((u >> 8) & 0xFF, w[1])
        elif fmt == "32x":
            txt = "v%d, v%d" % (w[0], w[1])
        elif fmt == "21s":
            txt = "v%d, #%d" % ((u >> 8) & 0xFF, s16(w[1]))
        elif fmt == "21h":
            txt = "v%d, #0x%x" % ((u >> 8) & 0xFF, w[1] << 16)
        elif fmt == "31i":
            txt = "v%d, #%d" % ((u >> 8) & 0xFF, s32(w[1] | (w[2] << 16)))
        elif fmt == "51l":
            v = w[1] | (w[2] << 16) | (w[3] << 32) | (w[4] << 48)
            txt = "v%d, #0x%xL" % ((u >> 8) & 0xFF, v)
        elif fmt in ("21t", "31t"):
            txt = "v%d, :l%+d" % ((u >> 8) & 0xFF, s16(w[1]))
        elif fmt in ("20t", "30t"):
            txt = ":l%+d" % s16(w[1])
        elif fmt == "22t":
            txt = "v%d, v%d, :l%+d" % ((u >> 8) & 0xF, (u >> 12) & 0xF, s16(w[1]))
        elif fmt in ("22s",):
            txt = "v%d, v%d, #%d" % ((u >> 8) & 0xF, (u >> 12) & 0xF, s16(w[1]))
        elif fmt == "22b":
            a = (u >> 8) & 0xFF
            b = (u >> 12) & 0xFF
            lit = w[1] & 0xFF
            txt = "v%d, v%d, #%d" % (a, b, lit - 256 if lit > 127 else lit)
        elif fmt == "23x":
            txt = "v%d, v%d, v%d" % ((u >> 8) & 0xFF, w[1] & 0xFF, (w[1] >> 8) & 0xFF)
        elif fmt == "21c":
            idx = w[1]
            if name.startswith("const-string"):
                txt = "v%d, \"%s\"" % ((u >> 8) & 0xFF, dx.string(idx))
            elif name == "const-class":
                txt = "v%d, %s" % ((u >> 8) & 0xFF, dx.type(idx))
            elif name.startswith(("sget", "sput")):
                c, fn, ft = dx.field(idx)
                txt = "v%d, %s->%s:%s" % ((u >> 8) & 0xFF, c, fn, ft)
            else:
                txt = "v%d, @%d" % ((u >> 8) & 0xFF, idx)
        elif fmt == "22c":
            idx = w[1]
            if name.startswith(("iget", "iput")):
                c, fn, ft = dx.field(idx)
                txt = "v%d, v%d, %s->%s:%s" % ((u >> 8) & 0xF, (u >> 12) & 0xF, c, fn, ft)
            else:
                txt = "v%d, v%d, %s" % ((u >> 8) & 0xF, (u >> 12) & 0xF, dx.type(idx))
        elif fmt in ("35c", "45cc"):
            cnt = (u >> 12) & 0xF
            idx = w[1]
            if name.startswith(("invoke", "filled")):
                c, mn, rt, ar = dx.method(idx)
                txt = "{%d args}, %s->%s(%s)%s" % (cnt, c, mn, "".join(ar), rt)
            else:
                txt = "{%d args}, @%d" % (cnt, idx)
        elif fmt == "3rc":
            cnt = (u >> 8) & 0xFF
            idx = w[1]
            c, mn, rt, ar = dx.method(idx)
            txt = "{v%d..v%d}, %s->%s(%s)%s" % (
                w[2], w[2] + cnt - 1, c, mn, "".join(ar), rt)
        else:
            txt = " ".join("0x%04x" % x for x in w)
        print("    %04x: %-26s %s" % (i * 2, name, txt))
        i += n
    print("  .end method")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    want = sys.argv[1]
    only = None
    if "--meth" in sys.argv:
        only = sys.argv[sys.argv.index("--meth") + 1]
    desc = want if want.startswith("L") else "L%s;" % want.replace(".", "/")

    for dn, dx in load():
        ti = dx.class_index(desc)
        if ti is None:
            continue
        cd = dx.class_data(ti)
        if not cd:
            continue
        print("### %s (%s)" % (desc, dn))
        for mi, acc, code in cd["dmethods"] + cd["vmethods"]:
            c, mn, rt, ar = dx.method(mi)
            if only and mn != only:
                continue
            if not code:
                print("  .method native/abstract %s(%s)%s" % (mn, "".join(ar), rt))
                continue
            dump_method(dx, mn, rt, ar, code, acc)
        return
    print("class not found:", desc)


if __name__ == "__main__":
    main()
