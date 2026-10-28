# 91直播 安卓客户端 制作方案（可交付版）

> 目标：自建 Android App，能浏览直播间、正常播放（含 1V1 未开演房间）、**保留弹幕**。
> 本文件是给「另一个代码生成客户端」的实施规格，也是 GitHub Actions 出包的依据。
>
> 标注约定：**【已验证】** = 本人实测通过；**【待验证】** = 有依据但未跑通，必须先验证再动工。

---

## 0. 先看结论

| 问题 | 结论 |
|---|---|
| 能否自建 App | 能 |
| 播放可行性 | 能，但**必须经过转码服务**（原因见 §3） |
| 弹幕 | 有 WebSocket 通道，协议**部分已解**，需先补验证（§5） |
| 最稳的落地形态 | **WebView 壳 + 自建页面**（弹幕在 JS 里实现一次，三端复用） |
| 原生形态 | 可行（Media3 + ExoPlayer），但工作量与风险都更高 |
| 本机能否直接编译 APK | **不能**：无 Android SDK / gradle / javac，必须用 GitHub Actions（§7） |

推荐路线：**先出 WebView 版**（1～2 天可跑通），再按需升级原生。

---

## 1. 已确认的接口事实（实现依据）

### 1.1 三道加密（全部【已验证】）

**① 路径混淆**

```python
token = "/" + prefix + "".join(f"{ord(c):x}" for c in base64.b64encode(path.encode()).decode())
# prefix = 3 个随机十六进制字符（服务端忽略，长度必须是奇数）
# 反解：从偏移 0..19 逐个尝试，取能 base64 解出 "/" 开头可打印字符串的那个
```

**② 签名**

```python
d = {"ak": "jyzb", "authorization": TOKEN, "channel": "12",
     "terminal": "3", "version": "436", "x-auth-timestamp": ts}
d.update(业务参数)                       # 全部参与签名
src = path + "?" + "&".join(f"{k}={d[k]}" for k in sorted(d))
x_auth_sign = md5(src + "oknzoiwe@23")   # 32 位小写十六进制
```

- `sorted()` 按 **code unit** 升序
- 值是对象/数组时用紧凑 JSON（`separators=(",",":")`），标量用 `str()`
- `content-language`、`deviceuid` 参与发送但**不参与签名**

**③ 报文 xorCode（请求体 / 响应体同一算法，自逆）**

```python
xor(s) = "".join(chr((ord(c) ^ 0x152E) & 0xFFFF) for c in s)
# 发送：xor(json).encode("utf-8")
# 接收：raw.decode("utf-8","replace") 后 xor；判据：首字符码点 > 0x1400
```

**请求头**：`ak / authorization / channel / terminal / version / deviceuid /
content-language / x-auth-timestamp / x-auth-sign / content-type: application/json`

**域名池**：`https://utp.opuxrcm.com`、`https://ytt.jvjzmc.com`（可互换）

### 1.2 关键接口

| 用途 | 路径 | 参数 |
|---|---|---|
| 分类列表 | `/home/recommend/cls` | `{}` |
| 房间列表 | `/home/default/cls/query` | `{type, pageNo, pageSize}`，**pageSize ≤ 20**，超过静默返回空 |
| 全局参数 | `/param/list` | `{}` |
| 房间兜底 | `/home/rec` | `{type}`，无分页，每类固定 10 条 |

- `type` 取分类里的 **`fixedType`**（不是 `id`）
- 分类实测：关注1 / 热门2 / 颜值0 / 新人4 / 收费5 / 游戏6 / 帖子9 / 1V1表演10

房间对象关键字段：`roomId userId userName roomName currentHot roomCover
pullStreamAddr pullStreamAddr360 pullStreamAddr720 liveType payType moneyFen
oneToOneState stateOneToOne`

`/param/list` 里对本项目有用的字段【已验证】：
`previewTime=30`、`hlwCustomerAppSecret`、`webSocketDomain`、`interfaceDomain`

### 1.3 播放地址

```
https://pl.ycscts.com/h001/<streamid>_eh5.flv?auth_key=<exp>-0-<id>-<sig>
                                    _b3.flv   (_eh5=原画, _b3=720P, _b1=360P)
```

- `auth_key` 第一段是**过期 unix 秒**，实测有效期 **300 分钟**
- **CORS 全开**：`Access-Control-Allow-Origin: *`，GET 简单请求，无预检
- 编码：**HEVC / H.265**（FLV codecId=12），音频 AAC

---

## 2. 限制现状（决定要不要做额外处理）

