# DC Live · 安卓 App 设计方案（v1 已定稿，代码已落地）

> 目标：把现有 `site/` 网页改写成原生安卓 App，用 GitHub Actions 云端编译，本机零 Android 环境。
>
> **本文档状态：定稿（2026-10-29 按实现校正）。** 代码在 `app/`，流水线在 `.github/workflows/android.yml`，
> **默认数据源是实时模式**（内置房间号池 + 本机隧道），降级用的快照在 `app-data/rooms.json`，
> 仓库 `github.com/YeJoe/91zb-android` 的 **`duocai` 分支**。
>
> 结构：**底部三栏**（首页 / 关注 / 设置）→ 点卡片进**全屏播放页**（上下滑切房）→ 返回落回原 tab。

---

## 0. 一句话方案

**Kotlin + Jetpack Compose 单 Activity；底部三栏（首页 / 关注 / 设置）；数据默认走实时模式（内置房间号池 + 本机隧道 `get_list_by_ids` 批量刷新，隧道不通降级 JSON 快照）；播放页全屏覆盖，VerticalPager 整屏上下切房，播放中屏幕常亮 + 画中画，双指 1–3× 自由缩放；Media3 直连 FLV/RTMP；弹幕区右滑变透明；关注用本地收藏 + 在播列表比对；配置全在设置页。**
`git push` → GitHub Actions 出 APK，本地不装 JDK/SDK/Gradle 任何一个。

### 0.1 三项定稿信息

| 项 | 结论 |
|---|---|
| 仓库 / 分支 | `https://github.com/YeJoe/91zb-android.git` → **`duocai`** 分支（数据源 + 代码都在这个分支） |
| App 名称 | **DC Live**（包名 `com.duocai.live`，`applicationId` 带 `.debug` 后缀区分调试包） |
| 弹幕聊天区 | **右滑透明**：不透明度 0.62 一路滑到 0，全透明时只剩带投影的文字，不遮挡画面；左滑恢复；也可点头部的「背景 xx%」循环 62% / 25% / 透明 |


---

## 1. 关键事实（全部实测，不是推测）

| # | 事实 | 实测结果 | 对设计的影响 |
|---|---|---|---|
| 1 | **播放完全不需要凭证** | `ffprobe rtmp://<域名>/live/64378161` → **h264 720x1280 + aac**，地址里没有任何 token | 播放链路成立，这是 App 能独立工作的基础 |
| 2 | 带 token 的流地址不是必需的 | `.stream_url.txt` 里是 `...?token=...&t=1793289341&liteavToken=...`（App 侧生成、带过期），但去掉整串照样能拉 | 不实现 token 生成，播放地址就用 `rtmp://<域名>/live/<房间号>` |
| 3 | **`/room/list` 已下线（2026-10-29 复核）** | 17 个本地监听端口全部 404；源 App 冷启动 → 切分类 → 进房 全流程抓包里一次都没调它。源 App 自己维护 `localData:room.<uid>` 本地表（1972 间），靠 WS 增量维护 | 列表不能靠接口「列」出来 → 内置**房间号池** + `/room/get_list_by_ids` 批量刷新。旧结论「只能读 JSON 快照」作废 |
| 4 | 明文 API 活在**本机**隧道里 | App 打 `127.0.0.1:<随机端口>`，端口每次重启都变（实测 `38481→45209→33297→38103→36909`） | **在装了源 App 的手机上，这个端口是真实存在的** → 实时模式成立；读 `/proc/net/tcp` 自动探测，探不到才降级 JSON |
| 5 | 现有 token 是**游客号** | `.session.json`：`id=2094967700`、`nickname=包子臉哈哈_mqrnni`、`login_type=quick` | 游客号也能读大厅与播放；但拿不到真实关注关系（`/user_follow/my_follow` 只回主播 id，没房间号） |
| 6 | 会话 token **不落盘** | 8.5.3 起 `?token=` 只存在内存，必须 root + `tcpdump -i lo` + **冷启动**才抓得到 | 导出工具独立成 `tools/token_export.py`，App 里只存用户粘进来的值（Keystore 加密） |

