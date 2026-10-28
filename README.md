# 天美直播 App · 逆向分析与链路复现

对「天美」直播 App 8.5.3（包名 `com.l95c2450d7.f68bb4e2d5`，渠道 `ecy`）的完整分析产出：
**绕开加固与域名下发，直连本地隧道明文通道，独立复现房间列表、分类、视频播放与弹幕。**

> **命名说明**：包名 `com.l95c2450d7.f68bb4e2d5` 对应的真实应用名是**天美**。
> 早期分析一度误称其为「多彩」，项目目录已于 2026-10-29 由 `duocai-out_dir` 更名为 `tianmei-out_dir`，
> 父目录 `duocai原` 暂时保留原名（内部还有 blutter 产物 `tianmei/outdir`，改名会连带影响引用）。
> 文档正文与代码中的路径均已同步，个别旧笔记里的 `duocaiAPK` 路径是当时机器上的真实路径，保留原样。

不依赖 App 运行、不需要中间人证书、不需要破解加固 so。

产出分两层，互相独立：

| 层 | 目录 | 说明 |
|---|---|---|
| **网页版** | `site/` | 纯静态，双击 `index.html` 就能看分类、列表、播放、弹幕；带弹幕分类过滤开关 |
| **客户端脚本** | `tools/` | 复现同一套接口的命令行客户端：隧道探测、房间列表、实时弹幕、RTMP→HLS 网关 |

> 没有 App 端代码：本机没有 Android SDK / Gradle，无法编译验证 APK，所以不做"能装但没测过"的壳子。

---

## 目录

```
tianmei-out_dir/
├── docs/                          # 全部文档（索引见 docs/README.md）
│   ├── README.md                  # ★ 文档索引 + 结论演进表
│   ├── 成功总结.md                 # ★ 成果、方法论、已知边界
│   ├── 攻坚全记录.md               # ★ 7 小时完整过程与失败路径
│   ├── 弹幕分类与过滤.md           # ★ 弹幕类型码全表 + 广告判定规则
│   ├── 直播接口清单.md             # 接口 + 字段 + 推送协议
│   ├── 明文长连接通道.md           # /tunnel/open 协议、libkiwi.so 分析
│   ├── 明文接口突破.md             # 绕开路径加密拿 token
│   ├── 视频流拉取方案.md           # RTMP 零鉴权拉流
│   ├── 真人直播间筛选.md           # 分类判据（含被推翻的拉流方案）
│   ├── 加密方式分析.md             # RabbitCipher 变体（历史分析）
│   ├── BASE与TOKEN获取方案.md      # 四条获取路线
│   ├── 实机运行报告.md             # MuMu 实机取证
│   ├── MuMu抓包指南.md             # 采集步骤
│   ├── 功能模块分析文档.md / 安全分析报告.md / 直播流地址.md
│   └── 修改教程/                   # 7 篇二进制修改实战
├── site/                          # 网页版（双击可用）
│   ├── index.html                 # 分类 + 搜索 + 列表 + 播放器 + 弹幕
│   ├── dm_filter.js               # 弹幕分类/广告过滤（与 Python 端同规则）
│   ├── data.js                    # 房间数据（tools/build_site3.py 生成）
│   ├── dm_samples.js              # 弹幕样本（tools/build_danmaku.py 生成）
│   └── vendor/hls.min.js          # 本地 hls.js（Chrome/Edge 不支持原生 HLS）
├── tools/                         # 客户端脚本
│   ├── gateway.py                 # ★ RTMP→HLS 网关（同时托管页面）
│   ├── build_site3.py             # ★ 刷新房间数据（13 秒）
│   ├── danmaku_probe.py           # ★ 采集弹幕样本 -> data/dm.jsonl
│   ├── danmaku_filter.py          # ★ 弹幕分类 + 广告过滤（唯一规则源）
│   ├── build_danmaku.py           # ★ 样本 -> site/dm_samples.js
│   ├── live_ws.py                 # ★ 直播间实时通道客户端
│   ├── tunnel_map.py / ws_probe.py / tunnel_api.py   # 隧道底层
│   ├── pcap_streams.py / wsframe.py / wsdump.py / rtmp_sniff.py  # pcap 解析
│   ├── json_api.py / room_api.py / live_client.py    # 业务客户端
│   └── rabbit.py / dun_replay.py / dun_keys.txt / idalib_kiwi.py  # 加密与 so 分析
├── archive/                       # 少量长期素材（pp.txt 反汇编文本、join2.pcap 等）
└── channel.apk                    # 原始安装包（唯一不可再生素材）
```

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
房间列表  POST /room/list {"menu_id":104}          ← 参数是下划线，驼峰会被静默忽略
房间详情  POST /room/get_list_by_ids {"ids":"…"}
官方分类  POST /lobby_menu/list                     ← 34 项，真人区 5/101/102/103/104/105/108
实时通道  WS   /tunnel/open?z=1&token=<32hex>       ← zlib 明文 JSON
          └─ /websock/join			    ← 进房（旧名 /room_push/join 已失效）
             /websock/history_msg		    ← 历史弹幕
             /websock/leave
视频流    rtmp://<域名>/live/<room_id>              ← 零鉴权，域名平台级固定
```

三个硬前提（缺一个就失败）：

1. 隧道 URL 的 `?token=` 必须是 **App 当前会话的 32 位 hex**，只能从 `lo` 回环明文抓取；
2. 必须带 `authorization: Bearer <JWE>`；
3. 端口必须是同时支持 `/index/hello` 和 `/websock/*` 的那台 vhost。

细节见 `docs/攻坚全记录.md` 阶段五。

---

## 注意事项

- 根目录下的 `.jwe.txt`、`.apptoken.txt`、`.rtmp_domain.txt`、`.session.json` 是**运行时凭证**，
  `gateway.py` / `build_site3.py` / `live_ws.py` 都依赖它们；token 会随 App 重启失效，届时需重新抓。
- 凭证、APK、pcap、`data/` 采集结果、IDA 产物全部由 `.gitignore` 挡住；文档里出现过的 token / JWE
  已替换为 `<APP_TOKEN>` / `<JWE>` 占位符，脚本里也不再硬编码。
- `channel.apk` 是全部派生文件的唯一来源（blutter 反汇编、libapp.so、libkiwi.so 均可从中再生），
  其余逆向中间产物已清理。
- 本项目仅用于协议学习与流量分析，不提供任何博彩/直播内容的分发能力，请遵守当地法律。