| 限制 | 性质 | 能否绕过 |
|---|---|---|
| 30 秒试看 | **纯客户端**：读 `previewTime=30` 自己计时停播 | **能**，不跑那段逻辑即可 |
| 收费房门票 | 服务端仍下发流地址 | **能**，直接播 |
| 1V1 表演中（`oneToOneState=2`） | **服务端不下发**：`_one_` 流三种请求方式均 0 字节 | **不能**，需真实购买 |

结论：App 只需忽略客户端的试看计时，其余不必特殊处理。`oneToOneState==2` 的房间要在 UI 上明确提示，避免用户误以为 App 坏了。

---

## 3. 播放架构（核心决策）

### 3.1 为什么不能直连

- 浏览器/WebView 的 MSE **不支持 HEVC** → `CodecUnsupported`
- 手机芯片本身能硬解 HEVC，但**不吃 FLV 容器**
- 我实测过「FLV→TS 只换容器（`-c copy`）」这条路，**不可行**：

| 方案 | 实测吞吐 | 结果 |
|---|---|---|
| `/proxy` 原样转发 | 259 KB/s | 正常 |
| `-c copy -map 0:v:0 -map 0:a:0` | 6–29 KB/s | 提前退出，TS 里 SPS 缺失 |
| `-c copy -bsf:v hevc_mp4toannexb` | 21–38 KB/s | 仍不完整 |
| `-c:v libx264` 转码 | **600+ KB/s** | 稳定可用 |

原因：该 FLV 除 HEVC 主轨外还有一条 **codecId=13 的未知视频轨**，且 HEVC 帧格式转换后 SPS 丢失。

### 3.2 因此采用：转码服务 + H.264

```
手机 App  --HTTP-->  你的服务(serve.py + ffmpeg)  --HTTPS-->  源站 FLV(HEVC)
                        └─ 实时转 H.264/AAC 的 MPEG-TS
```

- 服务端已实现并验证：`/ts?u=<flv>&q=720` → `video/mp2t`，H.264 408×720 + AAC
- 手机端只需能播 MPEG-TS/H.264 —— 这是**所有设备都支持**的组合
- 代价：服务端 CPU（12 核实测一路 720p 很轻松），画质有损

### 3.3 部署位置三选一

| 位置 | 说明 | 适用 |
|---|---|---|
| 家里 PC（局域网） | `http://<PC内网IP>:8899` | 只在局域网用，最简单 |
| 云服务器 | 需公网 IP + 反代 + HTTPS | 随时随地用，需备案/带宽 |
| 内网穿透 | frp / Cloudflare Tunnel | 折中，无需公网 IP |

**App 里播放地址必须可配置**，不要硬编码。

---

## 4. App 设计（两条路线，先做 A）

### 4.1 路线 A：WebView 壳（推荐先做）

App 只有一个 Activity，加载自建页面：

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webChromeClient = object : WebChromeClient() {
            // 全屏播放
            override fun onShowCustomView(v: View, cb: CustomViewCallback) { }
            override fun onHideCustomView() { }
        }
        web.loadUrl(BuildConfig.PAGE_URL)   // 例如 http://192.168.1.10:8899/
    }
}
```

- 好处：现有 `web/index.html` 直接复用，弹幕用 JS 写一次三端通用
- 需要在页面里补的：弹幕（§5）
- 必须允许明文 HTTP：`android:usesCleartextTraffic="true"`

### 4.2 路线 B：原生（Media3 + ExoPlayer）

```kotlin
val player = ExoPlayer.Builder(ctx).build()
player.setMediaItem(MediaItem.fromUri("$SERVER/ts?u=${Uri.encode(flvUrl)}&q=720"))
player.prepare()
player.play()
```

- Media3 自带 `MpegTsExtractor`，H.264 走系统硬解，无需额外依赖
- 弹幕：自定义 `DanmakuView` 覆盖在 `PlayerView` 上
- 工作量比 A 大 3～5 倍，且弹幕协议未定时风险高

### 4.3 依赖与配置

```gradle
android {
    compileSdk 34
    defaultConfig { minSdk 23; targetSdk 34 }
    buildFeatures { buildConfig true }
}
dependencies {
    implementation "androidx.media3:media3-exoplayer:1.4.1"
    implementation "androidx.media3:media3-ui:1.4.1"
    implementation "androidx.appcompat:appcompat:1.7.0"
    // 弹幕可用开源库，或自绘
    implementation "com.github.bolaware:motionlayoutcircularcarousel:1.0" // 示例，可换
}
```

`AndroidManifest.xml` 必须：

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<application android:usesCleartextTraffic="true" ...>
```

---

## 5. 弹幕（唯一未完成项）

