#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pcap_streams.py — 无依赖 pcap 解析 + TCP 流重组（纯标准库）

支持链路层: LINUX_SLL2(276) / LINUX_SLL(113) / EN10MB(1) / RAW(101) / NULL(0)
支持网络层: IPv4 (IPv6 跳过)
传输层:     TCP（UDP 单独按 datagram 列出）

用法:
  python tools/pcap_streams.py flows  <pcap>
  python tools/pcap_streams.py dump   <pcap> <sport> <dport> [out.bin]   # 重组某方向
  python tools/pcap_streams.py strings <pcap> <sport> <dport> [minlen]
  python tools/pcap_streams.py try    <pcap> <sport> <dport>            # zlib / protobuf 试探
"""
import struct
import sys
import zlib
import collections


def read_pcap(path):
    """返回 [(ts, linktype_ok, raw_packet_bytes), ...]，已按 magic 处理大小端。"""
    data = open(path, 'rb').read()
    if len(data) < 24:
        raise SystemExit('pcap too short')
    magic = data[:4]
    if magic in (b'\xd4\xc3\xb2\xa1', b'\x4d\x3c\xb2\xa1'):
        endian = '<'
    elif magic in (b'\xa1\xb2\xc3\xd4', b'\xa1\xb2\x3c\x4d'):
        endian = '>'
    else:
        raise SystemExit('bad pcap magic %r' % magic)
    # magic 后两个字节决定时间精度: a1b23c4d = nanosecond
    ns = magic in (b'\x4d\x3c\xb2\xa1', b'\xa1\xb2\x3c\x4d')
    linktype = struct.unpack(endian + 'I', data[20:24])[0]
    out = []
    off = 24
    n = len(data)
    while off + 16 <= n:
        ts_s, ts_f, incl, orig = struct.unpack(endian + 'IIII', data[off:off + 16])
        off += 16
        pkt = data[off:off + incl]
        off += incl
        if len(pkt) < incl:
            break
        t = ts_s + (ts_f / 1e9 if ns else ts_f / 1e6)
        out.append((t, pkt))
    return linktype, out


def strip_link(linktype, pkt):
    """去掉链路层头，返回 (ethertype, payload)"""
    if linktype == 276:            # LINUX_SLL2
        if len(pkt) < 20:
            return None, b''
        proto = struct.unpack('>H', pkt[0:2])[0]
        return proto, pkt[20:]
    if linktype == 113:            # LINUX_SLL
        if len(pkt) < 16:
            return None, b''
        proto = struct.unpack('>H', pkt[14:16])[0]
        return proto, pkt[16:]
    if linktype == 1:              # EN10MB
        if len(pkt) < 14:
            return None, b''
        return struct.unpack('>H', pkt[12:14])[0], pkt[14:]
    if linktype == 101:            # RAW
        if not pkt:
            return None, b''
        return 0x0800 if pkt[0] >> 4 == 4 else 0x86dd, pkt
    if linktype == 0:              # NULL/loopback: 4 字节 AF
        if len(pkt) < 4:
            return None, b''
        fam = struct.unpack('<I', pkt[:4])[0]
        return (0x0800 if fam == 2 else 0x86dd), pkt[4:]
    return None, b''


def parse_ip(proto, payload):
    """返回 (src, dst, proto_num, l4_payload) 或 None"""
    if proto == 0x0800 and len(payload) >= 20:
        ihl = (payload[0] & 0x0f) * 4
        if ihl < 20 or len(payload) < ihl:
            return None
        p = payload[9]
        src = '.'.join(str(b) for b in payload[12:16])
        dst = '.'.join(str(b) for b in payload[16:20])
        total = struct.unpack('>H', payload[2:4])[0]
        body = payload[ihl:total] if total >= ihl else payload[ihl:]
        return src, dst, p, body
    return None


def iter_segments(path):
    """产出 (ts, src, sport, dst, dport, seq, flags, payload)"""
    linktype, pkts = read_pcap(path)
    for ts, pkt in pkts:
        et, ip = strip_link(linktype, pkt)
        if et is None:
            continue
        r = parse_ip(et, ip)
        if not r:
            continue
        src, dst, p, body = r
        if p != 6 or len(body) < 20:      # 只处理 TCP
            continue
        sport, dport = struct.unpack('>HH', body[0:4])
        seq = struct.unpack('>I', body[4:8])[0]
        off = (body[12] >> 4) * 4
        flags = body[13]
        payload = body[off:]
        yield ts, src, sport, dst, dport, seq, flags, payload


def reassemble(path, sport, dport):
    """按方向 (sport->dport) 重组，返回 (bytes, 段数)"""
    segs = []
    for ts, src, sp, dst, dp, seq, flags, pay in iter_segments(path):
        if sp == sport and dp == dport and pay:
            segs.append((seq, pay))
    if not segs:
        return b'', 0
    segs.sort(key=lambda x: x[0])
    buf = bytearray()
    expect = None
    for seq, pay in segs:
        if expect is None:
            buf += pay
            expect = seq + len(pay)
        else:
            if seq <= expect:                 # 重叠/重传
                skip = expect - seq
                if skip < len(pay):
                    buf += pay[skip:]
                    expect += len(pay) - skip
            else:                             # 缺口，用 0 占位保持偏移
                buf += b'\x00' * (seq - expect)
                buf += pay
                expect = seq + len(pay)
    return bytes(buf), len(segs)


def cmd_flows(path):
    linktype, pkts = read_pcap(path)
    print('linktype', linktype, 'packets', len(pkts))
    b = collections.Counter()
    c = collections.Counter()
    for ts, src, sp, dst, dp, seq, flags, pay in iter_segments(path):
        b[(sp, dp)] += len(pay)
        c[(sp, dp)] += 1
    for (sp, dp) in sorted(b, key=lambda k: -b[k])[:30]:
        print('%6d -> %-6d %9d B %6d pkt' % (sp, dp, b[(sp, dp)], c[(sp, dp)]))


def cmd_dump(path, sport, dport, out=None):
    data, n = reassemble(path, sport, dport)
    print('reassembled %d bytes from %d segments' % (len(data), n))
    if out:
        open(out, 'wb').write(data)
        print('written', out)
    else:
        print(repr(data[:1200]))


def cmd_strings(path, sport, dport, minlen=6):
    data, n = reassemble(path, sport, dport)
    print('bytes', len(data), 'segments', n)
    import re
    for m in re.finditer(rb'[\x20-\x7e]{%d,}' % minlen, data):
        print(m.start(), m.group().decode('ascii'))


def pb_walk(buf, depth=0, limit=400):
    """极简 protobuf 字段遍历：只打印 wire type 与可能的字符串"""
    i = 0
    n = len(buf)
    count = 0
    while i < n and count < limit:
        try:
            key = 0
            shift = 0
            while True:
                if i >= n:
                    return
                b = buf[i]
                i += 1
                key |= (b & 0x7f) << shift
                shift += 7
                if not b & 0x80:
                    break
            field = key >> 3
            wt = key & 7
            if field == 0:
                return
            if wt == 0:
                v = 0
                shift = 0
                while True:
                    if i >= n:
                        return
                    b = buf[i]
                    i += 1
                    v |= (b & 0x7f) << shift
                    shift += 7
                    if not b & 0x80:
                        break
                print('  ' * depth + 'f%d varint=%d' % (field, v))
            elif wt == 2:
                ln = 0
                shift = 0
                while True:
                    if i >= n:
                        return
                    b = buf[i]
                    i += 1
                    ln |= (b & 0x7f) << shift
                    shift += 7
                    if not b & 0x80:
                        break
                seg = buf[i:i + ln]
                i += ln
                printable = sum(1 for c in seg if 32 <= c < 127) / max(1, len(seg))
                if printable > 0.85 and ln >= 2:
                    print('  ' * depth + 'f%d str(%d)=%r' % (field, ln, seg[:120]))
                else:
                    print('  ' * depth + 'f%d bytes(%d)=%s' % (field, ln, seg[:40].hex()))
                    if depth < 2 and ln > 2:
                        pb_walk(seg, depth + 1)
            elif wt == 5:
                i += 4
            elif wt == 1:
                i += 8
            else:
                return
            count += 1
        except Exception:
            return


def cmd_try(path, sport, dport):
    data, n = reassemble(path, sport, dport)
    print('bytes', len(data), 'segments', n)
    print('head hex:', data[:64].hex())
    print('head repr:', repr(data[:120]))
    # zlib 试探：从流里任意位置找 zlib 头
    found = 0
    for i in range(0, min(len(data), 4000)):
        if data[i] == 0x78 and i + 1 < len(data) and data[i + 1] in (0x01, 0x9c, 0xda, 0x5e):
            try:
                out = zlib.decompressobj().decompress(data[i:i + 200000])
                if len(out) > 20:
                    print('\n[zlib] offset', i, '->', len(out), 'bytes')
                    print(repr(out[:300]))
                    found += 1
                    if found >= 3:
                        break
            except Exception:
                pass
    if not found:
        print('\n[zlib] 未发现可解压块')
    print('\n[protobuf 试解析前 200 字节]')
    pb_walk(data[:200])


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    cmd, path = sys.argv[1], sys.argv[2]
    if cmd == 'flows':
        cmd_flows(path)
    elif cmd == 'dump':
        cmd_dump(path, int(sys.argv[3]), int(sys.argv[4]),
                 sys.argv[5] if len(sys.argv) > 5 else None)
    elif cmd == 'strings':
        cmd_strings(path, int(sys.argv[3]), int(sys.argv[4]),
                    int(sys.argv[5]) if len(sys.argv) > 5 else 6)
    elif cmd == 'try':
        cmd_try(path, int(sys.argv[3]), int(sys.argv[4]))
    else:
        print(__doc__)