## 1.5 那 assets/ 里这些凭证，对 App 到底有什么用？

你把凭证挪到了 `assets/`，我逐个核过：

| 文件 | 内容 | App 用得上吗 |
|---|---|---|
| `.rtmp_domain.txt` | `1dtluv36amaiuckq.7kv5kqq7k77g.hebeisaixin.com` | ✅ 拼播放地址用。默认值内置，设置里可改 |
| `.apptoken.json`（新）/ `.apptoken.txt`（旧） | 32 hex 会话 token | ✅ **实时模式要它**：请求隧道时带 `?token=`。随源 App 重启失效 |
| `.jwe.txt` | 330 字符 Bearer | ✅ 同上，两个都得带（`?token=` + `authorization: Bearer`） |
| `.stream_url.txt` | 带 `token/t/liteavToken` 的完整 rtmp 地址 | ❌ 已验证非必需，且 `t` 是过期时间戳 |
| `.api_port` | 一次性端口（如 `24278`） | ❌ 别用。端口每次重启都变，App 自己扫 `/proc/net/tcp` |
| `.session.json` | 游客登录返回体 | ❌ 仅供追溯当初的登录参数（`channel=ecy`、`version=8.5.3`） |

**结论（2026-10-29 修正）**：凭证**是**实时模式的门票——`?token=` + `authorization: Bearer <JWE>` 两个都带上，
再配上本机隧道端口，就能用 `get_list_by_ids` 刷出 1900+ 间在播。但：

- 它**不是「装进 APK 就能联网」的通行证**：token 绑定源 App 的当前会话，App 一重启就废；
- 所以 token 绝不编译进 APK——由用户自己粘，Keystore 加密存本机；
- 拿不到 token / 隧道不通时，退回 `app-data/rooms.json` 快照，仍能看（只是不更新）。

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

**预估产物体积**：`app-release.apk` ≈ **4 MB**（只出 arm64-v8a 单包）。比初稿的 6~7MB 小了一档，原因就是砍掉了 Coil / OkHttp / serialization / DataStore 四个依赖，外加不再出 universal 包。

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
│ DC LIVE          ⟳   ⌕      │  ← 大标题 + 刷新 + 搜索
│ [全部 1943][星秀 236][交友…]│  ← 分类 chips，按 menu_id 本地分组，横向滑动
│ 排序: 人气 ▾   仅真人 ●      │
├─────────────────────────────┤
│ ┌─────────┐ ┌─────────┐    │  ← 双列瀑布卡片
│ │  封面    │ │  封面    │    │     渐变底 + 名字首字 + 左下在线人数
│ │         │ │         │    │
│ ├─────────┤ ├─────────┤    │
│ │  封面    │ │  封面    │    │
│ └─────────┘ └─────────┘    │
└─────────────────────────────┘
```

- 双列 `LazyVerticalStaggeredGrid`，卡片圆角 16dp，每张卡按 `room.id % 5` 取不同宽高比，让瀑布有错落感
- 底部压暗渐变 + 房间名 / 在线人数 / 分类 / 归属地，保证文字在亮色渐变上也读得清
- 右上角推广房标 `推广`（金色小标签，默认过滤掉）
- 刷新按钮在拉取中显示转圈并禁用，避免重复触发；`reload()` 里也有 `loading` 防重入
- 顶部无数据时显示「数据源没加载成功 / 去设置」引导卡
- 卡片右上角 **星标**（点了只收藏、不进播放页）
- 点击卡片 → 直接进播放页并定位到该房间（Pager 起始页 = 点击的位置），播放序列就是当前筛选后的列表

### 3.2 播放页（核心）

```
┌─────────────────────────────┐
│ ←  │ 主播名 · 颜值 · 1.2万  │  ← 顶部渐变遮罩，单击隐/显
│                    [适应][浮窗]│  ← 缩放胶囊（适应/拉伸/×2.4）+ 画中画
│         视频画面             │  ← 全屏，双指 1–3× 缩放 + 拖动
│                      3/1943  │  ← 右侧角标：当前第几间 / 共几间
│                        ↑↓    │
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
- **上下滑切房**：`VerticalPager` 整屏翻页，每页只画封面，视频层固定不动（见 §4.1）。
  首次进播放页会弹一次「↑ 上下滑动切换直播间 ↓」，看过即记 `sawPlayerSwipeHint`
