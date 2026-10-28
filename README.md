# JJLive · 安卓直播客户端

原生 Kotlin 实现的 91直播房间浏览与播放客户端。**不依赖任何服务端转码**——自己解析 FLV 容器，直接交给系统 MediaCodec 硬件解码 HEVC/H.265。

## 特性

- **HEVC 硬解直连**：源站全部是 HEVC（FLV codecId=12），Media3 的 `FlvExtractor` 写死只支持 H.264，所以这里自己实现 FLV 解封装 + Annex-B 转换，喂给 `MediaCodec`。无转码、画质无损、不吃 CPU。
- **不走 30 秒试看限制**：该限制是客户端读 `previewTime=30` 后自行计时停播，本 App 直接拉流不经过这段逻辑。
- **完整接口加密**：路径混淆（base64→逐字符 hex→随机前缀）+ md5 签名 + xorCode 报文体，全部本地实现。
- **房间列表**：按类别分页抓取（`pageSize ≤ 20`）、搜索、封面图（自动剥离图片域名返回的多余前缀字节）。
- **1V1 判断**：`oneToOneState == 2` 时服务端不下发媒体流，提前明确提示，不白等。
- **弹幕**：WebSocket + AES-128-CBC 解密 + Choreographer 驱动的高性能弹幕层（onDraw 零分配）。

## 构建（GitHub Actions，本地零安装）

仓库已内置 `.github/workflows/build.yml`。推到 GitHub 后：

1. Actions → `Build APK` → 等待完成
2. 在 workflow run 页面下载 artifact `jjlive-debug-apk`

CI 流程：checkout → JDK 17 → Android SDK → `gradle wrapper`（**本地不需要装 Gradle，wrapper 在 CI 里生成**）→ `./gradlew assembleDebug` → 上传 APK。

本地若有 Android Studio，直接打开 `android-app/` 目录即可。

## 使用

1. 安装 APK
2. 首次启动会弹出令牌输入框，粘贴 `authorization`（从 App 或网页登录后的请求头复制）
3. 令牌失效后点右下角浮动按钮重新粘贴
4. 点房间卡片进入播放

## 工程结构

```
android-app/
├── app/src/main/kotlin/com/jjlive/app/
│   ├── Api.kt              加密三件套 + HTTP + 模型解析 + 图片加载
│   ├── FlvLivePlayer.kt    ★ FLV 解封装 + MediaCodec 硬解（核心）
│   ├── MainActivity.kt     房间列表
│   ├── RoomAdapter.kt      列表适配器
│   ├── PlayerActivity.kt   播放页
│   ├── DanmakuView.kt      弹幕渲染
│   └── ImSocket.kt         弹幕 WebSocket + AES 解密
└── app/src/main/res/       布局与资源
```

## 关键实现说明

### 为什么不用 Media3 / ExoPlayer

Media3 的 `VideoTagPayloadReader` 源码里写死：

```java
if (videoCodec != VIDEO_CODEC_AVC) {
    throw new UnsupportedFormatException("Video format not supported: " + videoCodec);
}
```

`VIDEO_CODEC_AVC = 7`，而源站是 codecId=12（HEVC）。所以内置 FLV 解析器必然抛异常。

### FLV HEVC 视频 tag 结构

```
byte0    = (frameType << 4) | 12      // frameType 1 = 关键帧
byte1    = packetType                 // 0=配置 1=NALU 2=end
byte2..4 = compositionTime (signed 24-bit, ms)
byte5..  = 负载
```

配置帧是 hvcC（`HEVCDecoderConfigurationRecord`），第 21 字节低 2 位是 `lengthSizeMinusOne`；
把它整体作为 `csd-0` 交给 MediaCodec，NALU 帧则把长度前缀换成 Annex-B 起始码。

### 接口要点

| 项 | 值 |
|---|---|
| 域名 | `https://utp.opuxrcm.com` |
| 签名盐 | `oknzoiwe@23` |
| 参数口径 | `terminal=3 / channel=12 / version=436`（网页版为准） |
| 房间列表 | `/home/recommend/cls` + `/home/default/cls/query` |
| 播放地址 | `/app/live/joinRoom` → `pullAddress*`（兼容 `pullStreamAddr*`） |
| 流有效期 | `auth_key` 约 300 分钟 |
| 弹幕 | `wss://yzj.opuxrcm.com/live?auth=<token>&s=<ms>&aid=<uid>&v=<ver>` |

### 弹幕协议（已实测验证）

- 进房 `{"type":"1002","toLiveId":"<roomId>"}`
- 退房 `{"type":"1003","toLiveId":"<roomId>"}`
- 心跳 `{"type":"1007"}`，连续 3 次无 pong 重连
- 收包：Base64 → AES-128-CBC（key `t4@*77~i&4j#~#c~`）→ protobuf 内嵌 JSON

IV 尚未最终确定，但 CBC 模式下 IV 只影响首个 16 字节块，业务字段（`evt/uid/cn/lv/…`）都在后续块，所以 `ImSocket` 采用「跳过首块」的兼容策略，实测可稳定取到昵称等内容。

## 已知限制

1. **需要用户提供令牌**：没有游客登录接口，无法自动获取。
2. **1V1 表演中不可看**：服务端在表演期间不下发媒体，需真实购买。
3. **弹幕 IV 未闭环**：影响每条消息的首块，昵称与正文不受影响（见上）。
4. **A/V 同步为轻量实现**：以音频时钟为主，落后 1 秒以上丢帧，直播场景够用。

## 免责声明

本项目为接口协议学习与技术研究用途，代码仅用于验证已逆向的公开接口行为。
