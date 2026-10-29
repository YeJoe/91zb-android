# TM Live · 安卓 App 设计方案（v1 已定稿，代码已落地）

> 目标：把现有 `site/` 网页改写成原生安卓 App，用 GitHub Actions 云端编译，本机零 Android 环境。
>
> **本文档状态：定稿。** 代码在 `app/`，流水线在 `.github/workflows/android.yml`，
> 数据源在 `app-data/rooms.json`，仓库 `github.com/YeJoe/91zb-android` 的 **`duocai` 分支**。
>
> 结构：**底部三栏**（首页 / 关注 / 设置）→ 点卡片进**全屏播放页**（上下滑切房）→ 返回落回原 tab。

---

## 0. 一句话方案

**Kotlin + Jetpack Compose 单 Activity；底部三栏（首页 / 关注 / 设置）；播放页全屏覆盖，VerticalPager 整屏上下切房；Media3 + librtmp 直连 RTMP；弹幕区右滑变透明；关注用本地收藏 + 快照判在播；配置全在设置页。**
`git push` → GitHub Actions 出 APK，本地不装 JDK/SDK/Gradle 任何一个。

### 0.1 三项定稿信息

| 项 | 结论 |
|---|---|
| 仓库 / 分支 | `https://github.com/YeJoe/91zb-android.git` → **`duocai`** 分支（数据源 + 代码都在这个分支） |
| App 名称 | **TM Live**（包名 `com.duocai.live`，`applicationId` 带 `.debug` 后缀区分调试包） |
| 弹幕聊天区 | **右滑透明**：不透明度 0.62 一路滑到 0，全透明时只剩带投影的文字，不遮挡画面；左滑恢复；也可点头部的「背景 xx%」循环 62% / 25% / 透明 |


---

## 1. 关键事实（全部实测，不是推测）

| # | 事实 | 实测结果 | 对设计的影响 |
|---|---|---|---|
| 1 | **播放完全不需要凭证** | `ffprobe rtmp://<域名>/live/64378161` → **h264 720x1280 + aac**，地址里没有任何 token | 播放链路成立，这是 App 能独立工作的基础 |
| 2 | 带 token 的流地址不是必需的 | `.stream_url.txt` 里是 `...?token=...&t=1793289341&liteavToken=...`（App 侧生成、带过期），但去掉整串照样能拉 | 不实现 token 生成，播放地址就用 `rtmp://<域名>/live/<房间号>` |
| 3 | **房间列表接口公网不通** | `http://<域名>/room/list` → **403 openresty**；`https://` → TLS 握手 `000` | App 拉不到实时列表 → 必须读一份 JSON 数据源 |
| 4 | 明文 API 只活在模拟器隧道里 | App 打 `127.0.0.1:<随机端口>`（本次 `.api_port=24278`），由 libkiwi 加密转发到 `121.204.228.227:7001` | 手机上 `127.0.0.1:24278` 指向手机自己，隧道不存在 |
| 5 | 现有 token 是**游客号** | `.session.json`：`id=2094967700`、`nickname=包子臉哈哈_mqrnni`、`login_type=quick` | 即便接口通了，游客号也拿不到真实关注关系 |

## 1.5 那 assets/ 里这些凭证，对 App 到底有什么用？

你把凭证挪到了 `assets/`，我逐个核过：

| 文件 | 内容 | App 用得上吗 |
|---|---|---|
| `.rtmp_domain.txt` | `1dtluv36amaiuckq.7kv5kqq7k77g.hebeisaixin.com` | ✅ **唯一有用**，拼播放地址用。可作默认值预填 |
| `.stream_url.txt` | 带 `token/t/liteavToken` 的完整 rtmp 地址 | ❌ 已验证非必需，且 `t` 是过期时间戳 |
| `.apptoken.txt` | 32 hex 会话 token | ❌ 只在隧道 vhost 上有效，脱离隧道无意义 |
| `.jwe.txt` | 330 字符 Bearer | ❌ 同上，且绑定那个游客会话 |
| `.api_port` | `24278` | ❌ 模拟器本机回环端口，手机上等于不存在 |
| `.session.json` | 游客登录返回体 | ❌ 仅供追溯当初的登录参数（`channel=ecy`、`version=8.5.3`） |