- **屏幕常亮**：进入播放页给 Window 加 `FLAG_KEEP_SCREEN_ON`，`DisposableEffect` 退出即清——
  只在这一页生效，回到列表该灭还是灭
- **画中画**：播放中按 Home 自动缩成 9:16 小窗（`onUserLeaveHint` → `enterPictureInPictureMode`），
  小窗里顶栏 / 弹幕区 / 手势层全部不渲染，`chromeAlpha` 直接 0，只留画面。
  也可点顶栏「浮窗」手动进。设置里可关掉自动缩窗
- **右滑透明**：在聊天区任意位置向右拖 → 背景不透明度线性下降（跟手，1:1 映射到 400dp 行程），
  降到 0 时背景与输入条完全消失，只剩带投影的弹幕文字浮在画面上；向左拖回来即恢复。
  头部的「背景 xx%」按钮可循环 62% → 25% → 全透明。首次进入会给 4 秒提示
- 单击视频 = 隐/显整个 chrome（顶栏 + 聊天区）；双击 = 暂停/继续（缩放态下则是复位）
- 双指 = 缩放 1–3×，缩放后单指平移；缩放时禁用 Pager 纵向翻页，避免误切房

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
每条关注记录拿 `房间 id / 主播名` 去**当前在播列表**里比对，命中且 `on_air=1` 的进「正在直播」，其余进「未开播」（封面去饱和）。

**关注数据的三级来源（逐级降级）**：

| 级别 | 来源 | 可用性 | 说明 |
|---|---|---|---|
| L1 | **App 本地收藏** | ✅ 立刻可用 | 点星即存 `roomId / 主播名 / 分类 / 关注时间`，不依赖任何服务端 |
| L2 | 当前在播列表匹配 | ✅ 立刻可用 | 拿 L1 的 id 去 `AppContainer.rooms` 查在播状态、最新人气与分类 |
| L3 | 服务端 `/user_follow/my_follow` | ❌ 本版不做 | 见下方 |

**"能不能拿到已登录账号的关注"—— 接口在，但拼不出播放地址**：

- 接口确实有：`POST /user_follow/my_follow`，隧道内可正常调通
- 但它只回 **`podcast_id`（主播 id）**，**不带房间号**；播放地址是 `rtmp://<域名>/live/<房间号>`，
  没有房间号就无从播放。要拿房间号还得再查一次「主播 → 房间」，而 8.5.3 已经没有这个接口（`tb_name=room` 查询失败）
- 所以 L3 **这一版直接不做**，不占设置页位置，也不假装能同步

> 一句话：关注页靠「本地收藏 + 在播列表比对」就能用；服务端关注关系这一版拿不到房间号，不做。

### 3.4 设置页

分 6 组，**全部即时生效**、持久化：

| 分组 | 项 | 说明 |
|---|---|---|
| **数据源** | 房间数据 URL | 降级通道：实时模式拿不到数据时才用它。默认 jsDelivr 镜像 |
| | 导入配置 / 直接粘贴 JSON | 粘贴 `token_export.py` 导出的整段文本，一键填好 token / JWE / 端口 |
| **实时直播源**（需源 App 同设备后台运行） | 实时模式 | 开 = 走本机隧道（默认）；关 = 只读 JSON 快照 |
| | 会话 Token / JWE | 两个都要。Keystore 加密存本机，不进 APK |
| | 隧道端口 | 留空自动扫 `/proc/net/tcp`；填了优先用它 |
| | 自动刷新 | 关 / 10s / 20s / 30s（默认 20s）。实时模式下定时重拉在播列表 |
| **播放** | RTMP / 流媒体域名 | 必填，拼 `http://<域名>/live/<房间号>.flv` 与 `rtmp://…` |
| | 播放通道 | 自动（FLV → RTMP）/ 只 FLV / 只 RTMP |
| | 备用 HLS 网关 | 可选，指向 PC 上的 `gateway.py` |
| | 画面缩放 | **适应 / 拉伸**两档。「裁剪」已去掉——固定裁切会切掉画面边缘，改由播放页双指自由缩放代替 |
| | 缓冲 | 0.8s / 1.5s / 3s（默认 1.5s，弱网调大；下次启动生效，解码器只在创建时读一次） |
| | 进房自动播放 | 默认开 |
| | 切后台自动浮窗 | 默认开。关掉后只能点播放页顶栏的「浮窗」手动进画中画 |
| **弹幕** | WebSocket 端点 / 鉴权 Token | 可选；留空则回放打包进 APK 的 182 条真实样本 |
| | 只显示观众聊天 | 默认开（复用 `danmaku_filter` 的 `typ` 规则，已移植成 Kotlin） |
| **关注** | 已关注数量 / 清空 | 纯本地，不依赖账号 |
| **外观** | 主题 / 显示推广位 | 跟随系统 / 深色 / 浅色；推广位默认隐藏 |
| **关于** | 版本 / 包名 / 数据源说明 | 版本号来自 CI 构建 |

