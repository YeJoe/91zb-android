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

    def root_ok(self, timeout=15):
        rc, out, err = self.raw("exec-out", "su", "-c", "id", timeout=timeout)
        if rc == 124:
            raise SuError("su 调用超时 (%ds) —— 大概率是 root 授权弹窗卡住了, "
                          "点亮屏幕后在设备上允许本机的 root 请求" % timeout)
        return rc == 0 and "uid=0" in out

    def sudo(self, cmd, timeout=25):
        """以 root 执行一条 shell 命令。用 exec-out 避免 pty 把 \\n 改成 \\r\\n,
        二进制/大文件读出来才是干净的。"""
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
    lines.append("App 是否安装     : %s" % ("是 " + out.strip() if installed else "否"))
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
        d = adb.sudo("ls -d /data/data/%s 2>/dev/null" % pkg).strip()
    except SuError as e:
        d = ""
        lines.append("数据目录         : 读取失败 (%s)" % e)
    lines.append("数据目录         : %s" % (d or "(不存在)"))
    if not d:
        lines.append("  → App 从未启动过, 先手动打开 App 到登录完成")
        return False, lines

    try:
        listing = adb.sudo("ls -1 /data/data/%s/shared_prefs/ 2>/dev/null" % pkg)
    except SuError:
        listing = ""
    files = [f.strip() for f in listing.splitlines() if f.strip().endswith(".xml")]
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
        ports = listen_ports(serial)
        lines.append("隧道 LISTEN 端口 : %d 个" % len(ports))
        if not ports:
            lines.append("  → App 没在前台跑 (退后台隧道会被冻结)")
    except SuError as e:
        lines.append("隧道 LISTEN 端口 : 读取失败 (%s)" % e)

    return ok, lines


# ----------------------------------------------------------------- prefs 解析
def read_prefs(device, pkg):
    """读源 App 全部 shared_prefs xml, 合并成 {key: value} 与原始文本。"""
    adb = Adb(device)
    # -1 强制一行一个: adb exec-out 下 toybox ls 会按列排版, 直接把文件名拼坏
    listing = adb.sudo("ls -1 /data/data/%s/shared_prefs/ 2>/dev/null" % pkg)
    files = [f.strip() for f in listing.splitlines() if f.strip().endswith(".xml")]
    merged = {}
    raw_texts = []
    if not files:
        _print("[!] shared_prefs 目录里没有 xml。跑 `token_export.py diagnose` 看具体原因。")
        return merged, raw_texts
    for fn in files:
        path = "/data/data/%s/shared_prefs/%s" % (pkg, fn)
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
def listen_ports(device):
    adb = Adb(device)
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


def _probe_one(device, port, token, jwe, host_port, timeout=5):
    """打一条隧道端口, 返回 (ok, status, snippet)。

    用独立的 host 端口做转发, 避免和「设备端口号相同」时撞上 PC 上已占用的端口。
    """
    import http.client
    adb = Adb(device)
    adb.raw("forward", "tcp:%d" % host_port, "tcp:%d" % port, timeout=10)
    try:
        h = {
            "user-agent": "Dart/3.10 (dart:io)",
            "content-type": "application/json",
            "accept-encoding": "gzip",
        }
        if jwe:
            h["authorization"] = "Bearer " + jwe
        suffix = ("?token=%s" % token) if token else ""
        last = (False, None, "")
        for path, body in PROBE_TARGETS:
            try:
                c = http.client.HTTPConnection("127.0.0.1", host_port, timeout=timeout)
                c.request("POST", path + suffix, body=json.dumps(body).encode(), headers=h)
                r = c.getresponse()
                raw = r.read()
                c.close()
            except Exception as e:
                return (False, "ERR", "%s: %s" % (type(e).__name__, e))
            if raw[:2] == b"\x1f\x8b":
                try:
                    raw = gzip.decompress(raw)
                except OSError:
                    pass
            txt = raw.decode("utf-8", "replace")
            code = None
            try:
                code = json.loads(txt).get("code")
            except (ValueError, AttributeError):
                pass
            if r.status == 200 and code == 0:
                return (True, r.status, txt[:80])
            last = (False, r.status, txt[:80] or "code=%s" % code)
        return last
    finally:
        adb.raw("forward", "--remove", "tcp:%d" % host_port, timeout=10)


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


def capture_loopback_tokens(device, seconds=8):
    """在设备 lo 上抓明文 HTTP, 从 ?token=<32hex> 里取会话令牌。

    8.5.3 起 App 不把会话令牌落盘, 这是唯一不需要 hook 的取法。
    需要 root + tcpdump。
    """
    adb = Adb(device)
    for binpath in ("tcpdump", "/system/bin/tcpdump", "/data/local/tmp/tcpdump"):
        try:
            rc, out, err = adb.raw(
                "exec-out", "su", "-c",
                "timeout %d %s -i lo -s 0 -A -n tcp 2>/dev/null" % (seconds, binpath),
                timeout=seconds + 10,
            )
        except Exception:
            continue
        if rc == 127 or "not found" in (err or ""):
            continue
        found = CAPTURE_TOKEN_RE.findall(out or "")
        if found:
            # 出现次数最多的更可能是当前会话
            uniq = {}
            for t in found:
                uniq[t] = uniq.get(t, 0) + 1
            return sorted(uniq.items(), key=lambda kv: -kv[1])
        if rc == 0:
            return []          # tcpdump 能跑, 只是这段时间没抓到
    return None                # 压根没有 tcpdump


