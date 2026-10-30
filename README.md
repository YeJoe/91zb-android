# 天美直播 App · 逆向分析与链路复现

对「天美」直播 App 8.5.3（包名 `com.l95c2450d7.f68bb4e2d5`，渠道 `ecy`）的完整分析产出：
**绕开加固与域名下发，直连本地隧道明文通道，独立复现房间列表、分类、视频播放与弹幕。**

> **命名说明**：包名 `com.l95c2450d7.f68bb4e2d5` 对应的真实应用名是**天美**。早期分析一度误称其为「多彩」，
> 所以仓库与父目录里仍残留 `duocai` 字样。本文用「项目根」统称仓库根目录，不依赖具体文件夹名；
> 文档与脚本内部一律用相对路径，改名不影响运行。

不依赖 App 运行、不需要中间人证书、不需要破解加固 so。

产出分三层，互相独立：

| 层 | 目录 | 说明 |
|---|---|---|
| **网页版** | `site/` | 纯静态，双击 `index.html` 就能看分类、列表、播放、弹幕；带弹幕分类过滤开关、手机布局、弹幕区右滑透明 |
| **客户端脚本** | `tools/` | 复现同一套接口的命令行客户端：隧道探测、房间列表、实时弹幕、RTMP→HLS 网关 |
| **安卓 App** | `app/` | **TM Live**：Kotlin + Compose 原生 App，底部三栏（首页/关注/设置），上下滑切房，实时弹幕，在播探活，画中画；GitHub Actions 云端编译，本机不用装任何安卓环境 |

> App 的包名是 `com.duocai.live`。**装完即用**：`libkiwi.so` 和云层 SDK 直接打进 APK，
> App 启动时自己握手、自己拿本地端口，直连源 App 的服务端接口——
> **不需要源 App 常驻，不需要 root，不需要抓包**。
> 列表用 id 池 + `/room/get_list_by_ids` 批量刷新（1972 号 → 1900+ 间，2–6 秒），
> 隧道不可用时降级到本地缓存、再降级到 `app-data/rooms.json` 快照。
> 编译产物（APK）不进仓库，走 Actions 的 artifact / Release。
>
> App 现在到底怎么跑的（数据链路、探活策略、弹幕、画中画、已知边界）全在
> **[`docs/App实现现状.md`](docs/App实现现状.md)**。

---

## 目录

顶层按"跑什么 / 看什么 / 留证据"分工，互不重叠：

```
项目根/
├── README.md                      本文
├── site/                          ★ 网页版，纯静态可发布（548K）
├── app/                           ★ 安卓 App（TM Live），Kotlin + Compose
├── app-data/                      ★ App 的数据源 rooms.json（32K）
├── tools/                         ★ 客户端脚本 + 数据构建（282K）
├── docs/                          ★ 文档 20 篇（476K），索引见 docs/README.md
├── .github/workflows/android.yml  ★ 出包流水线
└── assets/                        ☆ 保留素材，全部不入库（约 115M）
```

### site/ — 网页版

| 文件 | 体积 | 作用 |
|---|---|---|
| `index.html` | 35K | 分类 + 搜索 + 排序 + 列表 + 播放器 + 弹幕区；桌面/手机两套布局 |
| `data.js` | 72K | 300 个真实房间 + 34 个分类 + RTMP 域名，由 `build_site3.py` 生成 |
| `dm_samples.js` | 14K | 弹幕演示样本 + 全量分类统计（`window.DM_STATS`），由 `build_danmaku.py` 生成 |
| `dm_filter.js` | 6.6K | 前端弹幕分类/过滤规则，与 `tools/danmaku_filter.py` 同源 |
| `vendor/hls.min.js` | 404K | 本地 hls.js——Chrome/Edge 没有原生 HLS，必须靠它 |

> `data.js` 和 `dm_samples.js` 都是**生成物**，不要手改；改数据请改生成脚本。

### tools/ — 客户端脚本

