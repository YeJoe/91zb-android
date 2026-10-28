# BASE 与 TOKEN 获取方案（天美 8.1.7 跑不起来的情况）

> 前提：天美旧版（`com.ufnhzkflua.eyybasftmi` 8.1.7，代码未混淆）和新版在 MuMu 里都闪退；
> 手上能跑的只有同源不同渠道的 `com.l95c2450d7.f68bb4e2d5`（8.5.3，渠道 `ecy`）。
> 本文解决：**在没有天美可运行实例的前提下，怎么拿到能用的 BASE 和 TOKEN。**

---

## ⚠️ 实测更正（2026-10-28，已在 MuMu 15 上跑通取证）

下文 §2/§3 的"找域名"路线**已被实测取代**，请先读 **[`实机运行报告.md`](./实机运行报告.md)**。要点：

| 本文原假设 | 实测结果 |
|---|---|
| BASE 是 `https://api.xxx.com` 这类域名 | **不是域名**，是 `http://127.0.0.1:<随机端口>`，由 `libkiwi.so` 隧道转发；真实域名全程无明文 |
| 拿域名的方式：logcat / 证书反查 | 都不成立（7001 端口是加密隧道，非 TLS）。有效方式是**抓 loopback**（`tcpdump -i lo`）后 `adb forward` 复用该端口 |
| token 是明文 JWT | 是 **JWE 原样**进 `Authorization: Bearer`，不解密、不重签 |
| 路径编码 `base64Url` 去填充 | 必须 **urlsafe + 保留 `=`**；且服务端要求路径 ≥ 11 字符（载荷 ≥ 24 字节） |
| 路径混淆算法通用 | **8.5.3 已改**，8.1.7 的 `reverse(xor(...))` 反推无解 → 需对 `channel.apk` 重跑 blutter |

§4（TOKEN 四个真相）与 §5（闪退定位）仍然有效。

---

## 0. 一句话结论

| 目标 | 能不能静态拿到 | 结论 |
|---|---|---|
| BASE（接口域名） | **不能** | 它是「云层 kiwi 隧道 + 盾配置」在运行时按 **设备 × 渠道** 解析出来的 host，代码里只有 `http://kiwi_*` 占位符。要么从**任一同源 App 的运行进程**里掏解析结果，要么离线重放盾配置接口。 |
| TOKEN（鉴权） | 部分能 | prefs 里的 `MAINUSER_DATA.token` 是 JWE，**客户端不解密，直接 `Bearer ` + 原值发出**（见 §4.1）。所以它本身就是可用 token —— 但属于套壳的账号/渠道。天美自己的 token **不需要登录也能拿**：`/login/quick` 游客登录。 |

最短路径：

```
① 从套壳版掏 BASE（§2，30 分钟，四选一）
② 用 BASE 打 /login/quick 换游客 token（§4.3）
③ python tools/live_client.py rooms 验证（§6）
```

> 注意：套壳版不是天美的"同款套壳"，而是**同一套代码基线的另一个渠道包**。BASE 的解析结果按渠道下发，
> 所以 ①拿到的是 `ecy` 渠道的 host —— 大概率同一套集群（服务端按 `appId/channel/version` 头区分租户，见 §2.5），
> 但**必须用天美的包名/渠道头去验证**，不能直接假设通用。

---

## 1. 证据链：为什么 BASE 不在静态产物里

| 证据 | 位置 | 含义 |
|---|---|---|
| 全量字符串池里没有任何真实 API 域名，只有 `http://kiwi_hd` `http://kiwi_live/tunnel/a033b072?z=1&nobase64=1` `http://kiwi_im/tunnel/open?z=1` `http://kiwi_pay` `http://kiwi_install` `http://kiwi_yx` … | `pp.txt` / `prefs_deep.txt` | 配置表里全是**占位符**，运行时替换 |
| `FlutterYunCengKiwi::initEx(appKey, token)` + `getProxyTcpByDomain(group_name)` | `asm/data_center/live_old/manager/dun_mgr.dart:1859, 2097` | host 由**云层（kiwi）隧道 SDK** 解析，不是 DNS、不是常量 |
| `DunMgr.add(apiUrls.field_13, groupId)` 把 `kiwi_xxx` 映射成真实 tunnel URL | `asm/.../page/web_page/web_page.dart:12900+`（`WebPage::getDunPath`） | 映射表由盾配置下发 |
| `_ApiInfoManager.hosts / _pos / _version` 持久化到 prefs，`_getUri` 只读写缓存 | `asm/.../app/api_urls.dart:1461`（`_getUri`）、`1971`（`tryPrevPos`） | 域名是**登录后才落盘的缓存**，不是编译期常量 |
| `ApiUrls.dunUrl / dunKey / dunKiwiStart` 三个字段运行期 `setString` | `api_urls.dart:3368-3435` | 盾入口地址本身也是下发/继承的 |

