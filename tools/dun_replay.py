#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dun_replay.py —— 天美系 App「盾配置 / 游客登录」离线工具箱

用途（对应 docs/BASE与TOKEN获取方案.md 第 3、4 节）:
  1) decrypt   解密盾配置响应 (base64 -> AES-256-ECB/PKCS7)，输出 kiwi_* -> 真实 host 映射
  2) sign      复算盾请求签名 md5("...")[:20]，多种字段排布一次打全，与真实请求比对即定顺序
  3) pbdecode  把抓到的 protobuf 十六进制翻成 字段号=值（用于对齐 LoginQuickRequest 字段号）
  4) pbbuild   按 字段号=值 手工拼一个 protobuf 请求体（对齐后即可脱离抓包）

不依赖第三方库（AES 走本机 openssl CLI，Win10+ 自带）。

用法:
  python dun_replay.py decrypt resp.txt
  python dun_replay.py decrypt "eyJ...base64..."
  type resp.txt | python dun_replay.py decrypt -
  python dun_replay.py sign --device 29e9eb7c-b442-350b-b325-3427cd26f85b --day 20380 --channel ecy
  python dun_replay.py sign --device X --day 20380 --template "{device}.{day}.lAqUXLnH3Qkx1NHBL2+o0"
  python dun_replay.py pbdecode 0a24323965396562...
  python dun_replay.py pbbuild "1=abc,2=123,3=@body.bin"
