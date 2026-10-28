#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wsframe.py - 从 pcap 定向解析 WebSocket 帧并尝试 zlib 解压"""
import sys, os, zlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcap_streams as ps

def frames(buf):
    out = []; i = 0; n = len(buf)
    while i + 2 <= n:
        b0 = buf[i]; b1 = buf[i+1]
        op = b0 & 0x0f
        masked = b1 & 0x80; ln = b1 & 0x7f; p = i + 2
        if ln == 126:
            if p + 2 > n: break
            ln = int.from_bytes(buf[p:p+2], 'big'); p += 2
        elif ln == 127:
            if p + 8 > n: break
            ln = int.from_bytes(buf[p:p+8], 'big'); p += 8
        mk = b''
        if masked:
            if p + 4 > n: break
            mk = buf[p:p+4]; p += 4
        if p + ln > n: break
        pl = bytes(buf[p:p+ln])
        if masked:
            pl = bytes(c ^ mk[k % 4] for k, c in enumerate(pl))
        out.append((op, pl)); i = p + ln
    return out

def tryz(pl):
    for fn in (zlib.decompress, lambda x: zlib.decompress(x, -15),
               lambda x: zlib.decompress(x, 15+32)):
        try: return fn(pl)
        except Exception: pass
    return None

def main():
    path = sys.argv[1]; sp = int(sys.argv[2]); dp = int(sys.argv[3])
    d, n = ps.reassemble(path, sp, dp)
    fs = frames(d)
    print(f"[{sp}->{dp}] bytes={len(d)} segs={n} frames={len(fs)}")
    for idx, (op, pl) in enumerate(fs):
        z = tryz(pl)
        if z is not None:
            print(f"--#{idx} op={op} len={len(pl)} -> inflated {len(z)}")
            print(z.decode('utf-8', 'replace'))
        else:
            print(f"--#{idx} op={op} len={len(pl)} RAW {pl[:120]!r}")

if __name__ == '__main__':
    main()