**结论：凭证不能替代数据源。** 它们的作用是"证明当初怎么打通的"，不是"一个能装进 APK 就联网的通行证"。
唯一能落地的价值是 **RTMP 域名**——我会把它做成内置默认值，用户可在设置里改。

> 顺带一个安全点：token 绝不能打进 APK。APK 一旦分发，token 就等于公开。所以设置里填的 token 用 Keystore 加密存本地，仓库和 APK 里都不出现。

## 2. 技术选型（性价比优先，逐项给取舍）

| 项 | 选择 | 为什么不是别的 |
|---|---|---|
| 语言 / UI | Kotlin 2.0.21 + Compose（BOM 2024.12.01）+ Material3 | 单 Activity，切页动画和手势用 Compose 最省代码；XML + Fragment 要多写一倍 |
| 播放器 | **Media3 ExoPlayer 1.5.1 + `media3-datasource-rtmp`** | 官方维护的 librtmp JNI 扩展，**+1.1MB**。备选 `io.antmedia:rtmp-client` 需 JitPack 仓库、维护弱；ijkplayer 已停更且 +8MB 起。性价比没有第二个选项 |
| 依赖注入 | **不用 Hilt** | 少 1 个注解处理器、少 ~800KB、少一堆 KSP 配置。手写 `AppContainer` 单例足够 |
| 网络 | **`HttpURLConnection`**（零依赖） | 只有"拉一份 JSON"一件事，OkHttp 的 800KB 换不来什么；不用 Retrofit 同理 |
| JSON | **`org.json`**（系统自带） | 结构就三个数组，用 kotlinx-serialization 要多一个编译器插件 + R8 keep 规则，不划算 |
| 配置存储 | **SharedPreferences** | 全是"改一次读很多次"的标量；DataStore 的异步 API 只会让每个设置项多绕一圈 Flow，还多一个依赖 |
| 敏感项 | **Android Keystore AES/GCM**（约 60 行，不引库） | 只有弹幕 Token 一项需要加密；加密失败自动退化为明文并打标记，不牺牲可用性 |
| 封面图 | **不加载图片**（渐变卡 + 名字首字） | 关键发现：`avatar` 是 `/static/upload/...` 相对路径，挂在**公网打不通**的接口域名上，任何图床都取不到。所以直接不引 Coil，用房间号取固定渐变色，比占位图好看，还省 1.5MB |
| 导航 | **不用 Navigation-Compose** | 3 个页面 + 一个 Pager，用 `enum Tab` + `when` + 一个 `openIndex` 就够 |
| 视频表面 | **TextureView**（`surface_type="texture_view"`） | SurfaceView 的画面在独立图层里，Compose 的 `scale/alpha` 对它不生效，双指缩放和淡入会失效。多一次 GPU 拷贝换"缩放真的能用" |
| 最低版本 | minSdk 24 / targetSdk 35 / compileSdk 35 | 24 覆盖 98%+ 设备；35 满足 2026 上架要求 |
| 构建 | AGP 8.7.3 + Gradle 8.9 + JDK 17 | 稳定组合，GitHub runner 原生支持 |

**依赖总清单**（就这些，没有别的）：`core-ktx`、`activity-compose`、`lifecycle-runtime-ktx`、Compose BOM（ui / graphics / tooling-preview / material3 / material-icons-core）、media3（exoplayer / exoplayer-hls / ui / datasource-rtmp）。

**预估产物体积**：`app-release.apk` ≈ **5~6 MB**（universal），arm64 单包 ≈ 4MB。比初稿的 6~7MB 又小了一档，原因就是砍掉了 Coil / OkHttp / serialization / DataStore 四个依赖。

---

## 3. 界面设计

### 3.0 全局结构：底部三栏

```
        ┌──────────────────────────┐
        │                          │
        │        页面内容          │
        │                          │
        ├──────────────────────────┤
        │  ⌂ 首页   ★ 关注   ⚙ 设置 │  ← NavigationBar
        └──────────────────────────┘
```

- **首页 / 关注 / 设置** 三个 tab，Material3 `NavigationBar` + 选中指示器胶囊（32dp 圆角、`secondaryContainer`）
- **播放页不属于任何 tab**：全屏覆盖在 `Box` 顶层，`AnimatedVisibility` 上滑入场，进入时隐藏底栏，下滑/返回键关闭
- tab 之间用 `Crossfade(200ms)`，不做横向滑动 —— 避免和播放页的纵向 Pager 手势语义打架
- 首页与设置 `statusBarsPadding`；播放页 `edge-to-edge` 全透明