| 分组 | 文件 | 说明 |
|---|---|---|
| **运行链路** | `gateway.py` | RTMP→HLS 网关，同时托管 `site/`；打印手机可用的局域网地址 |
| | `build_site3.py` | 拉 7 个真人分类的房间，13 秒刷新 `site/data.js` |
| | `live_ws.py` | 实时通道：`join` 进房 / `hist` 历史弹幕 / `watch` 持续监听 / `leave` |
| **弹幕** | `danmaku_probe.py` | 采样：`history_msg` + 实时推送 → `assets/data/dm.jsonl`（断点续采） |
| | `danmaku_filter.py` | 类型码分类 + 广告过滤，**唯一规则源** |
| | `build_danmaku.py` | 样本 → `site/dm_samples.js`，附全量统计 |
| **隧道底层** | `tunnel_map.py` | adb 端口映射、vhost 探测（`/websock/*` 回 405 的那台才是业务口） |
| | `ws_probe.py` | WebSocket 握手与帧读写 |
| | `tunnel_api.py` | 把 `/tunnel/open` 上的明文 HTTP 封装成普通请求 |
| | `wsframe.py` / `wsdump.py` | 帧编解码、抓包复放 |
| **业务客户端** | `json_api.py` | JSON 接口 + token 获取 |
| | `room_api.py` | 房间详情（`/room/get_list_by_ids`）；`/room/list` 已下线，留作历史 |
| | `live_client.py` | 拉流探测（已废弃：测的是推流状态，不是身份） |
| **抓包解析**（历史） | `pcap_streams.py` | 从 pcap 还原 TCP 流 |
| | `rtmp_sniff.py` | 从 pcap 提取 RTMP 消息 |
| **加密 / so**（历史） | `rabbit.py` | RabbitCipher 变体复现 |
| | `dun_replay.py` / `dun_keys.txt` | 握手重放与抓到的密钥 |
| | `idalib_kiwi.py` | 在 IDA 里调 libkiwi.so 做解密 |

### docs/ — 文档

**完整索引见 [`docs/README.md`](docs/README.md)**（含「哪些旧结论已被推翻」对照表）。先看这五篇：

| 文件 | 内容 |
|---|---|
| **`App实现现状.md`** | ★ **单一事实源**：App 现在到底怎么跑——数据链路、探活策略、弹幕、画中画、设置项、已知边界、代码地图 |
| **`8.5.3接口现状.md`** | ★★ **接口基准**：接口存活表、id 池方案、凭证、端口探测（其余文档与它冲突时以它为准） |
| `C方案-自研隧道可行性.md` | 把 `libkiwi.so` 搬进自己进程（已落地，所以不再依赖源 App） |
| `源App在播判定-冷启动实测.md` | 源 App 怎么判「在播」、`live_in` 真实区分力、403 是房间级、探活成本模型 |
| `弹幕接口实测-端口与判据.md` | 端口每次冷启动都变、405 才是 IM vhost、进房实测收 130 条 |

其余按主题分：接口与通道（`直播接口清单.md` / `明文长连接通道.md` / `明文接口突破.md`）、
视频与筛选（`视频流拉取方案.md` / `真人直播间筛选.md`）、弹幕（`弹幕分类与过滤.md`）、
过程（`成功总结.md` / `攻坚全记录.md`）、取证（`实机运行报告.md` / `MuMu抓包指南.md` /
`BASE与TOKEN获取方案.md`）、底层历史（`加密方式分析.md` / `功能模块分析文档.md` / `安全分析报告.md`）。

### assets/ — 保留素材（不入库）

**只有要留证据、又要占空间的东西才放这里**，运行完全不需要它们。

| 路径 | 体积 | 用途 | 能否再生 |
|---|---|---|---|
| `channel.apk` | 89M | 8.5.3 渠道包原包 | ❌ 唯一不可再生，**别删** |
| `archive/853_all_api.pcap` | 18M | **8.5.3 全接口抓包**：冷启动 → 切分类 → 进房 → 退出，18060 包 / 113 flow，`/room/list` 已下线的直接证据 | ❌ 要源 App 在跑且重新走一遍全流程 |
| `archive/pp.txt` | 7.2M | blutter 反汇编文本 | ⚠️ 需版本匹配的 blutter，重跑有失败概率 |
| `archive/join2.pcap` | 340K | 进房抓包，`/websock/join` 的原始证据 | ⚠️ 需重新进房抓包 |
| `archive/853_lo_token.pcap` | 315K | 冷启动时 `lo` 回环上的 `?token=` 抓取过程 | ❌ 必须 root + 冷启动 |
| `archive/853_get_list_by_ids.sample.json` | 343K | 一次 250 id 的完整响应样本，字段对照用 | ✅ 接口还通就能重拉 |
| `archive/lobby_menu.json` | 4.3K | 34 个分类的原始响应 | ✅ 接口还通就能重拉 |
| `archive/login_init.json` / `login_init_20261029.json` | 48K ×2 | 登录初始化（域名 / token 下发），后者是 10-29 的最新一次 | ⚠️ 需 App 在线 |
| `archive/version_info.json` | 662B | 版本与渠道 | ✅ |
| `archive/kiwi_report.txt` | 26K | libkiwi.so 分析记录 | ⚠️ 需重新分析 |
| `archive/AndroidManifest.xml` | 15K | 清单文件 | ✅ 从 apk 解 |
| `data/dm.jsonl` | 176K | 弹幕原始采样：31 房 / 462 条 | ⚠️ 需 App + 隧道在线，token 现已过期 |
| `ida_decomp/*.c` | 105K | libkiwi.so 的 RabbitCipher 反编译 | ⚠️ 需 IDA 重新反编译 |

