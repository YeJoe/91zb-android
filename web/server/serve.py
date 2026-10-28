# -*- coding: utf-8 -*-
"""
本地服务器：静态页面 + HEVC 转码 + HLS 切片（手机端）

    python serve.py            # 默认 0.0.0.0:8899（同一局域网内手机可访问）
    python serve.py 9000

路由
----
/                      页面
/mpegts.js             MSE 播放库
/status                JSON：ffmpeg 是否可用、版本、HLS 会话数
/ts?u=<flv>&q=720      实时转码 HEVC->H.264，MPEG-TS over HTTP（Android Chrome + mpegts.js）
/hls/start?u=<flv>&q=720   创建 HLS 会话，返回 {sid, url}
/hls/<sid>/index.m3u8      HLS 播放列表（iOS Safari 原生播放）
/hls/<sid>/segNNNNN.ts     HLS 分片
/proxy?u=<flv>         原样转发（不转码，供 ffmpeg 读取明文流）
/img?u=/15/xxx.jpg     封面图代理：剥掉图床防盗链前缀字节后下发（带本地缓存）

为什么需要两条通道
------------------
源站全部是 HEVC（FLV codecId=12）。
  * Android Chrome：有 MSE，能接受 MPEG-TS，但解不了 HEVC  -> /ts 转 H.264
  * iOS Safari：完全没有 MSE，只能靠原生 HLS 播放         -> /hls 出 m3u8
"""
import hashlib
import json
import os
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    sys.stderr.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))   # 本文件目录（server/）
PROJECT_ROOT = os.path.dirname(HERE)                 # 91zb-web/
TOOLS_DIR = os.path.join(PROJECT_ROOT, "tools")      # 数据抓取 / 构建脚本
WEB_DIR = os.path.join(PROJECT_ROOT, "web")          # 前端静态目录
DATA_DIR = os.path.join(PROJECT_ROOT, "data")        # 房间数据 / 令牌
# 让 serve.py 能 import tools/ 下的 jj_rooms（用于读取/校验令牌）
if TOOLS_DIR not in sys.path:
    sys.path.insert(0, TOOLS_DIR)

ROOT = WEB_DIR                                       # 静态文件根目录
LOG_DIR = os.path.join(PROJECT_ROOT, "_logs")
HLS_DIR = os.path.join(PROJECT_ROOT, "_hls")         # 运行时按需创建，不在导入时建目录
IMG_DIR = os.path.join(PROJECT_ROOT, "_imgcache")    # 封面图本地缓存（运行时按需创建）

# 封面图源：imgEncryDomain（App /网页版同款）。注意图床在真实文件前多塞了 1 个字节
# 做防盗链，浏览器直接 <img> 会解码失败，必须由 /img 代理剥掉前缀再下发。
IMG_DOMAINS = (
    "https://rgf.rucqnij.xyz",
    "https://utp.opuxrcm.com",
    "https://ytt.jvjzmc.com",
)
IMG_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

# 图片真实起始魔数（去前缀后按此判断类型）
IMG_SIGS = (
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"GIF8", "image/gif"),
    (b"RIFF", "image/webp"),
)


def _ensure_dir(p):
    try:
        os.makedirs(p, exist_ok=True)
    except Exception:
        pass

_CTX = ssl.create_default_context()
_CTX.check_hostname = False
_CTX.verify_mode = ssl.CERT_NONE


def img_sniff(buf):
    """按魔数判断图片类型，返回 (数据起点, Content-Type)。"""
    for sig, ctype in IMG_SIGS:
        i = buf.find(sig, 0, 64)
        if i >= 0:
            return i, ctype
    return -1, "application/octet-stream"


def img_strip_prefix(buf):
    """去掉图床塞在文件头的垃圾字节，返回 (纯净数据, Content-Type)。"""
    off, ctype = img_sniff(buf)
    if off > 0:
        buf = buf[off:]
    elif off < 0:
        ctype = "application/octet-stream"
    return buf, ctype


_FF_CACHE = {}
_HLS = {}
_HLS_LOCK = threading.Lock()
_HLS_IDLE = 40          # 秒：无请求多久后回收会话
_HLS_SWEEP = 10

