#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""源 App 凭证「一键导出」工具 (v2)
=================================

DC Live 的「实时直播源」直连源 App 隧道端口, 需要两个凭证 + 一个端口:

  - appToken : 32 位 hex 会话令牌, 拼在隧道 URL 的 ?token=
  - jwe      : Authorization: Bearer 的值, 来自源 App 的 MAINUSER_DATA.token (长期密钥)
  - tunnelPort: 能响应隧道 REST 的端口 (可留空让 App 自动探测)

凭证来源 (按顺序尝试, 前面的拿不到才走后面):

  1) SharedPreferences:  /data/data/<pkg>/shared_prefs/*.xml
     键 flutter.MAINUSER_DATA 是一段 JSON, 其中 token 字段即 JWE。
  2) 项目缓存 .session.json (仅当其中的 JWE 与设备上一致时才采信)
  3) loopback 抓包:  App 把明文 HTTP 打到 127.0.0.1:<随机端口>,
     URL 里就带 ?token=<32hex>。8.5.3 起会话令牌**只存在内存**, 磁盘上没有,
     所以这是该版本唯一可靠的取法 (需要设备 root + tcpdump)。

用法:
  python tools/token_export.py                 # 默认 = export: 读设备 + 探测 + 出可粘贴配置
  python tools/token_export.py dump            # 只列候选 token/jwe/端口 (不在线探测)
  python tools/token_export.py ports           # 只列设备上的隧道 LISTEN 端口
  python tools/token_export.py capture         # 只做 loopback 抓包取 appToken
  python tools/token_export.py diagnose        # 排查为什么读不到 prefs
  python tools/token_export.py from-file X.xml # 离线解析本地 prefs 快照 (调试, 不碰设备)

选项:
  --device SERIAL   指定 adb 设备 (默认: 第一个 online 设备)
  --pkg PKG         源 App 包名 (默认 com.l95c2450d7.f68bb4e2d5)
  --no-probe       导出但不做在线探测 (只给候选, 适合设备不在手边)
  --no-capture     禁用 loopback 抓包 (不想让 App 被拉前台时用)
  --out FILE        把可粘贴配置同时写入文件 (默认只打印到 stdout)
"""
import gzip
import base64
import json
import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime

# ----------------------------------------------------------------- 默认参数
DEFAULT_PKG = "com.l95c2450d7.f68bb4e2d5"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SESSION_CACHE = os.path.join(HERE, ".session.json")
TOKEN_CACHE = os.path.join(HERE, ".apptoken.json")

# adbd 自己会 listen 5555, 不是隧道端口
SKIP_PORTS = {5555}

# 32 位会话令牌: 严格 32 个十六进制字符。
TOKEN_RE = re.compile(r"^[0-9a-fA-F]{32}$")
# JWE / 长期密钥: 点分 (至少两段)、每段 base64url。
# 注意: alg=dir 时 encrypted_key 段是**空的**, 形如 "hdr..iv.ciphertext.tag",
# 所以除首段外每段都允许空 —— 这里必须用 * 而不是 +, 否则真正的 JWE 会被丢掉。
JWE_RE = re.compile(r"^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*)+$")

# 轻量验证接口: 按顺序试, 第一个返回 200/code=0 就算这条隧道通。
# 注意: 8.5.3 起 /room/list 已经 404 (App 自己改成走 ws 的 ctl=room 帧了),
# 所以不能拿它当探针 —— 否则 token 明明有效也会被判成不通。
PROBE_TARGETS = (
    ("/game_list/list", {"pageIndex": 1, "menuId": 0}),
    ("/game_club_member/get_money", {"id": 0}),
    ("/room/list", {"pageIndex": 1, "menuId": 0}),
)


def _print(*a, **k):
    try:
        print(*a, **k)
    except UnicodeEncodeError:
        enc = sys.stdout.encoding or "utf-8"
        txt = " ".join(str(x) for x in a)
        print(txt.encode(enc, "replace").decode(enc), **k)


class SuError(RuntimeError):
    """su 不可用 / 未授权 / 超时。"""


# ----------------------------------------------------------------- adb 封装
class Adb:
    def __init__(self, device=None):
        self.device = device
        # None = 还没探; True = adb shell 本身就是 root (KernelSU/Magisk 常见)
        self._shell_root = None

    def raw(self, *args, timeout=30):
        cmd = ["adb"]
        if self.device:
            cmd += ["-s", self.device]
        cmd += list(args)
        try:
            p = subprocess.run(
                cmd, capture_output=True, timeout=timeout,
                encoding="utf-8", errors="replace",
            )
            return p.returncode, p.stdout or "", p.stderr or ""
        except FileNotFoundError:
            return 127, "", "adb 不在 PATH 里"
        except subprocess.TimeoutExpired:
            return 124, "", "adb 命令超时"

    def shell_is_root(self, timeout=8):
        """adb shell 自己是不是 root。

        KernelSU / Magisk 常把 adbd 直接跑成 uid=0（context u:r:ksu:s0），
        这时再调 `su -c` 等于让 root 进程去申请 root —— 授权弹窗一卡就是十几秒超时。
        先探一次，是 root 就彻底绕开 su。
        """
        if self._shell_root is None:
            rc, out, err = self.raw("exec-out", "id", timeout=timeout)
            self._shell_root = (rc == 0 and "uid=0" in out)
        return bool(self._shell_root)

    def root_ok(self, timeout=15):
        if self.shell_is_root():
            return True
        rc, out, err = self.raw("exec-out", "su", "-c", "id", timeout=timeout)
        if rc == 124:
            raise SuError("su 调用超时 (%ds) —— 大概率是 root 授权弹窗卡住了, "
                          "点亮屏幕后在设备上允许本机的 root 请求。" % timeout
                          + "若 adb shell 本身已是 root, 本工具会自动跳过 su。")
        return rc == 0 and "uid=0" in out

    def sudo(self, cmd, timeout=25):
        """以 root 执行一条 shell 命令。用 exec-out 避免 pty 把 \\n 改成 \\r\\n,
        二进制/大文件读出来才是干净的。"""
        if self.shell_is_root():
            rc, out, err = self.raw("exec-out", "sh", "-c", cmd, timeout=timeout)
            if rc == 124:
                raise SuError("命令超时: %s" % cmd[:60])
            if rc != 0 and not out.strip():
                raise SuError("执行失败 (rc=%d): %s" % (rc, (err or "").strip()[:120]))
            return out
        rc, out, err = self.raw("exec-out", "su", "-c", cmd, timeout=timeout)
        if rc == 124:
            raise SuError("su 执行超时: %s" % cmd[:60])
        if rc != 0 or not out.strip():
            # 没有 su 的设备退化到普通 shell (读不到别人家的 prefs, 但至少给个准信)
            rc2, out2, err2 = self.raw("exec-out", "sh", "-c", cmd, timeout=timeout)
            if out2.strip():
                return out2
            detail = (err or err2 or "").strip()
            raise SuError("root 执行失败 (rc=%d)%s" % (rc, (": " + detail[:120]) if detail else ""))
        return out


def pick_device(adb):
    rc, out, err = adb.raw("devices")
    if rc != 0:
        _print("[!] 执行 adb devices 失败:", err.strip())
        return None
    online = []
    for line in out.splitlines():
        cols = line.split()
        # 形如 "emulator-5554\tdevice product:... model:..." —— 不能只看行尾
        if len(cols) >= 2 and cols[1] == "device":
            online.append(cols[0])
    if not online:
        return None
    if len(online) > 1:
        _print("[!] 有 %d 台在线设备, 默认用第一台: %s (要换用 --device)" % (len(online), online[0]))
    return online[0]


# ----------------------------------------------------------------- 环境自检
def diagnose(pkg, device):
    """把「读不到 prefs」拆成具体原因, 而不是一句猜测。"""
    adb = Adb(device)
    lines = []
    ok = True

    rc, out, err = adb.raw("devices")
    devs = [l.split()[0] for l in out.splitlines() if len(l.split()) >= 2 and l.split()[1] == "device"]
    lines.append("adb devices      : %s" % (devs or "(无在线设备)"))
    if not devs:
        lines.append("  → 设备没连上/未授权, 先插线或确认 adb 弹窗已允许")
        return False, lines

    serial = adb.device or devs[0]
    lines.append("使用设备         : %s" % serial)

    rc, out, _ = adb.raw("shell", "pm", "path", pkg)
    installed = rc == 0 and out.strip().startswith("package:")
    if not installed:
        # 工作资料/多开容器里装的应用, user 0 的 pm 看不见 —— 用数据目录兜底判断
        try:
            installed = bool(prefs_dirs(serial, pkg))
        except SuError:
            installed = False
    lines.append("App 是否安装     : %s" % ("是" if installed else "否"))
    if not installed:
        lines.append("  → 包名不对或 App 没装, 用 --pkg 指定正确的包名")
        return False, lines

    try:
        root = adb.root_ok()
    except SuError as e:
        lines.append("root             : 不可用 (%s)" % e)
        return False, lines
    lines.append("root             : %s" % ("可用" if root else "不可用 (su 拒绝)"))
    if not root:
        lines.append("  → 需要 KernelSU/Magisk 授权 adb shell, 否则读不了别的 App 的私有目录")
        return False, lines

    try:
        dirs = prefs_dirs(serial, pkg)
    except SuError as e:
        dirs = []
        lines.append("数据目录         : 读取失败 (%s)" % e)
    lines.append("数据目录         : %s" % (", ".join(dirs) or "(不存在)"))
    if not dirs:
        lines.append("  → App 从未启动过, 先手动打开 App 到登录完成")
        return False, lines

    files = []
    for d in dirs:
        try:
            listing = adb.sudo("ls -1 %s/ 2>/dev/null" % d)
        except SuError:
            continue
        files = [f.strip() for f in listing.splitlines() if f.strip().endswith(".xml")]
        if files:
            lines.append("shared_prefs 目录: %s" % d)
            break
    lines.append("shared_prefs     : %d 个 xml" % len(files))
    if not files:
        lines.append("  → 目录在但没有 xml: App 可能被清过数据, 或被安全软件锁了目录")
        ok = False
    else:
        lines.append("  " + ", ".join(files))
        if "FlutterSharedPreferences.xml" not in files:
            lines.append("  → 没有 FlutterSharedPreferences.xml: 这不是 Flutter App, 或还没登录过")
            ok = False

    try:
        ports = listen_ports(serial, pkg)
        lines.append("隧道 LISTEN 端口 : %d 个" % len(ports))
        if not ports:
            lines.append("  → App 没在前台跑 (退后台隧道会被冻结)")
    except SuError as e:
        lines.append("隧道 LISTEN 端口 : 读取失败 (%s)" % e)

    return ok, lines


# ----------------------------------------------------------------- prefs 解析
def prefs_dirs(device, pkg):
    """列出可能存放 shared_prefs 的目录（按优先级）。

    普通安装:   /data/data/<pkg>/shared_prefs
    工作资料:   /data/user/12/<pkg>/shared_prefs        ← 源 App 装在分身空间
    多开容器:   /data/user/12/<宿主>/app_split/<pkg>/0/shared_prefs
                （如 top.bienvenido.saas.i18n 这类容器，prefs 埋在 app_split 里）
    """
    adb = Adb(device)
    dirs = []

    def add(p):
        p = p.rstrip("/")
        if p and p not in dirs:
            dirs.append(p)

    add("/data/data/%s/shared_prefs" % pkg)
    # 用 glob 而不是 find /data/user 递归: 后者在装了 200+ 应用的机器上要几十秒
    try:
        found = adb.sudo(
            "ls -d /data/user/*/%s /data/user/*/*/app_split/%s 2>/dev/null" % (pkg, pkg),
            timeout=20)
    except SuError:
        found = ""
    for line in found.splitlines():
        line = line.strip()
        if not line.startswith("/data/user/"):
            continue
        add(line + "/shared_prefs")
        add(line + "/0/shared_prefs")
    return dirs