**判断标准**：删除前先问「重新生成它要什么条件」。要 App 在线、要 IDA、要 blutter 的，都留着——
它们加起来才 115M 里的零头，真正的大头只有 `channel.apk` 一个。

> 2026-10-29 已清掉一轮：`tools/_dbg/`（140M，含与 `channel.apk` 完全相同的 `base.apk`、
> 可再生的 `libapp.so` / `fsp.xml`、一次性调试脚本）以及三个误提交进 git 的 CI 查询残留
> （`.r.json` / `.runs.json` / `.dist/build6.zip`）。
>
> 2026-10-30 又清一轮：`.tmp/` `.tmp_analysis/` `.tmp_dm/` `.tmp_pip/` `.dist/` `tools/__pycache__`
> （共 85M，全是一次性探测脚本、pcap 副本、模拟器截图、APK 构建残留），并把 `.tmp_*/` 加进了
> `.gitignore`。同一轮还删了三份过时文档（早期设计稿、RabbitCipher 补充笔记、改源 App 的二进制教程），
> 清单见 `docs/README.md` §九。

### 隐藏文件

| 路径 | 说明 |
|---|---|
| `.jwe.txt` / `.apptoken.txt` / `.session.json` | 运行期凭证，`gateway.py` / `live_ws.py` / `build_site3.py` 依赖；token 随 App 重启失效 |
| `.rtmp_domain.txt` | 当前生效的 RTMP 域名（网关拼播放地址用） |
| `.ws_port` / `.stream_url.txt` | 上次抓到的隧道端口与流地址 |
| `.gitignore` | 挡住上面全部凭证 + 整个 `assets/` + `.tmp_*/` |
| `.workbuddy/` | 项目记忆（每日工作日志），不入库 |
| `.vscode/` | 编辑器配置，不入库 |

---

## 安卓 App：TM Live

`app/` 是把网页版重写成原生 App 的成果：**Kotlin 2.0 + Jetpack Compose + Media3**，
播放走 **HTTP-FLV**（RTMP 作备选）。数据不经过任何第三方服务器：
`libkiwi.so` + 云层 SDK 直接打进 APK，App 自己起隧道、自己连服务端。
本机不需要装 JDK / SDK / Gradle，推送代码后由 GitHub Actions 出包。

### 用它

1. 打开仓库的 **Actions** → `build-apk` → 最新一次运行 → 下载 `build-<run>` 里的 APK
   （只有 `app-arm64-v8a-debug.apk` 一个包，直接装）
2. **装完就能看列表**——不需要源 App，不需要 root，不需要填任何凭证
   （appKey 和 JWE 都内置了，隧道由 App 自己拉起）
3. 只有 appKey 失效（设置页「内置隧道」显示 `Init` 返回码非 0）时才需要折腾：
   在设置页粘贴新的 appKey，或临时打开「借源 App 端口」的降级开关

> 设置页顶部有个状态灯：**隧道在线 :端口** / **未连接**，旁边是 SDK 的 `Init` 返回码和组探测明细。
> 未连接时先用本地缓存渲染，缓存也没有才降级到 `app-data/rooms.json`（10-29 的静态快照，
> 入口收在设置页折叠的「备用数据源」里——正常流程根本用不到它）。

