# TM Live · App 实现现状（单一事实源）

> 更新：2026-10-30 · 对应代码 `app/`（`com.duocai.live`，debug 后缀 `.debug`）
> 这篇描述**我们自己的 App 现在到底怎么跑的**。源 App 的协议事实看
> `8.5.3接口现状.md` / `直播接口清单.md`，两次专项实测看
> `源App在播判定-冷启动实测.md` / `弹幕接口实测-端口与判据.md`。
> 与本文冲突的旧文档（含已删除的 `安卓App设计方案.md`），以本文为准。

---

## 0. 一句话

**装完即用**：App 内部自带云层隧道 SDK（`libkiwi.so` + `kiwi-sdk.jar`），
启动时自己完成握手、自己拿到本地端口，直连源 App 的服务端接口与推流 CDN。
不需要源 App 常驻，不需要 root，不需要抓包，不需要改包。

| 能力 | 状态 | 依赖 |
|---|---|---|
| 直播间列表（1900+ 间，实时人数） | ✅ | 内置隧道 |
| 分类 / 搜索 / 排序 / 收藏 / 关注 | ✅ | 同上 |
| 播放（HTTP-FLV 优先，RTMP 备选） | ✅ | CDN 直连 |
| 实时弹幕（WS 进房后只收推送） | ✅ | 内置隧道的 IM vhost |
| 在播探活（可播置顶、探死沉底压暗） | ✅ | CDN 直连 |
| 画中画（手动进入 + 还原） | ✅ | 系统 PiP |
| 发弹幕 | ❌ | 写接口鉴权未解决，输入框只读 |
| 大厅级 WS 订阅（实时维护 `live_in`） | ❌ 未做 | 见 §9 |

---

## 1. 启动链路

```
App 启动
  └─ Tunnel.start()                       tunnel/Tunnel.kt
       ├─ KiwiSdk.ensureLoaded()          反射加载 kiwi-sdk.jar（不能自己 System.load）
       ├─ Kiwi.Init(appKey)               同步握手，IO 线程，数秒；code=0 才算成功
       ├─ ServerToLocal(group) × 14       每个 vhost 组各开一个 127.0.0.1 listener
       ├─ 拿每个组的端口真打一次 /room/get_list_by_ids
       │    回 {"rooms":…} 的那个组 = /room vhost  → tunnel.port
       └─ 同一张表里 kiwi_im 那个组               → tunnel.imPort / groupPorts
  └─ AppContainer.refresh(force=false)    AppContainer.kt
       └─ LiveRepo.fetch() → id 池 → 批量拉详情 → live_in 软筛 → 缓存落盘
  └─ HomeScreen 渲染
       └─ 可见区间上报 → ensureProbed() → FLV 探活 → 三档排序
```

失败降级：SDK 没起来（appKey 失效 / 非 arm64）→ 退回「借源 App 端口」模式（扫 `/proc/net/tcp`）；
列表接口拉不到 → 用本地缓存；缓存也没有 → 用 `app-data/rooms.json` 快照（设置页折叠项）。

---

## 2. 房间列表：id 池 + 批量刷新

源 App 的大厅**没有列表接口**（`/room/list` 在 8.5.3 已下线，17 个本地端口全 404，
冷启动全程不调用）。它自己也是「本地房间表 + WS 增量推送」。我们照抄这个思路：

| 环节 | 做法 | 代码 |
|---|---|---|
| id 池 | `assets/room_ids.txt` 1972 个房间号，首次启动复制到 `filesDir`，之后只增不减 | `LiveRepo.idPool()` |
| 拉详情 | `POST /room/get_list_by_ids`，250 id 一片，3 片一波滚动并发 | `LiveRepo.pullAll()` |
| 首屏 | 先只拉前 500 个（约 0.6–1 秒出画面），其余后台补 | `AppContainer.refresh()` |
| 硬筛 | `on_air != 1` 直接丢；游戏/棋牌/彩票导流房按 `menu_id` + 房名剔除 | `toRoom()`、`NonHuman.kt` |
| 软筛 | `live_in==0` 丢；**筛完不足 8 间就整批放行**（绝不返回空列表） | `fetch()`，`MIN_KEPT_ROOMS` |
| 排序 | 在线人数降序 + 房号降序 | `fetch()` |
| 缓存 | 结果写 `filesDir`，带时间戳；刷新失败用它渲染，设置页标「本地缓存」 | `RoomRepo` |