**推论**：想拿 BASE 只有两条路 —— ① 在**能跑的进程里**读它的解析结果；② 自己把盾配置接口重放一遍（§3，
其中 AES key 和签名算法已经还原好了）。

---

## 2. 路线 A：从能跑的套壳版里掏 BASE（推荐）

四条子路线，按成本从低到高。**A1 + A2 通常就够了**。

### A1. logcat 关键字（1 分钟）

代码里日志前缀是固定的，直接过滤：

```bat
adb connect 127.0.0.1:7555
adb logcat -c
adb logcat | findstr /i "游戏盾 解析域名地址 尝试取得新的地址 当前标签索引 initEx 连接服务器"
```

命中 `最新api 游戏盾配置:{...}` 或 `_apiUrlsMgr.tryPrevPos 取得新的位置 ...` 就拿到了 host 列表。
（对应 `api_urls.dart` 里的 `ApiUrls.dunKey` / `最新api 游戏盾配置:` / `_apiUrlsMgr.tryPrevPos 取得新的位置` 三条日志。）

### A2. `/proc/net/tcp` + 证书反查（不需要 root 工具、不需要 Frida）

App 一启动就会和真实 API 建连。MuMu 自带 root，直接看连接表，再用 `openssl` 从证书里读域名：

```bat
adb shell su -c "cat /proc/net/tcp /proc/net/tcp6"
```

第 2 列是本地地址、第 3 列是远端地址，格式 `0100007F:1F90` = **小端十六进制 IP : 大端十六进制端口**。
取远端端口 `01BB`(443) / `0050`(80) 的条目，转成点分 IP（Python 一行）：

```python
ip = ".".join(str(int(h[i:i+2],16)) for i in (6,4,2,0))   # 例: 2E1A0A0A -> 10.10.26.46
```

然后**用证书反查域名**（这是关键技巧，TLS 证书的 SAN 就是真实域名）：

```bash
openssl s_client -connect <IP>:443 -servername <IP> </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -ext subjectAltName
```

明文 HTTP（80 端口）的，直接 `curl -sv http://<IP>/` 看 `Host`/跳转；或者对 IP 抓一次 `tcpdump` 看 SNI。

### A3. WebView JS 桥 `getDunUrl`（最准，直接返回真实 origin）

App 给所有 H5 页面注册了桥接方法 `getDunUrl`（→ `WebPage::getDunPath`），
传一个 `kiwi_*` 占位 URL，返回**解析后的真实 URL**：

```js
// 在 App 内任意 H5 页面的 console 里（Chrome: chrome://inspect 附加 WebView）
window.<bridgeName>.postMessage(JSON.stringify({
  method: "getDunUrl",
  params: ["http://kiwi_live/tunnel/a033b072?z=1&nobase64=1"]
}));
```

返回值的 origin 就是 BASE。桥接对象名在同一批注册表里（`web_page.dart:11300+` 的 method map，
`openConfirm / getDunUrl / getLocalUrl / setUiStyle / checkIphoneBind ...`），用 `Object.keys(window)` 找带
`postMessage` 的那个即可。

### A4. Frida（要精确 headers / 加密明文时）

`tools/frida_hook_crypto.js` 里的偏移是 **8.1.7** 的，套壳是 8.5.3，**不能直接用**。要么重新 blutter 套壳版拿新偏移：

```bat
blutter com.l95c2450d7.f68bb4e2d5-8.5.3.apk out853
:: 在 out853/asm 里找同款三个函数并记新地址
::   HttpTunnelLive4::updateSocketParams   (URL + Bearer token)
::   RabbitCipher::encryptDecrypt          (明文/密文)
::   HttpRequest::encryptApiPath           (原始路径)
```

要么退一步只 hook `SharedPreferences` 读 `_ApiInfoManager.hosts` 这个 key（按字符串匹配，不依赖偏移）。

---

## 3. 路线 B：离线重放盾配置（不依赖任何可运行 App）

这条路的**密码学部分已经全部还原**，缺的只是 `dunUrl` 和签名字段顺序 —— 而这俩正好能在路线 A 里顺手抓一次。

### 3.1 已确认的算法