def read_prefs(device, pkg):
    """读源 App 全部 shared_prefs xml, 合并成 {key: value} 与原始文本。"""
    adb = Adb(device)
    files, base = [], None
    for d in prefs_dirs(device, pkg):
        # -1 强制一行一个: adb exec-out 下 toybox ls 会按列排版, 直接把文件名拼坏
        try:
            listing = adb.sudo("ls -1 %s/ 2>/dev/null" % d)
        except SuError:
            continue
        got = [f.strip() for f in listing.splitlines() if f.strip().endswith(".xml")]
        if got:
            files, base = got, d
            break
    merged = {}
    raw_texts = []
    if not files:
        _print("[!] shared_prefs 目录里没有 xml。跑 `token_export.py diagnose` 看具体原因。")
        return merged, raw_texts
    _print("[*] prefs 目录: %s (%d 个 xml)" % (base, len(files)))
    for fn in files:
        path = "%s/%s" % (base, fn)
        try:
            content = adb.sudo("cat %s" % path, timeout=40)
        except SuError:
            continue
        if not content.strip():
            continue
        raw_texts.append(content)
        try:
            root = ET.fromstring(content)
        except ET.ParseError:
            continue
        for el in root.iter():
            name = el.get("name")
            if not name:
                continue
            if el.tag == "string":
                merged[name] = el.text or ""
            elif el.tag in ("boolean", "long", "int", "float"):
                merged[name] = el.get("value") or ""
            elif el.tag == "set":
                merged[name] = "\n".join((s.text or "") for s in el)
    return merged, raw_texts


