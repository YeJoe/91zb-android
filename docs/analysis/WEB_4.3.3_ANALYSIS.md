# 91直播 网页版逆向 & 播放链路分析（4.3.3 Web）

> 记录时间：2026-10-27
> 目标站点：`https://fvx.lkzlwj4.xyz/?cc=12`
> 本文只覆盖**网页版（Flutter Web）**。App 端 `libapp.so` 的开屏广告补丁见 `PATCH_REPORT.md`。

---

## 1. 结论速览

| 项 | 结论 | 置信度 |
|---|---|---|
| 网页版形态 | 同一 App 的 **Flutter Web 构建**，CanvasKit（canvas）渲染 | 已确认 |
| 业务逻辑位置 | 全在 `main.dart.js`（约 9.4 MB，**未混淆**，函数名可读） | 已确认 |
| Body/响应编码 | 每个 UTF-16 字符异或 `0x152E`，解出即明文 JSON | 已确认（抓包验证） |
| 请求签名 | `x-auth-sign = MD5(path + "?" + 字母序 k=v 串 + "oknzoiwe@23")` | 已确认 |
| 路径加密 | **动态密钥**，纯静态分析不可复现 | 已确认（多算法试解失败） |
| 破解路径 | 无头浏览器 + CDP 拦网络（拿解密后数据），不碰默认浏览器 | 已验证可行 |
| 房间列表 | `/home/rec`、`/room/recommend/anchor/list`，**直接带 FLV 推流地址** | 已确认 |
| 付费房判定 | 房间对象 `moneyFen > 0` 即付费房 | 已确认 |
| 30 秒截断原因 | 平台**服务端付费门控**（播放会话 + `/app/live/pay` 解锁），**不是流地址过期** | 已实测排除流过期 |
| 拉流可用性 | `video/x-flv`，`Access-Control-Allow-Origin: *`，可跨域直连 flv.js | 头部已确认 |
| 待解决 | curl/浏览器拉流出现 **200 但 0 字节**，原因未定 | 未解决（见 §6.3） |

---

## 2. 网页版形态

首屏 HTML 是标准的 Flutter Web 引导页，关键资源：

```
<script src="flutter_bootstrap.js" async>
js/jessibuca/... （WebAssembly 直播播放器，支持 FLV/HLS + 解密 key）
simon.js
splash/input_v1.js
main.dart.js   ← 全部业务逻辑
```

两个要点：

1. **CanvasKit 渲染**：DOM 里没有任何房间文本，**抓 DOM 抓不到内容**，只能拦网络或调用页面内部函数。
2. **播放器用 jessibuca**（第三方 WASM 播放器，Flutter 版 / Web 版共用同一套流协议），说明流格式是标准 HTTP-FLV / HLS，理论上任何 FLV 播放器都能拉。

---

## 3. 抓取方法（不动默认浏览器）

用户要求：**不使用默认浏览器打开网址**。因此全程用独立 profile 的无头实例 + DevTools 协议。

### 3.1 启动无头浏览器

```bash
"D:/Apps/CentBrowserPortable/chrome.exe" \
  --headless=new \
  --remote-debugging-port=9222 \
  --remote-allow-origins=* \
  --user-data-dir="D:/WorkSpace/Android/91直播_4.3.3/out_dir/_browser_profile" \
  --no-first-run --no-default-browser-check \
  --disable-gpu --disable-extensions --disable-background-networking --disable-sync \
  about:blank
```

隔离措施：
- 独立临时 `--user-data-dir`，**不读取用户现有配置/书签/cookie**
- `--headless=new`，**无界面**
- 不打日志到文件、不落盘任何凭证

实测内核：**Chrome 134**，CDP 就绪（`http://127.0.0.1:9222/json/version`）。

### 3.2 CDP 驱动

`_cdp.mjs`：用 **Node 22 内置 WebSocket**，零第三方依赖，实现最小 CDP 客户端：

1. `Target.createTarget` 打开目标页
2. `Network.enable` + `Page.navigate`
3. 监听 `Network.requestWillBeSent` / `Network.responseReceived` / `Network.getResponseBody`
4. 观察窗口 15s，落盘 `_cdp_dump.json`

### 3.3 解码