"""
import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------- 常量
# api_urls.dart 0x171a6a4 + 0x171a71c: key 常量 + "AES/ECB/PKCS7"
AES_KEY = b"56c51dbc887d0487f942e6fca95108e4"
# api_urls.dart 0x1b08140: 签名末尾拼接的固定盐
SIGN_SALT = "lAqUXLnH3Qkx1NHBL2+o0"


# ---------------------------------------------------------------- 通用
def _read_arg(arg: str) -> bytes:
    """从 文件 / '-' / 字面量 读入，并去掉所有空白（服务端返回带换行）。"""
    if arg == "-":
        data = sys.stdin.buffer.read()
    elif os.path.isfile(arg):
        with open(arg, "rb") as f:
            data = f.read()
    else:
        data = arg.encode("utf-8", "ignore")
    return re.sub(rb"[\s\r\n\t]+", b"", data)


def _openssl_aes_ecb_dec(data: bytes, key: bytes) -> bytes:
    """AES-256-ECB 无填充解密（填充自己剥，方便定位错在哪）。"""
    try:
        p = subprocess.run(
            ["openssl", "enc", "-d", "-aes-256-ecb",
             "-K", binascii.hexlify(key).decode(), "-nopad"],
            input=data, capture_output=True, check=False,
        )
    except FileNotFoundError:
        sys.exit("[!] 找不到 openssl，请装 Git for Windows / OpenSSL 或改用 WSL")
    if p.returncode != 0:
        sys.exit("[!] openssl 失败: " + p.stderr.decode("utf-8", "ignore").strip())
    return p.stdout


def _pkcs7_unpad(b: bytes) -> bytes:
    if not b:
        return b
    n = b[-1]
    if 1 <= n <= 16 and b[-n:] == bytes([n]) * n:
        return b[:-n]
    return b


# ---------------------------------------------------------------- 1) decrypt
def cmd_decrypt(a) -> int:
    raw = _read_arg(a.input)
    if not raw:
        sys.exit("[!] 输入为空")

    # 密文可能是 base64 / base64url / hex，逐个试
    cands = []
    try:
        cands.append(("base64", base64.b64decode(raw + b"=" * (-len(raw) % 4), validate=False)))
    except Exception:
        pass
    try:
        cands.append(("base64url", base64.urlsafe_b64decode(raw + b"=" * (-len(raw) % 4))))
    except Exception:
        pass
    if re.fullmatch(rb"[0-9a-fA-F]+", raw) and len(raw) % 2 == 0:
        cands.append(("hex", binascii.unhexlify(raw)))

    key = a.key.encode() if a.key else AES_KEY
    if len(key) != 32:
        sys.exit("[!] key 必须是 32 字节（AES-256），当前 %d" % len(key))

    for enc_name, blob in cands:
        if not blob or len(blob) % 16:
            continue
        plain = _pkcs7_unpad(_openssl_aes_ecb_dec(blob, key))
        try:
            txt = plain.decode("utf-8")
        except UnicodeDecodeError:
            continue
        if a.verbose:
            print("[*] 命中编码: %s, 密文 %d 字节" % (enc_name, len(blob)))
        print(txt)
        _summarize_hosts(txt)
        return 0

    print("[!] 三种编码都解不出 UTF-8 明文，可能不是盾配置密文 / key 不对", file=sys.stderr)
    print("    排查: 1) 密文是否被截断  2) key 是否随版本变化(pp.txt 搜 AES/ECB/PKCS7 附近常量)", file=sys.stderr)
    print("          3) 是否 base64 前被二次编码（先 url-decode）", file=sys.stderr)
    return 2


def _summarize_hosts(txt: str) -> None:
    """把明文里的 kiwi_xxx -> host 映射挑出来，方便直接抄进 config.json。"""
    try:
        obj = json.loads(txt)
    except Exception:
        return
    if not isinstance(obj, dict):
        return
    hits = {}
    for k, v in obj.items():
        if isinstance(v, str) and ("kiwi" in k or "kiwi" in v or "://" in v):
            hits[k] = v
    if hits:
        print("\n=== kiwi_* -> 真实地址（填 config.json / BASE）===", file=sys.stderr)
        for k, v in hits.items():
            print("  %-28s %s" % (k, v), file=sys.stderr)


# ---------------------------------------------------------------- 2) sign
def cmd_sign(a) -> int:
    tpls = []
    if a.template:
        tpls = [a.template]
    else:
        # 代码里是「数组 + '.' 拼接 + 末尾常量」，元素顺序需靠真实请求对齐，故全部列出
        tpls = [
            "{device}.{day}." + SIGN_SALT,
            "{device}.{channel}." + SIGN_SALT,
            "{device}.{channel}.{day}." + SIGN_SALT,
            "{device}.{day}.{channel}." + SIGN_SALT,
            "{day}.{device}." + SIGN_SALT,
            "{device}." + SIGN_SALT,
            "{channel}.{device}." + SIGN_SALT,
        ]
    vals = {"device": a.device or "", "channel": a.channel or "", "day": str(a.day or "")}
    print("device=%s channel=%s day=%s" % (vals["device"], vals["channel"], vals["day"]))
    print("%-6s %-46s %s" % ("len", "plain", "md5[:20]"))
    seen = set()
    for t in tpls:
        s = t.format(**vals)
        if s in seen:
            continue
        seen.add(s)
        d = hashlib.md5(s.encode()).hexdigest()
        print("%-6d %-46s %s" % (len(s), s, d[:20]))
    print("\n[提示] 拿真实请求里 key= 的值, 与最后一列比对, 命中的那行就是正确排布。", file=sys.stderr)
    return 0


# ---------------------------------------------------------------- protobuf
def _read_varint(b: bytes, i: int):
    shift = val = 0
    while i < len(b):
        c = b[i]
        i += 1
        val |= (c & 0x7F) << shift
        if not c & 0x80:
            return val, i
        shift += 7
        if shift > 70:
            break
    raise ValueError("varint 越界")


def _pb_dump(b: bytes, indent: int = 0, depth: int = 0) -> None:
    pad = "  " * indent
    i = 0
    while i < len(b):
        start = i
        try:
            key, i = _read_varint(b, i)
        except ValueError:
            print(pad + "<尾部 %d 字节非 protobuf: %s>" % (len(b) - start, b[start:start + 16].hex()))
            return
        num, wire = key >> 3, key & 7
        if wire == 0:
            v, i = _read_varint(b, i)
            print("%s%-3d varint  %d" % (pad, num, v))
        elif wire == 2:
            ln, i = _read_varint(b, i)
            chunk = b[i:i + ln]
            i += ln
            printable = all(32 <= c < 127 or c in (9, 10, 13) for c in chunk)
            if printable and ln and depth < 3:
                print("%s%-3d str     %r" % (pad, num, chunk.decode("utf-8", "replace")))
            elif printable:
                print("%s%-3d str     %r" % (pad, num, chunk.decode("utf-8", "replace")))
            else:
                print("%s%-3d bytes   %d B  %s" % (pad, num, ln, chunk[:24].hex() + ("..." if ln > 24 else "")))
                # 可能是嵌套消息，尝试下钻
                if depth < 3 and ln >= 2:
                    try:
                        _pb_dump(chunk, indent + 2, depth + 1)
                    except Exception:
                        pass
        elif wire == 5:
            print("%s%-3d fixed32 %s" % (pad, num, b[i:i + 4][::-1].hex()))
            i += 4
        elif wire == 1:
            print("%s%-3d fixed64 %s" % (pad, num, b[i:i + 8][::-1].hex()))
            i += 8
        else:
            print("%s%-3d <wire=%d 不支持>" % (pad, num, wire))
            return


def cmd_pbdecode(a) -> int:
    raw = _read_arg(a.input)
    if re.fullmatch(rb"[0-9a-fA-F]+", raw) and len(raw) % 2 == 0:
        raw = binascii.unhexlify(raw)
    _pb_dump(raw)
    return 0


def _pb_varint(num: int, val: int) -> bytes:
    out = bytearray()
    v = (num << 3)
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    v = val
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def _pb_bytes(num: int, data: bytes) -> bytes:
    out = bytearray()
    v = (num << 3) | 2
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    v = len(data)
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out) + data


def cmd_pbbuild(a) -> int:
    out = bytearray()
    for part in a.fields.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            sys.exit("[!] 格式应为 号=值, 出错片段: %r" % part)
        k, v = part.split("=", 1)
        num = int(k)
        if v.startswith("@"):
            with open(v[1:], "rb") as f:
                out += _pb_bytes(num, f.read())
        elif v.lstrip("-").isdigit():
            out += _pb_varint(num, int(v))
        else:
            out += _pb_bytes(num, v.encode())
    print(out.hex())
    print("\n[%d 字节] 把它作为 Request.data 或直接当 data 字段用" % len(out), file=sys.stderr)
    return 0


# ---------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description="天美系盾配置/游客登录离线工具箱")
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("decrypt", help="解密盾配置响应 (AES-256-ECB)")
    d.add_argument("input", help="文件路径 / '-' 读 stdin / 直接粘密文")
    d.add_argument("--key", help="覆盖 32 字节 key（版本变化时用）")
    d.add_argument("-v", "--verbose", action="store_true")
    d.set_defaults(func=cmd_decrypt)

    s = sub.add_parser("sign", help="复算盾请求签名 md5()[:20]")
    s.add_argument("--device", help="deviceUUID / apnsCode")
    s.add_argument("--channel", help="渠道号, 如 ecy")
    s.add_argument("--day", type=int, help="自 1970 起的天数 = unix秒/86400")
    s.add_argument("--template", help="自定义模板, 支持 {device} {channel} {day}")
    s.set_defaults(func=cmd_sign)

    p = sub.add_parser("pbdecode", help="protobuf 十六进制/文件 -> 字段清单")
    p.add_argument("input")
    p.set_defaults(func=cmd_pbdecode)

    b = sub.add_parser("pbbuild", help="按 号=值 拼 protobuf")
    b.add_argument("fields", help='例: "1=abc,2=123,3=@file.bin"')
    b.set_defaults(func=cmd_pbbuild)

    a = ap.parse_args()
    return a.func(a)


if __name__ == "__main__":
    sys.exit(main())