def _collect_strings(obj, path, out):
    if isinstance(obj, str):
        out.append((path, obj))
    elif isinstance(obj, dict):
        for k, v in obj.items():
            _collect_strings(v, path + "." + str(k), out)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            _collect_strings(v, path + "[%d]" % i, out)


def parse_mainuser_data(merged):
    """解析 MAINUSER_DATA (JSON 字符串), 返回 (dict, [(path, str)])。"""
    raw = merged.get("flutter.MAINUSER_DATA", "")
    if not raw:
        return {}, []
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        try:
            data = json.loads(json.loads(raw))
        except Exception:
            return {}, [("(raw)", raw)]
    strings = []
    _collect_strings(data, "MAINUSER_DATA", strings)
    return data, strings


def find_jwe_candidates(strings):
    cands, seen = [], set()
    for path, s in strings:
        s = s.strip()
        if not JWE_RE.match(s):
            continue
        # 太短的点分串(版本号 "8.5.3")排除; JWE 实测 330 字符左右, 上限放宽到 2000
        if 120 <= len(s) <= 2000 and s not in seen:
            seen.add(s)
            cands.append((path, s))
    # 名字里带 token 的排前面
    cands.sort(key=lambda kv: 0 if "token" in kv[0].lower() else 1)
    return cands


def find_token_candidates(strings, known_junk=()):
    """从 JSON 里抠 32 位 hex。

    known_junk 用来丢掉那些「长得像 token 其实不是」的值 —— 典型是
    MAINUSER_DATA.mobile (服务端下发的手机号 md5), 它会被误当成会话令牌,
    让用户拿着一个永远不通的值去配。
    """
    junk = {j.strip().lower() for j in known_junk if isinstance(j, str)}
    strong, weak, seen = [], [], set()
    for path, s in strings:
        s = s.strip()
        if not TOKEN_RE.match(s) or s in seen:
            continue
        if s.lower() in junk:
            continue
        seen.add(s)
        low = path.lower()
        (strong if ("token" in low or "session" in low) else weak).append((path, s))
    return strong + weak


# ----------------------------------------------------------------- 端口 / 探测
def app_pid(device, pkg):
    """取源 App 的 pid（多开容器里进程名就是子包名）。"""
    adb = Adb(device)
    try:
        out = adb.sudo("ps -A -o PID,NAME 2>/dev/null | grep -w '%s'" % pkg)
    except SuError:
        return []
    pids = []
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 2 and cols[0].isdigit() and cols[1] == pkg:
            pids.append(int(cols[0]))
    return pids