### 5.1 已知信息【部分已验证】

- 通道：WebSocket，域名来自 `/param/list` 的 `webSocketDomain`
  实测值：`wss://yzj.opuxrcm.com/live`、`wss://zcp.sid1688.com/live`
  另见 `wss://tinode.jjzb2.live`（IM 主通道）
- 客户端实现：`web_socket_channel` 的 `IOWebSocketChannel.connect(uri)`
- 加密：AES，密钥由
  `md5(hlwCustomerAppSecret + "#@^@#" + 时间戳)` 取 `substring(8, …)` 得到（6.24 反编译可见 `#@^@#` 与 `encodeMd5`）
- 帧为 Base64 字符串，长度多为 16 的倍数
- 帧内明文结构（已见）：`{"type":"1002","toLiveId":"..."}`（进房）、
  `{"type":"1007"}`（退房）、`{"type":"1003",...}`（消息，疑为弹幕）

### 5.2 必须先做的验证（Phase 0）

**这一步不做完，不要写弹幕代码。**

1. 手机装官方 App，进任意直播间
2. PC 用 mitmproxy 抓包（HTTPS 需装证书；若 App 有 SSL Pinning，改用 Frida 关掉校验）
3. 过滤 WebSocket，记录：
   - 完整 URL（含 query 里的 token/时间戳参数）
   - 握手请求头
   - **客户端发出的前 3 帧**（进房帧）原文
   - **服务端推送的弹幕帧**原文
4. 用 Python 按 §5.1 的算法解一帧，确认密钥/IV 假设
5. 把结果填进下面这张表再继续

| 项 | 值 |
|---|---|
| WS URL | 待填 |
| 握手头 | 待填 |
| 进房帧明文 | 待填 |
| 弹幕帧明文 | 待填 |
| AES 模式/密钥/IV | 待填 |
| 心跳间隔 | 待填 |

### 5.3 若协议短期啃不下来

降级方案，仍然能交付：
- 弹幕区做**只读展示**：直接复用官方网页版的弹幕渲染（WebView 内嵌一段官方 H5）
- 或先上线「无弹幕」版本，弹幕留作 v2

---

## 6. 服务端接口（已实现，无需再开发）

`serve.py` 已提供以下路由，App 端**不必实现三道加密**：

| 路由 | 说明 |
|---|---|
| `GET /health` | `{"ok":true,"ffmpeg":"…","rooms":54,"hls_sessions":0}` |
| `GET /api/rooms` | 精简房间列表，**已含可直接播放的地址** |
| `GET /ts?u=<flv>&q=720` | 实时转码 → H.264/AAC 的 MPEG-TS |
| `GET /proxy?u=<flv>` | 原样转发（排障用） |
| `GET /status` | 调试信息 |

`/api/rooms` 每个房间返回：

```json
{
  "roomId": "...", "userId": "...", "userName": "...", "roomName": "...",
  "hot": 2001, "cover": "/114/xxx.jpg", "liveType": 1,
  "payType": 0, "moneyFen": 20000, "oneToOneState": 1,
  "cats": ["热门", "颜值"],
  "playPath": "/ts?u=<urlencoded flv>&q=720",
  "playUrl":  "http://<当前Host>/ts?u=...",
  "rawFlv":   "https://pl.ycscts.com/..."
}
```

- **App 用 `playPath` 拼自己的服务器地址**，不要直接用 `playUrl`（那是按请求 Host 生成的，局域网访问时会变成 127.0.0.1）
- `oneToOneState == 2` 时 `playPath` 仍存在但播放会失败——UI 上要先判断并提示

刷新房间数据仍由 PC 上的 `启动网页.bat` 完成（token 约 5 小时过期）。

> 若要求「完全脱离 PC」，App 必须自行实现 §1.1 的三道加密 + 定期刷新；
> Kotlin 约 300 行，但 token 仍需人工提供。

---

## 7. GitHub Actions 出 APK

仓库结构建议：

```
repo/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ kotlin/.../MainActivity.kt
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle/wrapper/gradle-wrapper.properties
└─ .github/workflows/build.yml
```