> **关于「开关点了不生效」**：早期版本 UI 直接读 `prefs.xxx`（SharedPreferences 的普通 getter），
> 写盘不触发 Compose 重组，所以点了没反应、要等别的原因引起重组才「突然生效」。
> 现在 `streamMode` / `scaleMode` / `bufferMs` / `autoPlay` / `refreshSec` / `liveMode` / `autoPip`
> 统一挂在 `AppContainer` 的 `mutableStateOf` 上，setter 里同步写盘（`bufferMs` 还顺手下发给解码器）。
> 另外 `Switch` 自身带 `onCheckedChange`，和整行 `clickable` 叠在一起会「点一次切两次」，
> 所以统一 `onCheckedChange = null`，只走整行点击。

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

**「裁剪」模式已删除（2026-10-29）**：`RESIZE_MODE_ZOOM` 会固定切掉画面上下（或左右）边缘，
直播画面里的人经常正好在边缘上，看不出是被裁了还是本来就没拍到。改成「两档预设 + 自由缩放」：

| 层 | 做法 |
|---|---|
| **预设** | `AspectRatioFrameLayout.RESIZE_MODE_FIT`（适应，保比例留黑边）/ `RESIZE_MODE_FILL`（拉伸，铺满变形）。设置页与顶栏胶囊都能切 |
| **自由缩放** | 自己写的双指手势，**只有两根手指才消费事件**：`zoom = 缩放比 × 上一帧`，钳在 **1.0–3.0**，按双指中心同步平移 |
| **平移钳制** | 按 `size × (zoom-1) / 2` 限制，画面不会被拖出边界 |
| **状态显示** | 顶栏胶囊：`zoom > 1.01` 时显示 `×2.4`，点一下先还原到 1×，再点才切适应/拉伸 |
| **与翻页互斥** | 缩放 > 1.01 时 `userScrollEnabled = false`，避免缩放和翻页抢手势；双击也先复位 |

> 视频本体不会重新解码——`graphicsLayer { scaleX/scaleY/translationX/translationY }` 是渲染层变换，
> 缩放和平移都不触发重新缓冲。

> 手势层压在 Pager 上面，所以**不能用 `detectTapGestures`**（它会 consume 掉 down，
> 单指上下滑就被吃掉了）。改成自己判"按下后没有移动 = 点击"，单击延迟 260ms 等第二下，全程不消费事件。

### 4.4 弹幕区

- `LazyColumn`，最多保留 200 条，贴底时自动滚到最新；手动上滑后显示「↓ 回到底部」
- 过滤规则从 `site/dm_filter.js` **1:1 移植成 Kotlin**（`data/Danmaku.kt` 的 `DmFilter`），保证与网页版行为一致
- 实时来源：配置了 `wsEndpoint` 才订阅；没配就用**打包进 APK 的真实样本**（`assets/danmaku_demo.json`，182 条）按接近实时的节奏回放，聊天区不会空着
- **右滑透明**：`detectHorizontalDragGestures` 把 `bg` 从 0.62 线性拉到 0；背景是 `verticalGradient(黑 0.10 → 0.72 → 0.86)`，乘上 `bg`，
  所以 `bg=0` 时渐变整条消失；文字始终带 `Shadow(blur 5f)`，透明后仍然可读

