# 91 直播客户端

自研的 91 直播房间浏览与播放客户端，包含 **Android 原生版** 与 **网页版** 两套实现。

完全不走服务端转码：自己解析 FLV 容器、还原 CDN 载荷混淆，直接交给系统 `MediaCodec` 解码 HEVC。

> 核心难点（视频全黑）的完整排查过程与算法推导见 [docs/播放黑屏排查与成功总结.md](docs/播放黑屏排查与成功总结.md)。

---

## 目录结构

```
.
├── app/              Android 客户端源码（Kotlin）
├── web/              网页版源码（Python 服务 + 前端页面）
├── docs/             全部文档（架构 / 接口 / 逆向 / 排查记录）
├── tools/            文档生成与接口提取脚本
└── .github/          CI：自动构建 APK 并发布 Release
```

## 两套客户端

| | Android | Web |
|---|---|---|
| 入口 | `MainActivity` | `web/tools/run.py` |
| 播放 | 自研 FLV 解封装 + `MediaCodec` + `SurfaceView` | jessibuca（WASM）+ mpegts.js |
| 解码 | 硬解优先，无 HEVC 硬解时回落 WASM | 纯软解 |
| 聊天 | 原生 RecyclerView + WebSocket | 页面内渲染 |
| 状态 | 主推，功能最全 | 本地运行，便于对比验证 |

---

## Android 特性

- **HEVC 直解**：源站统一 HEVC（FLV codecId=12），Media3 的 `FlvExtractor` 写死只支持 H.264，因此这里自实现 FLV 解封装 + Annex-B 转换喂给 `MediaCodec`。无转码、画质无损。
- **载荷混淆还原**：视频 NALU tag 被 CDN 做了差分 XOR 混淆，还原后才能解出画面。算法见下文。
- **绕过 30 秒试看**：该限制是客户端读 `previewTime=30` 后自行计时停播，直接拉流不经过这段逻辑。
- **接口加密**：路径混淆 + md5 签名 + 报文 xorCode，全部本地实现。
- **首页**：分类标签 + 自适应列数网格 + 3:4 封面；按 `anchorType` 与关键字过滤博彩/广告房。
- **播放页**：上下滑动切换直播间；底部聊天区（事件号分类 + 关键词双层过滤）；原画 → 720 → 360 自动降级。
- **弹幕**：WebSocket + AES 解密，事件号 `evt=5` 为唯一聊天事件，其余（进房、下注、开奖、在线数）全部屏蔽。

## Web 特性

- 本地 HTTP 服务托管页面，内置封面代理（图床文件头带防盗链字节，必须剥离）与流代理。
- 可选把 FLV 转 HLS。
- 双击 `web/start.bat` 启动，首次运行按提示粘贴 `authorization`。

---

## 关键实现

### 1. CDN 载荷混淆（黑屏根因）

视频 NALU tag 首字节被整体 +1，载荷做差分异或：

```
触发：首字节 ∈ {0x18, 0x28, 0x1d, 0x2d}
还原：b[i] ^= b[i-1]     (i >= 2)
      b[1] = 1
      b[0] -= 1
```

H.265 I 帧 `0x1c→0x1d`、P 帧 `0x2c→0x2d`；H.264 对应 `0x17→0x18`、`0x27→0x28`。
序列头（`0x1c`）与音频 tag 不参与混淆。

### 2. FLV HEVC 视频 tag

```
byte0    = (frameType << 4) | 12      // frameType 1 = 关键帧
byte1    = packetType                 // 0=配置 1=NALU 2=end
byte2..4 = compositionTime (signed 24-bit, ms)
byte5..  = 负载
```

配置帧是 hvcC（`HEVCDecoderConfigurationRecord`），第 21 字节低 2 位为 `lengthSizeMinusOne`。
**csd 必须拆成独立的 VPS/SPS/PPS 三个 NAL** 与 Annex-B 帧配套，整块塞 hvcC 会导致解码器认不出任何 NAL（有声音没画面）。

### 3. 接口要点

| 项 | 值 |
|---|---|
| 域名 | `https://utp.opuxrcm.com` |
| 参数口径 | `terminal=3 / channel=12 / version=436` |
| 房间列表 | `/home/recommend/cls` + `/home/default/cls/query` |
| 播放地址 | `/app/live/joinRoom` → `pullStreamAddr*` |
| 流有效期 | `auth_key` 约 300 分钟 |
| 弹幕 | `wss://yzj.opuxrcm.com/live?auth=<token>&s=<ms>&aid=<uid>&v=<ver>` |

签名盐与 AES 密钥不在此处公开，见 `docs/api/` 下的分析文档。

### 4. 弹幕协议

- 进房 `{"type":"1002","toLiveId":"<roomId>"}`
- 退房 `{"type":"1003","toLiveId":"<roomId>"}`
- 心跳 `{"type":"1007"}`，连续 3 次无 pong 重连
- 收包：Base64 → AES-128 → JSON

---

## 构建

### Android

推送到 GitHub 后，Actions 会自动执行 `./gradlew assembleDebug`，并把 APK 发布到 `releases/tag/latest`。

CI 流程：checkout → JDK 17 → Android SDK → 生成 wrapper → 构建 → 上传 artifact 与 Release。

本地用 Android Studio 直接打开根目录即可（需自备 `local.properties`）。

### Web

```bat
python web\tools\run.py              :: 完整流程：抓房间 → 生成页面 → 起服务 → 开浏览器
python web\tools\run.py --serve-only :: 用现有数据只起服务
```

依赖 Python 3 标准库，无需 pip。

---

## 使用

1. 安装 APK
2. 首次启动粘贴 `authorization`（从 App 或网页登录后的请求头复制）
3. 令牌失效后点右下角浮动按钮重新粘贴
4. 点房间卡片进入播放，上下滑动切换直播间

---

## 已知限制

1. **需要用户提供令牌**：没有游客登录接口，无法自动获取。
2. **1V1 表演中不可看**：服务端在表演期间不下发媒体流（`oneToOneState == 2`），提前提示不白等。
3. **A/V 同步为轻量实现**：以音频时钟为主，视频落后 1 秒以上丢帧，直播场景够用。
4. **硬解未在真机验证**：开发环境只有模拟器（`OMX.google.hevc.decoder` 软解），真机应走到 `c2.*`。

## 免责声明

本项目为接口协议与媒体封装格式的学习研究用途，代码仅用于验证已逆向的公开接口行为。
