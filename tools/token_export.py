#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""源 App 凭证「一键导出」工具
=================================

DC Live 的「实时直播源」直连源 App 隧道端口, 需要两个凭证 + 一个端口:

  - appToken : 32 位 hex 会话令牌, 拼在隧道 URL 的 ?token=
  - jwe      : Authorization: Bearer 的值, 来自源 App 的 MAINUSER_DATA.token (长期密钥)
  - tunnelPort: 能响应 /room/list 的隧道端口 (可留空让 App 自动探测)

这两个凭证都躺在源 App 的 Flutter SharedPreferences 里:
  /data/data/<pkg>/shared_prefs/FlutterSharedPreferences.xml
  键 flutter.MAINUSER_DATA 是一段 JSON, 其中 token 字段即 JWE。
  32 位 hex 会话令牌散落在整个 prefs (不同版本字段名不一), 本工具把所有
  "长得像" 的候选都抠出来, 再实际打隧道端口验证, 找出能用的那一对。

用法:
  python tools/token_export.py                 # 默认 = export: 读设备 + 探测 + 出可粘贴配置
  python tools/token_export.py dump            # 只列候选 token/jwe/端口 (不在线探测)
  python tools/token_export.py ports           # 只列设备上的隧道 LISTEN 端口
  python tools/token_export.py from-file X.xml # 离线解析本地 prefs 快照 (调试, 不碰设备)

选项:
  --device SERIAL   指定 adb 设备 (默认: 第一个 online 设备)
  --pkg PKG         源 App 包名 (默认 com.l95c2450d7.f68bb4e2d5)
  --no-probe       导出但不做在线探测 (只给候选, 适合设备不在手边)
  --out FILE        把可粘贴配置同时写入文件 (默认只打印到 stdout)