```
响应 = base64 → AES-256-ECB/PKCS7 解密
key  = ASCII("56c51dbc887d0487f942e6fca95108e4")   # 32 字节, 即 AES-256
       证据: api_urls.dart 0x171a6a4 (key 常量) + 0x171a71c ("AES/ECB/PKCS7")
             调用链 _Util.decrypt2Base64 → 去 "\n" → Base64Decoder → _Util.decrypt
明文 = JSON, 形如 {"kiwi_live": "host:port", "kiwi_hd": ..., ...}

请求签名 = md5("<device>.<…>lAqUXLnH3Qkx1NHBL2+o0")[:20]
       证据: api_urls.dart 0x1b08124~0x1b08190
             AllocateArray(10) → 用 "." 连接 → 末尾拼常量 lAqUXLnH3Qkx1NHBL2+o0
             → Utf8Encoder → MD5 → hex → substring(0, 20)
       前置: 0x1b080d0~0x1b080f4 出现 /1000/1000/86400 的整算, 即"自 1970 起的天数"参与拼接
```

### 3.2 用法

```bat
:: 解密一次抓到的响应（粘贴/文件/管道都行）
python tools\dun_replay.py decrypt resp.txt

:: 生成候选签名（device/天数/渠道 三种排布都打出来，跟真实请求比对即可确定顺序）
python tools\dun_replay.py sign --device 29e9eb7c-b442-350b-b325-3427cd26f85b --day 20380 --channel ecy

:: 把抓到的 LoginQuickRequest 十六进制翻成字段号 + 值（换渠道时照着改）
python tools\dun_replay.py pbdecode 0a2432396539656237632d...
```

拿到 `dunUrl` 后整个请求是：

```
GET {dunUrl}?{device|channel|day|key=签名}     ← 字段名用抓包对齐, 无需猜
→ {密文} → dun_replay.py decrypt → {"kiwi_live":"真实host:port"}
```

---

## 4. TOKEN 的四个真相

### 4.1 prefs 里的 JWE **就是** token，不需要解密

`http_tunnel_live4.dart:0x1923efc` 处的构造是：

```
"Bearer " + this.field_23          // field_23 = 主用户对象里的 token 字段(原样)
```

也就是说 `MAINUSER_DATA.token` 里那串 `<JWE>`（JWE，`alg=dir, enc=A128CBC-HS256`）
**不经过任何本地解密**就进了 `Authorization`。我实测过：用盾配置那把 AES key 解 JWE 是**失败**的
（明文是乱码），说明 JWE 的 CEK 在服务端，客户端只是**存和发** —— 这对你是好消息：它可以直接用。

### 4.2 但它不是天美的

`MAINUSER_DATA` 里写死了 `"app_name":"com.l95c2450d7.f68bb4e2d5"`, `"channel_id":"ecy"`。
服务端按渠道记账，拿它当天美的 token 用，最好的结果是 401，最坏是被风控标记。**只能当"验证 BASE 通不通"的临时凭证。**

### 4.3 天美的 token：走游客登录，不用短信

`LoginQuickRequest` 的字段（`login_mgr.dart:0x112ed1c` 的命名参数表，共 15 个）：

```
apnsCode  appName  appVersion  captchaId  channelId  clientToken  deviceType
isPodcast  machineInfo  openChannelId  osVersion  packageName  parentIcode  validate  version
```

其中 `apnsCode` 就是 `flutter.deviceUUID`（同一个 UUID），`appName`/`packageName` 填天美包名，
`channelId` 填 `ecy` 之外的渠道（天美自己的渠道值可从其 `MAINUSER_DATA.channel_id` 或配置表 `channel` 得出）。
字段号用 `dun_replay.py pbdecode` 从套壳版的真实请求里读出来即可，一次对齐永久复用。

游客态还有个便利：**`derive_key` 允许空 token**（`rabbit.py:derive_key(hour, token="")`），
所以哪怕 `/login/quick` 一时调不通，也能先用 `TOKEN=""` 打通 `room/list` 验证 BASE 是否正确。

### 4.4 换 token 必须重算 key

```
key = MD5(SALT + 当前小时 + token)      # 每小时轮换
```

token 一变、或跨小时，`cipher/checksum` 全要重算 —— 这就是为什么"抄一份 header 反复用"必然 401。
`tools/live_client.py` 与 `webui/lib/rabbit.js` 都是每次请求现算的，直接用就行。

---

## 5. 顺带解决：让天美自己跑起来（治本）

`AndroidManifest.xml` 里的事实（旧版 8.1.7）：