### 3.1 首页（直播列表）

```
┌─────────────────────────────┐
│ TIANMEI          ⟳   ⌕      │  ← 大标题 + 刷新 + 搜索
│ [全部 216][交友 50][星秀 36]│  ← 分类 chips，横向滑动
│ 排序: 人气 ▾   仅真人 ●      │
├─────────────────────────────┤
│ ┌─────────┐ ┌─────────┐    │  ← 双列瀑布卡片
│ │  封面    │ │  封面    │    │     16:9 封面 + 左下在线人数
│ │         │ │         │    │     底部两行：主播名 / 房间名
│ ├─────────┤ ├─────────┤    │
│ │  封面    │ │  封面    │    │
│ └─────────┘ └─────────┘    │
└─────────────────────────────┘
```

- 双列 `LazyVerticalStaggeredGrid`，卡片圆角 14dp，封面 `AspectRatio(4/3)` 让瀑布有错落感
- 左上角在线人数胶囊（`👁 1.2万`），右上角推广房标 `推广`（金色小标签，默认过滤掉）
- 下拉刷新 → 重新拉数据源；顶部无数据时显示"数据源未配置，去设置"引导卡
- 卡片右上角 **星标**（点了只收藏、不进播放页）；已关注主播在封面左上角显示小圆点
- 点击卡片 → 直接进播放页并定位到该房间（Pager 起始页 = 点击的位置）

### 3.2 播放页（核心）

```
┌─────────────────────────────┐
│ ←  │ 主播名 · 颜值 · 1.2万  │  ← 顶部渐变遮罩，单击隐/显
│                             │
│         视频画面             │  ← 全屏，双指缩放 1.0~3.0x / 双击复位
│                             │
│                    [裁剪]   │  ← 右上角缩放模式（适应/裁剪/拉伸，点一下循环）
├─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─┤  ← 右滑这里：0.62 → 0，背景渐变消失
│ 弹幕 128  离线样本  背景 62% │  ← 聊天区顶栏（右上角显示当前不透明度）
│  小野猫：主播好漂亮           │
│  观众A：来了来了              │  ← 文字带投影，透明后依然读得清
│  观众B：这首歌叫什么          │
│ ┌───────────────────────┐   │
│ │ 说点什么…          ➤  │   │  ← 只读输入条（全透明时自动隐藏）
│ └───────────────────────┘   │
└─────────────────────────────┘
```

- 视频层与聊天区**不是简单上下堆叠**：聊天区默认占底部 40%，可**右滑让它整体透明**
- **右滑透明（本轮新增）**：在聊天区任意位置向右拖 → 背景不透明度线性下降（跟手，1:1 映射到 400dp 行程），
  降到 0 时背景与输入条完全消失，只剩带投影的弹幕文字浮在画面上；向左拖回来即恢复。
  头部的「背景 xx%」按钮可循环 62% → 25% → 全透明，不记手势也能用。首次进入会给 4 秒提示。
  点击区域始终存在（弹幕还要能滚、能点按钮），只是**视觉上不再遮挡画面**
- 单击视频 = 隐/显整个 chrome（顶栏 + 聊天区）；双击 = 暂停/继续（缩放态下则是复位）
- 双指 = 缩放 1.0~3.0x，缩放后单指平移；缩放时禁用 Pager 纵向翻页，避免误切房

### 3.3 关注页

```
┌─────────────────────────────┐
│ 关注                  12 位 │
├─────────────────────────────┤
│ ● 正在直播 · 3               │
│ ┌──────┐ 小野猫              │
│ │ 封面  │ 今晚不睡了  👁1.2万│
│ └──────┘              [取消]│
│ ┌──────┐ 静静                │
│ │ 封面  │ 聊会儿天      👁 860│
│ └──────┘                    │
│ ○ 未开播 · 9                 │
│ ┌──────┐ 阿七                │
│ │ 灰封面│ 上次开播 3 天前      │
│ └──────┘                    │
└─────────────────────────────┘
```

**分两组是这页能不能用的关键**：关注列表本身只是"人"，用户真正要的是"谁现在在播"。
每条关注记录拿 `房间 id / 主播名` 去当前快照比对，命中且在播的进「正在直播」，其余进「未开播」（封面去饱和、显示上次见到的时间）。