# ─── 令牌管理 ───
TOKEN_FILE = os.path.join(DATA_DIR, "token.txt")
_TOKEN_CACHE = {"token": None, "ok": None, "checked": 0.0, "msg": "", "source": ""}
_TOKEN_LOCK = threading.Lock()


def read_token():
    try:
        from jj_rooms import load_token
        return load_token(TOKEN_FILE)
    except Exception:
        return None


def write_token(t):
    """令牌只含十六进制字符，按 ASCII 写，避免编码问题。"""
    t = (t or "").strip()
    if not t:
        return False
    with open(TOKEN_FILE, "w", encoding="ascii", newline="") as f:
        f.write(t)
    with _TOKEN_LOCK:
        _TOKEN_CACHE.update({"token": t, "ok": None, "checked": 0.0, "source": "manual"})
    return True


def _save_rotated_token(new_tok):
    """jj_client 收到服务端下发的新 authorization 时回调到这里。"""
    try:
        with _TOKEN_LOCK:
            cur = _TOKEN_CACHE.get("token")
            _TOKEN_CACHE.update({"token": new_tok, "ok": True,
                                 "checked": time.time(), "source": "rotated"})
        with open(TOKEN_FILE, "w", encoding="ascii", newline="") as f:
            f.write(new_tok)
        sys.stderr.write("[token] 已保存服务端轮换的新令牌\n")
    except Exception as e:
        sys.stderr.write(f"[token] 保存轮换令牌失败: {e}\n")


def validate_token(tok, force=False):
    """调一次轻量接口验证令牌；结果缓存 5 分钟。"""
    if not tok:
        return {"ok": False, "msg": "未设置令牌"}
    now = time.time()
    with _TOKEN_LOCK:
        c = dict(_TOKEN_CACHE)
    if not force and c.get("token") == tok and c.get("ok") is not None and now - c.get("checked", 0) < 300:
        return {"ok": c["ok"], "msg": c.get("msg", "")}
    try:
        from jj_client import JJClient, set_token_callback
        set_token_callback(_save_rotated_token)
        cli = JJClient("https://utp.opuxrcm.com", tok,
                       "1bbd0f822e77afd060cd5e8cc1ac147c",
                       terminal="3", version="436", channel="12")
        r = cli.call("/param/list", {})
        d = r.get("json") or {}
        ok = (r.get("status") == 200) and (d.get("code") == 0)
        msg = "" if ok else f"HTTP {r.get('status')}, code={d.get('code')}, msg={str(d.get('msg'))[:60]}"
    except Exception as e:
        ok, msg = False, f"{type(e).__name__}: {e}"
    with _TOKEN_LOCK:
        _TOKEN_CACHE.update({"token": tok, "ok": ok, "checked": now, "msg": msg})
    return {"ok": ok, "msg": msg}


# ─────────────────────────── ffmpeg ───────────────────────────
def find_ffmpeg():
    if "path" in _FF_CACHE:
        return _FF_CACHE["path"]
    cands = [
        os.path.join(PROJECT_ROOT, "_tools", "ffmpeg", "ffmpeg.exe"),
        os.path.join(PROJECT_ROOT, "_tools", "ffmpeg.exe"),
        r"D:\Apps\DouyinLiveRecorder_v4.0.7\ffmpeg\ffmpeg.exe",
        shutil.which("ffmpeg"),
        r"D:\Apps\Scoop\shims\ffmpeg.exe",
        r"C:\ffmpeg\bin\ffmpeg.exe",
    ]
    for c in cands:
        if c and os.path.isfile(c):
            _FF_CACHE["path"] = c
            return c
    _FF_CACHE["path"] = None
    return None


def ffmpeg_version(path):
    try:
        out = subprocess.run([path, "-version"], capture_output=True, timeout=15)
        line = (out.stdout or b"").decode("utf-8", "replace").splitlines()
        return line[0] if line else "unknown"
    except Exception as e:
        return f"error: {e}"