def listen_ports(device, pkg=None):
    """LISTEN 端口。给了 pkg 就只取该进程自己的（设备上别的 App 也开着几十个口）。"""
    adb = Adb(device)
    if pkg:
        for pid in app_pid(device, pkg):
            try:
                out = adb.sudo("ss -tlnp 2>/dev/null | grep 'pid=%d,'" % pid)
            except SuError:
                continue
            ports = set()
            for line in out.splitlines():
                m = re.search(r":(\d+)\s", line)
                if m:
                    ports.add(int(m.group(1)))
            ports = sorted(p for p in ports if 1024 <= p <= 65535 and p not in SKIP_PORTS)
            if ports:
                return ports
    out = adb.sudo("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null")
    ports = set()
    for line in out.splitlines():
        cols = line.split()
        if len(cols) < 4 or cols[3] != "0A":      # 0A = TCP LISTEN
            continue
        try:
            ports.add(int(cols[1].rsplit(":", 1)[-1], 16))
        except ValueError:
            continue
    return sorted(p for p in ports if 1024 <= p <= 65535 and p not in SKIP_PORTS)


def _http_on_device(adb, port, method, path, body, headers, timeout=5):
    """在**设备侧**发一条裸 HTTP 请求（nc），返回原始响应字节串。

    为什么不走 adb forward：本机 env 里有 HTTP_PROXY、且部分 ROM（含 KernelSU
    下的 adbd）forward 到 127.0.0.1 的应用监听口会握手成功但永远不回数据——
    表现为"连上了、超时、零字节"。设备侧 nc 是同一 netns 里的直连，稳。
    """
    req = ("%s %s HTTP/1.0\r\n" % (method, path)).encode()
    for k, v in headers.items():
        req += ("%s: %s\r\n" % (k, v)).encode()
    req += b"Connection: close\r\n\r\n" + body
    b64 = base64.b64encode(req).decode()
    cmd = ("printf %%s '%s' | base64 -d | timeout %d toybox nc 127.0.0.1 %d"
           % (b64, timeout, port))
    rc, out, err = adb.raw("exec-out", "sh", "-c", cmd, timeout=timeout + 8)
    if "nc: not found" in (err or "") or "inaccessible" in (err or ""):
        return None
    return (out or "").encode()


def _probe_one(device, port, token, jwe, host_port, timeout=5):
    """打一条隧道端口, 返回 (ok, status, snippet)。"""
    adb = Adb(device)
    h = {
        "user-agent": "Dart/3.10 (dart:io)",
        "content-type": "application/json",
        "accept-encoding": "identity",
    }
    if jwe:
        h["authorization"] = "Bearer " + jwe
    suffix = ("?token=%s" % token) if token else ""
    last = (False, None, "")
    for path, body in PROBE_TARGETS:
        raw = _http_on_device(adb, port, "POST", path + suffix,
                              json.dumps(body).encode(), h, timeout)
        if raw is None:
            return (False, "NONC", "设备上没有 nc")
        if not raw:
            last = (False, "EMPTY", "端口无响应")
            continue
        if raw[:2] == b"\x1f\x8b":
            try:
                raw = gzip.decompress(raw)
            except OSError:
                pass
        txt = raw.decode("utf-8", "replace")
        m = re.match(r"HTTP/1\.[01] (\d{3})", txt)
        status = int(m.group(1)) if m else None
        payload = txt.split("\r\n\r\n", 1)[-1].split("\n\n", 1)[-1]
        code = None
        try:
            code = json.loads(payload).get("code")
        except (ValueError, AttributeError):
            pass
        if status == 200 and code == 0:
            return (True, status, payload[:80])
        last = (False, status, (payload or txt)[:80] or "code=%s" % code)
    return last


def probe_combos(device, ports, tokens, jwes, budget=400):
    """并发探测 (token × port), 找第一个真的通的组合。

    App 重启后端口号会变、上游分配也会重排, 所以必须实测而不是猜。
    这里限制总尝试数, 防止 token 候选一多就把设备打爆。
    """
    if not ports or not tokens:
        return None
    jwes = jwes or [""]
    best = {}
    lock = threading.Lock()
    stop = threading.Event()
    counter = {"n": 0}

    def work(host_port, port, token, jwe):
        if stop.is_set():
            return
        with lock:
            if counter["n"] >= budget:
                return
            counter["n"] += 1
        ok, st, snippet = _probe_one(device, port, token, jwe, host_port)
        if ok:
            with lock:
                if "hit" not in best:
                    best["hit"] = (port, token, jwe)
                    stop.set()

    threads = []
    hp = 31000
    for token in tokens:
        for jwe in jwes:
            for port in ports:
                if hp > 31999:
                    break
                t = threading.Thread(target=work, args=(hp, port, token, jwe), daemon=True)
                threads.append(t)
                t.start()
                hp += 1
    for t in threads:
        t.join(timeout=0.2)
    if stop.is_set():
        for t in threads:
            t.join(timeout=0.1)
    return best.get("hit")


# ----------------------------------------------------------------- loopback 抓包
CAPTURE_TOKEN_RE = re.compile(r"token=([0-9a-fA-F]{32})")
# tcpdump -n 的行首: "IP 127.0.0.1.36050 > 127.0.0.1.44079: Flags [P.]"
CAPTURE_FLOW_RE = re.compile(r"IP (\d+\.\d+\.\d+\.\d+)\.(\d+) > (\d+\.\d+\.\d+\.\d+)\.(\d+):")