**关注数据的三级来源（逐级降级）**：

| 级别 | 来源 | 可用性 | 说明 |
|---|---|---|---|
| L1 | **App 本地收藏**（DataStore） | ✅ 立刻可用 | 点星即存 `roomId / 主播名 / 封面 / 关注时间`，不依赖任何服务端 |
| L2 | 当前快照匹配 | ✅ 立刻可用 | 拿 L1 的 id 去 `rooms.json` 查在播状态、最新人气与封面 |
| L3 | 服务端 `/relationship/followees/` | ⚠️ 有条件 | 见下方 |

**"能不能拿到已登录账号的关注"—— 接口存在，但现在拿不到**：

- 接口确实有：`/relationship/followees/`，是 `pp.txt` 581 条路径里 42 条 REST 接口之一（同组还有 `/chat/list_chat_ids/`、`/message/latest/`）
- 三个硬门槛：
  1. **公网不可达** —— 只在隧道内的明文 vhost 上响应，公网域名一律 403 / TLS 失败（§1）
  2. **需要真实登录态** —— 游客 `token` 来自 `/login/quick`，关注关系绑在真实账号上，游客态返回空
  3. **原凭证已丢失** —— `.jwe.txt`、`.apptoken.txt` 都不在了，重抓要重开 App 重新抓包
- 因此 L3 设计成**可选同步**：设置里填了「关注同步端点 + Token」才尝试；拿到后与本地合并，**以本地为准、服务端只做增量补全**（否则会误删用户手动加的星）。失败静默跳过，不弹错、不挡路。

> 一句话：关注页 P3 就能用（本地收藏 + 快照判在播）；服务端同步是加分项，不是前置条件。

### 3.4 设置页

分 5 组，全部即时生效、持久化：

| 分组 | 项 | 说明 |
|---|---|---|
| **数据源** | 房间数据 URL | 默认指向仓库的 `rooms.json` raw 地址；可换成任意 URL |
| | 自动刷新间隔 | 关闭 / 30 分钟 / 1 小时 / 每次启动 |
| | 当前快照时间 | 只读展示，提示"数据为快照，非实时" |
| **播放** | RTMP 域名 | 必填，`rtmp://<这里>/live/<房间号>` |
| | 备用 HLS 网关地址 | 可选，填了就在 RTMP 失败时降级（指向你 PC 上的 `gateway.py`） |
| | 默认缩放模式 | 适应 / 裁剪 / 拉伸 |
| | 硬解优先 | 开关，默认开 |
| | 缓冲时长 | 1s / 3s / 5s（弱网调大） |
| | 进入自动播放 | 默认开 |
| **关注** | 关注同步端点 | 可选，填了才尝试 `/relationship/followees/`（需真实账号 Token） |
| | 同步账号 Token | 可选，Keystore 加密；留空则纯本地收藏 |
| | 是否显示未开播 | 默认显示（灰化），关掉只列在播的 |
| **弹幕** | WebSocket 端点 | 可选（`ws://`/`wss://`，需自备反代） |
| | 鉴权 Token | 可选，Keystore 加密存储 |
| | 只显示观众聊天 | 默认开（复用 `danmaku_filter` 的 `typ` 规则，已移植成 Kotlin） |
| | 字号 / 显示礼物 | 12~18sp；礼物开关 |
| **外观** | 主题 | 跟随系统 / 深色 / 浅色（默认跟随） |
| | 动态取色 | Android 12+ 从壁纸取色（Monet），默认开 |
| **高级** | 导入配置 / 导出配置 | 一份 JSON，可粘贴或扫码导入，方便换机 |
| | 关于 / 版本 | 显示 CI 构建号与提交短 hash |

> 配置文件模型（导入导出的就是它）：
> ```json
> {
>   "v": 1,
>   "source": { "roomsUrl": "https://raw.githubusercontent.com/<user>/<repo>/main/app-data/rooms.json" },
>   "play":   { "rtmpDomain": "xxx.hebeisaixin.com", "hlsGateway": "", "scaleMode": "fit" },
>   "danmaku":{ "wsEndpoint": "", "token": "", "chatOnly": true }
> }
> ```

---

## 4. 核心交互实现（这是"丝滑"的全部秘密）

### 4.1 单播放器 + 视频层固定（关键决策，初稿已修正）

