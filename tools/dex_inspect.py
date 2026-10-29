#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 DEX 解析器：只做「按类名 dump 方法签名 / 找字符串」这类活。

用法:
    python tools/dex_inspect.py cls  com.kiwi.sdk.Kiwi
    python tools/dex_inspect.py find kiwi              # 类名含 kiwi
    python tools/dex_inspect.py str  appkey            # 字符串池里搜
    python tools/dex_inspect.py meth initEx            # 方法名搜

只用标准库，不做反编译，够用就行。
"""
import sys
import struct
import zipfile

import os

_HERE = os.path.dirname(os.path.abspath(__file__))
APK = os.path.join(os.path.dirname(_HERE), "assets", "channel.apk")


class Dex:
    def __init__(self, data):
        self.d = data
        assert data[:4] == b"dex\n", "not a dex"
        u = lambda o: struct.unpack_from("<I", data, o)[0]
        self.sids_sz, self.sids_off = u(0x38), u(0x3C)
        self.tids_sz, self.tids_off = u(0x40), u(0x44)
        self.pids_sz, self.pids_off = u(0x48), u(0x4C)
        self.fids_sz, self.fids_off = u(0x50), u(0x54)
        self.mids_sz, self.mids_off = u(0x58), u(0x5C)
        self.cdef_sz, self.cdef_off = u(0x60), u(0x64)

    # ---- 基础表 ----
    def string(self, i):
        o = struct.unpack_from("<I", self.d, self.sids_off + i * 4)[0]
        # string_data_item = uleb128(utf16_size) + MUTF-8 + 0x00
        _, o = self.uleb(o)
        e = self.d.index(b"\x00", o)
        return self.d[o:e].decode("utf-8", "replace")

    def type(self, i):
        return self.string(struct.unpack_from("<I", self.d, self.tids_off + i * 4)[0])

    def proto(self, i):
        o = self.pids_off + i * 12
        _shorty, ret, par = struct.unpack_from("<III", self.d, o)
        args = []
        if par:
            # type_list = uint32 size + uint16 type_idx[size]
            n = struct.unpack_from("<I", self.d, par)[0]
            args = [self.type(struct.unpack_from("<H", self.d, par + 4 + j * 2)[0])
                    for j in range(n)]
        return self.type(ret), args

    def method(self, i):
        o = self.mids_off + i * 8
        cls, proto, name = struct.unpack_from("<HHI", self.d, o)
        ret, args = self.proto(proto)
        return self.type(cls), self.string(name), ret, args

    def field(self, i):
        o = self.fids_off + i * 8
        cls, typ, name = struct.unpack_from("<HHI", self.d, o)
        return self.type(cls), self.string(name), self.type(typ)

    # ---- uleb ----
    def uleb(self, off):
        r = s = 0
        while True:
            b = self.d[off]
            off += 1
            r |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                return r, off

    # ---- class_data ----
    def class_index(self, desc):
        """desc 形如 Lcom/kiwi/sdk/Kiwi; 返回 class_defs 下标"""
        for i in range(self.tids_sz):
            if self.type(i) == desc:
                return i
        return None

    def class_data(self, type_idx):
        for ci in range(self.cdef_sz):
            o = self.cdef_off + ci * 32
            if struct.unpack_from("<I", self.d, o)[0] != type_idx:
                continue
            cdo = struct.unpack_from("<I", self.d, o + 24)[0]
            if not cdo:
                return None
            return self._parse_class_data(cdo)
        return None

    def _parse_class_data(self, cdo):
        off = cdo
        sf, off = self.uleb(off)
        inf, off = self.uleb(off)
        dm, off = self.uleb(off)
        vm, off = self.uleb(off)

        def fields(n):
            nonlocal off
            out = []
            idx = 0
            for _ in range(n):
                diff, off = self.uleb(off)
                acc, off = self.uleb(off)
                idx += diff
                out.append((idx, acc))
            return out, off

        def methods(n):
            nonlocal off
            out = []
            idx = 0
            for _ in range(n):
                diff, off = self.uleb(off)
                acc, off = self.uleb(off)
                code, off = self.uleb(off)
                idx += diff
                out.append((idx, acc, code))
            return out, off

        sfields, off = fields(sf)
        ifields, off = fields(inf)
        dmethods, off = methods(dm)
        vmethods, off = methods(vm)
        return dict(sfields=sfields, ifields=ifields,
                    dmethods=dmethods, vmethods=vmethods)

    def class_list(self):
        """返回 [(desc, superclass_desc, source_file)]"""
        out = []
        for ci in range(self.cdef_sz):
            o = self.cdef_off + ci * 32
            tidx, acc, sup, ifo, src, ano, cdo, sval = struct.unpack_from(
                "<IIIIIIII", self.d, o)
            src_s = ""
            if src:
                e = self.d.index(b"\x00", src)
                src_s = self.d[src:e].decode("utf-8", "replace")
            out.append((self.type(tidx), self.type(sup) if sup else "", src_s))
        return out


def load():
    z = zipfile.ZipFile(APK)
    dexes = []
    for n in sorted(z.namelist()):
        if n.endswith(".dex") and n.startswith("classes"):
            dexes.append((n, Dex(z.read(n))))
    return dexes


ACC_STATIC, ACC_FINAL, ACC_NATIVE, ACC_ABSTRACT = 0x8, 0x10, 0x100, 0x400


def fmt_acc(a):
    tags = []
    for bit, name in ((0x1, "public"), (0x2, "private"), (0x4, "protected"),
                      (0x8, "static"), (0x10, "final"), (0x20, "sync"),
                      (0x100, "native"), (0x400, "abstract"), (0x800, "strict")):
        if a & bit:
            tags.append(name)
    return " ".join(tags) or "-"


def cmd_cls(desc):
    for dn, dx in load():
        ti = dx.class_index(desc)
        if ti is None:
            continue
        cd = dx.class_data(ti)
        print("== %s in %s" % (desc, dn))
        if not cd:
            print("  (no class_data)")
            continue
        for i, acc in cd["sfields"]:
            c, n, t = dx.field(i)
            print("  S %-28s %-30s %s" % (n, t, fmt_acc(acc)))
        for i, acc in cd["ifields"]:
            c, n, t = dx.field(i)
            print("  F %-28s %-30s %s" % (n, t, fmt_acc(acc)))
        for i, acc, code in cd["dmethods"] + cd["vmethods"]:
            c, n, ret, args = dx.method(i)
            print("  M %-28s(%s) -> %s   [%s]%s" % (
                n, ", ".join(args), ret, fmt_acc(acc),
                "" if code else "  <no-code>"))


def cmd_find(sub):
    sub = sub.lower()
    for dn, dx in load():
        for desc, sup, src in dx.class_list():
            if sub in desc.lower() or sub in src.lower():
                print("%-8s %-52s super=%s  src=%s" % (dn, desc, sup, src))


def cmd_str(sub):
    seen = set()
    for dn, dx in load():
        for i in range(dx.sids_sz):
            s = dx.string(i)
            if sub.lower() in s.lower() and s not in seen:
                seen.add(s)
                print("%-8s %s" % (dn, s))


def cmd_meth(sub):
    for dn, dx in load():
        for i in range(dx.mids_sz):
            c, n, ret, args = dx.method(i)
            if sub.lower() in n.lower():
                print("%-8s %-48s %s(%s) -> %s" % (dn, c, n, ", ".join(args), ret))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    cmd, arg = sys.argv[1], sys.argv[2]
    if cmd == "cls":
        cmd_cls(arg if arg.startswith("L") else "L%s;" % arg.replace(".", "/"))
    elif cmd == "find":
        cmd_find(arg)
    elif cmd == "str":
        cmd_str(arg)
    elif cmd == "meth":
        cmd_meth(arg)
    else:
        print(__doc__)