def _parse_capture(text):
    """从 -A -n 的抓包文本里同时取出 (token, 目标端口, 次数)。

    会话 token 是拼在请求行上的（`POST /x?token=<32hex> HTTP/1.1`），
    它前面最近的一条 IP 行就是这个请求的目标端口 —— 也就是 tunnelPort。
    一次抓包拿到两样，不用再去盲探 46 个端口。
    """
    hits = {}
    dst = None
    for line in (text or "").splitlines():
        m = CAPTURE_FLOW_RE.search(line)
        if m:
            dst = int(m.group(4))
            continue
        for tok in CAPTURE_TOKEN_RE.findall(line):
            key = (tok, dst)
            hits[key] = hits.get(key, 0) + 1
    return sorted(((t, n, p) for (t, p), n in hits.items()), key=lambda x: -x[1])


def capture_loopback_tokens(device, seconds=8):
    """在设备 lo 上抓明文 HTTP, 取 ?token=<32hex> 以及它打到的端口。

    8.5.3 起 App 不把会话令牌落盘, 这是唯一不需要 hook 的取法。
    需要 root + tcpdump。
    """
    adb = Adb(device)
    ran = False
    for binpath in ("tcpdump", "/system/bin/tcpdump", "/data/local/tmp/tcpdump"):
        try:
            out = adb.sudo("timeout %d %s -i lo -s 0 -A -n tcp 2>/dev/null"
                           % (seconds, binpath), timeout=seconds + 20)
            ran = True
        except SuError:
            continue
        got = _parse_capture(out)
        if got:
            return got
    return [] if ran else None   # 能跑但没抓到 / 压根没有 tcpdump


def app_user(device, pkg):
    """源 App 跑在哪个 user 下（工作资料 = 12 之类），monkey/am 都得带上。"""
    adb = Adb(device)
    pids = app_pid(device, pkg)
    if not pids:
        return None
    try:
        out = adb.sudo("ps -o USER -p %d 2>/dev/null | tail -1" % pids[0]).strip()
    except SuError:
        return None
    m = re.match(r"u(\d+)_", out or "")
    return int(m.group(1)) if m else None


def poke_app(device, pkg):
    """把 App 拉到前台, 触发一次网络活动 (退后台时隧道是冻结的)。"""
    adb = Adb(device)
    user = app_user(device, pkg)
    opts = []
    if user:
        try:
            brief = adb.sudo("cmd package resolve-activity --brief %s 2>/dev/null | tail -1" % pkg)
            act = (brief or "").strip()
            if "/" in act:
                opts.append("am start --user %d -n %s" % (user, act))
        except SuError:
            pass
        opts.append("monkey -p %s -c android.intent.category.LAUNCHER 1" % pkg)
    else:
        opts.append("monkey -p %s -c android.intent.category.LAUNCHER 1" % pkg)
    for cmd in opts:
        if "monkey" in cmd:
            adb.raw("shell", "monkey", "-p", pkg,
                    "-c", "android.intent.category.LAUNCHER", "1", timeout=20)
        else:
            adb.raw("shell", *cmd.split(), timeout=20)


def nudge(device, times=2):
    """制造一点网络活动：下拉刷新。

    App 前台空闲时几乎不发请求，抓 8 秒经常一个 `?token=` 都没有；
    而 8.5.3+ 之后**不需要**为了拿 token 去冷启动（token 跟着会话走，重启反而换新的），
    只要让它真的发一次业务请求就行。
    """
    adb = Adb(device)
    for _ in range(times):
        adb.raw("shell", "input", "swipe", "540", "800", "540", "1800", "250", timeout=15)
        time.sleep(2.5)


def tokens_from_capture(device, pkg, seconds=8, trigger=True, rounds=2):
    """抓 loopback 上的 ?token=，顺带把 tunnelPort 一起带出来。

    顺序：静默抓（同时下拉刷新制造流量）→ 仍抓不到才冷启动一次再抓。
    """
    if trigger:
        th = threading.Thread(target=nudge, args=(device,), daemon=True)
        th.start()
    for i in range(max(1, rounds)):
        _print("[*] 在设备 loopback 上抓 %ds, 找 ?token=…（第 %d 轮）" % (seconds, i + 1))
        got = capture_loopback_tokens(device, seconds)
        if got is None:
            _print("[!] 设备上没有 tcpdump, 抓包这条路走不通")
            return []
        if got:
            if trigger:
                th.join(timeout=3)
            return got
    if trigger:
        th.join(timeout=3)

    cold = max(seconds, 20)
    _print("[*] 没抓到。冷启动一次 App 再抓 %ds（App 会被重启）…" % cold)
    box = {}

    def _cap():
        box["got"] = capture_loopback_tokens(device, cold) or []

    th = threading.Thread(target=_cap, daemon=True)
    th.start()
    time.sleep(2.5)                     # 等 tcpdump 就位
    user = app_user(device, pkg)
    if user:
        Adb(device).raw("shell", "am", "force-stop", "--user", str(user), pkg, timeout=15)
    else:
        Adb(device).raw("shell", "am", "force-stop", pkg, timeout=15)
    poke_app(device, pkg)
    th.join(cold + 15)
    return box.get("got") or []