def quality_params(q):
    height = {"1080": 1080, "720": 720, "480": 480, "360": 360}.get(str(q), 720)
    vbr = {1080: "3000k", 720: "2000k", 480: "1200k", 360: "800k"}[height]
    mx = {1080: "3600k", 720: "2500k", 480: "1500k", 360: "1000k"}[height]
    return height, vbr, mx


def base_input_cmd(ff, local_in, height, vbr, mx):
    return [
        ff, "-hide_banner", "-loglevel", "warning",
        "-fflags", "nobuffer", "-flags", "low_delay",
        "-analyzeduration", "1000000", "-probesize", "1000000",
        "-i", local_in,
        "-c:v", "libx264",
        "-preset", "veryfast", "-tune", "zerolatency",
        "-profile:v", "main", "-pix_fmt", "yuv420p",
        "-vf", f"scale=-2:{height}",
        "-b:v", vbr, "-maxrate", mx, "-bufsize", "5000k",
        "-g", "25", "-keyint_min", "25", "-sc_threshold", "0",
        "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2",
    ]


def local_proxy_url(port, target):
    """ffmpeg 自带 schannel 对源站 TLS 握手会失败，改读本机明文代理。"""
    return f"http://127.0.0.1:{port}/proxy?u=" + urllib.parse.quote(target, safe="")


# ─────────────────────────── HLS 会话 ───────────────────────────
class HlsSession:
    def __init__(self, sid, target, height):
        self.sid = sid
        self.target = target
        self.height = height
        self.dir = os.path.join(HLS_DIR, sid)
        self.last = time.time()
        self.proc = None
        self.log = None

    def playlist(self):
        return os.path.join(self.dir, "index.m3u8")

    def start(self, ff, port):
        os.makedirs(self.dir, exist_ok=True)
        h, vbr, mx = quality_params(str(self.height))
        cmd = base_input_cmd(ff, local_proxy_url(port, self.target), h, vbr, mx) + [
            "-f", "hls",
            "-hls_time", "1",
            "-hls_list_size", "6",
            "-hls_flags", "delete_segments+append_list+omit_endlist",
            "-hls_segment_filename", os.path.join(self.dir, "seg%05d.ts"),
            "-hls_allow_cache", "0",
            "-muxdelay", "0", "-muxpreload", "0",
            self.playlist(),
        ]
        _ensure_dir(self.dir)
        _ensure_dir(LOG_DIR)
        self.log = open(os.path.join(LOG_DIR, f"hls_{self.sid}.log"), "wb", buffering=0)
        self.proc = subprocess.Popen(
            cmd, stdout=subprocess.DEVNULL, stderr=self.log,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )

    def wait_ready(self, timeout=25):
        t0 = time.time()
        while time.time() - t0 < timeout:
            if os.path.isfile(self.playlist()) and os.path.getsize(self.playlist()) > 0:
                return True
            if self.proc and self.proc.poll() is not None:
                return False
            time.sleep(0.25)
        return False

    def log_tail(self, n=600):
        try:
            p = os.path.join(LOG_DIR, f"hls_{self.sid}.log")
            if not os.path.isfile(p):
                return ""
            with open(p, "rb") as f:
                f.seek(0, os.SEEK_END)
                size = f.tell()
                f.seek(max(0, size - n))
                return f.read().decode("utf-8", "replace")
        except Exception:
            return ""

    def stop(self):
        try:
            if self.proc and self.proc.poll() is None:
                self.proc.kill()
                self.proc.wait(timeout=5)
        except Exception:
            pass
        try:
            if self.log:
                self.log.close()
        except Exception:
            pass
        try:
            shutil.rmtree(self.dir, ignore_errors=True)
        except Exception:
            pass


def sweep_hls():
    while True:
        time.sleep(_HLS_SWEEP)
        now = time.time()
        dead = []
        with _HLS_LOCK:
            for sid, s in list(_HLS.items()):
                if now - s.last > _HLS_IDLE:
                    dead.append(sid)
            for sid in dead:
                s = _HLS.pop(sid, None)
                if s:
                    s.stop()
        if dead:
            sys.stderr.write(f"[hls] 回收会话 {dead}\n")