| 项 | 值 | 意义 |
|---|---|---|
| `package` | `com.ufnhzkflua.eyybasftmi` | 与套壳不同包 |
| `android:debuggable` | **true** | 能直接 `run-as` / attach，排障极方便 |
| `minSdk / targetSdk` | 24 / 29 | MuMu(Android 12/13) 装得上，**不是 SDK 版本问题** |
| `extractNativeLibs` | true | so 会解到 `lib/`，可核对 ABI |
| `EnableImpeller` | **false** | 走 Skia，不吃 Impeller 的 x86 坑 |
| `application` | `com.KqnHgYerjv` | 混淆过的入口类，闪退多半发生在它或 `libapp.so` 里 |
| 无加固特征 | — | 没看到 jiagu/secneo/bangcle，**不是加固导致的闪退** |

三步定位（`debuggable=true`，日志一定有）：

```bat
:: 1) 装不上的真实原因
adb install -r -t -d duocai-8.1.7.apk

:: 2) 闪退的真实原因（native crash 也在里面）
adb logcat -c && adb logcat *:E AndroidRuntime:E DEBUG:E libc:F | findstr /i "FATAL UnsatisfiedLink abort tombstone"

:: 3) 看 so 的 ABI 是否缺 x86_64
unzip -l duocai-8.1.7.apk | findstr "lib/"
```

四类常见原因与对策：

| 症状 | 原因 | 对策 |
|---|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 只有 `arm64-v8a`，MuMu 是 x86_64 | MuMu 设置里开 **ARM 兼容/翻译**；或换 arm64 云手机；或真机 |
| 启动 1 秒内白屏退出，`libc: Fatal signal 11` in `libflutter.so`/`libapp.so` | 引擎在该 CPU 上跑不动 / 反模拟器 | 优先真机（arm64 + root）；MuMu 关掉"极致性能"再试 |
| 起来就退，日志有"设备异常/环境异常" | 反模拟器/反 root 主动 exit | 真机；或 Frida hook 掉检测（`Build.FINGERPRINT`、`ro.kernel.qemu`、`/proc/cpuinfo`、传感器数量） |
| 能装能起，进首页闪退 | 缺渠道配置/签名校验（服务端下发空配置） | 用 `channel`/`packageName` 对齐渠道；或先只跑游客链路 |

**如果最终只是要 BASE + 游客 token，跑不跑得起来其实无所谓** —— 走路线 A + §4.3 更快。
只有要"天美自己账号的数据"（关注/私信/充值记录）时，才必须把它跑起来。

---

## 6. 拿到之后的 5 分钟验证

```bat
set BASE=https://<A1/A2/A3 得到的 origin>      :: 只要 origin, 不带 /go
set TOKEN=<套壳的 JWE 或 /login/quick 的游客 token>
set DEVICE_ID=29e9eb7c-b442-350b-b325-3427cd26f85b
set VERSION=8.1.7
python tools\live_client.py rooms
```

| 现象 | 判定 | 下一步 |
|---|---|---|
| 返回房间列表 | BASE 正确、加密链路通 | 填 `webui/config.json` 起页面 |
| `401` / `code=1000x` | BASE 通、token 不对/过期/跨渠道 | 走 §4.3 换游客 token |
| 响应解出来是乱码 | BASE 通、但 **SALT 或 version 头**不匹配 | 确认 `version` 头 = App 版本；SALT 用 `pp.txt` 里 `initCipher` 附近的常量覆盖 `KEY_SALT` |
| `404` / HTML 错误页 | origin 对但 `pathPrefix` 不对 | 抓一次真实路径改 `config.json.pathPrefix`（默认 `/go`） |
| 连接超时 / TLS 握手失败 | 拿到的是隧道内网 host，公网直连不通 | 必须走隧道：这一步就只能靠 App 进程内部转发（A3/A4），或抓 App 的明文流量 |

最后一行是这套架构的真实门槛：**`kiwi_*` 指向的是隧道入口，不是公网域名**。如果 A2 反查出来的域名
`ping`/`curl` 不通，说明它只在隧道里可达 —— 那就必须让某个同源 App 跑着，用 mitmproxy/`tcpdump` 抓它
真正发出去的 `POST {host}/go/{base64path}`，把 host 抄下来（同一台设备、同一时段内有效）。

---

## 7. 下一步（按优先级）

1. `A1` logcat 抓一轮，看有没有现成 host 行 —— 有就结束，5 分钟收工。
2. 没有就用 `A2` 从 `/proc/net/tcp` + `openssl s_client` 反查域名。
3. 拿到 origin 后先跑 `live_client.py rooms`（`TOKEN` 先用套壳的 JWE）确认链路，再换游客 token。
4. 只有需要天美账号数据时，才按 §5 修闪退。