def tokens_from_session_cache(jwe, mainuser=None):
    """项目里的 .session.json 只有在「确认是同一个账号」时才能用。

    这个缓存是上一次运行写下的, 换号登录后就完全过期了 —— 里面躺着别的账号的
    32hex 会话令牌, 直接拿来用只会得到 403。
    """
    if not os.path.exists(SESSION_CACHE):
        return []
    try:
        with open(SESSION_CACHE, encoding="utf-8") as f:
            sess = json.load(f)
    except (OSError, ValueError):
        return []
    tok = (sess.get("token") or "").strip()
    if not TOKEN_RE.match(tok):
        return []

    cached_jwe = (sess.get("jwe") or "").strip()
    if cached_jwe and jwe and cached_jwe != jwe:
        _print("[!] .session.json 的 JWE 与设备上不一致(不同账号), 不采信它的 token")
        return []

    # 缓存里有登录资料时, 用 uid 再校一次
    cached_uid = None
    try:
        cached_uid = (sess.get("login") or {}).get("data", {}).get("id")
    except AttributeError:
        cached_uid = None
    cur_uid = (mainuser or {}).get("id")
    if cached_uid and cur_uid and str(cached_uid) != str(cur_uid):
        _print("[!] .session.json 属于 uid=%s, 设备当前登录的是 uid=%s, 不采信它的 token"
               % (cached_uid, cur_uid))
        return []
    return [(tok, 1)]


def _pid1(device, pkg):
    """源 App 主进程 pid（多开容器下可能有多个, 取第一个做会话指纹）。"""
    pids = app_pid(device, pkg)
    return pids[0] if pids else None


def tokens_from_own_cache(jwe, mainuser, pid=None):
    """本工具上一次成功导出时存下的 token (带 jwe + uid 指纹)。

    抓包要冷启动一次 App, 挺烦的; 只要还是同一个账号同一个会话, 直接复用就行,
    反正后面还会实测一遍。
    """
    if not os.path.exists(TOKEN_CACHE):
        return []
    try:
        with open(TOKEN_CACHE, encoding="utf-8") as f:
            c = json.load(f)
    except (OSError, ValueError):
        return []
    tok = (c.get("token") or "").strip()
    if not TOKEN_RE.match(tok):
        return []
    if (c.get("jwe") or "") != (jwe or ""):
        return []
    cur_uid = (mainuser or {}).get("id")
    if c.get("uid") and cur_uid and str(c["uid"]) != str(cur_uid):
        return []
    # token 是"每个进程一份"的: 源 App 冷启动(pid 变)就换了。缓存里的 pid 对不上
    # 说明它重启过, 这串已经失效 —— 采信它只会让弹幕 join 回 403 bad token。
    cached_pid = c.get("pid")
    if cached_pid and pid and str(cached_pid) != str(pid):
        _print("[!] 源 App 已重启 (pid %s → %s), 缓存 token 失效, 重新抓包" % (cached_pid, pid))
        return []
    return [(tok, 1)]


def save_token_cache(token, jwe, mainuser, pid=None):
    try:
        with open(TOKEN_CACHE, "w", encoding="utf-8") as f:
            json.dump({"token": token, "jwe": jwe or "",
                       "uid": (mainuser or {}).get("id"), "pid": pid,
                       # uid / channel 一律从 MAINUSER_DATA 取真值，别再猜默认值：
                       # 它决定「导入的是哪个号」，也决定部分接口的兜底参数
                       "channel": (mainuser or {}).get("channel_id"),
                       "nickname": (mainuser or {}).get("nickname"),
                       "saved_at": datetime.now().isoformat(timespec="seconds")},
                      f, ensure_ascii=False, indent=1)
    except OSError:
        pass


# ----------------------------------------------------------------- 输出
def render_config(token, jwe, port, uid=None, channel=None, nickname=None):
    bar = "═" * 52
    who = ("%s（uid %s，渠道 %s）" % (nickname or "?", uid, channel or "?")) if uid else "(未解析到)"
    lines = [
        bar,
        " DC Live 直播源配置（源 App 一键导出）",
        " 导出时间: %s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        " 当前账号: %s" % who,
        bar,
        "",
        "能力 → 凭证对照（别只填 JWE，弹幕会 403）：",
        "  看列表 get_list_by_ids  不需要任何凭证",
        "  关注列表 my_follow      只要 JWE",
        "  关注/取关 relationship   ❌ 明文层实测不存在（docs/关注写接口-实测结论-2026-10-31.md）",
        "                           关注只落本机，别指望这组凭证能让服务端记住",
        "  实时弹幕 /websock/join  会话 Token + JWE，缺一个就 403",
        "",
        "★ 一键导入：设置 → 直播源与隧道 → 「导入配置（从导出工具粘贴）」",
        "  把下面这段 JSON 整段粘进去，appToken / JWE / userId / 渠道 / 端口一次填好：",
        "",
        render_json(token, jwe, port, uid, channel),
        "",
        "── 手工填的话，逐项对照 ──",
        "【会话 Token】 (= appToken，弹幕必填) → 设置 → 弹幕 → 会话 token",
        token or "(未探测到。源 App 在前台且下拉刷新一次再跑本工具)",
        "",
        "【JWE】 (关注 + 弹幕都认) → 设置 → 直播源与隧道 → JWE",
        jwe or "(未探测到，见下方候选)",
        "",
        "【userId】 (关注写接口必填) → 设置 → 关注 → 我的 userId",
        str(uid) if uid else "(未解析到 MAINUSER_DATA.id)",
        "",
        "【渠道 channelId】 → 设置 → 关注 → 渠道",
        channel or "(未解析到 MAINUSER_DATA.channel_id)",
        "",
        "【隧道端口】 可留空：每次源 App 启动端口都会变，App 自己扫",
        str(port) if port else "(留空，让 App 自动扫 /proc/net/tcp)",
        "",
        "提醒: 会话 token 是**每次冷启动都会换**的短命凭证，弹幕 403 就先重跑本工具。",
        "      源 App 必须同机、后台运行：监听端口是它进程里起的，它一关就没数据。",
        bar,
    ]
    return "\n".join(lines)