**为什么不按 `player_count` 筛**：它是残留值，房间下播后人数不清零。
`on_air` 更没用——2248 间里 2209 间是 1。

**关注的人不在池子里**：关注走 `/user_follow/my_follow`（要 JWE），拿到的 `podcast_id`
就是房间号，逐次并进这一轮的 `targets` 一起拉详情。

---

## 3. 在播判定：只认 FLV 头

```
GET http://<域名>/live/<房间号>.flv
Range: bytes=0-15
→ 200 + 头三字节是 "FLV"  ⇒ 在播
→ 403 + Server: NgxFence  ⇒ 没在推流（房间级，不是限频、不是鉴权）
```

三种候选判据的实测结论（2026-10-30 冷启动实测，详见 `源App在播判定-冷启动实测.md`）：

| 字段/手段 | 能不能判在播 | 原因 |
|---|---|---|
| `on_air` | ❌ | 2248 间里 2209 间是 1，几乎恒真 |
| `player_count` | ❌ | 下播后不清零 |
| `live_in` | ⚠️ 只能软筛 | 唯一会翻转的布尔，但接口里 600 条样本只有 5 条为 0 |
| **FLV 头** | ✅ | 唯一与「点进去能不能出画面」完全一致的判据 |

任意时刻全池真实在推流的只有 **约 8%**（随机 100 间探活 8 间通过），
`live_in=1` 的子集约 37% —— 这就是「列表里九成播不了」的真正原因：
**基数取的是主播库，不是在播库**。

### 3.1 探活只探可见窗口

全量探 1972 间要发近 2000 条 HTTP、24 路并发跑 40 秒，每小时 12 轮 ≈ **8 分钟/小时射频满载**。
现在改成由列表驱动：

| 维度 | 值 |
|---|---|
| 触发 | 首页当前 tab 的瀑布流上报可见区间（200ms 去抖） |
| 范围 | 可见区间 + 往后 40 张，单批上限 160 |
| 去重 | 同一间房 **20 分钟内不重探**，滚动来回不发请求 |
| 前置 | 只在前台、且没进播放页时探（`foreground` / `openIndex`） |
| 清理 | 手动刷新时清空缓存（用户就是想看「此刻」），自动轮询不清 |
| 成本 | 每小时射频活跃 **< 1 分钟**，额外内存约 40KB |

### 3.2 结果怎么用

**不删房间**（删了列表会在手指底下变短，也是上一版「刷新后 0 房间」的成因），只调整顺序和观感：

| 档位 | 卡片表现 |
|---|---|
| 可播（探通） | 置顶，正常显示 |
| 未探 | 居中，正常显示 |
| 探死 | 沉底，`alpha 0.38` + 灰点 + 「未在播」标签 |

重排时机也是刻意的：探活结果是分批回来的，**只有用户滑回列表顶部才真正换顺序**
（`orderEpoch`），否则滚动中内容会突然跳。

---

## 4. 弹幕

```
WS /tunnel/open?z=1  (zlib 明文 JSON)
  └─ POST /websock/join {"room_id":…,"menu_id":…,"cp_type":3}   → body.code == 0
       └─ 持续收 {"opt":"u"|"r","ctl":"room_msg","data":…}
```

### 4.1 端口是动态的，只能靠 405 认

隧道 listener 每次冷启动重新 bind（实测 `11689` → `34199` → `37679`），写死必挂。
候选表按可靠度分级，**逐级验证**：