> 首次构建没有 keystore secret 时自动出 **debug 签名**的包（能直接装，但不能上架、不能覆盖升级）。
> 想要稳定升级：把 keystore base64 放进 `KEYSTORE_BASE64`，再配 `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

### 功能

| 界面 | 内容 |
|---|---|
| **首页** | 双列瀑布卡（**封面图 + 名字首字兜底**）、一级 tab（关注/推荐/热门/附近）、分类 chips（按 `menu_id` 本地分组）、搜索、按人气/名字排序、星标收藏、**在播探活**（可播置顶、探死沉底压暗标「未在播」） |
| **关注** | 「正在直播 / 未开播」两组；本地收藏 ∪ 源 App 账号关注（`podcast_id` 就是房间号）；右上角独立刷新 |
| **设置** | 内置隧道（appKey / Init 返回码 / 组探测）、直播源状态灯、JWE、隧道端口、自动刷新、在播探活进度、RTMP 域名 / 封面图床 / 图片代理、播放通道 / 缩放 / 缓冲 / 自动播放、弹幕状态与端口、主题、备用数据源（折叠） |
| **播放页** | 全屏视频 + **上下滑整屏切房** + 双指缩放(1–3x) + 双击暂停 + 单击隐显 UI + 适应/拉伸切换 + **屏幕常亮** + **实时弹幕**（右滑调背景透明度）+ **画中画** + 右侧常驻关注按钮 |

**开关即时生效**：`streamMode` / `scaleMode` / `bufferMs` / `autoPlay` / `refreshSec` / `liveMode` 全部是
`AppContainer` 里的 `mutableStateOf`，不是「UI 直接读 prefs」。早期版本读的是 SharedPreferences 的普通 getter，
写盘不触发 Compose 重组，所以点了开关画面纹丝不动——这是修过的 bug。

**在播探活**：接口里的 `on_air` 恒为 1、`player_count` 下播不清零，唯一靠得住的是
「`GET /live/<房间号>.flv` 能不能读出 `FLV` 三个字节」。但全量探 1972 间要 8 分钟/小时射频满载，
所以只探**列表当前看得见的窗口 + 往后 40 张**，同一间房 **20 分钟内不重复探**；
结果**不删房间**，只做排序和压暗。

**画中画**：只能由播放页顶栏「浮窗」**手动**进入，退到桌面不会自动缩窗。
小窗里只留视频和底部居中的「还原」胶囊——整屏封面、手势层全部摘掉，
否则在模拟器上会盖住系统的全屏键和设置齿轮。

**弹幕是实时的**：走内置隧道的 IM vhost，`/websock/join` 进房后只收 `room_msg` 推送，
按 `content.typ` 过滤掉下注/开奖/进场用户。冷清房没人说话就是空的（不补历史）。

**弹幕区右滑透明**：在聊天区向右拖，背景不透明度从 62% 一路滑到 0（跟手），全透明时只剩带投影的弹幕文字浮在画面上；
左滑恢复；也可以点右上角「背景 62%」在 62% / 25% / 全透明之间循环。首次进房会给 4 秒提示。
网页版 `site/index.html` 同步支持这套手势（写进 `localStorage`）。

### 数据源怎么更新

```bash
python tools/build_site3.py       # 重新抓房间列表 → site/data.js
python tools/build_app_data.py    # → app-data/rooms.json（含分类统计）
git add -A && git commit -m "data: 刷新房间列表" && git push
```

App 端在设置页点「立即拉取」即可拿到新列表（失败会退回本地缓存）。

### 已知边界

- **列表是实时的，但房间号池是静态的**：人数、在播状态每次刷新都是服务端真值（1900+ 间），
  可**新主播新建的房间号发现不了**——8.5.3 已经没有 `/room/list`，源 App 自己也是靠本地表 +
  WS 增量维护，没有「底部无限加载」可以翻。详见 `docs/8.5.3接口现状.md` §3
- **在播状态靠探活，不靠字段**：`live_in` 只能软筛（接口里 600 条样本只有 5 条为 0），
  真判据是拉一次 FLV 头。所以列表里仍会有少量「标着在播、点进去是 403」的房，
  点进去会 0.8 秒后自动跳下一间（连跳 8 间才停）
- **大厅级 WS 订阅没接**：服务端其实会推 `{"ctl":"room","live_in":…}` 增量（冷启动 25 秒推了 167 条），
  接上就能让 `live_in` 变实时、彻底不用探活。这是下一步，不是缺陷
- **凭证**：看列表**不需要凭证**；「关注」要 JWE（330 字符，**已内置**，换号时才要粘新的）；
  弹幕要的 appKey 同样内置。两者都能在设置页覆盖，不用重新打包
- **关注关系**：本地收藏 ∪ 源 App 账号关注。`podcast_id` 就是房间号（8/8 命中），
  所以服务端关注能直接映射成房间；但**没有可用的取消关注接口**，那部分条目只能去源 App 里取消
- **弹幕只能看不能发**：写接口的鉴权没解，输入框只读。推送只收实时，不补历史——
  冷清房进去可能长时间空着
- **封面图是头像，不是直播画面截图**：`attachment_url` 拼 CloudFront 直连可取（覆盖率 100%），
  但没有画面截图字段，所以卡片＝头像放大裁切。无图时退回渐变卡 + 首字

---

## 快速开始

```bash
# 1) 起网关 + 托管网页（需要本机有 ffmpeg）
python tools/gateway.py
# 启动会打印两个地址：
#   本机: http://127.0.0.1:8899/
#   手机: http://192.168.x.x:8899/    <- 手机浏览器打开这个