"""
import json
import os
import re
import subprocess
import sys
import threading
import xml.etree.ElementTree as ET
from datetime import datetime

# ----------------------------------------------------------------- 默认参数
DEFAULT_PKG = "com.l95c2450d7.f68bb4e2d5"

# 32 位会话令牌: 严格 32 个十六进制字符, 不多不少。
TOKEN_RE = re.compile(r"^[0-9a-fA-F]{32}$")
# JWE / 长期密钥: 点分 (至少两段)、每段都是 base64url、长度明显大于普通字段。
# 例: header.iv.ciphertext.tag 或 header..iv.ciphertext(无加密密钥的变体)。
JWE_RE = re.compile(r"^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+$")


def _print(*a, **k):
    # 统一用 UTF-8 输出, 避免 Windows 控制台乱码
    try:
        print(*a, **k)
    except UnicodeEncodeError:
        enc = sys.stdout.encoding or "utf-8"
        txt = " ".join(str(x) for x in a)
        print(txt.encode(enc, "replace").decode(enc), **k)


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
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            return p.returncode, p.stdout, p.stderr
        except FileNotFoundError:
            return 127, "", "adb 不在 PATH 里"
        except subprocess.TimeoutExpired:
            return 124, "", "adb 命令超时"

    def shell_su(self, cmd, timeout=20):
        """以 root 执行一条 shell 命令, 返回 stdout。引号不易被 adb 拆坏,
        所以这里只把整段交给 su -c, 不做复杂管道。"""
        rc, out, err = self.raw("shell", "su", "-c", cmd, timeout=timeout)
        if rc != 0:
            # 有些设备没有 su, 退化到普通 shell (读不了别的 app 的 prefs, 但至少试试)
            rc, out, err = self.raw("shell", cmd, timeout=timeout)
        return out


def pick_device(adb):
    rc, out, err = adb.raw("devices")
    if rc != 0:
        _print("[!] 执行 adb devices 失败:", err.strip())
        return None
    online = []
    for line in out.splitlines():
        line = line.strip()
        if line.endswith("\tdevice"):
            serial = line.split("\t")[0]
            online.append(serial)
    if not online:
        return None
    return online[0]


# ----------------------------------------------------------------- prefs 解析
def read_prefs(device, pkg):
    """读源 App 全部 shared_prefs xml, 合并成 {key: value} 与原始文本。"""
    adb = Adb(device)
    # 先列目录, 找所有 .xml
    listing = adb.shell_su(
        "ls /data/data/%s/shared_prefs/ 2>/dev/null" % pkg
    )
    files = [f.strip() for f in listing.splitlines() if f.strip().endswith(".xml")]
    merged = {}
    raw_texts = []
    if not files:
        _print("[!] 在 %s 下没找到 shared_prefs (设备没 root? 或 App 没装?)" % pkg)
        return merged, raw_texts
    for fn in files:
        path = "/data/data/%s/shared_prefs/%s" % (pkg, fn)
        content = adb.shell_su("cat %s 2>/dev/null" % path)
        if not content.strip():
            continue
        raw_texts.append(content)
        try:
            root = ET.fromstring(content)
        except ET.ParseError:
            continue
        for el in root.iter("string"):
            name = el.get("name")
            if name:
                merged[name] = el.text or ""
    return merged, raw_texts


def _collect_strings(obj, path, out):
    """递归收集 JSON 里所有字符串值, 记下路径, 方便用户判断哪个是 token。"""
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
        # 有的版本包了一层再 encode, 试一次
        try:
            data = json.loads(json.loads(raw))
        except Exception:
            return {}, [("(raw)", raw)]
    strings = []
    _collect_strings(data, "MAINUSER_DATA", strings)
    return data, strings


def find_jwe_candidates(strings):
    cands = []
    seen = set()
    for path, s in strings:
        s = s.strip()
        if not JWE_RE.match(s):
            continue
        # JWE 一般 200~600 字符; 太短的点分串(如版本号)排除
        if 120 <= len(s) <= 1200 and s not in seen:
            seen.add(s)
            cands.append((path, s))
    return cands


def find_token_candidates(strings):
    cands = []
    seen = set()
    for path, s in strings:
        s = s.strip()
        if TOKEN_RE.match(s) and s not in seen:
            seen.add(s)
            cands.append((path, s))
    return cands


# ----------------------------------------------------------------- 端口探测
def listen_ports(device):
    adb = Adb(device)
    out = adb.shell_su("cat /proc/net/tcp 2>/dev/null; cat /proc/net/tcp6 2>/dev/null")
    ports = set()
    for line in out.splitlines():
        if " 0A " not in line:          # 0A = TCP LISTEN
            continue
        cols = line.split()
        if len(cols) < 2:
            continue
        hexport = cols[1].rsplit(":", 1)[-1]
        try:
            ports.add(int(hexport, 16))
        except ValueError:
            pass
    return sorted(p for p in ports if 1024 <= p <= 65535)


def _forward(device, port):
    Adb(device).raw("forward", "tcp:%d" % port, "tcp:%d" % port, timeout=10)


def _unforward(device, port):
    Adb(device).raw("forward", "--remove", "tcp:%d" % port, timeout=10)


def _probe_one(device, port, token, jwe, timeout=4):
    """打一个端口的 /room/list, 返回 (code_or_None, snippet)。"""
    import http.client
    _forward(device, port)
    try:
        h = {
            "user-agent": "Dart/3.10 (dart:io)",
            "content-type": "application/json",
            "accept-encoding": "gzip",
        }
        if jwe:
            h["authorization"] = "Bearer " + jwe
        body = json.dumps({"pageIndex": 1, "menuId": 0}).encode()
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        c.request("POST", "/room/list?token=%s" % token, body=body, headers=h)
        r = c.getresponse()
        raw = r.read(4096)
        c.close()
        try:
            obj = json.loads(raw.decode("utf-8", "replace"))
            code = obj.get("code")
            return code, raw.decode("utf-8", "replace")[:80]
        except (ValueError, json.JSONDecodeError):
            return None, raw.decode("utf-8", "replace")[:80]
    except Exception as e:
        return "ERR", "%s: %s" % (type(e).__name__, e)
    finally:
        _unforward(device, port)


def probe_combos(device, ports, tokens, jwes, max_workers=12):
    """并发探测 (token × port), 找到第一个返回 code==0 的组合。

    返回 (port, token, jwe) 或 None。jwe 通常只有一个候选, token 可能有几个,
    端口可能几十个, 所以用线程池并发, 命中即停。
    """
    if not ports or not tokens:
        return None
    if not jwes:
        jwes = [""]
    best = {}
    lock = threading.Lock()
    stop = threading.Event()

    def work(port, token, jwe):
        if stop.is_set():
            return
        code, _ = _probe_one(device, port, token, jwe)
        if code == 0:
            with lock:
                if "hit" not in best:
                    best["hit"] = (port, token, jwe)
                    stop.set()

    threads = []
    for token in tokens:
        for jwe in jwes:
            for port in ports:
                t = threading.Thread(target=work, args=(port, token, jwe), daemon=True)
                threads.append(t)
                t.start()
    # 限流 + 等待: 太多线程会打爆隧道, 分批 join
    for t in threads:
        t.join(timeout=0.2)
    if stop.is_set():
        for t in threads:
            t.join(timeout=0.1)
    return best.get("hit")


# ----------------------------------------------------------------- 输出
def render_config(token, jwe, port):
    bar = "═" * 52
    lines = [
        bar,
        " DC Live 实时直播源配置（源 App 一键导出）",
        " 导出时间: %s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        bar,
        "",
        "【会话 Token】  → 粘到设置页「会话 Token（32 位 hex）」框",
        token or "(未探测到，见下方候选)",
        "",
        "【JWE】  → 粘到设置页「JWE（Authorization: Bearer）」框",
        jwe or "(未探测到，见下方候选)",
        "",
        "【隧道端口】  → 粘到设置页「隧道端口」框（留空也能自动探测）",
        str(port) if port else "(留空，让 App 自动扫 /proc/net/tcp)",
        "",
        "用法: 打开 DC Live → 设置 → 实时直播源 → 实时模式打开，",
        "把上面三段分别粘进对应的三个输入框，保存后点「立即拉取」。",
        bar,
    ]
    return "\n".join(lines)


# ----------------------------------------------------------------- 子命令
def render_json(token, jwe, port):
    """机器可读配置, 直接粘进 DC Live 设置页的「导入配置」对话框。"""
    return json.dumps(
        {"appToken": token or "", "jwe": jwe or "", "tunnelPort": port or 0},
        ensure_ascii=False, indent=2,
    )


def cmd_dump(device, pkg):
    merged, _ = read_prefs(device, pkg)
    if not merged:
        return 1
    data, strings = parse_mainuser_data(merged)
    jwes = find_jwe_candidates(strings)
    tokens = find_token_candidates(strings)
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
        _print("  (无。该版本可能把会话令牌放在内存而非 prefs)")
    return 0


def cmd_ports(device, pkg):
    ports = listen_ports(device)
    _print("隧道 LISTEN 端口 (%d):" % len(ports))
    _print("  " + ", ".join(str(p) for p in ports))
    return 0


def cmd_export(device, pkg, no_probe, out_file):
    # 1) 读 prefs
    merged, _ = read_prefs(device, pkg)
    if not merged:
        return 1
    data, strings = parse_mainuser_data(merged)
    jwes = find_jwe_candidates(strings)
    tokens = find_token_candidates(strings)

    if not jwes and not tokens:
        _print("[!] 没抠到任何凭证。确认: 源 App 已安装并登录过、设备已 root。")
        return 1

    # 2) 在线探测 (可选)
    token, jwe, port = None, None, None
    if not no_probe:
        if device is None or pick_device(Adb(device)) is None:
            _print("[!] 没有可用设备, 跳过在线探测。下方只给候选, 你手动粘到设置页试。")
        else:
            ports = listen_ports(device)
            if not ports:
                _print("[!] 没扫到隧道端口。确认源 App 正在前台运行(退后台隧道会冻结)。")
            else:
                _print("[*] 扫到 %d 个端口, 开始并发探测可用的 (token,端口) 组合…"
                      % len(ports))
                jwe_strs = [s for _, s in jwes]
                token_strs = [s for _, s in tokens]
                hit = probe_combos(device, ports, token_strs, jwe_strs)
                if hit:
                    port, tk, jw = hit
                    token, jwe = tk, jw
                    _print("[✓] 命中: 端口 %d  token=%s…  jwe(len=%d)" % (port, tk[:8], len(jw)))

    # 3) 兜底: 探测没中就用第一个候选 (让用户自己试)
    if token is None and tokens:
        token = tokens[0][1]
    if jwe is None and jwes:
        jwe = jwes[0][1]

    text = render_config(token, jwe, port)
    _print("")
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
    """离线解析本地 prefs 快照 (调试用, 不碰设备)。"""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()
    # 支持直接给 FlutterSharedPreferences.xml, 或把多个 xml 拼一起
    merged = {}
    try:
        root = ET.fromstring(content)
        for el in root.iter("string"):
            name = el.get("name")
            if name:
                merged[name] = el.text or ""
    except ET.ParseError:
        _print("[!] XML 解析失败:", path)
        return 1
    data, strings = parse_mainuser_data(merged)
    jwes = find_jwe_candidates(strings)
    tokens = find_token_candidates(strings)
    _print("[*] 离线解析 %s" % path)
    _print("[*] MAINUSER_DATA 解析: %d 个字符串值" % len(strings))
    _print("── JWE 候选 (%d) ──" % len(jwes))
    for path_, s in jwes:
        _print("  [%s] len=%d  %s…%s" % (path_, len(s), s[:20], s[-10:]))
    _print("── 32hex 会话令牌候选 (%d) ──" % len(tokens))
    for path_, s in tokens:
        _print("  [%s]  %s" % (path_, s))
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
    out_file = None

    i = 0
    while i < len(rest):
        a = rest[i]
        if a == "--device":
            device = rest[i + 1]; i += 2
        elif a == "--pkg":
            pkg = rest[i + 1]; i += 2
        elif a == "--no-probe":
            no_probe = True; i += 1
        elif a == "--out":
            out_file = rest[i + 1]; i += 2
        else:
            i += 1

    if cmd == "dump":
        return cmd_dump(device, pkg)
    if cmd == "ports":
        return cmd_ports(device, pkg)
    if cmd == "from-file":
        if not rest or rest[0].startswith("--"):
            _print("[!] from-file 需要一个 xml 路径")
            return 2
        return cmd_from_file(rest[0])
    if cmd == "export":
        return cmd_export(device, pkg, no_probe, out_file)
    _print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