**不做**"每页一个 PlayerView"。抖音式做法看着简单，实际每页一个 `ExoPlayer` 就会开 3 个解码器，中低端机直接 OOM。

**最终做法**（比初稿那版更稳）：

- 整个播放页只有 **1 个 `ExoPlayer` + 1 个 `PlayerView`**，固定铺满屏幕，**放在 VerticalPager 下面**
- Pager 的每一页**只画封面**（渐变 + 首字 + 名字），不画视频
- 封面透明度：当前停住的页 `1 - videoAlpha`（视频就绪即淡出），其它页恒为 1
- 滑动时是**新一页的封面从下方盖上来**，不是视频被推走 —— 所以永远不会出现"视频划出屏幕露出黑边"
- 滚动稳定（`settledPage` 变化）→ `player.setMediaItem(新地址)`，切流那几百毫秒被新封面挡住

> 初稿写的是"视频层 `translationY` 跟随 `currentPageOffsetFraction`"。真做下去会发现：
> 只有一屏视频时，位移除了把画面推出屏幕之外没有第二个画面来接，反而露黑边。
> 所以改成"视频不动、封面动"，代码更少、视觉更连贯。

### 4.2 切页动画

- `VerticalPager` 默认的吸附与阻尼已经够用，不自定义 `flingBehavior`
- 切页时顶栏与聊天区按 `1 - min(1, |offset| × 2.2)` 整体淡出，换完再淡回来，掩盖换流空窗
- 每页 `key = rooms[index].id`，避免复用错位

### 4.3 缩放

- 自己写的双指手势（**只有两根手指才消费事件**）：`zoom = 缩放比 × 上一帧`，钳在 1.0~3.0
- 缩放 > 1.01 时 `userScrollEnabled = false`，避免缩放和翻页抢手势
- 平移量按 `size × (zoom-1) / 2` 钳制，画面不会被拖出边界
- 双击：缩放态先复位到 1.0，否则切换暂停/播放
- 视频本体用 `AspectRatioFrameLayout.RESIZE_MODE_FIT / ZOOM / FILL` 三档，对应"适应/裁剪/拉伸"

> 手势层压在 Pager 上面，所以**不能用 `detectTapGestures`**（它会 consume 掉 down，
> 单指上下滑就被吃掉了）。改成自己判"按下后没有移动 = 点击"，单击延迟 260ms 等第二下，全程不消费事件。

### 4.4 弹幕区

- `LazyColumn`，最多保留 200 条，贴底时自动滚到最新；手动上滑后显示「↓ 回到底部」
- 过滤规则从 `site/dm_filter.js` **1:1 移植成 Kotlin**（`data/Danmaku.kt` 的 `DmFilter`），保证与网页版行为一致
- 实时来源：配置了 `wsEndpoint` 才订阅；没配就用**打包进 APK 的真实样本**（`assets/danmaku_demo.json`，182 条）按接近实时的节奏回放，聊天区不会空着
- **右滑透明**：`detectHorizontalDragGestures` 把 `bg` 从 0.62 线性拉到 0；背景是 `verticalGradient(黑 0.10 → 0.72 → 0.86)`，乘上 `bg`，
  所以 `bg=0` 时渐变整条消失；文字始终带 `Shadow(blur 5f)`，透明后仍然可读

---

## 5. 工程结构（已落地）

