#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
天美 App 加密核心实现（逆向自 libapp.so / blutter 解析结果）

包含:
  1. RabbitCipher      —— flutter_net/rabbit_cipher.dart 的 1:1 复刻
                          (key=16B, iv=8B, 大端字, 自定义 g 函数与状态更新)
  2. derive_key()      —— MD5("jQBUO&2pfS5sC」¥\\?AtglXv*w+PcRK8%i" + 小时数 + token)
  3. encrypt_api_path()—— HttpRequest::encryptApiPath 路径混淆
                          base64Url( reverse(xor(reverse(xor(utf8(path),R1)),R2)) + R2 )

验证方式: tools/frida_hook_crypto.js 可在真机 dump (key, iv, 明文, 密文) 逐字节比对。
"""
import base64
import hashlib
import os
import time

M32 = 0xFFFFFFFF
# 8.1.7 的 SALT (pp+0x18c48)。套壳/新版若不同, 用环境变量 KEY_SALT 覆盖
# (从 blutter 的 pp.txt 里 initCipher 附近的字符串常量找)
KEY_SALT = os.environ.get("KEY_SALT") or "jQBUO&2pfS5sC」¥\\?AtglXv*w+PcRK8%i"


# ---------------------------------------------------------------- RabbitCipher
def _be32(b: bytes) -> int:
    """_bytesToUint32: (b[0]<<24)|(b[1]<<16)|(b[2]<<8)|b[3]  (大端)"""
    return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]


def _rotl16(x: int) -> int:
    return ((x << 16) | (x >> 16)) & M32


def _rotl8(x: int) -> int:
    return ((x << 8) | (x >> 24)) & M32


def _wrap(key: bytes, start: int, length: int) -> bytes:
    """key 循环窗口取 length 字节 (越界回绕) —— _setupKey 中 2i+4>16 的分支"""
    return bytes(key[(start + k) % len(key)] for k in range(length))


class RabbitCipher:
    """复刻 RabbitCipher (implements StreamCipher)

    - _setupKey: X[i] = BE32(key[2i:2i+4]) ; C[i] = BE32(key[(2i+8)&15 : +4])
    - _setupIV : X[0] ^= BE32(iv[0:4]) ; X[1] ^= BE32(iv[4:8]) ; 4x nextState
    - nextState: C[i] = C[i]+X[i]+carry (32位进位链)
                 g[i] = lo32(s*s) ^ hi32(s*s), s = X[i]+C[i]
                 X[i] = g[i] + ROTL16(g[i-1]) + ROTL16(g[i-2])   (i 偶数)
                 X[i] = g[i] + ROTL8 (g[i-1]) +        g[i-2]    (i 奇数)
    - keystream: 每 16 字节 nextState; S = X[0]^X[5] 起,
                 每 4 字节换 S = X[j]^X[(j+5)&7] (j=1,2,3...), 小端吐字节
    - encryptDecrypt(data) = data XOR keystream(len(data))
    """

    def __init__(self, key: bytes, iv: bytes):
        assert len(key) == 16, "key must be 16 bytes"
        assert len(iv) == 8, "iv must be 8 bytes"
        self.X = [0] * 8
        self.C = [0] * 8
        self._setup_key(key)
        self._setup_iv(iv)

    # ---- 密钥安装: _setupKey (0x19a5aa4) ----
    def _setup_key(self, key: bytes):
        for i in range(8):
            j = (2 * i + 8) & 15
            self.X[i] = _be32(_wrap(key, 2 * i, 4))
            self.C[i] = _be32(_wrap(key, j, 4))

    # ---- IV 安装: _setupIV (0x19a575c) ----
    def _setup_iv(self, iv: bytes):
        self.X[0] ^= _be32(iv[0:4])
        self.X[1] ^= _be32(iv[4:8])
        for _ in range(4):
            self._next_state()

    # ---- 状态推进: _nextState (0x111c5ac) ----
    def _next_state(self):
        X, C = self.X, self.C
        carry = 0
        for i in range(8):                                   # 计数器进位链
            t = (C[i] + X[i] + carry) & M32
            carry = 1 if (t < C[i] or (t == C[i] and carry > 0)) else 0
            C[i] = t
        g = [0] * 8
        for i in range(8):                                   # g 函数
            s = (X[i] + C[i]) & M32
            p = s * s
            g[i] = ((p & M32) ^ (p >> 32)) & M32
        for i in range(8):                                   # 状态更新
            if i % 2 == 0:
                X[i] = (g[i] + _rotl16(g[(i - 1) & 7]) + _rotl16(g[(i - 2) & 7])) & M32
            else:
                X[i] = (g[i] + _rotl8(g[(i - 1) & 7]) + g[(i - 2) & 7]) & M32

    # ---- 密钥流: _generateKeystream (0x111c394) ----
    def _generate_keystream(self, length: int) -> bytes:
        out = bytearray()
        s = 0
        for i in range(length):
            if i % 16 == 0:
                self._next_state()
                s = self.X[0] ^ self.X[5]
            out.append(s & 0xFF)
            s >>= 8
            if i % 4 == 3:
                j = (i // 4 + 1) & 7
                s = self.X[j] ^ self.X[(j + 5) & 7]
        return bytes(out)

    # ---- 加/解密: encryptDecrypt (0x111c2c4) ----
    def encrypt_decrypt(self, data: bytes) -> bytes:
        ks = self._generate_keystream(len(data))
        return bytes(a ^ b for a, b in zip(data, ks))


# ---------------------------------------------------------------- 密钥派生
def current_hour_ts() -> int:
    """initCipher 里的小时数: now_micros -> ms -> s -> /3600"""
    return int(time.time()) // 3600


def derive_key(hour_ts: int = None, token: str = "") -> bytes:
    """key = MD5(utf8(SALT + hour + token)) —— HttpTunnelLive4::initCipher"""
    if hour_ts is None:
        hour_ts = current_hour_ts()
    raw = (KEY_SALT + str(hour_ts) + (token or "")).encode("utf-8")
    return hashlib.md5(raw).digest()


# ---------------------------------------------------------------- 路径混淆
def _xor_with_key(data: bytes, key: bytes) -> bytes:
    """xorWithKey: data[i] ^ key[i % len(key)]"""
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def encrypt_api_path(path: str) -> str:
    """HttpRequest::encryptApiPath

    R1 = 11 random bytes, R2 = 13 random bytes
    t  = reverse( xor( reverse( xor(utf8(path), R1) ), R2 ) )
    return base64Url( t + R2 )

    2026-10-28 实测更正（MuMu 15 / 8.5.3）：服务端要求 **urlsafe 字母表 + 保留 '=' 填充**，
    且路径明文 >= 11 字符（载荷 >= 24 字节），否则返回
      "illegal base64 data at input byte N" / "encrypted api path too short: N bytes"。
    8.1.7 时代"去填充"的写法会被 400/500；如需旧行为设 B64_STRIP_PAD=1。
    """
    r1 = os.urandom(11)
    r2 = os.urandom(13)
    t = _xor_with_key(path.encode("utf-8"), r1)
    t = t[::-1]
    t = _xor_with_key(t, r2)
    t = t[::-1]
    blob = t + r2
    out = base64.urlsafe_b64encode(blob).decode("ascii")
    if os.environ.get("B64_STRIP_PAD") == "1":
        out = out.rstrip("=")
    return out


def build_encrypted_path(api_path: str, prefix: str = "") -> str:
    """updateSocketParams 的最终路径: {prefix}/{encrypted}

    客户端请求 URL 形如 {BASE}/go{api_path}，服务端收到 {prefix}/{base64url}
    prefix 由 static field 0x1400 决定（运行时可从抓包确认，默认 "/go"）。
    """
    enc = encrypt_api_path(api_path)
    return "%s/%s" % (prefix, enc) if prefix else "/%s" % enc


# ---------------------------------------------------------------- 请求封装
def build_headers(cipher_iv_b64: str, checksum: str, token: str = "") -> dict:
    """http.dart HttpRequest::send 的请求头"""
    h = {
        "Content-Type": "application/proto",
        "Accept": "application/proto",
        "cipher": cipher_iv_b64,
        "checksum": checksum,
    }
    if token:
        h["Authorization"] = "Bearer " + token
    return h


def md5hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")