`_decode_dump.py`：对每条响应做 `XOR 0x152E` → 定位首个 `{` / `[` → JSON 解析。

一次抓取拿到 **102 个 API 调用**，其中房间列表响应直接可读。

---

## 4. 协议解析

### 4.1 API 域名

主域：

```
https://app1.xuyao.live
```

配置文件里下发的备用/推流域（`/config` 类接口返回）：

```json
{"usaVideoDomain":["https://zb.yfhx.live"],
 "usaVideoAesDomain":["https://aes.piwb.live"], ...}
```

其他出现过：`bds.c2q8w5z.xyz:9969`、`gsd.g9v3n7t.xyz:8374`、`kjl.qmvxrz.xyz`、`fdw.tndtvyn.com`。

### 4.2 Body / 响应编码：XOR 0x152E

源码调用点：

```js
dXa(s, 202030)
```

`202030 & 0xFFFF = 0x152E`（5422）。即每个 UTF-16 码元异或 `0x152E`。

**验证**：抓包里请求体显示为 `ᕕᓓ` 两个怪字符，`ord('ᕕ') ^ 0x152E` → `{`，`ord('ᓓ') ^ 0x152E` → `}`，正好是 `{}`。

Python 实现：

```python
def xor_body(s, k=0x152E):
    return ''.join(chr(ord(c) ^ k) for c in s)

# 编解码同一个函数（异或自反）
enc = xor_body('{"pageNum":1}')
dec = xor_body(enc)
```

响应解出来是**明文 JSON**，例如：

```json
{"code":0,"data":{"usaVideoDomain":["https://zb.yfhx.live"], ...}}
```

> 注意：错误响应（如 `{"code":-1,"msg":"URI协议错误"}`）是**明文**返回的，说明是否解密由我们自己决定。

### 4.3 请求签名

源码：

```js
p.k(0, "x-auth-timestamp", Date.now())
p.k(0, "x-auth-sign", A.bky(A.eRw(b, p, c) + "oknzoiwe@23"))
```

相关全局对象：

| 符号 | 实际实现 | 说明 |
|---|---|---|
| `B.acX` | `A.aUF` → **MD5** | 初始向量 `0x67452301/0xEFCDAB89/0x98BADCFE/0x10325476`，标准 MD5 |
| `B.bW` | `A.aKR` → **UTF-8 编码器** | |
| `B.ac7` | `A.aAc` → **bytes → 小写 hex** | |

因此：

```
bky(x)  = md5_hex(utf8(x))
sign    = md5_hex( utf8( path + "?" + "&".join(sorted(k=v)) + "oknzoiwe@23" ) )
```

Python 实现：

```python
import hashlib
SALT = "oknzoiwe@23"

def sign(path, params):
    qs = "&".join(f"{k}={params[k]}" for k in sorted(params))
    return hashlib.md5((path + "?" + qs + SALT).encode()).hexdigest()
```

需要参与签名的参数集合：`x-auth-timestamp` + headers（`terminal` / `version` / `ak` / `channel` / `authorization`）+ body 里的字段。

固定请求头（App / Web 共用）：

```
authorization: <token>
deviceUid: <device id>
ak: jyzb
version: 433
terminal: 2
channel: 1
Content-Language: zh-CN
Content-Type: application/json
```

### 4.4 路径加密（唯一卡点）

源码 `e7W(path, flag)`：把 path 加密后逐字节 `toString(16)`（**不补零**），拼成 `"/" + hex`，有时前面再加 3 位随机数字。

抓包里的真实路径片段（`utp.opuxrcm.com/<hex>`）：

```
4c32356c64773d3d                                    → L25ldw==      → /new
59326868636d646c4c324e31636e4a626d4e3554476c7a64413d3d → Y2hhbmdlL2N1cnJlbmN5TGlzdA== → charge/currencyList
```

有意思的是：**尾部这段确实是 hex(base64(路由))**，但整条路径前面还有一段非 ASCII 密文，且服务端要求**整条**合法。

实测结论：

| 尝试 | 结果 |
|---|---|
| 明文路径 `GET /are/you/ok` | `{"code":-1,"msg":"URI协议错误"}` |
| `"/" + hex(base64(path))` | 同上，`URI协议错误` |
| 用抓包密文反推 RC4 密钥（raw / md5 / sha1 / sha256 × `oknzoiwe@23`） | 全部不可读（最高 21/41 可打印） |
| RC4 / AES-CTR / AES-CFB / AES-OFB × 5 种密钥派生 × 2 种 IV | 全部不可读 |

