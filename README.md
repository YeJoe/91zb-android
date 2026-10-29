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
- **三分类导航**：底部「首页 / 关注 / 设置」，切换只改 visibility，回来还在原滚动位置。
- **首页**：分类标签 + 自适应列数网格 + 3:4 封面；按 `anchorType` 与关键字过滤博彩/广告房。
- **关注**：拉真实关注列表（`/home/user/attention/list`），**分页拉全**（实测账号有 169 条关注），在播的排前面、未开播打灰标，顶部显示账号昵称与关注总数；关注为空时才展示推荐（`/home/user/notattention/list`），卡片右侧可一键关注 / 取关（`/user/attention/add|cancel`）。
- **设置**：整页表单，可改「接口域名 / 令牌 / 弹幕地址」并做连接测试，另有默认形态、优先清晰度、过滤开关。令牌内置默认值，首次打开直接进列表；换域名或令牌不用重新打包。
- **播放页**：默认半屏（上方画面 + 下方深色聊天区），点画面或「全屏」按钮切竖屏全屏；**聊天区右滑可甩走**（跟手变透明，甩出后画面铺满，左下角「聊天」胶囊叫回来）；上下滑动切换直播间（落点在聊天区也能切）；直播中屏幕常亮。
- **明暗跟随**：界面走 Material3 DayNight + 语义色（`values-night/colors.xml`），跟随系统深浅色；播放页固定深色，避免白底晃眼。
- **播放页降级**：原画 → 720 → 360 自动降级；无 HEVC 硬解时回落 WebView + WASM 软解。
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
| 我的关注 | `/home/user/attention/list`（`state`/`stateStr` 判断是否在播；分页 `pageNo`/`pageSize`） |
| 关注动作 | `/user/attention/add`、`/user/attention/cancel`，参数 `followedUserIds` |
| 关注推荐 | `/home/user/notattention/list` |

签名盐与 AES 密钥不在此处公开，见 `docs/api/` 下的分析文档。

### 4. 弹幕协议

- 进房 `{"type":"1002","toLiveId":"<roomId>"}`
- 退房 `{"type":"1003","toLiveId":"<roomId>"}`
- 心跳 `{"type":"1007"}`，连续 3 次无 pong 重连
- 收包：Base64 → AES-128 → JSON

### 5. 播放页布局与切间手势

**两种形态**（`PlayerActivity.applyMode`）：半屏时 `videoBox` 高度取屏高 46%、`chatPanel` 用深色圆角面板接在下方；全屏时 `videoBox` 铺满、`chatPanel` 变成压底的渐隐层。点画面或「全屏/半屏」按钮切换，`fitVideo` 会按形态重算缩放——全屏 contain（完整画面），半屏 cover（填满画面框，裁切超过 45% 时自动退回 contain）。

**切间手势**（`SwipeLayout`）：父容器拦截模型，而不是 `OnTouchListener`。后者只在「没有子 View 消费 DOWN」时才收事件，手指落在聊天列表上就会整个被 RecyclerView 吃掉，切间成功率因此很低。现在的判定是：

```
DOWN  → 正常派发给子 View（按钮、聊天滚动不受影响）
MOVE  → |dy| > slop 且 |dy| > |dx| 视为竖向意图
         落点在聊天区且列表该方向还能滚 → 不拦，交给列表
         其余情况 → 拦截并向子 View 发 CANCEL，自己接管拖拽
UP    → 位移 > 屏高 14% 或速度 > 780px/s 即切间，否则回弹
```

拖动过程中整层 1:1 跟手（不加阻尼），只有到首尾边界才上 0.2 的阻力；松手后 150ms 滑出、内容重建、再从对侧 16% 处 210ms 落位。全程不做整层 alpha 动画——`SurfaceView` 淡出在部分机型会闪黑。

**右滑甩走聊天区**（`SlideAwayLayout`）：只认「往右且水平远大于垂直（1.6 倍）」的滑动，竖向照旧交给 `SwipeLayout` 切间，聊天列表也照常滚动。拖动时 `translationX` 1:1 跟手、`alpha` 同步降到 0.08；松手超过 30% 宽度或速度 > 620px/s 即收起，否则回弹。收起后半屏会切全屏（`chatHidden`），左下角出现「聊天」胶囊，点它或点画面恢复收起前的形态。

---

## 构建

### Android

推送到 `main` 后，Actions 里的 **`91直播 Build APK`** 工作流（`.github/workflows/build.yml`）会自动执行 `./gradlew assembleDebug`，并把 APK 发布到 `releases/tag/latest`。

产物命名带分支、版本与短 sha，例如 `jjlive-main-v1.10-tabs-11-c104bcd.apk`；安装后应用名「91直播」，包名 `com.jjlive.app`。

> 同一仓库里还有一个 `duocai` 分支，跑的是 `build-apk` 工作流（`.github/workflows/android.yml`），那是另一套独立的客户端（TM Live，包名 `com.duocai.live`），与本工程**没有共同提交历史**，也在别的 release tag 上发布，两者互不影响。看 Actions 列表认「91直播 Build APK」这个名字即可。

CI 流程：checkout → JDK 17 → Android SDK → 生成 wrapper → 构建 → 改名 → 上传 artifact 与 Release。

本地用 Android Studio 直接打开根目录即可（需自备 `local.properties`）。

### Web

```bat
python web\tools\run.py              :: 完整流程：抓房间 → 生成页面 → 起服务 → 开浏览器
python web\tools\run.py --serve-only :: 用现有数据只起服务
```

依赖 Python 3 标准库，无需 pip。

---

## 使用

1. 安装 APK，首次启动直接进首页（内置默认令牌）
2. 令牌失效 / 换线路：底部「设置」→ 改域名或令牌 → 「测试连接」→「保存并重载」
3. 点房间卡片进入播放，上下滑动切换直播间，右滑收起聊天区
4. 「关注」页可看真实关注、也可在这里关注推荐主播

---

## 已知限制

1. **令牌会过期**：内置的是正式账号令牌（实测 `attenCnt=169`，能拉到真实关注）；失效后在「设置」里换一个，
   「测试」按钮会直接显示该令牌的昵称 / 关注数 / 粉丝数，方便确认换对了账号。
   - ⚠️ 服务端的 `visitors` 字段**不能**用来判断游客：正式账号的 `visitors` 同样是 1，
     判据只能是 `attenCnt` / `fansCnt` 是否为 0。
2. **1V1 表演中不可看**：服务端在表演期间不下发媒体流（`oneToOneState == 2`），提前提示不白等。
3. **A/V 同步为轻量实现**：以音频时钟为主，视频落后 1 秒以上丢帧，直播场景够用。
4. **硬解未在真机验证**：开发环境只有模拟器（`OMX.google.hevc.decoder` 软解），真机应走到 `c2.*`。

## 免责声明

本项目为接口协议与媒体封装格式的学习研究用途，代码仅用于验证已逆向的公开接口行为。