### 4.5 屏幕常亮与画中画

**常亮**（`PlayerScreen` 里的 `DisposableEffect`）：

```kotlin
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
```

只加在播放页，退出立刻还回去——不用 `WakeLock`（要权限、容易漏释放）。

**画中画**（三条命令 + 一个状态）：

| 位置 | 内容 |
|---|---|
| `AndroidManifest.xml` | `android:supportsPictureInPicture="true"`（API 26+ 才认，低版本自动忽略） |
| `MainActivity.enterPip()` | `PictureInPictureParams.Builder().setAspectRatio(Rational(9,16))` → `enterPictureInPictureMode(params)`。直播都是竖屏，9:16 小窗不留大黑边 |
| `MainActivity.onUserLeaveHint()` | 播放页开着时按 Home → 自动进小窗（设置里可关：`autoPip`） |
| `onPictureInPictureModeChanged` | 写 `inPip`（`mutableStateOf`），播放页据此**整块不渲染**顶栏 / 弹幕 / 手势层，`chromeAlpha` 直接 0 |

> `minSdk` 是 24，而 `PictureInPictureParams` 是 26 的 API，所以 `enterPip()` 里必须显式挡一层
> `if (Build.VERSION.SDK_INT < O) return`，否则 lint 报 `NewApi`。

---

## 5. 工程结构（已落地）

```
app/
├── build.gradle.kts                  # 版本号可由 CI 环境变量覆盖；签名按 secret 是否存在决定
├── proguard-rules.pro                # 保留 librtmp JNI 类
└── src/main/
    ├── AndroidManifest.xml           # DC Live · com.duocai.live · 竖屏 · cleartext · supportsPictureInPicture
    ├── assets/
    │   ├── room_ids.txt              # 1972 个房间号种子（实时模式的 id 池），22KB
    │   └── danmaku_demo.json         # 182 条真实弹幕样本（离线回放用）
    ├── res/
    │   ├── layout/view_player.xml    # 只有这里能设 surface_type=texture_view
    │   ├── drawable/ic_launcher.xml  # 矢量图标，不放 PNG
    │   └── values/{strings,colors,themes}.xml
    └── java/com/duocai/live/
        ├── App.kt                    # Application + Context.app 取容器
        ├── AppContainer.kt           # 手写容器 + 全局状态（rooms/tab/openIndex/followedIds）
        │                             #   + 设置项的可观察镜像（streamMode/scaleMode/bufferMs/autoPlay/
        │                             #     refreshSec/liveMode/autoPip），setter 同步写盘
        ├── MainActivity.kt           # 单 Activity，edge-to-edge，画中画（inPip / enterPip / onUserLeaveHint）
        ├── data/
        │   ├── Models.kt             # Room（含 menuId）/ SourceMeta / SourceParser（org.json）
        │   ├── Prefs.kt              # SharedPreferences + Keystore AES/GCM 加密 token
        │   ├── LiveRepo.kt           # ★ 实时数据源：端口探测 + id 池 + get_list_by_ids 批量刷新
        │   ├── RoomRepo.kt           # 降级通道：URL → 缓存文件 → 解析（HttpURLConnection）
        │   ├── FollowStore.kt        # 本地关注（房间号 + 名字 + 时间戳）
        │   └── Danmaku.kt            # DmFilter（dm_filter.js 的 Kotlin 移植）+ 离线回放
        ├── player/PlayerHolder.kt    # 单 ExoPlayer + FLV/RTMP 数据源 + 错误归类
        └── ui/
            ├── Common.kt             # 渐变配色 / 首字 / 人数格式化 / 昵称配色
            ├── RootScreen.kt         # 底部三栏 + 播放页覆盖
            ├── HomeScreen.kt         # 瀑布流卡片 + 分类 chips + 搜索 + 星标
            ├── FollowScreen.kt       # 正在直播 / 未开播 两组
            ├── SettingsScreen.kt     # 数据源 / 实时直播源 / 播放 / 弹幕 / 关注 / 外观 / 关于
            ├── PlayerScreen.kt       # Pager + 封面层 + 手势 + 聊天区 + 常亮 + 画中画分支
            └── theme/Theme.kt        # Material3 + Android 12 动态取色（主色锁品牌粉）
.github/workflows/android.yml         # 出包流水线
app-data/rooms.json                   # 降级用的快照数据（300 间 / 84 个推广位 / 31.6KB）
tools/build_app_data.py               # site/data.js → rooms.json + 弹幕样本
tools/build_site3.py                  # 号池 → get_list_by_ids → site/data.js
```

