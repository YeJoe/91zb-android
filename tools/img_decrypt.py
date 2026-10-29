#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""加密图片离线解密器（天美 App）

加密头格式（blutter 反编译确认）：
    <mode iv="<IV>">N</mode> + 密文
    N 是算法枚举索引：
        0 = none      1 = aes      2 = chaCha20      3 = rabbit

IV 编码在头里给出；密钥不在头里，需要猜/派生。本工具做两件事：
  1) 解析头、按 N 选算法；
  2) 用一组候选密钥逐个试解，用「解出来是不是合法图片」当判据，
     命中就写出 .jpg/.png 并打印用的是哪个密钥。

用法：
    python tools/img_decrypt.py enc.bin                 # 自动试候选密钥
    python tools/img_decrypt.py enc.bin -o out.jpg      # 指定输出
    python tools/img_decrypt.py enc.bin --iv-hex 0011223344556677 --key-hex <32hex>
    python tools/img_decrypt.py --selftest              # 自测（不需样本）

样本怎么来（源 App 能显示图，说明磁盘/内存里有密文）：
    adb shell su -c 'cp /sdcard/xxx /data/local/tmp/' 之类先落到本地，
    或直接抓一张 CDN 图片响应体存成 enc.bin。
"""
import base64
import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

HEADER_RE = re.compile(rb'<mode\s+iv="([^"]*)"\s*>(\d+)</mode>', re.S)
MODE_NAME = {0: "none", 1: "aes", 2: "chaCha20", 3: "rabbit"}


# ----------------------------------------------------------------- 头部解析
def parse_header(data: bytes):
    m = HEADER_RE.match(data)
    if not m:
        return None
    iv_raw = m.group(1).decode("latin1")
    mode = int(m.group(2))
    body = data[m.end():]
    return mode, iv_raw, body


def decode_iv(iv_raw: str):
    """IV 可能是 hex / base64 / base64url，全试一遍，返回 [(编码名, bytes)]"""
    out = []
    s = iv_raw.strip()
    if s and len(s) % 2 == 0 and re.fullmatch(r"[0-9a-fA-F]+", s):
        try:
            out.append(("hex", bytes.fromhex(s)))
        except ValueError:
            pass
    for name, fn in (("b64", base64.b64decode), ("b64url", base64.urlsafe_b64decode)):
        try:
            pad = s + "=" * (-len(s) % 4)
            b = fn(pad)
            if b and all(x[1] != b for x in out):
                out.append((name, b))
        except Exception:
            pass
    return out


# ----------------------------------------------------------------- 密钥候选
def _read_secret(name: str) -> str:
    try:
        with open(os.path.join(HERE, "..", "assets", name), "r", encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def key_candidates():
    """返回 [(描述, key_bytes)]，只保留长度合法的（16/24/32）"""
    from rabbit import KEY_SALT, derive_key, current_hour_ts

    token = _read_secret(".apptoken.txt")
    jwe = _read_secret(".jwe.txt")
    hour = current_hour_ts()
    out = []

    def add(name, b):
        if isinstance(b, str):
            b = b.encode("utf-8")
        if b and len(b) in (16, 24, 32):
            out.append((name, b))

    # 1) 只用 SALT
    add("md5(SALT)", hashlib.md5(KEY_SALT.encode()).digest())
    add("md5(SALT+hour)", hashlib.md5((KEY_SALT + str(hour)).encode()).digest())
    add("SALT[:16]", KEY_SALT.encode()[:16])

    if token:
        add("derive(SALT+hour+token)", derive_key(hour, token))
        add("md5(SALT+token)", hashlib.md5((KEY_SALT + token).encode()).digest())
        add("md5(SALT+hour+token)", hashlib.md5((KEY_SALT + str(hour) + token).encode()).digest())
        add("md5(token)", hashlib.md5(token.encode()).digest())
        try:
            add("token-hex16", bytes.fromhex(token))
        except ValueError:
            pass

    if jwe:
        add("md5(SALT+jwe)", hashlib.md5((KEY_SALT + jwe).encode()).digest())
        add("md5(jwe)", hashlib.md5(jwe.encode()).digest())
        add("jwe-bytes[:16]", jwe.encode()[:16])
        add("jwe-bytes[:32]", jwe.encode()[:32])

    # 3) 常见弱口令兜底（命中说明服务端用的是土密钥）
    for s in ("1234567890abcdef", "0123456789abcdef", "0000000000000000", "tianmei", "duocai"):
        add("literal:" + s, s)

    # 去重
    seen, uniq = set(), []
    for name, b in out:
        if b not in seen:
            seen.add(b)
            uniq.append((name, b))
    return uniq


# ----------------------------------------------------------------- 判据
def looks_image(b: bytes) -> str:
    """返回图片类型或 ''"""
    if len(b) < 12:
        return ""
    if b[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        return "webp"
    if b[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    return ""


# ----------------------------------------------------------------- 解密
def try_rabbit(body: bytes, iv: bytes, key: bytes) -> bytes:
    from rabbit import RabbitCipher
    if len(iv) != 8:
        return b""
    return RabbitCipher(key, iv).encrypt_decrypt(body)


def try_aes(body: bytes, iv: bytes, key: bytes):
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except ImportError:
        return []
    outs = []
    for m in (modes.CBC(iv) if len(iv) == 16 else None, modes.ECB()):
        if m is None:
            continue
        try:
            d = Cipher(algorithms.AES(key), m).decryptor()
            outs.append(d.update(body) + d.finalize())
        except Exception:
            pass
    return outs


def unpad_pkcs7(b: bytes) -> bytes:
    if not b:
        return b
    n = b[-1]
    if 1 <= n <= 16 and b.endswith(bytes([n]) * n):
        return b[:-n]
    return b


def decrypt_all(data: bytes, extra_keys=None):
    """返回 [(描述, 明文, 类型)] 命中列表"""
    parsed = parse_header(data)
    if not parsed:
        return []
    mode, iv_raw, body = parsed
    ivs = decode_iv(iv_raw)
    keys = key_candidates() + list(extra_keys or [])
    hits = []

    if mode == 3:  # rabbit
        for ivname, iv in ivs:
            for kname, key in keys:
                if len(key) != 16:
                    continue
                pt = try_rabbit(body, iv, key)
                t = looks_image(pt)
                if t:
                    hits.append(("rabbit iv=%s key=%s" % (ivname, kname), pt, t))

    elif mode == 1:  # aes
        for ivname, iv in ivs:
            for kname, key in keys:
                for i, pt in enumerate(try_aes(body, iv, key)):
                    for cand in (pt, unpad_pkcs7(pt)):
                        t = looks_image(cand)
                        if t:
                            hits.append(("aes[%d] iv=%s key=%s" % (i, ivname, kname), cand, t))
    elif mode == 2:  # chaCha20
        try:
            from cryptography.hazmat.primitives.ciphers import Cipher, algorithms
        except ImportError:
            return []
        for ivname, iv in ivs:
            for kname, key in keys:
                if len(key) != 32:
                    continue
                for nonce in (iv[:16], iv[:12] + b"\x00" * 4, iv[-16:]):
                    if len(nonce) != 16:
                        continue
                    try:
                        d = Cipher(algorithms.ChaCha20(key, nonce), None).decryptor()
                        pt = d.update(body)
                    except Exception:
                        continue
                    t = looks_image(pt)
                    if t:
                        hits.append(("chacha20 iv=%s key=%s" % (ivname, kname), pt, t))
    else:
        t = looks_image(body)
        if t:
            hits.append(("none", body, t))
    return hits


# ----------------------------------------------------------------- 自测
def selftest():
    from rabbit import RabbitCipher
    key = bytes.fromhex("00112233445566778899aabbccddeeff")
    iv = bytes.fromhex("0102030405060708")
    plain = b"\xff\xd8\xff\xe0" + b"FAKEJPEG" * 20 + b"\xff\xd9"
    ct = RabbitCipher(key, iv).encrypt_decrypt(plain)

    for iv_enc in ("b64", "b64url", "hex"):
        if iv_enc == "b64":
            ivs = base64.b64encode(iv).decode()
        elif iv_enc == "b64url":
            ivs = base64.urlsafe_b64encode(iv).decode()
        else:
            ivs = iv.hex()
        blob = ('<mode iv="%s">3</mode>' % ivs).encode() + ct
        hits = decrypt_all(blob, extra_keys=[("test-key", key)])
        ok = any(h[1] == plain for h in hits)
        print("  iv=%-6s header=%s -> %s" % (iv_enc, ivs, "OK" if ok else "FAIL"))
        if not ok:
            return False
    print("[selftest] PASS")
    return True


# ----------------------------------------------------------------- 入口
def main(argv):
    if "--selftest" in argv:
        return 0 if selftest() else 1

    if not argv or argv[0].startswith("-"):
        print(__doc__)
        return 2

    path = argv[0]
    out = None
    iv_hex = None
    key_hex = None
    i = 1
    while i < len(argv):
        if argv[i] == "-o":
            out = argv[i + 1]; i += 2
        elif argv[i] == "--iv-hex":
            iv_hex = argv[i + 1]; i += 2
        elif argv[i] == "--key-hex":
            key_hex = argv[i + 1]; i += 2
        else:
            i += 1

    with open(path, "rb") as f:
        data = f.read()

    # --iv-hex：手动覆盖头里的 IV（IV 编码没猜对时用）
    if iv_hex:
        data = HEADER_RE.sub(
            lambda m: b'<mode iv="%s">%s</mode>' % (iv_hex.encode(), m.group(2)),
            data, count=1,
        )

    parsed = parse_header(data)
    if not parsed:
        print("[!] 没找到 <mode iv=\"...\">N</mode> 头，前 64 字节：")
        print("   ", data[:64])
        return 1
    mode, iv_raw, body = parsed
    print("[*] mode=%d (%s)  iv=%s  密文=%d 字节" % (mode, MODE_NAME.get(mode, "?"), iv_raw, len(body)))

    extra = []
    if key_hex:
        extra.append(("cli-key", bytes.fromhex(key_hex)))
    hits = decrypt_all(data, extra_keys=extra)

    if not hits:
        print("[x] 所有候选密钥都没解出合法图片。")
        print("    下一步：真机 hook 一次 RabbitCipher 拿到 (key, iv)，或确认 key 是否随账号变化。")
        return 1

    desc, pt, kind = hits[0]
    ext = {"jpeg": ".jpg", "png": ".png", "webp": ".webp", "gif": ".gif"}[kind]
    if not out:
        out = os.path.splitext(path)[0] + ".dec" + ext
    with open(out, "wb") as f:
        f.write(pt)
    print("[✓] 命中：%s" % desc)
    print("[✓] 类型 %s，%d 字节 -> %s" % (kind, len(pt), out))
    if len(hits) > 1:
        print("    另外 %d 个候选也解出了图（可能是同一密钥的不同描述）" % (len(hits) - 1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