```
app/
├── build.gradle.kts                  # 版本号可由 CI 环境变量覆盖；签名按 secret 是否存在决定
├── proguard-rules.pro                # 保留 librtmp JNI 类
└── src/main/
    ├── AndroidManifest.xml           # TM Live · com.duocai.live · 竖屏 · cleartext 允许
    ├── assets/danmaku_demo.json      # 182 条真实弹幕样本（离线回放用）
    ├── res/
    │   ├── layout/view_player.xml    # 只有这里能设 surface_type=texture_view
    │   ├── drawable/ic_launcher.xml  # 矢量图标，不放 PNG
    │   └── values/{strings,colors,themes}.xml
    └── java/com/duocai/live/
        ├── App.kt                    # Application + Context.app 取容器
        ├── AppContainer.kt           # 手写容器 + 全局状态（rooms/tab/openIndex/followedIds）
        ├── MainActivity.kt           # 单 Activity，edge-to-edge
        ├── data/
        │   ├── Models.kt             # Room / SourceMeta / SourceParser（org.json）
        │   ├── Prefs.kt              # SharedPreferences + Keystore AES/GCM 加密 token
        │   ├── RoomRepo.kt           # URL → 缓存文件 → 解析（HttpURLConnection）
        │   ├── FollowStore.kt        # 本地关注（房间号 + 名字 + 时间戳）
        │   └── Danmaku.kt            # DmFilter（dm_filter.js 的 Kotlin 移植）+ 离线回放
        ├── player/PlayerHolder.kt    # 单 ExoPlayer + RTMP/HLS 数据源 + 错误归类
        └── ui/
            ├── Common.kt             # 渐变配色 / 首字 / 人数格式化 / 昵称配色
            ├── RootScreen.kt         # 底部三栏 + 播放页覆盖
            ├── HomeScreen.kt         # 瀑布流卡片 + 分类 chips + 搜索 + 星标
            ├── FollowScreen.kt       # 正在直播 / 未开播 两组
            ├── SettingsScreen.kt     # 数据源 / 播放 / 弹幕 / 关注 / 外观 / 关于
            ├── PlayerScreen.kt       # Pager + 封面层 + 手势 + 聊天区（右滑透明）
            └── theme/Theme.kt        # Material3 + Android 12 动态取色（主色锁品牌粉）
.github/workflows/android.yml         # 出包流水线
app-data/rooms.json                   # App 的数据源（300 间 / 84 个推广位 / 31.6KB）
tools/build_app_data.py               # site/data.js → rooms.json + 弹幕样本
```

**`rooms.json` 从哪来**：`tools/build_app_data.py` 读 `site/data.js`，瘦身成 App 需要的字段
（房间号 / 名字 / 分类 / 人气 / 归属地 / 是否推广 / 钻数 / rtmp 域名），顺手把 `site/dm_samples.js` 转成
`app/src/main/assets/danmaku_demo.json`。以后刷新列表就是三步：

```bash
python tools/build_site3.py        # 重新抓 → site/data.js
python tools/build_app_data.py     # → app-data/rooms.json + assets/danmaku_demo.json
git add -A && git commit -m "data: 刷新房间列表" && git push
```

App 端下拉/点刷新即可拿到新列表（默认数据源就指向本仓库 `duocai` 分支的 raw 地址）。

**不内置房间快照**（按你的选择）：APK 里不带 rooms.json，首启是空态引导 —— 填数据源 URL，或者直接
「粘贴 JSON」把一份数据塞进去；拉到之后写进 `filesDir/rooms.json` 缓存，之后断网也能看上次的列表。

---

## 6. GitHub Actions 流水线

```yaml
name: build-apk
on:
  push:
    branches: [duocai]
    tags: ['v*']
    paths-ignore: ['**.md', 'docs/**']
  pull_request: { branches: [duocai] }
  workflow_dispatch:
```

步骤（`.github/workflows/android.yml` 实际内容）：

1. `actions/checkout@v4`
2. `actions/setup-java@v4`（temurin 17）
3. `gradle/actions/setup-gradle@v4` 指定 **gradle 8.9**（仓库里没有 `gradle-wrapper.jar`，也不需要它）
4. 解析签名：有 `KEYSTORE_BASE64` secret → 解到 `$RUNNER_TEMP/release.jks` 并导出 `KEYSTORE_FILE`；没有 → 跳过
5. `gradle :app:assembleRelease`（有签名）/ `assembleDebug`（无签名，**debug 包能直接装**）
6. 收集 `app/build/outputs/apk/**/*.apk` → `dist/` → `upload-artifact@v4`
7. 推 `v*` tag 时自动 `gh release create` 挂上 APK

要点（都是为了"零配置能出包"）：

