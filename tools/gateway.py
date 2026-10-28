#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gateway.py — 把天美的 RTMP 直播转成 HLS，让浏览器能直接播

为什么要它：
  天美的播放地址是 rtmp://<域名>/live/<房间号>，浏览器内核不支持 RTMP
  （跟代理无关，是内核限制）。该域名的 HTTP-FLV / HLS 又全部返回 403。
  唯一能让浏览器看到画面的办法，是在本地把 RTMP 无损转封装成 HLS。

  -c copy，不转码，一路直播大约占 2~4% CPU。

用法：
  python tools/gateway.py                 # 默认 127.0.0.1:8899
  python tools/gateway.py --port 9000

然后用浏览器打开  http://127.0.0.1:8899/  （同时也托管了 site/）
页面会自动优先走网关播放，网关没开就退回降级面板。

路由：
  GET /ping                    健康检查
  GET /api/link?room=<id>      返回该房间的 rtmp/m3u/hls 三种地址
  GET /api/play?room=<id>      调起本机播放器（VLC / PotPlayer / mpv / 系统默认）
  GET /play/<id>.m3u           下载 m3u 播放列表，交给系统默认播放器
  GET /live/<房间号>.m3u8       该房间的 HLS 播放列表
  GET /live/<房间号>/<分片>.ts   HLS 分片
  GET /...                     其余走 site/ 静态文件