def render_json(token, jwe, port, uid=None, channel=None):
    out = {"appToken": token or "", "jwe": jwe or "", "tunnelPort": port or 0}
    # userId / channel 只在解析出来时才写 —— 空值写进去反而会把 App 里的好值覆盖掉
    if uid:
        out["userId"] = uid
    if channel:
        out["channel"] = channel
    return json.dumps(out, ensure_ascii=False, indent=2)


# ----------------------------------------------------------------- 子命令
def cmd_dump(device, pkg):
    merged, _ = read_prefs(device, pkg)
    if not merged:
        return 1
    data, strings = parse_mainuser_data(merged)
    jwes = find_jwe_candidates(strings)
    tokens = find_token_candidates(strings, known_junk=[data.get("mobile", "")])
    _print("[*] 源 App 包名:", pkg)
    _print("[*] MAINUSER_DATA 解析: %d 个字符串值" % len(strings))
    _print("")
    _print("── JWE / 长期密钥候选 (%d) ──" % len(jwes))
    if jwes:
        for path, s in jwes:
            _print("  [%s]  %s…%s  (len=%d)" % (path, s[:24], s[-12:], len(s)))
    else:
        _print("  (无。确认 App 已登录过, MAINUSER_DATA 里有 token 字段)")
    _print("")
    _print("── 32 位 hex 会话令牌候选 (%d) ──" % len(tokens))
    if tokens:
        for path, s in tokens:
            _print("  [%s]  %s" % (path, s))
    else:
        _print("  (磁盘上没有。8.5.3 起会话令牌只存在内存, 用 `capture` 或直接跑 export)")
    return 0


def cmd_ports(device, pkg):
    ports = listen_ports(device, pkg)
    _print("隧道 LISTEN 端口 (%d):" % len(ports))
    _print("  " + ", ".join(str(p) for p in ports))
    return 0


def cmd_capture(device, pkg):
    got = tokens_from_capture(device, pkg, 8)
    if not got:
        _print("[!] 没抓到。确认 App 已登录且网络活动正常。")
        return 1
    _print("[✓] 抓到 %d 个候选:" % len(got))
    for tok, n, port in got:
        _print("  %s  (出现 %d 次, 端口 %s)" % (tok, n, port or "?"))
    return 0


def cmd_diagnose(device, pkg):
    ok, lines = diagnose(pkg, device)
    _print("── 环境自检 (%s) ──" % pkg)
    for l in lines:
        _print("  " + l)
    _print("")
    _print("结论:", "可以正常读取" if ok else "存在上面标 → 的阻塞项")
    return 0 if ok else 1