def poke_app(device, pkg):
    """把 App 拉到前台, 触发一次网络活动 (退后台时隧道是冻结的)。"""
    Adb(device).raw("shell", "monkey", "-p", pkg,
                    "-c", "android.intent.category.LAUNCHER", "1", timeout=20)


def tokens_from_capture(device, pkg, seconds=8):
    """先静默抓一轮; 抓不到就冷启动一次 App 再抓。

    App 已在前台空闲时几乎不发请求, 单纯 monkey 拉起是不会产生流量的,
    必须 force-stop 再启动, 让它走一遍 init/login, 才会把 ?token= 打出来。
    """
    _print("[*] 在设备 loopback 上抓 %ds, 找 ?token=…" % seconds)
    got = capture_loopback_tokens(device, seconds)
    if got is None:
        _print("[!] 设备上没有 tcpdump, 抓包这条路走不通")
        return []
    if got:
        return got

    cold = max(seconds, 20)
    _print("[*] 没抓到。冷启动一次 App 再抓 %ds（App 会被重启）…" % cold)
    box = {}

    def _cap():
        box["got"] = capture_loopback_tokens(device, cold) or []

    th = threading.Thread(target=_cap, daemon=True)
    th.start()
    time.sleep(2.5)                     # 等 tcpdump 就位
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


def tokens_from_own_cache(jwe, mainuser):
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
    return [(tok, 1)]


def save_token_cache(token, jwe, mainuser):
    try:
        with open(TOKEN_CACHE, "w", encoding="utf-8") as f:
            json.dump({"token": token, "jwe": jwe or "",
                       "uid": (mainuser or {}).get("id"),
                       "saved_at": datetime.now().isoformat(timespec="seconds")},
                      f, ensure_ascii=False, indent=1)
    except OSError:
        pass


# ----------------------------------------------------------------- 输出
def render_config(token, jwe, port):
    bar = "═" * 52
    lines = [
        bar,
        " DC Live 直播源配置（源 App 一键导出）",
        " 导出时间: %s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        bar,
        "",
        "【JWE】  → 只有这一段要填，粘到设置页「JWE」框",
        jwe or "(未探测到，见下方候选)",
        "",
        "【隧道端口】  → 可留空：每次源 App 启动端口都会变，App 自己扫",
        str(port) if port else "(留空，让 App 自动扫 /proc/net/tcp)",
        "",
        "【会话 Token】  → 不用填。实测 /room/get_list_by_ids 零凭证即可返回，",
        "                 这串只在个别接口上可能有用，留着备查：",
        token or "(未探测到，不影响使用)",
        "",
        "用法: 打开 DC Live → 设置 → 直播源 → 把 JWE 粘进去 → 点「测试连接并刷新」。",
        "      源 App 必须**同机、后台运行**：监听端口是它进程里起的，它一关就没数据。",
        bar,
    ]
    return "\n".join(lines)


def render_json(token, jwe, port):
    return json.dumps(
        {"appToken": token or "", "jwe": jwe or "", "tunnelPort": port or 0},
        ensure_ascii=False, indent=2,
    )


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
    ports = listen_ports(device)
    _print("隧道 LISTEN 端口 (%d):" % len(ports))
    _print("  " + ", ".join(str(p) for p in ports))
    return 0


def cmd_capture(device, pkg):
    got = tokens_from_capture(device, pkg, 8)
    if not got:
        _print("[!] 没抓到。确认 App 已登录且网络活动正常。")
        return 1
    _print("[✓] 抓到 %d 个候选:" % len(got))
    for tok, n in got:
        _print("  %s  (出现 %d 次)" % (tok, n))
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
            token_cands = tokens_from_own_cache(jwe, data)
            if token_cands:
                _print("[✓] 复用上次导出缓存 .apptoken.json（稍后实测校验）")

    # 2) 还在没有 token: 抓 loopback
    if not token_cands and not no_capture:
        got = tokens_from_capture(device, pkg, 8)
        if got:
            token_cands = [(t, n) for t, n in got]
            _print("[✓] 抓包取到 %d 个候选: %s" % (len(got), ", ".join(t for t, _ in got)))
    elif not token_cands and no_capture:
        _print("[!] --no-capture 且没有落盘 token, 无法自动取会话令牌")

    if not jwe and not token_cands:
        _print("[!] 什么凭证都没拿到, 放弃。")
        return 1

    # 3) 在线探测: 哪个端口 + 哪个 token 真的能用
    token, port = None, None
    if not no_probe:
        ports = listen_ports(device)
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
        save_token_cache(token, jwe, data)

    _print("")
    text = render_config(token, jwe, port)
    _print(text)
    _print("")
    _print("── 机器可读 JSON（DC Live 设置页「导入配置」直接粘这段）──")
    _print(render_json(token, jwe, port))
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
                         jwes[0][1] if jwes else None, None))
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
