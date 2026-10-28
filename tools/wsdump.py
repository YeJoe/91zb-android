#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""wsdump.py - 把 pcap 中某方向 WS 帧（含 zlib 解压）逐行写出"""
import sys, os, zlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wsframe import frames, tryz
import pcap_streams as ps

sp, dp = int(sys.argv[2]), int(sys.argv[3])
d, n = ps.reassemble(sys.argv[1], sp, dp)
fs = frames(d)
out = open(sys.argv[4], 'w', encoding='utf-8')
for i, (op, pl) in enumerate(fs):
    z = tryz(pl)
    if z is not None:
        out.write(f"#{i}\t{op}\t{len(pl)}\t{z.decode('utf-8','replace')}\n")
    else:
        out.write(f"#{i}\t{op}\t{len(pl)}\tHEX {pl.hex()}\n")
out.close()
print(f"{sp}->{dp}: {len(fs)} frames -> {sys.argv[4]}")