- **不提交 `gradle-wrapper.jar`**（二进制）。用 `setup-gradle` 指定版本，直接调 `gradle` 命令。
- **签名**：没有 keystore secret 也能出**可安装**的 debug 包；要长期升级同一个 app，就把 keystore 以 base64 放进
  `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 四个 secret。
- **版本号**：`APP_VERSION_CODE = github.run_number`，settings 关于页能看到构建号，方便对日志。
- **ABI 拆分**：`splits.abi` → `arm64-v8a`（真机用，最小）+ `universal`（模拟器/不挑）。
- **缓存**：`setup-gradle` 自带依赖缓存，第二次构建 ~2 分钟。

首次构建预计 **6~9 分钟**（含下载 Gradle 与依赖）。

---

## 7. 我能做 / 不能做（先说清）

| | 状态 |
|---|---|
| 写全部 Kotlin / Compose / Gradle / workflow 代码 | ✅ 已写完，16 个 Kotlin 文件 |
| 本地静态检查（JSON / YAML / HTML / JS 语法） | ✅ 已跑（内联 JS 过 `node --check`，数据源过 JSON 解析） |
| **本地编译验证** | ❌ 不装 JDK/SDK，靠 CI 首跑暴露编译错误 |
| 在手机上跑起来调 UI | ❌ 需要你装 APK 反馈 |
| 直连接口拿实时列表/弹幕 | ❌ 见 §1，需你自备反代端点 |

> 现实预期：CI 首跑大概率会有 1~3 轮编译错误（依赖 API 细节、Compose 版本差异），我会根据 Actions 日志修到绿。这是云端编译的正常成本，不是设计缺陷。
> 自查过的高风险点：`ExoPlayer.setLoadControl`（改缓冲用）、`VerticalPager` 的 `key` 参数、
> `Icons.Filled.*` 是否在 `material-icons-core` 里 —— 这三处若报错，都是一行改法。

---

## 8. 已定 / 待定

### 已确认（全部，无遗留）

| 项 | 结论 |
|---|---|
| App 名称 | **TM Live** |
| 包名 | **`com.duocai.live`**（debug 包 `com.duocai.live.debug`） |
| 仓库 / 分支 | `github.com/YeJoe/91zb-android` → **`duocai`**；数据源 raw = `.../duocai/app-data/rooms.json` |
| 数据源 | 远程 JSON，**不内置房间快照** → 首启空态引导（可填 URL 或粘贴 JSON） |
| 弹幕输入 | **只读展示**（输入条置灰 + 提示"配置端点后可发"） |
| 弹幕透明 | **聊天区右滑变透明**（0.62→0），另有「背景 xx%」循环按钮 |
| 关注 | 本地收藏为主；服务端 `/relationship/followees/` 不做（公网不可达 + 无真实登录态），设置页留同步占位说明 |
| 播放降级 | RTMP 失败 → 重试 → 「外部播放器」按 `ACTION_VIEW rtmp://…` 唤起 VLC/其它播放器（`runCatching` 兜底，失败不崩） |
| 主题 | 跟随系统 / 深色 / 浅色；Android 12+ 用壁纸取色（主色锁品牌粉） |

> 没有遗留待定项了。剩下的是实机调优：滑动手感、字体大小、缓冲档位这些只能上手改。

## 9. 分期建议（P1–P3 本轮已一次性写完）

| 期 | 内容 | 状态 |
|---|---|---|
| **P1** | 工程骨架 + CI + 底部三栏 + 首页列表 + 设置页（数据源/域名） | ✅ 已写 |
| **P2** | 播放页 + 单播放器（视频层固定）+ 上下滑切房 + 缩放手势 + 右滑透明 | ✅ 已写 |
| **P3** | 关注页（本地收藏 + 在播匹配 + 星标）+ 聊天区 + 弹幕过滤 + 主题 | ✅ 已写 |
| **P4** | 可选：服务端关注同步、WS 实时弹幕、发弹幕、观看历史 | ⬜ 未做（依赖你自备反代端点） |

下一步就是 push → 看 CI 第一次构建的结果 → 按日志修编译 → 装包实测手感。

---

## 10. 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| librtmp 对部分 RTMP 变体兼容差 | 个别房间黑屏 | 出错浮层给「重试」+「外部播放器」；设置里可填 HLS 网关 |
| 缩放 + Pager 手势冲突 | 缩放时误翻页 | 缩放 >1.01 时 `userScrollEnabled=false`；点击手势不消费事件 |
| 快照列表会过期 | 点进去发现主播下播 | 卡片显示人气；进房失败可直接下滑换下一个 |
| Compose 版本 / AGP 不匹配 | CI 首跑失败 | 版本已锁定为验证过的组合，按日志调整 |
| 动态取色把界面染成奇怪颜色 | 观感失控 | 只让系统决定表面色，`primary` 固定为品牌粉 |
| 仓库公开发布 | 内容合规风险 | 不含任何凭证、不含 APK 本体、README 声明仅协议学习 |
