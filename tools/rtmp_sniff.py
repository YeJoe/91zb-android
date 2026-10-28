#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rtmp_sniff.py — 从 pcap 里挖出天美的 RTMP 播放地址（纯标准库 + ffmpeg）

背景（8.5.3 实测）：
  - 播放走标准 RTMP，服务端 1935，Server 头为 NgxFence
  - 流名 = 房间号本身，且服务端【不校验】token / liteavToken
  - 但 tcUrl 里的域名是服务端按会话下发的一次性加速域名，必须现抓

用法:
  # 1) 抓包（会阻塞，提示你在模拟器里点进一个直播间）
  python tools/rtmp_sniff.py cap 40

  # 2) 解析 pcap，打印并保存播放地址
  python tools/rtmp_sniff.py parse j.pcap

  # 3) 用解析出的域名拼任意房间的地址
  python tools/rtmp_sniff.py url 736303908

  # 4) 录制（默认 10 秒）
  python tools/rtmp_sniff.py record 736303908 10
"""
import os
import re
import sys
import time
import struct
import subprocess

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pcap_streams as ps          # noqa: E402

SERIAL = os.environ.get('DUOCAI_SERIAL', 'emulator-5554')
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOMAIN_FILE = os.path.join(HERE, '.rtmp_domain.txt')

# ---------------------------------------------------------------- ADB


def adb(*args, timeout=90):
    cmd = ['adb', '-s', SERIAL] + list(args)
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


# ---------------------------------------------------------------- RTMP 分块解析

MSG_SET_CHUNK_SIZE = 1
MSG_AMF0_COMMAND = 20


def parse_chunks(buf):
    """
    解析 RTMP 块流，返回 [(msgtype, payload_bytes), ...]（按消息完整重组）。

    关键点：
      1) 块流前面有 3073 字节握手（C0 1B + C1 1536B + C2 1536B），必须先跳过；
      2) 一个 AMF0 消息可能被切成多个 chunk，每个 chunk 前面有块头。
    不处理这两点就会出现 'd5f4\\xc35fd2c502' 这种脏字节。
    """
    if buf[:1] == b'\x03' and len(buf) > 3073:
        buf = buf[3073:]
    i = 0
    n = len(buf)
    chunk_size = 128
    # csid -> dict(ts, delta, length, mtype, msid, got, data)
    st = {}
    msgs = []
    while i < n:
        b0 = buf[i]
        i += 1
        fmt = b0 >> 6
        csid = b0 & 0x3f
        if csid == 0:
            if i >= n:
                break
            csid = 64 + buf[i]
            i += 1
        elif csid == 1:
            if i + 1 >= n:
                break
            csid = 64 + buf[i] + buf[i + 1] * 256
            i += 2
        s = st.setdefault(csid, dict(ts=0, delta=0, length=0,
                                     mtype=0, msid=0, got=0, data=b''))
        # message header
        if fmt == 0:
            if i + 11 > n:
                break
            ts = int.from_bytes(buf[i:i + 3], 'big')
            length = int.from_bytes(buf[i + 3:i + 6], 'big')
            mtype = buf[i + 6]
            msid = int.from_bytes(buf[i + 7:i + 11], 'little')
            i += 11
            if ts == 0xFFFFFF:
                if i + 4 > n:
                    break
                ts = int.from_bytes(buf[i:i + 4], 'big')
                i += 4
            s.update(ts=ts, delta=0, length=length, mtype=mtype,
                     msid=msid, got=0, data=b'')
        elif fmt == 1:
            if i + 7 > n:
                break
            delta = int.from_bytes(buf[i:i + 3], 'big')
            length = int.from_bytes(buf[i + 3:i + 6], 'big')
            mtype = buf[i + 6]
            i += 7
            if delta == 0xFFFFFF:
                if i + 4 > n:
                    break
                delta = int.from_bytes(buf[i:i + 4], 'big')
                i += 4
            s.update(delta=delta, length=length, mtype=mtype, got=0, data=b'')
        elif fmt == 2:
            if i + 3 > n:
                break
            delta = int.from_bytes(buf[i:i + 3], 'big')
            i += 3
            if delta == 0xFFFFFF:
                if i + 4 > n:
                    break
                delta = int.from_bytes(buf[i:i + 4], 'big')
                i += 4
            s.update(delta=delta, got=0, data=b'')
        else:                      # fmt == 3，继续上一个消息
            pass
        # chunk data
        need = s['length'] - s['got']
        take = min(chunk_size, need)
        if take <= 0:
            s.update(got=0, data=b'')
            continue
        seg = buf[i:i + take]
        if len(seg) < take:
            break
        s['data'] += seg
        s['got'] += take
        i += take
        if s['got'] >= s['length']:
            msgs.append((s['mtype'], s['data']))
            if s['mtype'] == MSG_SET_CHUNK_SIZE and len(s['data']) >= 4:
                cs = int.from_bytes(s['data'][:4], 'big') & 0x7fffffff
                if cs > 0:
                    chunk_size = cs
            s.update(got=0, data=b'')
    return msgs


# ---------------------------------------------------------------- AMF0
def amf0_read(buf, i):
    """递归解析一个 AMF0 值，返回 (value, next_index)"""
    if i >= len(buf):
        return None, i
    t = buf[i]
    i += 1
    if t == 0x00:                                   # number
        return struct.unpack('>d', buf[i:i + 8])[0], i + 8
    if t == 0x01:                                   # boolean
        return bool(buf[i]), i + 1
    if t == 0x02:                                   # string
        ln = struct.unpack('>H', buf[i:i + 2])[0]
        i += 2
        return buf[i:i + ln].decode('utf-8', 'replace'), i + ln
    if t == 0x0C:                                   # long string
        ln = struct.unpack('>I', buf[i:i + 4])[0]
        i += 4
        return buf[i:i + ln].decode('utf-8', 'replace'), i + ln
    if t in (0x05, 0x06):                           # null / undefined
        return None, i
    if t == 0x0B:                                   # date
        return struct.unpack('>d', buf[i:i + 8])[0], i + 10
    if t == 0x03 or t == 0x08:                      # object / ECMA array
        if t == 0x08:
            i += 4
        obj = {}
        while i + 2 <= len(buf):
            if buf[i:i + 3] == b'\x00\x00\x09':
                i += 3
                break
            klen = struct.unpack('>H', buf[i:i + 2])[0]
            i += 2
            key = buf[i:i + klen].decode('utf-8', 'replace')
            i += klen
            val, i = amf0_read(buf, i)
            obj[key] = val
        return obj, i
    if t == 0x0A:                                   # strict array
        cnt = struct.unpack('>I', buf[i:i + 4])[0]
        i += 4
        arr = []
        for _ in range(cnt):
            v, i = amf0_read(buf, i)
            arr.append(v)
        return arr, i
    return None, i


def amf0_parse(payload):
    """把一条 AMF0 命令消息解成 [name, txn, *args]"""
    out = []
    i = 0
    while i < len(payload):
        v, i = amf0_read(payload, i)
        if i > len(payload) or v is None and i >= len(payload):
            break
        out.append(v)
    return out


def collect_strings(v, out):
    if isinstance(v, str):
        out.append(v)
    elif isinstance(v, dict):
        for k, x in v.items():
            out.append(k)
            collect_strings(x, out)
    elif isinstance(v, list):
        for x in v:
            collect_strings(x, out)


def extract_urls(pcap_path):
    """返回 dict(app=.., streams=[..], server_ip=.., server_port=..)"""
    apps = []
    streams = []
    sip, sport = None, None
    seen_ports = set()
    segs = list(ps.iter_segments(pcap_path))
    # 找 RTMP 端口：1935 或客户端连出去的、上行含 connect 的端口
    cand = {}
    for ts, src, sp, dst, dp, seq, flags, pay in segs:
        if dp in (1935,) or sp in (1935,):
            key = (sp, dp) if dp == 1935 else (dp, sp)
            cand[key] = cand.get(key, 0) + len(pay)
            seen_ports.add(dp if dp == 1935 else sp)
    if not cand:
        return None
    # 取上行字节数最少的那个组合作为 (client, server)
    best = None
    for (c, s), _v in cand.items():
        up, _n = ps.reassemble(pcap_path, c, s)
        if b'connect' in up:
            best = (c, s, up)
            break
    if not best:
        return None
    c, s, up = best
    sip, sport = None, s
    for ts, src, sp, dst, dp, seq, flags, pay in segs:
        if sp == c and dp == s and src != '127.0.0.1':
            sip = dst
            break
    for mtype, data in parse_chunks(up):
        if mtype != MSG_AMF0_COMMAND:
            continue
        vals = amf0_parse(data)
        if not vals:
            continue
        cmd = vals[0] if isinstance(vals[0], str) else None
        strs = []
        for v in vals:
            collect_strings(v, strs)
        for v in strs:
            if v.startswith('rtmp://') or v.startswith('rtmps://'):
                if v not in apps:
                    apps.append(v)
        if cmd in ('play', 'FCSubscribe', 'play2'):
            for v in strs:
                if v not in ('play', 'FCSubscribe', 'play2') and v not in streams:
                    if not v.startswith('rtmp') and '?' in v or v.isdigit():
                        streams.append(v)
    return dict(apps=apps, streams=streams, server=sip, port=sport)


# ---------------------------------------------------------------- 命令
def cmd_cap(seconds=40):
    print('[1/3] 启动抓包（设备 root）...')
    adb('shell', "su -c 'pkill tcpdump'")
    time.sleep(1)
    adb('shell', "su -c 'rm -f /data/local/tmp/j.pcap; nohup tcpdump -i any -s 0 "
                 "-w /data/local/tmp/j.pcap -U >/dev/null 2>&1 &'")
    time.sleep(3)
    print('[2/3] 现在请在模拟器里点进一个正在直播的房间，停留 15 秒以上。')
    for k in range(seconds, 0, -1):
        sys.stdout.write('\r      剩余 %2d 秒 ' % k)
        sys.stdout.flush()
        time.sleep(1)
    print()
    adb('shell', "su -c 'pkill tcpdump'")
    time.sleep(2)
    local = os.path.join(HERE, 'j.pcap')
    print('[3/3] 拉回 pcap ...')
    adb('pull', '/data/local/tmp/j.pcap', local, timeout=180)
    print('已保存', local)
    cmd_parse(local)


def cmd_parse(path):
    r = extract_urls(path)
    if not r:
        print('没找到 RTMP 流。确认抓包期间真的进过直播间。')
        return 1
    print('RTMP 服务端 :', r['server'], ':', r['port'])
    print('tcUrl/app   :', r['apps'])
    print('流名        :', r['streams'])
    doms = re.findall(r'rtmps?://([^/]+)/', ' '.join(r['apps']))
    if doms:
        open(DOMAIN_FILE, 'w').write(doms[0])
        print('已记录域名  :', doms[0], '->', DOMAIN_FILE)
    if r['apps'] and r['streams']:
        print('\n完整播放地址（可直接 ffplay / VLC）:')
        print(r['apps'][0].rstrip('/') + '/' + r['streams'][0])
    return 0


def domain():
    if not os.path.exists(DOMAIN_FILE):
        raise SystemExit('还没有域名，先跑: python tools/rtmp_sniff.py cap')
    return open(DOMAIN_FILE).read().strip()


def cmd_url(room_id):
    print('rtmp://%s/live/%s' % (domain(), room_id))


def cmd_record(room_id, secs=10):
    url = 'rtmp://%s/live/%s' % (domain(), room_id)
    out = 'rec_%s_%s.flv' % (room_id, time.strftime('%H%M%S'))
    print('录制', url, '->', out)
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-rtmp_live', 'live',
                    '-i', url, '-t', str(secs), '-c', 'copy', out], timeout=secs + 30)
    if os.path.exists(out):
        print('完成: %s (%.1f MB)' % (out, os.path.getsize(out) / 1048576))
    else:
        print('失败：可能已下播，或房间号不是直播间')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    c = sys.argv[1]
    if c == 'cap':
        sys.exit(cmd_cap(int(sys.argv[2]) if len(sys.argv) > 2 else 40))
    elif c == 'parse':
        sys.exit(cmd_parse(sys.argv[2]))
    elif c == 'url':
        cmd_url(sys.argv[2])
    elif c == 'record':
        cmd_record(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 10)
    else:
        print(__doc__)