"""
import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(HERE, 'site')
MAX_STREAMS = 4        # 同时转封装的房间数，超过就踢最久没看过的
IDLE_KILL = 50         # 秒，无请求则干掉 ffmpeg

# 本机播放器探测：先看环境变量 PLAYER，再按顺序找常见安装位置。
# 都没有也可以用 PLAYER 指一个可执行文件，例如
#   set PLAYER="D:\Portable\vlc\vlc.exe"
PLAYERS = [
    ('vlc', ['vlc.exe',
             r'C:\Program Files\VideoLAN\VLC\vlc.exe',
             r'C:\Program Files (x86)\VideoLAN\VLC\vlc.exe',
             '/Applications/VLC.app/Contents/MacOS/VLC', 'vlc']),
    ('potplayer', ['PotPlayerMini64.exe', 'PotPlayerMini.exe',
                   r'C:\Program Files\DAUM\PotPlayer\PotPlayerMini64.exe',
                   r'C:\Program Files (x86)\DAUM\PotPlayer\PotPlayerMini64.exe',
                   r'C:\Program Files\PotPlayer\PotPlayerMini64.exe']),
    ('mpv', ['mpv.exe', 'mpv']),
    ('mpc-hc', ['mpc-hc64.exe', 'mpc-hc.exe']),
]

# 便携版常放这些目录，按 exe 名找两层（不递归扫盘）
SEARCH_DIRS = [
    os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs'),
    r'D:\Program Files', r'D:\Apps', r'D:\Soft',
    r'C:\Program Files', r'C:\Program Files (x86)',
]

PLAYER_EXES = ['vlc.exe', 'PotPlayerMini64.exe', 'PotPlayerMini.exe', 'mpv.exe',
               'mpc-hc64.exe', 'mpc-hc.exe']

_lock = threading.Lock()
_streams = {}          # rid -> {'proc','dir','last'}


def domain():
    f = os.path.join(HERE, '.rtmp_domain.txt')
    if os.path.exists(f):
        return open(f).read().strip()
    return ''


def rtmp_url(rid):
    return 'rtmp://%s/live/%d' % (domain(), rid)


def m3u_text(urls):
    out = ['#EXTM3U']
    for u in urls:
        out.append('#EXTINF:-1,天美 ' + u.rsplit('/', 1)[-1])
        out.append(u)
    return '\n'.join(out) + '\n'


def find_player():
    """返回 (名字, 可执行路径)；找不到返回 (None, None)"""
    env = (os.environ.get('PLAYER') or '').strip('"')
    if env:
        p = env if os.path.exists(env) else shutil.which(env)
        if p:
            return os.path.basename(p), p

    for name, cands in PLAYERS:
        for c in cands:
            p = c if os.path.isabs(c) else shutil.which(c)
            if p and os.path.exists(p):
                return name, p

    # 便携版：在几个常见目录下按 exe 名找一层
    for d in SEARCH_DIRS:
        if not d or not os.path.isdir(d):
            continue
        for root, dirs, files in os.walk(d):
            if root.count(os.sep) - d.count(os.sep) > 2:
                dirs[:] = []
                continue
            low = {f.lower(): f for f in files}
            for exe in PLAYER_EXES:
                if exe.lower() in low:
                    p = os.path.join(root, low[exe.lower()])
                    return os.path.basename(os.path.dirname(p)) or exe, p
    return None, None


def open_in_player(url):
    """在本机播放器里打开地址；没有已知播放器就交给系统默认程序"""
    name, exe = find_player()
    if exe:
        kw = {}
        if sys.platform == 'win32':
            kw['creationflags'] = 0x00000008 | 0x08000000   # DETACHED | NO_WINDOW
        subprocess.Popen([exe, url], close_fds=True, **kw)
        return {'ok': True, 'player': name, 'url': url}

    fd, path = tempfile.mkstemp(suffix='.m3u', prefix='tianmei_')
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        f.write(m3u_text([url]))
    try:
        if sys.platform == 'win32':
            os.startfile(path)                              # noqa: S606
        elif sys.platform == 'darwin':
            subprocess.Popen(['open', path])
        else:
            subprocess.Popen(['xdg-open', path])
    except Exception as e:
        return {'ok': False, 'player': None, 'url': url, 'error': str(e), 'm3u': path}
    return {'ok': True, 'player': 'system-default', 'url': url, 'm3u': path}


def _spawn(rid):
    d = tempfile.mkdtemp(prefix='hls_%d_' % rid)
    url = 'rtmp://%s/live/%d' % (domain(), rid)
    cmd = ['ffmpeg', '-y', '-v', 'error', '-rtmp_live', 'live',
           '-i', url, '-c', 'copy', '-f', 'hls',
           '-hls_time', '2', '-hls_list_size', '6',
           '-hls_flags', 'delete_segments+append_list',
           os.path.join(d, 'index.m3u8')]
    kw = {}
    if sys.platform == 'win32':
        kw['creationflags'] = 0x08000000       # CREATE_NO_WINDOW
    p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, **kw)
    return {'proc': p, 'dir': d, 'last': time.time(), 'url': url}


def _kill(rid):
    e = _streams.pop(rid, None)
    if not e:
        return
    try:
        e['proc'].kill()
    except Exception:
        pass
    shutil.rmtree(e['dir'], ignore_errors=True)


def ensure(rid):
    """拿到（必要时启动）某房间的转封装进程，返回目录"""
    with _lock:
        e = _streams.get(rid)
        if e and e['proc'].poll() is None:
            e['last'] = time.time()
            return e['dir']
        if e:
            _kill(rid)
        # 超量就踢掉最久没访问的
        if len(_streams) >= MAX_STREAMS:
            old = sorted(_streams.items(), key=lambda kv: kv[1]['last'])[0][0]
            _kill(old)
        e = _spawn(rid)
        _streams[rid] = e
        return e['dir']


def janitor():
    while True:
        time.sleep(10)
        now = time.time()
        with _lock:
            for rid in list(_streams):
                e = _streams[rid]
                dead = e['proc'].poll() is not None
                if dead and now - e['last'] > 3:
                    _kill(rid)
                elif now - e['last'] > IDLE_KILL:
                    _kill(rid)


STATIC = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8',
          '.css': 'text/css; charset=utf-8', '.json': 'application/json',
          '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml',
          '.m3u8': 'application/vnd.apple.mpegurl', '.ts': 'video/mp2t'}


class H(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=SITE, **kw)

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype='application/json', extra=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-cache')
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        path = u.path
        qs = parse_qs(u.query)

        if path == '/ping':
            with _lock:
                active = {str(k): v['url'] for k, v in _streams.items()}
            name, exe = find_player()
            return self._send(200, json.dumps({'ok': True, 'domain': domain(),
                                               'player': name, 'active': active}))

        # /api/link?room=<id>  三种地址一次给全，前端复制/播放都用它
        if path == '/api/link':
            try:
                rid = int((qs.get('room') or ['0'])[0])
            except ValueError:
                return self._send(400, json.dumps({'error': 'bad room id'}))
            if not domain():
                return self._send(503, json.dumps({'error': '缺少 .rtmp_domain.txt'}))
            return self._send(200, json.dumps({
                'room': rid,
                'rtmp': rtmp_url(rid),
                'm3u': '/play/%d.m3u' % rid,
                'hls': '/live/%d.m3u8' % rid,
            }, ensure_ascii=False))

        # /api/play?room=<id>  直接调起本机播放器
        if path == '/api/play':
            try:
                rid = int((qs.get('room') or ['0'])[0])
            except ValueError:
                return self._send(400, json.dumps({'error': 'bad room id'}))
            if not domain():
                return self._send(503, json.dumps({'error': '缺少 .rtmp_domain.txt'}))
            return self._send(200, json.dumps(open_in_player(rtmp_url(rid)),
                                              ensure_ascii=False))

        # /play/<id>.m3u  下载播放列表，双击即可用系统默认播放器打开
        if path.startswith('/play/') and path.endswith('.m3u'):
            try:
                rid = int(path[len('/play/'):-len('.m3u')])
            except ValueError:
                return self._send(400, 'bad room id')
            body = m3u_text([rtmp_url(rid)])
            return self._send(200, body, 'audio/x-mpegurl',
                              {'Content-Disposition':
                               'attachment; filename="tianmei_%d.m3u"' % rid})

        # /live/<id>.m3u8  或  /live/<id>/indexN.ts
        if path.startswith('/live/'):
            rest = path[len('/live/'):]
            rid_s = rest.split('.')[0].split('/')[0]
            try:
                rid = int(rid_s)
            except ValueError:
                return self._send(400, json.dumps({'error': 'bad room id'}))

            d = ensure(rid)

            if path.endswith('.m3u8'):
                # ffmpeg 刚启动，等播放列表出现
                pl = os.path.join(d, 'index.m3u8')
                for _ in range(60):
                    if os.path.exists(pl) and os.path.getsize(pl) > 40:
                        break
                    time.sleep(0.2)
                if not os.path.exists(pl):
                    return self._send(503, '#EXTM3U\n# 该房间暂时拉不到流\n',
                                      'application/vnd.apple.mpegurl')
                txt = open(pl, encoding='utf-8', errors='replace').read()
                # 分片路径改写成本服务的绝对路径
                out = []
                for line in txt.splitlines():
                    s = line.strip()
                    if s and not s.startswith('#') and s.endswith('.ts'):
                        out.append('/live/%d/%s' % (rid, s))
                    else:
                        out.append(line)
                return self._send(200, '\n'.join(out) + '\n',
                                  'application/vnd.apple.mpegurl')

            # 分片
            fn = os.path.basename(rest)
            if not fn.endswith('.ts') or '/' in fn or '..' in fn:
                return self._send(400, 'bad')
            fp = os.path.join(d, fn)
            for _ in range(25):
                if os.path.exists(fp):
                    break
                time.sleep(0.2)
            if not os.path.exists(fp):
                return self._send(404, 'no segment')
            try:
                data = open(fp, 'rb').read()
            except Exception:
                return self._send(404, 'read fail')
            return self._send(200, data, 'video/mp2t')

        return super().do_GET()


def lan_ips():
    """枚举本机局域网 IPv4，用来给出手机能访问的地址。
    先连一个不存在的公网地址骗内核选出默认出口网卡，拿不到再退回 getaddrinfo。"""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('8.8.8.8', 80))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith('127.'):
                ips.append(ip)
    except OSError:
        pass
    return [ip for ip in ips if not ip.startswith('127.') and not ip.startswith('169.254.')]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8899)
    # 默认监听 0.0.0.0：手机在同一 Wi-Fi 下要能连上；
    # 只想本机用就加 --host 127.0.0.1
    ap.add_argument('--host', default='0.0.0.0')
    a = ap.parse_args()

    dom = domain()
    if not dom:
        print('缺少 .rtmp_domain.txt，无法拼播放地址'); return
    if not shutil.which('ffmpeg'):
        print('找不到 ffmpeg，请先安装并加入 PATH'); return

    threading.Thread(target=janitor, daemon=True).start()
    srv = ThreadingHTTPServer((a.host, a.port), H)
    print('网关已启动，RTMP 域名: %s' % dom)
    print('  本机:   http://127.0.0.1:%d/' % a.port)
    if a.host in ('0.0.0.0', ''):
        ips = lan_ips()
        if ips:
            # 第一个是默认出口网卡（真 Wi-Fi），其余多半是 VMware/Hyper-V 虚拟网卡
            print('  手机:   http://%s:%d/   <-- 手机浏览器打开这个' % (ips[0], a.port))
            for ip in ips[1:]:
                print('  备用:   http://%s:%d/   （虚拟网卡，一般不用）' % (ip, a.port))
        print('  手机打不开就先放行防火墙：')
        print('    netsh advfirewall firewall add rule name="tianmei-gw" dir=in '
              'action=allow protocol=TCP localport=%d' % a.port)
    print('点任意房间即可播放；Ctrl+C 停止。')
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        with _lock:
            for rid in list(_streams):
                _kill(rid)
        srv.server_close()


if __name__ == '__main__':
    main()