# 2) 需要刷新房间列表
python tools/build_site3.py

# 3) 不看页面，直接听某个房间的弹幕
python tools/live_ws.py watch <room_id> 1 60

# 4) 重采弹幕样本并重建网页样本池
python tools/danmaku_probe.py --rooms 40 --seconds 5
python tools/build_danmaku.py
```

不启网关也能双击 `site/index.html`：分类、搜索、列表、弹幕全部正常，播放区会提示启动网关。

### 手机上用（Android / iOS）

网关默认监听 `0.0.0.0`，手机和电脑连同一个 Wi-Fi，用启动时打印的 `http://192.168.x.x:8899/` 打开即可。
窄屏下页面自动换布局：**播放器在最上、聊天区居中、房间列表在下**，分类改成横向滑动，列表行的
「复制源 / 本地播」不再依赖 hover、直接常显。

```bash
# 只想本机用，收窄监听范围
python tools/gateway.py --host 127.0.0.1
```

手机打不开时，九成是 Windows 防火墙拦了入站端口（管理员 PowerShell 放行 8899/tcp）。

两点要知道：

- 手机上播放仍走 **PC 的 ffmpeg 转封装**，所以电脑得一直开着网关；电脑一关，手机就只剩列表和弹幕。
- 想彻底脱离电脑，用播放区右上的「VLC 打开」：它在安卓上走 `intent://` 直接唤起 VLC for Android
  （原生支持 RTMP），同时把 `rtmp://` 地址复制到剪贴板兜底。iOS 没有这跳转，复制地址后用 VLC/nPlayer 播。
- 手机浏览器用 `http://192.168.x.x` 打开时不是安全上下文，剪贴板 API 可能被拒；页面会自动退回
  `execCommand`，再失败就弹出手动复制的输入框。

---

## 播放：网页播放 / 复制源 / 本地播放器

浏览器内核不支持 RTMP，所以页面里播的是网关转出来的 HLS。除此之外列表每行和播放区都给了直达外部的入口：

| 入口 | 位置 | 行为 |
|---|---|---|
| **复制源** | 列表每行右侧（hover / 选中时出现） | 复制 `rtmp://<域名>/live/<房间号>` |
| **本地播** | 同上 | 让网关在本机调起播放器并直接打开该房间 |
| **复制播放源** | 播放区右上 | 同上，复制当前房间地址 |
| **本地播放器** | 播放区右上 | 同上，调起本机播放器 |

网关侧三个新接口（`tools/gateway.py`）：

```
GET /api/link?room=<id>    {"room":…,"rtmp":…,"m3u":…,"hls":…}
GET /api/play?room=<id>    调起本机播放器，返回 {"ok":true,"player":"PotPlayer64",…}
GET /play/<id>.m3u         下载 m3u 播放列表（系统默认播放器 / 手动拖进播放器）
```

播放器按 `PLAYER` 环境变量 → VLC / PotPlayer / mpv / MPC-HC 常规安装路径 → 便携目录（`D:\Apps` 等）
的顺序探测；都找不到就下载 m3u 交给系统默认程序，同时把地址复制到剪贴板。
想指定任意播放器：

```bash
set PLAYER="D:\Portable\vlc\vlc.exe"
python tools/gateway.py
```

---

## 弹幕为什么需要过滤