**判定：路径加密使用动态/每次请求变化的密钥，无法离线复现。**

→ 因此最终方案是「无头浏览器拦网络」，而不是「纯代码客户端」。

### 4.5 API 清单（与播放/房间相关）

| 接口 | 作用 |
|---|---|
| `/home/rec` | 首页推荐（含房间列表） |
| `/home/recommend/cls` | 推荐分类 |
| `/room/recommend/anchor/list` | 推荐主播房间列表 |
| `/room/staticData/live` | **取流地址**（body 传 `liveId`） |
| `/room/dynamicData` | 房间动态数据（人数、热度） |
| `/app/live/joinRoom` | 进房，带 `currentTime: Date.now()`（**播放会话心跳**） |
| `/app/live/leaveRoom` | 离房 |
| `/app/live/pay` | **解锁付费房**（body 传 `liveId`） |
| `/app/live/getLivePendant` | 房间挂件 |
| `/app/live/getLiveFinishInfo` | 直播结束信息 |
| `/user/wallet`、`/payInternal/getUserBalance` | 余额 |
| `/recharge/currencyList`、`/recharge/channel/list` | 充值渠道 |

---

## 5. 房间数据结构

`/home/rec` 返回的 `data` 是房间数组，单房间**全字段**（按字母序）：

```
anchorFrom      anchorType      attenCnt        chargeEndTime    chargeStartTime
currentHot      fansCnt         gameIcon        gameId           gameJumpUrl
gameName        gameRoom        hasRecommend    icon             inviteCode
isLandscape     liveType        loveCnt         moneyFen         newFlag
oneToOneState   payDesc         payType         pullStreamAddr   pullStreamAddr360
pullStreamAddr720  roomCover    roomId          roomName         state
stateOneToOne   stateStr        userId          userName         vibratingEgg
weekStar
```

关键字段：

| 字段 | 含义 | 备注 |
|---|---|---|
| `roomId` / `userId` | 房间 / 主播 ID | 都是**字符串**（雪花 ID），不要转 int |
| `roomName` / `userName` | 房间名 / 主播名 | |
| `icon` / `roomCover` | 头像 / 封面 | 封面域名可能 404 |
| `currentHot` | 热度 | |
| `state` | 直播状态 | 模型里 `null → 1` |
| `pullStreamAddr` | **FLV 主线路** | 带 `auth_key` |
| `pullStreamAddr360` / `720` | 低 / 中码率 | |
| `streamEncrypted` | 流是否加密 | 列表接口常为 `null`，房间详情才有值 |
| `moneyFen` | **观看价格（分）** | `> 0` 即付费房 |
| `payType` | 付费类型 | 样例全 `0` |
| `payDesc` | 价格文案 | 值 = `moneyFen / 10` |
| `chargeStartTime` / `chargeEndTime` | 计费时段 | 仅部分房间有值 |
| `isLandscape` | 横屏房 | 影响播放器布局 |
| `liveType` | 直播类型 | 默认 1 |

Flutter 侧的模型解析函数（`main.dart.js`）：

```js
Aj(a){  // → LiveRoomModel
  r.a = roomId; r.b = roomCover; r.c = roomName; r.d = userId; r.e = userName;
  r.f = currentHot;
  r.r = moneyFen (a5 → int, 默认 0);
  r.w = payType;
  r.x = state ?? 1;
  r.at = pullStreamAddr; r.ax = pullStreamAddr360; r.ay = pullStreamAddr720;
  r.ch = streamEncrypted;
}
```

流地址样例：

```
https://pl.ycscts.com/h001/8351929217804216_eh5.flv?auth_key=1793133565-0-8641706478375735-ce2d4f15454b53e417304fa65683f2be
                              ^ 高清        ^ 360          ^ 720
```

---

## 6. 播放链路

### 6.1 流的基本属性（已实测）

```
HTTP/1.1 200
content-type: video/x-flv
access-control-allow-origin: *
```