**实时数据怎么流动**（`LiveRepo`）：

```
读 /proc/net/tcp 的 LISTEN 端口
        ↓  用真实房间号打 /room/get_list_by_ids 试探（认响应里的 rooms 键）
选定 vhost 端口（存 prefs.tunnelPort，60s 内不重扫）
        ↓  号池 1972 个 id，250/批、3 批一波并发
{"code":0,"rooms":[{"id":…,"on_air":1,"player_count":…,"menu_id":…}]}
        ↓  只留 on_air=1，按 player_count 降序
rooms → AppContainer.rooms（Compose 状态）→ 首页瀑布流 / 分类 chips（按 menu_id 本地分组）
```

**`rooms.json` 从哪来**（降级通道，仍然保留）：`tools/build_app_data.py` 读 `site/data.js`，
瘦身成 App 需要的字段（房间号 / 名字 / 分类 / 人气 / 归属地 / 是否推广 / 钻数 / rtmp 域名），
顺手把 `site/dm_samples.js` 转成 `app/src/main/assets/danmaku_demo.json`。刷新数据就是三步：

```bash
python tools/build_site3.py        # 号池 → /room/get_list_by_ids → site/data.js
python tools/build_app_data.py     # → app-data/rooms.json + assets/danmaku_demo.json
git add -A && git commit -m "data: 刷新房间列表" && git push
```

> `build_site3.py` 以前靠 `/room/list` 找房间号，**那个接口已经下线**，现在改成读
> `app/src/main/assets/room_ids.txt`（与 App 同一份号池）+ 补齐 `site/data.js` 里已有的号。

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
- **ABI**：`splits.abi` 只留 `arm64-v8a`（`isUniversalApk = false`），产物就一个 `app-arm64-v8a-*.apk`，体积最小。
- **缓存**：`setup-gradle` 自带依赖缓存，第二次构建 ~2 分钟。

首次构建预计 **6~9 分钟**（含下载 Gradle 与依赖）。

---

## 7. 我能做 / 不能做（先说清）

| | 状态 |
|---|---|
| 写全部 Kotlin / Compose / Gradle / workflow 代码 | ✅ 已写完，17 个 Kotlin 文件 |
| 本地静态检查（JSON / YAML / HTML / JS 语法） | ✅ 已跑（内联 JS 过 `node --check`，数据源过 JSON 解析） |
| **本地编译验证** | ❌ 不装 JDK/SDK，靠 CI（push 到 `duocai` 分支）暴露编译错误 |
| 在手机上跑起来调 UI | ❌ 需要你装 APK 反馈 |
| **直连隧道拿实时列表** | ✅ 已打通：端口探测 + id 池 + `get_list_by_ids`，实测 1943 间在播 / 2–6s |
| 实时弹幕 | ❌ 隧道被源 App 独占，WS 旁观拿不到全量；需自备反代端点 |

> 现实预期：CI 首跑大概率会有 1~3 轮编译错误（依赖 API 细节、Compose 版本差异），根据 Actions 日志修到绿。
> 这是云端编译的正常成本，不是设计缺陷。
> 自查过的高风险点：`ExoPlayer.setLoadControl`（改缓冲用）、`VerticalPager` 的 `key` 参数、
> `PictureInPictureParams` 在 minSdk 24 上的 `NewApi` lint —— 这几处若报错，都是一行改法。

---

## 8. 已定 / 待定

### 已确认（全部，无遗留）