def cmd_export(device, pkg, no_probe, out_file, no_capture):
    adb = Adb(device)

    # 0) 先把设备/root 定下来, 免得后面每个调用各失败一次还互相盖住原因
    if device is None:
        device = pick_device(adb)
        if device is None:
            _print("[!] 没有在线设备。先 `adb devices` 确认设备已连接并已授权。")
            return 1
    try:
        if not adb.root_ok():
            _print("[!] su 不可用 —— 读不了 /data/data/<pkg>。")
            _print("    跑 `token_export.py diagnose` 看完整自检。")
            return 1
    except SuError as e:
        _print("[!] %s" % e)
        return 1

    # 1) prefs: 拿 JWE (长期密钥), 顺便看看有没有落盘的 token
    merged, _ = read_prefs(device, pkg)
    if not merged:
        _print("[!] 没读到 shared_prefs。跑 `token_export.py diagnose` 看具体原因。")
        return 1
    data, strings = parse_mainuser_data(merged)
    jwes = find_jwe_candidates(strings)
    tokens = find_token_candidates(strings, known_junk=[data.get("mobile", "")])
    jwe = jwes[0][1] if jwes else None
    # 账号身份：原本是给「关注写接口 /relationship/add」准备的（userId + channelId
    # 必须与 JWE 同属一个账号）。2026-10-31 实测那条路由在明文层根本不存在，
    # 这组字段现在只用于：① 让用户在设置页看清导入的是哪个号 ② 弹幕等接口的兜底。
    uid = data.get("id")
    channel = data.get("channel_id")
    if uid:
        _print("[✓] 账号: %s (uid=%s, 渠道=%s)"
               % (data.get("nickname") or "?", uid, channel or "?"))

    if jwe:
        _print("[✓] JWE: %s…%s (len=%d, 来自 %s)"
               % (jwe[:16], jwe[-8:], len(jwe), jwes[0][0]))
    else:
        _print("[!] MAINUSER_DATA 里没抠到 JWE —— App 可能没登录完。")

    token_cands = [(s, 1) for _, s in tokens]
    if token_cands:
        _print("[✓] prefs 里有 %d 个 32hex 候选" % len(token_cands))
    else:
        _print("[*] prefs 里没有会话令牌 (8.5.3 起不再落盘)")
        token_cands = tokens_from_session_cache(jwe, data)
        if token_cands:
            _print("[✓] 从 .session.json 取到 1 个候选")
        else:
            token_cands = tokens_from_own_cache(jwe, data, _pid1(device, pkg))
            if token_cands:
                _print("[✓] 复用上次导出缓存 .apptoken.json（稍后实测校验）")

    # 2) 还没有 token: 抓 loopback（顺带把 tunnelPort 读出来）
    port_hint = None
    if not token_cands and not no_capture:
        got = tokens_from_capture(device, pkg, 8)
        if got:
            token_cands = [(t, n) for t, n, _p in got]
            port_hint = got[0][2]
            _print("[✓] 抓包取到 %d 个候选: %s (端口 %s)"
                   % (len(got), ", ".join(t for t, _, _p in got), port_hint or "?"))
    elif not token_cands and no_capture:
        _print("[!] --no-capture 且没有落盘 token, 无法自动取会话令牌")

    if not jwe and not token_cands:
        _print("[!] 什么凭证都没拿到, 放弃。")
        return 1

    # 3) 在线探测: 哪个端口 + 哪个 token 真的能用
    token, port = None, port_hint
    if port:
        _print("[✓] 隧道端口: %d（抓包里这条请求打的就是它，不用再盲探）" % port)
    if not no_probe and not port:
        ports = listen_ports(device, pkg)
        if not ports:
            _print("[!] 没扫到隧道端口。确认源 App 正在前台运行(退后台隧道会冻结)。")
        else:
            _print("[*] 扫到 %d 个端口, 实测 %d 个 token 候选…"
                   % (len(ports), len(token_cands)))
            hit = probe_combos(device, ports, [t for t, _ in token_cands], [jwe] if jwe else [])
            if hit:
                port, token, _ = hit
                _print("[✓] 命中: 端口 %d  token=%s…" % (port, token[:8]))
            else:
                _print("[!] 没探测到能通的组合 (接口改版 / token 过期 / 端口没开)")

    # 4) 兜底: 没命中就用第一候选, 让用户自己试
    if token is None and token_cands:
        token = token_cands[0][0]
    if jwe is None and jwes:
        jwe = jwes[0][1]

    # 5) 存一份, 下次同账号同 JWE 就不用再冷启动抓包了
    if token and jwe:
        save_token_cache(token, jwe, data, _pid1(device, pkg))

    _print("")
    text = render_config(token, jwe, port, uid, channel, data.get("nickname"))
    _print(text)
    _print("")
    _print("── 机器可读 JSON（DC Live 设置页「导入配置」直接粘这段）──")
    _print(render_json(token, jwe, port, uid, channel))
    if out_file:
        with open(out_file, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        _print("")
        _print("[✓] 已写入:", out_file)
    return 0


def cmd_from_file(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()
    merged = {}
    try:
        root = ET.fromstring(content)
    except ET.ParseError:
        _print("[!] XML 解析失败:", path)
        return 1
    for el in root.iter():
        name = el.get("name")
        if not name:
            continue
        merged[name] = el.text if el.tag == "string" else (el.get("value") or "")
    data, strings = parse_mainuser_data(merged)
    jwes = find_jwe_candidates(strings)
    tokens = find_token_candidates(strings, known_junk=[data.get("mobile", "")])
    _print("[*] 离线解析 %s" % path)
    _print("[*] MAINUSER_DATA 解析: %d 个字符串值" % len(strings))
    _print("── JWE 候选 (%d) ──" % len(jwes))
    for p, s in jwes:
        _print("  [%s] len=%d  %s…%s" % (p, len(s), s[:20], s[-10:]))
    _print("── 32hex 会话令牌候选 (%d) ──" % len(tokens))
    for p, s in tokens:
        _print("  [%s]  %s" % (p, s))
    _print("")
    _print(render_config(tokens[0][1] if tokens else None,
                         jwes[0][1] if jwes else None, None,
                         data.get("id"), data.get("channel_id"),
                         data.get("nickname")))
    return 0


# ----------------------------------------------------------------- 入口
def main():
    args = sys.argv[1:]
    cmd = "export"
    rest = args
    if args and not args[0].startswith("--"):
        cmd = args[0]
        rest = args[1:]

    device = None
    pkg = DEFAULT_PKG
    no_probe = False
    no_capture = False
    out_file = None
    positional = []

    i = 0
    while i < len(rest):
        a = rest[i]
        if a == "--device":
            device = rest[i + 1]; i += 2
        elif a == "--pkg":
            pkg = rest[i + 1]; i += 2
        elif a == "--no-probe":
            no_probe = True; i += 1
        elif a == "--no-capture":
            no_capture = True; i += 1
        elif a == "--out":
            out_file = rest[i + 1]; i += 2
        else:
            positional.append(a); i += 1

    if cmd == "dump":
        return cmd_dump(device, pkg)
    if cmd == "ports":
        return cmd_ports(device, pkg)
    if cmd == "capture":
        return cmd_capture(device, pkg)
    if cmd == "diagnose":
        return cmd_diagnose(device, pkg)
    if cmd == "from-file":
        if not positional:
            _print("[!] from-file 需要一个 xml 路径")
            return 2
        return cmd_from_file(positional[0])
    if cmd == "export":
        return cmd_export(device, pkg, no_probe, out_file, no_capture)
    _print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