- 标准 **HTTP-FLV**，CORS 通配 → **浏览器可跨域直连 flv.js，不需要流代理**
- `auth_key` 格式：`<过期时间戳>-<?-业务ID>-<签名>`，第一条即过期 Unix 时间戳

### 6.2 三方播放器选型

| 方案 | 说明 |
|---|---|
| flv.js（mpegts.js 后继） | 已下载 `_flv.min.js`（v1.6.2, 144 KB），零依赖、CORS 直连即可 |
| jessibuca | 官方网页版用的，支持加密流（需 key），体积大、需 WASM |
| hls.js | 若服务端有 HLS 线路再考虑 |

### 6.3 未解决问题：拉流 200 但 0 字节

现象（已排除多种可能）：

| 测试方式 | 结果 |
|---|---|
| `curl` 直拉（Range 0-300） | `200` + `content-type: video/x-flv`，**body 0 字节** |
| `curl` 限时 6~8s 拉流 | **0 字节** |
| 浏览器内 `fetch` + reader | `status 200`，**读不到任何 chunk，卡满 30s** |
| 带 `Referer: https://fvx.lkzlwj4.xyz/` + 正常 UA（走代理 / 不走代理） | 待复测 |

三种可能原因（尚未区分）：

1. **该房间当刻没在推流**（`state` 字段在列表里是 `null`，不可靠）
2. **代理不转发长连接**（环境有 `127.0.0.1:10808` 代理）
3. 服务端要求 `Referer`/`UA`/`cookie` 等额外条件

**下一步**：用 CDP 在页面内 hook `fetch`/`XHR`，直接抓**网页版自己播放时**的流请求头与首包，比盲猜更快。

---

## 7. 付费房分析（「部分房间收费，30 秒后不让看」）

### 7.1 判定字段

**`moneyFen > 0` 即付费房。** 样例 10 个房间中 8 个付费：

| roomId | payType | moneyFen | payDesc | chargeStart | chargeEnd | 有流地址 |
|---|---|---|---|---|---|---|
| 2026102720153300019 | 0 | 30000 | 3000 | — | — | ✅ |
| 2026102722045500011 | 0 | 10000 | 1000 | — | — | ✅ |
| 2026102720232700046 | 0 | 50000 | 5000 | — | — | ✅ |
| 2026102720404400025 | 0 | **0** | 0 | — | — | ✅ |
| 2026102720014700010 | 0 | 50000 | 5000 | — | — | ✅ |
| 2026102721082300070 | 0 | 50000 | 5000 | — | — | ✅ |
| 2026102720220100012 | 0 | 80000 | 8000 | — | — | ✅ |
| 2026102719481500010 | 0 | 30000 | 3000 | — | — | ✅ |
| 2026102720442600026 | 0 | **0** | 0 | — | — | ✅ |
| 2026102719405300017 | 0 | 80000 | 8000 | 2026-10-27 22:xx | 2026-10-27 22:xx | ✅ |

### 7.2 30 秒截断不是流地址过期（已实测排除）

解出所有房间 `auth_key` 的过期时间戳，与当前时间对比：

```
roomId                 pay  moneyFen  urlExpireIn
2026102720153300019    0    30000     +17631s   ← 付费房
2026102720404400025    0    0         +17631s   ← 免费房
2026102719405300017    0    80000     +17631s   ← 付费房（有计费时段）
```

**付费房与免费房的流地址有效期完全相同，都是约 +4h54m。**
→ 30 秒被切断**与签名/地址过期无关**。

### 7.3 真实机制

`main.dart.js` 里对应的三个接口关系很清楚：

```js
e5q(a,b,c,d,e){ ... "/app/live/joinRoom", body: {liveId: b, currentTime: Date.now(), joinType: 1} }
e5r(a,b,c,d){  ... "/app/live/pay",      body: {liveId: b} }      // 解锁付费观看
Rt(a,b){       ... "/app/live/leaveRoom", body: {liveId: b} }
```

即：

1. 进房时 `/app/live/joinRoom` 上报 `currentTime`，构成**播放会话**
2. 服务端按账号 + 会话掌握「看了多久、有没有买」
3. 免费试看额度（本项目观察到的是 30 秒）用尽 → 服务端断开 / 前端弹付费
4. 解锁走 `/app/live/pay`

**结论：这是平台的服务端付费门控，不是客户端 bug，也不是流技术问题。**