| 顺序 | 来源 | 代码 |
|---|---|---|
| 1 | 上次真进房成功的端口（`goodPort`） | `LiveDanmaku` |
| 2 | `tunnel.imPort`（SDK 问到的 `kiwi_im`） | `Tunnel.resolveIm()` |
| 3 | `tunnel.groupPorts` 全部组端口，并发 8 路逐个验 | `LiveRepo.verifyWsPorts()` |
| 4 | 扫 `/proc/net/tcp` 本机监听端口 | `LiveRepo.probeWsVhosts()` |
| 5 | `tunnel.port` / `live.lastPort`（`/room` 那个组，一定 404） | 兜底 |

**判据 = 明文 `POST /websock/join` 回 405**。`/websock/*` 这个 location 只接受 WS 升级，
明文 POST 在 nginx 的 method 层就被挡，根本到不了应用层 —— 这就是它的指纹。
403 的那几个（`设备登陆中` / `用户不存在或超时`）是**别的业务 vhost 的鉴权层**，
也回 JSON、也是 `code:-10000`，只看「是不是 JSON」就会误收。

### 4.2 断线与假死

- `join` 应答 8 秒没来 → 换下一个端口；
- 进房后读超时放宽到 30 秒，连续 3 次空转（90 秒无帧）→ 主动 ping 一次 WebSocket，
  仍无数据就判死重连。隧道是长连接代理，上游断了可能既不 FIN 也不 RST，
  只靠 TCP 会表现为「状态显示已进房、弹幕永远不动」。

### 4.3 只留聊天

推送按 `ctl` 分流：`room_msg` 收，`room_player`（进场用户，占推送量约 85%）和 `room` 直接丢。
`room_msg` 再按 `content.typ` 过滤：只留 `32`（聊天）和 `4`（@提及），
礼物 `3` 视设置决定，下注/结算/开奖/关注/管理一律丢。规则见 `弹幕分类与过滤.md`。

**只收实时推送，不补历史**：进房那一刻之前的弹幕拿不到，冷清房就是空的。

---

## 5. 播放

| 项 | 实现 | 为什么 |
|---|---|---|
| 播放器 | 全局唯一 `ExoPlayer` + 唯一 `PlayerView` | 抖音式「每页一个播放器」一次开三个解码器，中低端机直接 OOM |
| 渲染层 | `surface_type="texture_view"` | SurfaceView 在独立图层，Compose 的 scale/alpha 对它无效（缩放和淡入会失效） |
| 地址 | `http://…/live/<id>.flv` 优先，失败换 `rtmp://…/live/<id>` | librtmp 连这个 CDN 会报 `RTMP error: -14`（握手不兼容），FLV 最稳 |
| 切房 | 整页上下滑（跟手），松手切/回弹 | 只换 `MediaItem`，不重建播放器 |
| 缩放 | 双指 1×–3× + 拖动，顶栏胶囊一键还原 | 旧的「裁剪」预设已删 |
| 常亮 | 播放页 `FLAG_KEEP_SCREEN_ON` | 退出即还 |
| 死房 | 报错 0.8 秒后自动跳下一间，连跳 8 间才停 | 停在错误页很烦，但也不能一路空转刷完整个列表 |
| 后台 | 非 PiP 进 `onStop` 就暂停 | 不再后台偷跑解码和声音 |

---

## 6. 画中画

**只能手动进**：播放页顶栏「浮窗」。退到桌面不缩窗（`setAutoEnterEnabled(false)`，已删 `onUserLeaveHint`）。

小窗里只画两样东西：视频 + 底部居中的「还原」胶囊。

- 整屏封面 `CoverPage`、全屏手势层在小窗里**整块不挂** —— 它们是整屏图层，而模拟器上
  应用层合成排在 `PipMenuView` 之上，会把系统菜单的正中间「全屏」键、左上角「设置」齿轮盖住；
- 视频层固定 `alpha=1`，不开离屏缓冲；
- 「还原」放底部，因为 SystemUI 的菜单只用顶部和中间，底部是空的；
- 还原 = `moveTaskToFront` + `REORDER_TO_FRONT` 双保险（单靠后者是空操作）。

细节与实测日志见 `画中画-手动进入与还原修复.md`。

---

## 7. 关注

本地收藏 ∪ 源 App 账号关注。

