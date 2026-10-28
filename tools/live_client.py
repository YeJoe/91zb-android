#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
天美直播 API 客户端 —— 不安装 App 也能: 列房间 / 取播放地址 / 直接播放。

链路 (逆向自旧版 blutter 项目 + libapp.so):
  POST {BASE}/go{api_path}
    ├─ path  : {prefix}/{base64Url(reverse(xor(reverse(xor(utf8(path),R1)),R2))+R2)}
    ├─ header: cipher=base64(8B IV), checksum=md5hex(body), Authorization=Bearer <jwt>
    └─ body  : RabbitCipher(MD5(SALT+hour+token), IV).encrypt(protobuf(Request{path,data}))

用法:
  pip install requests
  set BASE=https://<抓包得到的域名>          (Windows cmd; 只要 origin, 不带 /go)
  set TOKEN=<jwt>                          (可选, 游客可空)
  python live_client.py list
  python live_client.py rooms               # 列表 + 详情(封面/播放地址)
  python live_client.py play <roomId>       # 打印 PotPlayer/ffplay 命令
  python live_client.py potplayer <roomId>  # 直接调起 PotPlayer

BASE 获取: 见 docs/直播接口清单.md 第 6 节 (logcat / Frida / mitmproxy 三选一)。
最终 URL = BASE + pathPrefix + "/" + base64url(加密路径); pathPrefix 默认 /go, 可用
PATH_PREFIX 环境变量覆盖 (对应 App 的 static field 0x1400, 抓包可校正)。
"""
import os
import sys
import json
import time
import random
import subprocess

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rabbit import (RabbitCipher, derive_key, build_encrypted_path,
                    build_headers, md5hex, b64, current_hour_ts)

try:
    import requests
except ImportError:
    requests = None  # 离线功能(编解码/加密)仍可用; 发请求时才需要 pip install requests

BASE = (os.environ.get("BASE") or "").rstrip("/")
TOKEN = os.environ.get("TOKEN") or ""
PATH_PREFIX = os.environ.get("PATH_PREFIX") or "/go"   # static field 0x1400, 抓包可校正
APP_ID = os.environ.get("APP_ID", "live3")
VERSION = os.environ.get("VERSION", "8.5.3")
DEVICE_ID = os.environ.get("DEVICE_ID") or os.environ.get("deviceUUID") or ("%032x" % random.getrandbits(128))
POTPLAYER = os.environ.get("POTPLAYER") or r"C:\Program Files\DAUM\PotPlayer\PotPlayerMini64.exe"

STREAM_KEYS = ("playRtmp", "playFlv", "playHls", "rtmp_url", "stream_url",
               "playUrl", "pull_url", "flv_url", "hls_url", "m3u8")


# ================================================================ protobuf 迷你编解码
def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pb_field(num: int, wire: int) -> bytes:
    return _varint((num << 3) | wire)


def pb_varint(num: int, val: int) -> bytes:
    return pb_field(num, 0) + _varint(val)


def pb_bytes(num: int, data: bytes) -> bytes:
    return pb_field(num, 2) + _varint(len(data)) + data


def pb_int64(num: int, val: int) -> bytes:
    return pb_varint(num, val)


def pb_str(num: int, s: str) -> bytes:
    return pb_bytes(num, s.encode("utf-8"))


def pb_msg(num: int, data: bytes) -> bytes:
    return pb_bytes(num, data)


def pb_packed_int64(num: int, vals) -> bytes:
    body = b"".join(_varint(int(v)) for v in vals)
    return pb_bytes(num, body)


def pb_decode(buf: bytes):
    """返回 [(field_num, wire_type, value)]，value: varint->int, bytes->bytes"""
    out = []
    i, n = 0, len(buf)
    while i < n:
        key, i = _read_varint(buf, i)
        num, wire = key >> 3, key & 7
        if wire == 0:
            v, i = _read_varint(buf, i)
            out.append((num, wire, v))
        elif wire == 2:
            ln, i = _read_varint(buf, i)
            out.append((num, wire, buf[i:i + ln]))
            i += ln
        elif wire == 5:
            out.append((num, wire, buf[i:i + 4])); i += 4
        elif wire == 1:
            out.append((num, wire, buf[i:i + 8])); i += 8
        else:
            raise ValueError("bad wire %d at %d" % (wire, i))
    return out


def _read_varint(buf: bytes, i: int):
    shift, result = 0, 0
    while True:
        b = buf[i]
        result |= (b & 0x7F) << shift
        i += 1
        if not (b & 0x80):
            return result, i
        shift += 7


def pb_unpack_int64(blob: bytes):
    out, i = [], 0
    while i < len(blob):
        v, i = _read_varint(blob, i)
        out.append(v)
    return out


# ================================================================ 消息构造
def make_request(path: str, data: bytes) -> bytes:
    """bean.Request { 1:random, 2:time?, 3:path, 4:data }"""
    body = bytearray()
    body += pb_varint(1, random.randint(1, 2**31 - 1))
    body += pb_varint(2, int(time.time()))
    body += pb_str(3, path)
    body += pb_bytes(4, data)
    return bytes(body)


def make_list_request(page: int = 1, menu_id: int = 0, channel_id: str = "") -> bytes:
    """room.ListRequest { 1:pageIndex, 2:menuId, 3:params, 4:channelId }"""
    body = bytearray()
    body += pb_varint(1, page)
    if menu_id:
        body += pb_varint(2, menu_id)
    if channel_id:
        body += pb_str(4, channel_id)
    return bytes(body)


def make_get_info_request(ids) -> bytes:
    """room.GetInfoByIdsRequest { 1: ids (packed repeated int64) }"""
    if isinstance(ids, (str, int)):
        ids = [ids]
    return pb_packed_int64(1, [int(i) for i in ids])


# ================================================================ 传输层
def api_post(api_path: str, req_msg: bytes, timeout: int = 15):
    """一次完整的加密请求: 返回 (response_body_bytes, raw_json_or_None)"""
    if requests is None:
        sys.exit("缺少依赖: pip install requests")
    if not BASE:
        sys.exit("请先设置 BASE (只要 origin, 不带 /go), 例如:\n"
                 "  set BASE=https://api.xxx.com\n"
                 "  python live_client.py rooms")
    wrapper = make_request(api_path, req_msg)
    hour = current_hour_ts()
    key = derive_key(hour, TOKEN)
    iv = os.urandom(8)
    cipher = RabbitCipher(key, iv)
    enc_body = cipher.encrypt_decrypt(wrapper)

    url = BASE + build_encrypted_path(api_path, PATH_PREFIX)
    headers = build_headers(b64(iv), md5hex(wrapper), TOKEN)
    headers.update({"appId": APP_ID, "version": VERSION, "platform": "android",
                    "channel": "official", "deviceId": DEVICE_ID,
                    "timestamp": str(int(time.time() * 1000))})
    r = requests.post(url, data=enc_body, headers=headers, timeout=timeout)
    txt = r.text[:400]
    try:
        j = r.json()
    except Exception:
        raise SystemExit("非 JSON 响应 %s %s\n%s" % (r.status_code, url, txt))

    # 响应: bean.Response { 1:random, 2:body(加密), 3:httpStatus }
    code = j.get("code", j.get("status", 0))
    if code not in (0, 200, "0", "200", None):
        print("[!] %s -> code=%s msg=%s" % (api_path, code, j.get("msg") or j.get("message") or ""))
        return None, j
    data = j.get("data") or j.get("result") or {}
    if not isinstance(data, dict):
        return None, j
    enc = data.get("body")
    if not enc:
        return None, j
    # body 是 hex 或 base64 或原始 bytes(json 里通常是 list/hex)
    raw = _to_bytes(enc)
    if raw is None:
        return None, j
    # 用同一把 key/iv 解不开 (iv 每请求随机), 服务端约定: 响应体用请求的 cipher IV 解密
    # 若服务端在响应头回带 iv/checksum 则优先; 这里回退到从 json data 里找
    resp_iv = data.get("iv") or data.get("cipher")
    if resp_iv:
        try:
            riv = _to_bytes(resp_iv)
            if riv and len(riv) == 8:
                return RabbitCipher(key, riv).encrypt_decrypt(raw), j
        except Exception:
            pass
    # 无 IV 信息时按请求 IV 尝试 (部分服务端如此)
    try:
        return RabbitCipher(key, iv).encrypt_decrypt(raw), j
    except Exception:
        return raw, j


def _to_bytes(v):
    if isinstance(v, (bytes, bytearray)):
        return bytes(v)
    if isinstance(v, list):
        try:
            return bytes(v)
        except Exception:
            return None
    if isinstance(v, str):
        s = v.strip()
        try:
            if len(s) % 2 == 0 and all(c in "0123456789abcdefABCDEF" for c in s):
                return bytes.fromhex(s)
        except Exception:
            pass
        import base64
        try:
            return base64.b64decode(s + "=" * (-len(s) % 4))
        except Exception:
            return None
    return None


# ================================================================ 业务
def list_rooms(page: int = 1, menu_id: int = 0):
    """POST /room/list -> (room_ids, user_datas, counts, has_next)"""
    body, j = api_post("/room/list", make_list_request(page, menu_id))
    if body is None:
        return [], [], [], False
    # Response.body -> ListReply { 2: data: ListReplyData { 1:ids, 2:countList, 3:hasNext, 5:resultData } }
    fields = pb_decode(body)
    reply_data = None
    for num, wire, val in fields:
        if num == 2 and wire == 2:
            reply_data = val
            break
    if reply_data is None:
        return [], [], [], False
    ids, counts, users, has_next = [], [], [], False
    for num, wire, val in pb_decode(reply_data):
        if num == 1 and wire == 2:
            ids = pb_unpack_int64(val)
        elif num == 2 and wire == 2:
            counts = pb_unpack_int64(val)
        elif num == 3 and wire == 0:
            has_next = bool(val)
        elif num == 5 and wire == 2:
            users.append(val)
    return ids, users, counts, has_next


def parse_user_data(blob: bytes) -> dict:
    """UserData { 2:avatar, 3:nickname, 4:sex, 5:level, 6:vipLevel, 8:signature, 9:isPodcast, 10:liveIn }"""
    u = {"avatar": "", "nickname": "", "sex": 0, "level": 0, "vipLevel": 0,
         "signature": "", "isPodcast": False, "liveIn": 0}
    for num, wire, val in pb_decode(blob):
        if num in (2, 3, 8) and wire == 2:
            key = {2: "avatar", 3: "nickname", 8: "signature"}[num]
            u[key] = val.decode("utf-8", "replace")
        elif num in (4, 5, 6, 10) and wire == 0:
            key = {4: "sex", 5: "level", 6: "vipLevel", 10: "liveIn"}[num]
            u[key] = val
        elif num == 9 and wire == 0:
            u["isPodcast"] = bool(val)
    return u


def parse_room(blob: bytes) -> dict:
    """Room { 1:id, 2:name, 4:city, 5:cityShow, 12:onAir, 20:attachmentUrl,
              21:playerCount, 24:desc, 25:pic, 28:streamFlag }"""
    r = {"id": 0, "name": "", "city": "", "cityShow": "", "onAir": 0,
         "playerCount": 0, "desc": "", "pic": "", "streamFlag": 0, "attachmentUrl": ""}
    for num, wire, val in pb_decode(blob):
        if num == 1 and wire == 0:
            r["id"] = val
        elif num in (2, 4, 5, 20, 24, 25) and wire == 2:
            key = {2: "name", 4: "city", 5: "cityShow", 20: "attachmentUrl",
                   24: "desc", 25: "pic"}[num]
            r[key] = val.decode("utf-8", "replace")
        elif num in (12, 21, 28) and wire == 0:
            key = {12: "onAir", 21: "playerCount", 28: "streamFlag"}[num]
            r[key] = val
    return r


def parse_video(blob: bytes) -> dict:
    """Video { 8:pushRtmp, 9:playRtmp, 10:playFlv, 11:playHls, 12:liveImage,
              24:agoraChan, 28:onAir, 31:title }"""
    v = {"playRtmp": "", "playFlv": "", "playHls": "", "liveImage": "",
         "agoraChan": "", "title": "", "onAir": 0}
    for num, wire, val in pb_decode(blob):
        if num in (8, 9, 10, 11, 12, 24, 31) and wire == 2:
            key = {8: "pushRtmp", 9: "playRtmp", 10: "playFlv", 11: "playHls",
                   12: "liveImage", 24: "agoraChan", 31: "title"}[num]
            v[key] = val.decode("utf-8", "replace")
        elif num == 28 and wire == 0:
            v["onAir"] = val
    return v


def _walk_find(buf: bytes, matcher, out, depth=0):
    """递归 protobuf 树, 收集 matcher(fields)->dict 非空结果"""
    if depth > 6:
        return
    try:
        fields = pb_decode(buf)
    except Exception:
        return
    m = matcher(fields)
    if m:
        out.append(m)
    for num, wire, val in fields:
        if wire == 2 and len(val) > 2:
            _walk_find(val, matcher, out, depth + 1)


def get_rooms_detail(ids):
    """POST /room/get_info_by_ids -> [Room...]"""
    body, j = api_post("/room/get_info_by_ids", make_get_info_request(ids))
    if body is None:
        return []
    rooms = []
    _walk_find(body, lambda fs: parse_room(fs) if any(n == 25 or n == 2 for n, w, v in fs) else None, rooms)
    # 去重
    seen, uniq = set(), []
    for r in rooms:
        if r["id"] and r["id"] not in seen:
            seen.add(r["id"]); uniq.append(r)
    return uniq


def get_play_urls(room_id):
    """依次尝试 get_info_by_ids / join_room_new, 提取播放地址"""
    urls = {}
    body, j = api_post("/room/get_info_by_ids", make_get_info_request([room_id]))
    if body:
        vids = []
        _walk_find(body, lambda fs: parse_video(fs) if any(n in (9, 10, 11) for n, w, v in fs) else None, vids)
        for v in vids:
            for k in ("playRtmp", "playFlv", "playHls"):
                if v.get(k):
                    urls[k] = v[k]
    if not urls:
        # JoinRoomV2Request { 1: userId }
        body, j = api_post("/room_push/join_room_new", pb_varint(1, 0))
        if body:
            vids = []
            _walk_find(body, lambda fs: parse_video(fs) if any(n in (9, 10, 11) for n, w, v in fs) else None, vids)
            for v in vids:
                for k in ("playRtmp", "playFlv", "playHls"):
                    if v.get(k):
                        urls[k] = v[k]
    return urls


# ================================================================ 播放
def guess_player(url: str) -> str:
    low = url.lower()
    if low.startswith("rtmp"):
        return 'ffplay -fflags nobuffer -flags low_delay -probesize 32 -analyzeduration 0 -i "%s"' % url
    if ".flv" in low:
        return 'ffplay -fflags nobuffer -i "%s"' % url
    if ".m3u8" in low:
        return 'ffplay -i "%s"' % url
    return "VLC/ffplay: " + url


def launch_potplayer(url: str):
    if not os.path.exists(POTPLAYER):
        print("[!] PotPlayer 不存在: %s (用 POTPLAYER 环境变量指定)" % POTPLAYER)
        print("    " + guess_player(url))
        return False
    subprocess.Popen([POTPLAYER, url])
    print("[+] PotPlayer 已启动: %s" % url)
    return True


# ================================================================ CLI
def cmd_rooms(a):
    ids, users, counts, has_next = list_rooms(a.page)
    print("房间数: %d (hasNext=%s)" % (len(ids), has_next))
    details = get_rooms_detail(ids) if ids else []
    dmap = {d["id"]: d for d in details}
    out = []
    for idx, rid in enumerate(ids):
        d = dmap.get(rid, {})
        u = parse_user_data(users[idx]) if idx < len(users) else {}
        out.append({
            "roomId": rid,
            "name": d.get("name") or "",
            "pic": d.get("pic") or "",
            "city": d.get("cityShow") or d.get("city") or "",
            "onAir": d.get("onAir") or 0,
            "playerCount": d.get("playerCount") or (counts[idx] if idx < len(counts) else 0),
            "nickname": u.get("nickname") or "",
            "avatar": u.get("avatar") or "",
        })
    print(json.dumps(out, ensure_ascii=False, indent=2)[:6000])
    return out


def cmd_play(a):
    urls = get_play_urls(a.room_id)
    if not urls:
        print("未拿到播放地址; 房间可能需要 join/websocket 推送, 或为 RTC(agora) 房")
        return
    for k, v in urls.items():
        print(k, "=", v)
        if v.lower().startswith("rtmp") or ".flv" in v.lower() or ".m3u8" in v.lower():
            print("  -> " + guess_player(v))


def cmd_potplayer(a):
    urls = get_play_urls(a.room_id)
    if not urls:
        print("未拿到播放地址")
        return
    url = urls.get("playRtmp") or urls.get("playFlv") or urls.get("playHls") or list(urls.values())[0]
    launch_potplayer(url)


def main():
    import argparse
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("rooms"); s.add_argument("--page", type=int, default=1)
    s = sub.add_parser("ids")
    s = sub.add_parser("play"); s.add_argument("room_id")
    s = sub.add_parser("potplayer"); s.add_argument("room_id")
    a = ap.parse_args()
    if a.cmd == "rooms":
        cmd_rooms(a)
    elif a.cmd == "ids":
        ids, users, counts, _ = list_rooms(1)
        print(json.dumps({"ids": ids, "counts": counts}, ensure_ascii=False))
    elif a.cmd == "play":
        cmd_play(a)
    elif a.cmd == "potplayer":
        cmd_potplayer(a)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
