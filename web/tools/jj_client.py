# -*- coding: utf-8 -*-
"""
jj_live / 91直播 4.3.3 完整接口客户端（算法已用真实抓包双向验证）

已验证的三要素：
  1. 路径混淆  token = "." + 随机前缀(3) + hex(base64(path))   —— 逐字符转小写十六进制
  2. 签名      x-auth-sign = md5( path + "?" + 排序(k=v&...) + "oknzoiwe@23" )
               参与签名的键 = ak, authorization, channel, terminal, version,
                            x-auth-timestamp, + 全部业务参数
               （不含 content-language / deviceuid，这是与 6.24 版的关键差异）
  3. 报文体    xorCode: chr((ord(c) ^ 202030) & 0xFFFF)  ≡ chr(ord(c) ^ 0x152E)
               响应体同一算法

用法：
  python jj_client.py --token <authorization> --path /firstTopUp/firstTopUpEntrance
  python jj_client.py --token <authorization> --path /home/rec --params '{"type":1}'
"""
import base64
import hashlib
import json
import random
import ssl
import sys
import time
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

SALT = "oknzoiwe@23"
SIGN_KEYS = ("ak", "authorization", "channel", "terminal", "version")

# 服务端可能在响应头里轮换 authorization，这里留回调给上层持久化
_token_callback = None


def set_token_callback(fn):
    """注册「收到新令牌」回调：fn(new_token) -> None"""
    global _token_callback
    _token_callback = fn


# ───────────────────────────── 编解码 ─────────────────────────────
def xor_code(s: str) -> str:
    """Dart: fromCharCodes(codeUnits.map((c) => (c ^ 202030) & 0xFFFF))"""
    return "".join(chr((ord(c) ^ 202030) & 0xFFFF) for c in s)


def encode_path(path: str, prefix: str | None = None) -> str:
    """base64 -> 逐字符小写 hex -> 前置 3 字符随机串"""
    b64 = base64.b64encode(path.encode()).decode()
    hexed = "".join(f"{ord(c):x}" for c in b64)
    if prefix is None:
        prefix = "".join(random.choices("0123456789abcdef", k=3))
    return "/" + prefix + hexed


def decode_path(token: str):
    """逆向：扫描偏移，找到能解出 / 开头可打印路径的位置。返回 (前缀, 路径)。"""
    tok = token.lstrip("/")
    b64chars = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    for off in range(0, min(20, len(tok))):
        body = tok[off:]
        if len(body) % 2:
            continue
        try:
            chars = "".join(chr(int(body[i:i + 2], 16)) for i in range(0, len(body), 2))
        except ValueError:
            continue
        if not all(c in b64chars for c in chars):
            continue
        try:
            dec = base64.b64decode(chars + "=" * (-len(chars) % 4)).decode()
        except Exception:
            continue
        if dec.startswith("/") and dec.isprintable():
            return tok[:off], dec
    raise ValueError("无法解析该路径 token")


# ───────────────────────────── 客户端 ─────────────────────────────
class JJClient:
    def __init__(self, host, authorization, device_uid="1bbd0f822e77afd060cd5e8cc1ac147c",
                 terminal="2", version="433", channel="1", ak="jyzb", lang="zh-CN"):
        self.host = host.rstrip("/")
        self.authorization = authorization
        self.device_uid = device_uid
        self.terminal, self.version, self.channel, self.ak, self.lang = (
            terminal, version, channel, ak, lang)

    def make_sign(self, path: str, params: dict | None, ts: str) -> str:
        d = {
            "ak": self.ak,
            "authorization": self.authorization,
            "channel": self.channel,
            "terminal": self.terminal,
            "version": self.version,
            "x-auth-timestamp": ts,
        }
        if params:
            for k, v in params.items():
                d[k] = json.dumps(v, separators=(",", ":"), ensure_ascii=False) if isinstance(v, (list, dict)) else str(v)
        src = path + "?" + "&".join(f"{k}={d[k]}" for k in sorted(d))
        return hashlib.md5((src + SALT).encode()).hexdigest(), src

    def call(self, path: str, params: dict | None = None, prefix: str | None = None,
             verbose: bool = False):
        ts = str(int(time.time() * 1000))
        sign, src = self.make_sign(path, params, ts)
        plaintext = json.dumps(params or {}, separators=(",", ":"), ensure_ascii=False)
        body = xor_code(plaintext).encode("utf-8")
        url = self.host + encode_path(path, prefix)

        headers = {
            "user-agent": "Dart/3.6 (dart:io)",
            "accept-encoding": "identity",
            "channel": self.channel,
            "authorization": self.authorization,
            "content-type": "application/json",
            "terminal": self.terminal,
            "content-language": self.lang,
            "x-auth-timestamp": ts,
            "version": self.version,
            "deviceuid": self.device_uid,
            "ak": self.ak,
            "x-auth-sign": sign,
        }
        if verbose:
            print(f"POST {url}")
            print(f"  签名源串: {src}")
            print(f"  x-auth-sign: {sign}")
            print(f"  请求体明文: {plaintext}")

        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
                raw = r.read().decode("utf-8", "replace")
                status = r.status
                resp_headers = {k.lower(): v for k, v in r.headers.items()}
        except urllib.error.HTTPError as e:
            raw, status = e.read().decode("utf-8", "replace"), e.code
            resp_headers = {k.lower(): v for k, v in (e.headers or {}).items()}
        except Exception as e:
            return {"status": None, "error": f"{type(e).__name__}: {e}"}

        # 服务端若下发新 authorization，交给上层持久化
        new_auth = resp_headers.get("authorization")
        if new_auth and new_auth != self.authorization and _token_callback:
            try:
                _token_callback(new_auth)
            except Exception:
                pass

        # 响应体同样 xorCode
        plain = xor_code(raw) if raw and ord(raw[0]) > 0x1400 else raw
        try:
            data = json.loads(plain)
        except Exception:
            data = None
        return {"status": status, "raw_len": len(raw), "plain": plain,
                "json": data, "headers": resp_headers}


def main() -> None:
    import argparse
    ap = argparse.ArgumentParser(description="91直播 4.3.3 接口客户端")
    ap.add_argument("--host", default="https://utp.opuxrcm.com")
    ap.add_argument("--token", required=True, help="authorization 头（登录 token）")
    ap.add_argument("--path", required=True, help="真实业务路径，如 /home/rec")
    ap.add_argument("--params", default=None, help='业务参数 JSON，如 \'{"type":1}\'')
    ap.add_argument("--device", default="1bbd0f822e77afd060cd5e8cc1ac147c")
    ap.add_argument("--prefix", default=None, help="路径随机前缀（默认随机 3 位）")
    ap.add_argument("--decode-path", default=None, help="仅解析某个路径 token 后退出")
    a = ap.parse_args()

    if a.decode_path:
        pre, p = decode_path(a.decode_path)
        print(f"随机前缀 = {pre!r}")
        print(f"真实路径 = {p}")
        return

    cli = JJClient(a.host, a.token, device_uid=a.device)
    params = json.loads(a.params) if a.params else None
    res = cli.call(a.path, params, prefix=a.prefix, verbose=True)
    print()
    print("HTTP:", res.get("status"), res.get("error") or "")
    print("响应密文字节数:", res.get("raw_len"))
    print("响应明文:", (res.get("plain") or "")[:2000])


if __name__ == "__main__":
    main()
