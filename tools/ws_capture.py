#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ws_capture — 隧道响应解码的最小重建版。

背景：原 ws_capture.py 已丢失（未在仓库中提交/本地亦无），
而 tunnel_api.py 在模块顶部无条件 import 它，导致整个脚本无法加载。
本文件按 tunnel_api.py 的实际用法重建：

    n, out = decodings(payload)
        payload 可能是 zlib / gzip 压缩，或已是裸 JSON 字节。
        解压成功返回 (解压后字节数, 解压后 bytes)；解不动返回 (0, None)。

    unmask(payload)
        对 decodings 的薄封装；tunnel_api.unmask 在 out 为 None 时会回退原始数据，
        因此这里解不动也安全。

注意：此重建版仅用于让 tunnel_api 的 rooms/live（明文 HTTP 路径）可用；
init/call（/tunnel/open 加密通道）若依赖更精细的帧处理，可能需要原实现。
"""
import zlib


def decodings(payload):
    if not payload:
        return 0, None

    # 1) zlib（/tunnel/open 响应体常见）
    try:
        out = zlib.decompress(payload)
        if out:
            return len(out), out
    except Exception:
        pass

    # 2) gzip
    try:
        import gzip
        out = gzip.decompress(payload)
        if out:
            return len(out), out
    except Exception:
        pass

    # 3) 已是裸文本/JSON（解不动就当裸数据返回）
    try:
        payload.decode("utf-8")
        return len(payload), payload
    except Exception:
        return 0, None


def unmask(payload):
    n, out = decodings(payload)
    return out if out is not None else payload