| 项 | 结论 |
|---|---|
| App 名称 | **DC Live** |
| 包名 | **`com.duocai.live`**（debug 包 `com.duocai.live.debug`） |
| 仓库 / 分支 | `github.com/YeJoe/91zb-android` → **`duocai`**；push 即触发 Actions 出包 |
| 数据源 | **默认实时模式**：内置 `assets/room_ids.txt`（1972 个号）+ 本机隧道 `get_list_by_ids`；隧道不通降级 `app-data/rooms.json`（jsDelivr 镜像，国内可达） |
| 房间号池 | 静态种子，**只增不减**；新主播新建的号发现不了（8.5.3 没有列表接口可翻页） |
| 弹幕输入 | **只读展示**（输入条置灰 + 提示"配置端点后可发"） |
| 弹幕透明 | **聊天区右滑变透明**（0.62→0），另有「背景 xx%」循环按钮 |
| 关注 | 本地收藏为主；服务端 `/user_follow/my_follow` 只回主播 id（无房间号），**这一版不做同步** |
| 播放页 | 上下滑切房 / 双指 1–3× 缩放 / 适应·拉伸两档 / **播放时屏幕常亮** / **画中画（可关自动缩窗）** |
| 播放降级 | RTMP 失败 → 重试 → 「外部播放器」按 `ACTION_VIEW rtmp://…` 唤起 VLC/其它播放器（`runCatching` 兜底，失败不崩） |
| 主题 | 跟随系统 / 深色 / 浅色；Android 12+ 用壁纸取色（主色锁品牌粉） |

> 没有遗留待定项了。剩下的是实机调优：滑动手感、字体大小、缓冲档位这些只能上手改。

## 9. 分期建议（P1–P3 本轮已一次性写完）

| 期 | 内容 | 状态 |
|---|---|---|
| **P1** | 工程骨架 + CI + 底部三栏 + 首页列表 + 设置页（数据源/域名） | ✅ 已写 |
| **P2** | 播放页 + 单播放器（视频层固定）+ 上下滑切房 + 缩放手势 + 右滑透明 | ✅ 已写 |
| **P3** | 关注页（本地收藏 + 在播匹配 + 星标）+ 聊天区 + 弹幕过滤 + 主题 | ✅ 已写 |
| **P3.5** | 实时数据源（端口探测 + id 池 + `get_list_by_ids`）、设置项即时生效、上下滑切房、常亮、画中画、去裁剪改自由缩放 | ✅ 已写（2026-10-29） |
| **P4** | 可选：服务端关注同步、WS 实时弹幕、发弹幕、观看历史 | ⬜ 未做（依赖你自备反代端点） |

下一步就是 push → 看 CI 构建结果 → 按日志修编译 → 装包实测手感。

---

## 10. 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **号池是静态种子** | 新主播新建的房间号发现不了 | 池子只增不减；刷新时见到的新号自动回流。真需要新号只能在源 App 里逛到后手动追加 |
| **token 随源 App 重启失效** | 实时模式报「隧道端口不可用」 | 出错时明确提示重新跑 `token_export.py`；拿不到就退回 JSON 快照 |
| **源 App 被系统杀掉** | 隧道端口消失 | 60 秒端口缓存失效后会重新扫；整体失败时强制重探一次 |
| librtmp 对部分 RTMP 变体兼容差 | 个别房间黑屏 | 默认走 HTTP-FLV（实测最稳）；出错浮层给「重试」+「外部播放器」；设置里可填 HLS 网关 |
| 缩放 + Pager 手势冲突 | 缩放时误翻页 | 缩放 >1.01 时 `userScrollEnabled=false`；点击手势不消费事件 |
| `PictureInPictureParams` 是 API 26 | minSdk 24 上 lint 报 `NewApi` | `enterPip()` 里显式判 `SDK_INT >= O`；manifest 属性在低版本被忽略 |
| Compose 版本 / AGP 不匹配 | CI 构建失败 | 版本已锁定为验证过的组合，按日志调整 |
| 动态取色把界面染成奇怪颜色 | 观感失控 | 只让系统决定表面色，`primary` 固定为品牌粉 |
| 仓库公开发布 | 内容合规风险 | 不含任何凭证、不含 APK 本体、README 声明仅协议学习 |