```yaml
name: build-apk
on:
  push: { branches: [ main ] }
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v3
      - name: Build debug APK
        run: |
          chmod +x ./gradlew
          ./gradlew assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

要点：
- `gradle-wrapper.jar` **必须提交**（用 `gradle wrapper` 本地生成一次）
- 用自己的签名就在 workflow 里加 keystore（放 Secrets：`KS_B64`、`KS_PASS`、`KEY_ALIAS`），
  `base64 -d` 还原后 `apksigner` 签名
- 页面地址用 `buildConfigField("String","PAGE_URL","\"http://...\"")` 注入，避免改代码

---

## 8. 验收清单

| # | 项 | 通过标准 |
|---|---|---|
| 1 | 房间列表 | 分类正确、分页正常、能搜到指定主播 |
| 2 | 普通房播放 | 出画面 + 有声音，持续 > 2 分钟不断 |
| 3 | 30 秒限制 | 播放到 60 秒仍继续 |
| 4 | 1V1 未开演 | 能正常播放 |
| 5 | 1V1 表演中 | 明确提示「表演中/需购买」，不卡死 |
| 6 | 弹幕 | 能收到并滚动显示（若 Phase 0 未通过则跳过） |
| 7 | 弱网 | 断流后自动重连 |
| 8 | 后台/切屏 | 不崩溃，返回后能恢复 |

---

## 9. 风险与硬约束

1. **`auth_key` 5 小时过期** —— 数据必须定期刷新，App 播旧地址会 403。
2. **服务端 CPU** —— 每路转码约占 1～2 核；同时多路要控并发。
3. **明文 HTTP** —— 局域网可用；放公网建议加 HTTPS，否则 Android 9+ 需 `usesCleartextTraffic`。
4. **1V1 表演内容不可得** —— 已在服务端层面验证，不是客户端能解决的。
5. **接口可能变** —— `version` 已从 433 走到 436；路径/签名字段若变动需重新逆向。
6. **不要用 `-c copy` 转封装** —— §3.1 已实测失败，别再走这条路。

---

## 10. 原 app 是怎么实现的（反编译证据）

证据来源：6.24 未混淆版 blutter 产物 `out_dir/asm/`（原始包 `package:jj_live/...`）。

### 10.1 直播画面：自研插件，不是 ExoPlayer

| 事实 | 证据 |
|---|---|
| 通道名 | `MethodChannel("LivePlayer")`（pp.txt 中 `Obj!MethodChannel` 紧邻字符串 `"LivePlayer"`） |
| 调用方式 | `impl/client_live_player.dart` 内所有操作均为 `MethodChannel.invokeMethod` |
| Dart 侧方法 | `init / play / pause / resume / release / reset / mute / isMute / replay / checkPlayingUrl / addUrlStrs / setInterfaceDirection` |
| 原生回调 | `onStateChanged`、`onIosScreenShot` |
| 渲染方式 | `audience_live_room_view.dart` 的 `_buildTextureView()` → 外部纹理（Texture）交给 Flutter 合成 |

同时确认：全部第三方包里**没有** `flutter_vlc_player` / `media_kit` / `fvp` / `ijkplayer` /
`txplayer` / `aliplayer`；`video_player`（ExoPlayer）只出现在
`dialog/view/chat_video_view.dart`，用于**聊天里的短视频**，与直播间无关。

→ 直播间画面由团队自研原生播放器承担，通过平台通道交给 Flutter 显示。

### 10.2 它为什么能播 HEVC

Media3 的 `VideoTagPayloadReader` 写死只支持 `VIDEO_CODEC_AVC = 7`，遇到 codecId=12（HEVC）
直接抛 `UnsupportedFormatException`。原 app 不使用这条链路，因此不受限；
HEVC 由原生层自行解码（最合理推断是 MediaCodec 硬件解码，
但原生实现不在 `libapp.so` 内，本仓库无法进一步证实）。

### 10.3 弹幕：纯 Flutter 组件，与播放器解耦

- `page/live/barrage/barrage_view.dart`：`BarrageView` StatefulWidget，
  `_buildAnimal1Widget` / `_buildAnimal2Widget` 两条动画轨道，配 `TickerProviderStateMixin`
- 数据源：IM WebSocket（`im/impl/socket/im_socket_channel.dart`，`web_socket_channel`）
- 消息模型：`im/bean/*`（gift / level_up / broadcast / game_bet / fee …）

→ 弹幕是视频层之上的**普通 widget 层**，不侵入播放器。

### 10.4 对本项目的结论

1. 「自研播放器 + 硬件解 HEVC」这条路**已被原 app 实践证明可行**，不是理论推测。
2. 我们不必从零写 MediaCodec 管道：**Media3 + 自定义 FLV/HEVC Extractor**
   能复用 Media3 的音视频同步、缓冲、渲染，只补一个提取器，风险显著更低。
3. 弹幕单独做一层即可，不需要动播放器。
4. 因此原生版的主线定为：
   `自定义 FlvHevcExtractor` + `ExoPlayer` + `SurfaceView` + `DanmakuView`
   （详见下一份「原生版技术方案」）。