真人分类下的房间，弹幕里大部分不是观众在说话。实测采样（`docs/弹幕分类与过滤.md` 有全表）：

| 类型码 | 内容 | 占比 | 处理 |
|---|---|---|---|
| `32` | 观众聊天 | 约五成 | **保留** |
| `19` / `20` / `23` / `10` | 下注、结算中奖、开奖、博彩中奖播报 | 约四成 | 丢弃 |
| `5` / `1` / `26` / `33` | 关注、管理操作、心愿达成、商城下单 | 少量 | 丢弃 |
| `32` 但很长+表情轰炸 | 伪装成聊天的引流文案 | 极少数 | 丢弃 |

判定先看服务端类型码（`content.typ`），类型码缺失或遇到纯文本系统广播时再用关键词兜底；
真人聊天（`typ=32`）**不做博彩词过滤**，否则观众正常聊"下注"会被误杀。

规则只有一份：`tools/danmaku_filter.py`，网页端 `site/dm_filter.js` 是同一套规则的手抄版。
页面聊天区右上角「只看聊天 / 全部」可以现场对照过滤效果，被丢掉的消息在「全部」模式下带灰标签。

---

## 三条链路怎么走的

```
房间详情  POST /room/get_list_by_ids {"ids":"123,456,…"}  ← 唯一可用的房间接口，ids 是逗号串
分类表    POST /login/get_data_by_tbname {"tb_name":"lobby_menu"}   ← 34 项，真人区 5/101/102/103/104/105/108
关注      POST /user_follow/my_follow                     ← 唯一要 JWE 的接口（podcast_id 即房间号）
          ✗ /room/list 已在 8.5.3 下线：17 个本地端口全 404，源 App 冷启动全程不调用
实时通道  WS   /tunnel/open?z=1                            ← zlib 明文 JSON（脚本形态还要带 ?token=<32hex>）
          └─ /websock/join			    ← 进房（旧名 /room_push/join 已失效）
             /websock/history_msg		    ← 历史弹幕
             /websock/leave
视频流    http://<域名>/live/<room_id>.flv                 ← 零鉴权，FLV 优先；rtmp:// 同路径可备选
```

**App 里这几条硬前提都由内置 SDK 自己解决**，用户不用管：

1. 隧道地址与 `?token=`：SDK `Init(appKey)` 握手后自己拿（每次启动都变）；
2. 凭证：JWE / appKey 内置，可被设置页覆盖；
3. 端口：SDK 给每个 vhost 组各开一个本地 listener，逐个真打接口确认哪个挂 `/room`、哪个挂 `/websock`。

用 `tools/` 里的脚本（不跑 App）时才需要自己抓 token 和端口，见 `docs/README.md` §八。

「大厅列表」这一层不是接口，是 **id 池 + 批量刷新**：`assets/room_ids.txt` 里 1972 个种子号，
按 250 个一批并发打 `get_list_by_ids`，只留 `on_air=1` 且 `live_in` 软筛通过的。

细节见 `docs/8.5.3接口现状.md`（接口基准）、`docs/App实现现状.md`（App 怎么跑）、
`docs/攻坚全记录.md` 阶段五（拿 token 的过程）。

---

## 注意事项

- 根目录下的 `.jwe.txt`、`.apptoken.txt`、`.rtmp_domain.txt`、`.session.json` 是**运行时凭证**，
  `gateway.py` / `build_site3.py` / `live_ws.py` 都依赖它们；token 会随 App 重启失效，届时需重新抓。
- 凭证、APK、pcap、`assets/` 采集与反编译产物全部由 `.gitignore` 挡住；文档里出现过的 token / JWE
  已替换为 `<APP_TOKEN>` / `<JWE>` 占位符，脚本里也不再硬编码。
- `assets/channel.apk` 是全部派生文件的唯一来源（blutter 反汇编、libapp.so、libkiwi.so 均可从中再生），
  其余逆向中间产物已清理。**整个 `assets/` 删掉也不影响运行**，只是以后要复查抓包证据就没原料了。
- 移动 `assets/` 里的东西要注意：`danmaku_probe.py` / `build_danmaku.py` 的默认路径写死在
  `assets/data/dm.jsonl`，换位置得同步改或传 `--in` / `--out`。
- 本项目仅用于协议学习与流量分析，不提供任何博彩/直播内容的分发能力，请遵守当地法律。