# ─────────────────────────── HTTP ───────────────────────────
class Handler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_message(self, fmt, *args):
        try:
            msg = fmt % args
        except Exception:
            msg = str(fmt)
        if any(k in msg for k in ("/ts", "/proxy", "/hls", " 50", " 40")):
            sys.stderr.write("[http] " + msg + "\n")

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        if getattr(self, "_cache_ok", False):
            self.send_header("Cache-Control", "public, max-age=86400")
        else:
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Max-Age", "86400")
        self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/token":
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            try:
                payload = json.loads(raw.decode("utf-8", "replace") or "{}")
            except Exception:
                payload = {}
            tok = (payload.get("token") or "").strip()
            if not tok:
                self._json({"ok": False, "msg": "缺少 token"}, code=400)
                return
            v = validate_token(tok, force=True)
            if not v["ok"]:
                self._json({"ok": False, "msg": v["msg"] or "令牌无效"}, code=400)
                return
            write_token(tok)
            self._json({"ok": True, "msg": "令牌已保存"})
            return
        if parsed.path == "/api/refresh":
            self._api_refresh()
            return
        self.send_error(404)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        q = parsed.query

        if path == "/status":
            self._status()
            return
        if path == "/health":
            self._health()
            return
        if path == "/api/rooms":
            self._api_rooms()
            return
        if path == "/api/token":
            self._api_token(q)
            return
        if path == "/api/refresh":
            self._api_refresh()
            return
        if path == "/ts":
            self._transcode(q)
            return
        if path == "/remux":
            self._remux(q)
            return
        if path == "/hls/start":
            self._hls_start(q)
            return
        if path.startswith("/hls/"):
            self._hls_file(path)
            return
        if path == "/proxy":
            self._proxy(q)
            return
        if path == "/img":
            self._image(q)
            return
        if path == "/":
            self.path = "/index.html"
        super().do_GET()

    # ---------- /health ----------
    def _health(self):
        ff = find_ffmpeg()
        n = 0
        try:
            with open(os.path.join(DATA_DIR, "rooms_all.json"), encoding="utf-8") as f:
                n = len(json.load(f).get("rooms") or {})
        except Exception:
            pass
        self._json({"ok": bool(ff), "ffmpeg": ff, "rooms": n,
                    "hls_sessions": len(_HLS)})

    # ---------- /api/token 查询 / 校验 ----------
    def _api_token(self, query):
        qs = urllib.parse.parse_qs(query)
        force = (qs.get("force") or ["0"])[0] == "1"
        tok = read_token()
        if not tok:
            self._json({"has_token": False, "valid": False, "msg": "未设置令牌"})
            return
        v = validate_token(tok, force=force)
        preview = tok if len(tok) <= 28 else (tok[:12] + "..." + tok[-8:])
        with _TOKEN_LOCK:
            src = _TOKEN_CACHE.get("source", "")
        self._json({
            "has_token": True,
            "valid": v["ok"],
            "msg": v["msg"],
            "preview": preview,
            "source": src,
        })

    # ---------- /api/refresh 用当前令牌重新抓取并生成网页 ----------
    def _api_refresh(self):
        tok = read_token()
        if not tok:
            self._json({"ok": False, "msg": "未设置令牌"}, code=400)
            return
        v = validate_token(tok, force=True)
        if not v["ok"]:
            self._json({"ok": False, "msg": v["msg"] or "令牌已失效，请重新输入"}, code=400)
            return

        py = sys.executable
        try:
            r1 = subprocess.run(
                [py, os.path.join(TOOLS_DIR, "jj_rooms.py"), "--token", tok,
                 "--out", os.path.join(DATA_DIR, "rooms_all.json"), "--quiet"],
                cwd=TOOLS_DIR, capture_output=True, timeout=900)
            if r1.returncode != 0:
                err = (r1.stderr or b"").decode("utf-8", "replace")[-300:]
                self._json({"ok": False, "msg": "抓取房间失败：" + (err or "未知错误")}, code=500)
                return
            r2 = subprocess.run(
                [py, os.path.join(TOOLS_DIR, "build_web.py"),
                 os.path.join(DATA_DIR, "rooms_all.json"), WEB_DIR],
                cwd=TOOLS_DIR, capture_output=True, timeout=300)
            ok = r2.returncode == 0
            out = (r2.stdout or b"").decode("utf-8", "replace")
            self._json({"ok": ok, "msg": (out.strip().splitlines() or ["生成失败"])[0]})
        except Exception as e:
            self._json({"ok": False, "msg": f"{type(e).__name__}: {e}"}, code=500)

    # ---------- /api/rooms  给 App 用的精简房间列表 ----------
    def _api_rooms(self):
        """把 rooms_all.json 压成 App 直接可用的结构。

        App 端因此不必实现路径混淆 / 签名 / xorCode 三道加密，
        只要 GET 这一个接口 + 播 /ts 即可。
        """
        try:
            with open(os.path.join(DATA_DIR, "rooms_all.json"), encoding="utf-8") as f:
                src = json.load(f)
        except Exception as e:
            self._json({"error": f"rooms_all.json: {e}"}, code=500)
            return

        port = self.server.server_port
        base = f"http://{self.headers.get('Host') or ('127.0.0.1:%d' % port)}"
        out = []
        for rid, r in (src.get("rooms") or {}).items():
            u0 = r.get("pullStreamAddr") or ""
            u3 = r.get("pullStreamAddr360") or ""
            u7 = r.get("pullStreamAddr720") or ""
            raw = u0 or u7 or u3
            out.append({
                "roomId": str(r.get("roomId") or rid),
                "userId": str(r.get("userId") or ""),
                "userName": r.get("userName") or "",
                "roomName": r.get("roomName") or "",
                "hot": r.get("currentHot") or 0,
                "cover": (str(r.get("roomCover") or "")),
                "liveType": r.get("liveType"),
                "payType": r.get("payType"),
                "moneyFen": r.get("moneyFen") or 0,
                "oneToOneState": r.get("oneToOneState"),
                "cats": r.get("_cats") or [],
                # 已转码、可直接丢给播放器的地址（H.264/MPEG-TS）
                "playPath": ("/ts?u=" + urllib.parse.quote(raw, safe="") + "&q=720") if raw else "",
                "playUrl": (f"{base}/ts?u=" + urllib.parse.quote(raw, safe="") + "&q=720") if raw else "",
                # 原始 HEVC 流（原生硬解或排障用）
                "rawFlv": u0 or u7 or u3,
            })
        out.sort(key=lambda x: -(x["hot"] if isinstance(x["hot"], int) else 0))
        self._json({"count": len(out), "generated": src.get("generated"),
                    "categories": src.get("categories") or [], "rooms": out})

    # ---------- /status ----------
    def _status(self):
        ff = find_ffmpeg()
        body = json.dumps({
            "ffmpeg": ff,
            "available": bool(ff),
            "version": ffmpeg_version(ff) if ff else None,
            "hls_sessions": len(_HLS),
        }, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---------- /remux  只换容器，不重编码 ----------
    def _remux(self, query):
        """FLV(HEVC) -> MPEG-TS(HEVC)，-c copy。

        给 Android 原生播放器用：手机芯片能硬解 HEVC，
        只是不吃 FLV 容器。这样画质完全无损、几乎不耗 CPU。
        """
        qs = urllib.parse.parse_qs(query)
        target = (qs.get("u") or [""])[0]
        if not target:
            self.send_error(400, "missing u")
            return
        ff = find_ffmpeg()
        if not ff:
            self.send_error(503, "ffmpeg not found")
            return

        local_in = local_proxy_url(self.server.server_port, target)
        # 必须显式 -map：源 FLV 里还有一条 codecId=13 的未知视频轨，
        # 不指定映射时 mpegts 封装会直接失败（输出 0 字节）。
        cmd = [
            ff, "-hide_banner", "-loglevel", "error",
            "-analyzeduration", "2000000", "-probesize", "2000000",
            "-i", local_in,
            "-map", "0:v:0", "-map", "0:a:0",
            "-c", "copy",
            "-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0",
            "pipe:1",
        ]

        _ensure_dir(LOG_DIR)
        log_path = os.path.join(LOG_DIR, "ffmpeg_remux.log")
        try:
            logf = open(log_path, "ab", buffering=0)
        except Exception:
            logf = subprocess.DEVNULL

        try:
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=logf, bufsize=0,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as e:
            self.send_error(500, f"ffmpeg spawn: {e}")
            return

        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()

        sent = 0
        try:
            while True:
                chunk = proc.stdout.read(32768)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
                sent += len(chunk)
        except Exception:
            pass
        finally:
            try:
                proc.kill()
                proc.wait(timeout=5)
            except Exception:
                pass
            sys.stderr.write(f"[remux] closed, {sent} bytes\n")

    # ---------- /ts ----------
    def _transcode(self, query):
        qs = urllib.parse.parse_qs(query)
        target = (qs.get("u") or [""])[0]
        if not target:
            self.send_error(400, "missing u")
            return
        ff = find_ffmpeg()
        if not ff:
            self.send_error(503, "ffmpeg not found")
            return

        height, vbr, mx = quality_params((qs.get("q") or ["720"])[0])
        local_in = local_proxy_url(self.server.server_port, target)
        cmd = base_input_cmd(ff, local_in, height, vbr, mx) + [
            "-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "pipe:1",
        ]

        log_path = os.path.join(LOG_DIR, "ffmpeg_ts.log")
        _ensure_dir(LOG_DIR)
        try:
            logf = open(log_path, "ab", buffering=0)
        except Exception:
            logf = subprocess.DEVNULL

        try:
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=logf, bufsize=0,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as e:
            self.send_error(500, f"ffmpeg spawn: {e}")
            return

        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()

        sent = 0
        try:
            while True:
                chunk = proc.stdout.read(32768)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
                sent += len(chunk)
        except Exception:
            pass
        finally:
            try:
                proc.kill()
                proc.wait(timeout=5)
            except Exception:
                pass
            sys.stderr.write(f"[ts] closed, {sent} bytes\n")

    # ---------- /hls/start ----------
    def _hls_start(self, query):
        qs = urllib.parse.parse_qs(query)
        target = (qs.get("u") or [""])[0]
        qv = (qs.get("q") or ["720"])[0]
        if not target:
            self.send_error(400, "missing u")
            return
        ff = find_ffmpeg()
        if not ff:
            self.send_error(503, "ffmpeg not found")
            return

        height, _, _ = quality_params(qv)

        # 复用同一 target+画质 的存活会话，避免重复拉起 ffmpeg
        with _HLS_LOCK:
            for s in _HLS.values():
                if (s.target == target and s.height == height
                        and s.proc and s.proc.poll() is None):
                    s.last = time.time()
                    self._json({"sid": s.sid,
                                "url": f"/hls/{s.sid}/index.m3u8",
                                "reused": True})
                    return

        sid = secrets.token_hex(4)
        sess = HlsSession(sid, target, height)
        with _HLS_LOCK:
            _HLS[sid] = sess
        sess.start(ff, self.server.server_port)

        if not sess.wait_ready():
            with _HLS_LOCK:
                _HLS.pop(sid, None)
            tail = sess.log_tail()
            sess.stop()
            self._json({"error": "hls 启动失败", "log": tail}, code=500)
            return

        self._json({"sid": sid, "url": f"/hls/{sid}/index.m3u8"})

    # ---------- /hls/<sid>/<file> ----------
    def _hls_file(self, path):
        m = re.match(r"^/hls/([0-9a-fA-F]{4,16})/([A-Za-z0-9_.-]+)$", path)
        if not m:
            self.send_error(404, "bad hls path")
            return
        sid, fname = m.group(1), m.group(2)
        if ".." in fname or fname.startswith("."):
            self.send_error(403, "forbidden")
            return

        with _HLS_LOCK:
            sess = _HLS.get(sid)
            if sess:
                sess.last = time.time()

        fp = os.path.join(HLS_DIR, sid, fname)
        if not os.path.isfile(fp):
            self.send_error(404, "segment not found")
            return
        try:
            data = open(fp, "rb").read()
        except Exception as e:
            self.send_error(500, str(e))
            return

        ctype = ("application/vnd.apple.mpegurl" if fname.endswith(".m3u8")
                 else "video/mp2t" if fname.endswith(".ts")
                 else "application/octet-stream")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    # ---------- /img 封面图代理（剥掉图床的 1 字节防盗链前缀） ----------
    def _image(self, query):
        rel = (urllib.parse.parse_qs(query).get("u") or [""])[0]
        if not rel.startswith("/") or ".." in rel or len(rel) > 512:
            self.send_error(400, "bad u")
            return

        key = hashlib.sha1(rel.encode("utf-8")).hexdigest()
        fp = os.path.join(IMG_DIR, key + ".img")
        data = None
        ctype = "image/jpeg"

        if os.path.exists(fp):
            try:
                data = open(fp, "rb").read()
                off, ctype = img_sniff(data)
                if off > 0:
                    data = data[off:]
            except Exception:
                data = None

        if not data:
            err = "无可用的图片源"
            for dom in IMG_DOMAINS:
                url = dom + rel
                try:
                    req = urllib.request.Request(url, headers={
                        "User-Agent": IMG_UA,
                        "Referer": dom + "/",
                        "Accept": "image/avif,image/webp,image/*,*/*;q=0.8",
                    })
                    with urllib.request.urlopen(req, timeout=20, context=_CTX) as up:
                        raw = up.read()
                except Exception as e:
                    err = f"{type(e).__name__}: {e}"
                    continue
                if len(raw) < 200:
                    err = f"响应过短({len(raw)}B)"
                    continue
                data, ctype = img_strip_prefix(raw)
                if len(data) < 200:
                    data = None
                    err = "剥离前缀后仍无有效图像"
                    continue
                _ensure_dir(IMG_DIR)
                try:
                    tmp = fp + ".part"
                    with open(tmp, "wb") as f:
                        f.write(data)
                    os.replace(tmp, fp)
                except Exception:
                    pass
                break

        if not data:
            self.send_error(404, err)
            return

        self._cache_ok = True
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except Exception:
            pass

    # ---------- /proxy ----------
    def _proxy(self, query):
        target = (urllib.parse.parse_qs(query).get("u") or [""])[0]
        if not target:
            self.send_error(400, "missing u")
            return
        req = urllib.request.Request(target, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Origin": "http://127.0.0.1",
        })
        try:
            upstream = urllib.request.urlopen(req, timeout=30, context=_CTX)
        except Exception as e:
            self.send_error(502, f"upstream: {type(e).__name__}: {e}")
            return

        self.send_response(200)
        self.send_header("Content-Type", upstream.headers.get("Content-Type", "video/x-flv"))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            while True:
                chunk = upstream.read(32768)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except Exception:
            pass
        finally:
            try:
                upstream.close()
            except Exception:
                pass

    # ---------- helpers ----------
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)


def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def main():
    port = 8899
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print("端口参数无效，使用默认 8899")
    if not os.path.isdir(ROOT):
        print(f"找不到目录 {ROOT}，请先运行 build_web.py 生成网页")
        return

    ff = find_ffmpeg()
    print("=" * 66)
    print(f"  本机打开：   http://127.0.0.1:{port}/")
    ips = lan_ips()
    for ip in ips:
        print(f"  手机打开：   http://{ip}:{port}/    (同一 Wi-Fi)")
    if not ips:
        print("  手机打开：   未探测到局域网 IP，请确认已连接 Wi-Fi")
    print(f"  静态目录：   {ROOT}")
    if ff:
        print(f"  ffmpeg：     {ff}（仅 /ts 转码兜底用，网页默认走 WASM 不调用）")
    else:
        print("  ffmpeg：     未找到（不影响 WASM 直连播放）")
    print("  按 Ctrl+C 停止")
    print("=" * 66)

    threading.Thread(target=sweep_hls, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
        with _HLS_LOCK:
            for s in _HLS.values():
                s.stop()


if __name__ == "__main__":
    main()