- 本地收藏：`FollowStore`，点卡片右下角星标；
- 服务端关注：`POST /user_follow/my_follow`，凭证 JWE（330 字符，`alg=dir/A128CBC-HS256`），
  **默认内置**一份，设置页可粘贴覆盖；
- `podcast_id` 就是房间号（8/8 命中），所以服务端关注能直接映射成房间卡片；
- **没有可用的取消关注接口**，服务端那部分只能回源 App 里取消。

---

## 8. 设置页清单

| Section | 内容 |
|---|---|
| 内置隧道（不再需要源 App） | SDK appKey、自动拉起、Init 返回码、组探测明细 |
| 直播源（实时房间数据） | 隧道状态灯、JWE、隧道端口（留空自动）、自动刷新间隔、立即刷新、在播探活（本屏）本轮 X/60 |
| 备用数据源 | `rooms.json` URL / 粘贴 JSON / jsDelivr 与 GitHub raw 镜像 |
| 播放 | 流媒体域名、封面图床、图片代理、播放通道（AUTO/FLV/RTMP）、HLS 备用网关、缩放、缓冲、进房自动播放、画中画说明 |
| 弹幕 | 链路状态 `已进房 :端口`、本次已收条数、端口（im / 列表）、只显示观众聊天 |
| 关注 | 本地收藏数 / 服务端关注数 / JWE 状态、清空本地收藏 |
| 外观 | 主题 |
| 关于 | 版本、包名、边界声明 |

开关全部是 `AppContainer` 里的 `mutableStateOf`，不是「UI 直接读 prefs」——
早期读普通 getter 写盘不触发重组，点了开关画面纹丝不动。

---

## 9. 已知边界 / 没做的

1. **房间号池是静态的**：新主播新建的房间号发现不了，只能靠 `room_ids.txt` 里已有的 1972 个。
   源 App 也是这样维护本地表的，没有「底部无限加载」。
2. **`live_in` 不实时**：要靠 WS 大厅订阅持续刷新才能在本地做到「翻牌即准」。
   冷启动 25 秒内服务端推了 167 条房间更新，说明这条路可行，但**还没接**。
   现在的替代方案就是可见窗口探活。
3. **弹幕不能发**：写接口的鉴权没解，输入框只读。
4. **只收实时推送**：冷清房进房后可能长时间空着。
5. **封面是主播头像**，不是直播画面截图 —— 接口里没有截图字段，`/static/upload/…` 明文可取。
6. **appKey / JWE 是内置的**：哪天服务端换掉，表现为 `Init` 返回非 0 或关注 401。
   两者都能在设置页直接覆盖，不用重新打包。
7. **只打包 `arm64-v8a`**：因为 `libkiwi.so` 只有 arm64 版。带 ARM 转译层的 x86 模拟器能跑。

---

## 10. 代码地图

| 关注什么 | 看哪里 |
|---|---|
| 全局状态 / 刷新编排 / 探活调度 | `AppContainer.kt` |
| 列表接口、id 池、`live_in` 软筛、FLV 探活、WS 端口探测 | `data/LiveRepo.kt` |
| 房间模型、流地址拼接、JSON 解析 | `data/Models.kt` |
| 内置隧道外壳（状态机、组端口、vhost 校验） | `tunnel/Tunnel.kt` |
| 云层 SDK 反射加载（含 JNI 踩坑注释） | `tunnel/KiwiSdk.kt` |
| 弹幕 WS 客户端（进房、心跳、重连） | `data/LiveDanmaku.kt` |
| 弹幕分类过滤 | `data/NonHuman.kt` + `ui/PlayerScreen.kt` 的 `ChatPanel` |
| 播放器（唯一实例、候选地址切换） | `player/PlayerHolder.kt` |
| 瀑布流、排序、可见区间上报 | `ui/HomeScreen.kt` |
| 播放页、手势、聊天面板、小窗还原 | `ui/PlayerScreen.kt` |
| 画中画参数 / 进出 / 返回键 | `MainActivity.kt`、`ui/RootScreen.kt` |