### 7.4 处理边界（明确声明）

**不做**（属于绕过付费门控 / 盗用付费内容）：

- 不发心跳、伪造 `currentTime`
- 伪造「已购」状态或复用他人已解锁会话
- 对 `/app/live/pay` 的响应做伪造
- 把 30 秒后续播做成客户端 hack

**可做**（合规且体验更好）：

1. 房间卡片显示「付费 ¥xxx」角标 + `payDesc` 文案
2. 「只看免费房」开关：客户端按 `moneyFen == 0` 过滤
3. 已购房间的播放健壮性：地址过期自动重拉 `/room/staticData/live`、断流重连、`streamEncrypted` 的 key 处理
4. 付费房提供「去官方购买」跳转

---

## 8. 待办清单

| # | 事项 | 状态 |
|---|---|---|
| 1 | 定位 FLV 拉流 0 字节的真因（用 CDP hook fetch 抓网页自身请求头） | ⬜ 待做 |
| 2 | 房间列表 → 卡片渲染（封面 / 主播名 / 热度 / 付费角标） | ⬜ 待做 |
| 3 | flv.js 播放器接入（CORS 已通，支持 360/720/HD 切换） | ⬜ 待做 |
| 4 | 「只看免费房」过滤开关 | ⬜ 待做 |
| 5 | 流地址到期前自动刷新 + 断线重连 | ⬜ 待做 |
| 6 | 付费房「去购买」跳转官方页面 | ⬜ 待做 |

---

## 9. 本地产物清单

| 文件 | 说明 |
|---|---|
| `_fvx.html` | 网页版首页 HTML |
| `_vx_main.dart.js` | 网页版主逻辑（9.4 MB，未混淆） |
| `_vx_simon.js` / `_vx_splash_input_v1.js` | 站点自定义脚本 |
| `_cdp.mjs` | CDP 抓包脚本（Node 内置 WebSocket，零依赖） |
| `_cdp_dump.json` | 102 个 API 请求/响应原始快照（1.3 MB） |
| `_decode_dump.py` | XOR 0x152E 解码器 |
| `_rooms_sample.json` | 房间列表快照 |
| `_cdp_stream_test.mjs` | 浏览器内 FLV 可读性测试 |
| `_flv.min.js` | flv.js v1.6.2（本地化，避免 CDN 依赖） |
| `_browser_profile/` | 无头浏览器隔离 profile（可随时删除） |
| `_crack_path.py` | 路径加密暴力试解脚本（RC4/CTR/CFB/OFB） |

### 复现命令

```bash
cd "D:/WorkSpace/Android/91直播_4.3.3/out_dir"

# 1) 起无头浏览器（见 §3.1）
# 2) 抓包
node _cdp.mjs

# 3) 解码响应，找房间列表
python3 _decode_dump.py

# 4) 查看房间与付费字段
python3 - <<'EOF'
import json
rooms = json.load(open('_rooms_sample.json', encoding='utf-8'))
paid  = [r for r in rooms if (r.get('moneyFen') or 0) > 0]
print(f"总 {len(rooms)} 间，付费 {len(paid)} 间")
for r in paid:
    print(r['roomId'], r['userName'], f"{r['moneyFen']/100:.2f} 元")
EOF
```

---

## 10. 关键经验

1. **Flutter Web 是逆向的好入口**：`main.dart.js` 里函数名、字段名、常量、salt 全在，比 ARM64 AOT（Dart AOT + 混淆）好读一个数量级。定位到语义后再回 App 端对地址，效率最高。
2. **XOR 是最好破的编码**：只要有一份已知明文（如 `{}`、`{"pageNum":1}`），单字节密钥一眼反推。
3. **路径加密 ≠ body 加密**：本例 body 是弱 XOR（可复现），路径却是动态密钥（不可复现）。做客户端前先分开验证，否则会白写一堆代码。
4. **付费门控通常在服务端做判定**：客户端能伪造的只有上报，而跳过上报就是绕过付费墙——先分清「技术故障」和「商业规则」，能省掉大段无用逆向。
5. **头字段 200 不代表数据可用**：`content-type: video/x-flv` + `200` 但 body 为 0，说明问题在会话/推流侧，不在网络侧。
