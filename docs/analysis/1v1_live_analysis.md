# 1v1 直播「无法观看」原因分析与直播地址获取

> 目标 App：jj_live / 91zb（Flutter，blutter 反编译 6.24 版本）
> 分析结论：1v1 走 **Agora Interactive Live Streaming**，不存在 `.flv/.m3u8` 直链；「直播地址」= Agora 凭据。

---

## 一、核心结论

本 App 的**所有直播（含 1v1）都走 Agora RTC 实时音视频**，不是 CDN 拉流。

- 观众侧直播间 `audience_live_room_view.dart` 直接引用 Agora `RtcEngine`；而内容层 `audience_live_room_content_view.dart` 里**完全没有** `playUrl` / `VideoPlayer` / `RtcRemoteView` 的引用 → 视频经 Agora SD-RTN 传输，没有可直接播放的 URL。
- 因此「直播地址」不是一个能塞进 VLC 的链接，而是 **`appId + channelName + token(agoraToken) + uid (+ role)`**，必须用 Agora SDK 加入频道才能看到画面。

---

## 二、进房链路（blutter dump 证据）

| 环节 | 位置（blutter 偏移） | 说明 |
|---|---|---|
| 拉首充/资格 | `audience_live_room_page.dart` → `_requestFirstRechargeState` (0x12e2ee0) | 调 `AudienceRequest::firstRecharge` |
| 取 Agora Token | `AnchorRequest::getAgoraToken` (0xc1087c) → **POST `/voiceChat/getAgoraToken`** | 入参 `channelName` + `role`，回包解析出 `agoraToken` |
| 取 appId | `ParamManager::getAgoraAppId` (0xc251b4) | 远程配置下发 |
| 组装 token 请求 | `VoiceEngineManager::_requestAgoraToken` (0xc10778) | 用 `channelName + role` 触发上述接口 |
| 进频道 | `AudioManager::_joinAudioChannel` (0xc2eed8) 设置 `clientRoleType=audience` → `RtcEngineImpl::joinChannel` (0xc119d4) | 真正加入 Agora 频道 |
| 1v1 付费门禁 | `RequestRepositoryHome::requestInvite1V1Check` (0xed58cc) + `AudienceRequest::pay1v1` (0xc35d0c) | 先校验/扣币，再建会话 |
| 角色失败文案 | `audio_manager.dart` `ClientRoleChangeFailedReason` | 「频道内主播人数达到上限」/「请求被服务端拒绝」/「请求超时」 |

**调用顺序（观众侧 1v1）：**
```
requestInvite1V1Check  (校验资格/余额)
      ↓
pay1v1                (扣费，失败则无会话)
      ↓
getAgoraToken → /voiceChat/getAgoraToken   (拿 agoraToken)
      ↓
joinChannel(appId, channel, token, uid, role=audience)  (进频道看画面)
```

---

## 三、为什么「无法观看」——根因

1v1 看不了，本质是 **Agora 会话没建立成功 / 观众角色没收到视频流**。按概率排序：

1. **付费门槛没过（最常见）**：`requestInvite1V1Check` / `pay1v1` 在余额不足或风控时被拒，会话根本没建立 → 黑屏。
2. **Token 获取失败**：`/voiceChat/getAgoraToken` 返回错误（登录态失效、签名问题、该房间未开 1v1），join 拿不到合法 token。
3. **角色被拒 / 频道满 / 超时**：Agora 回调 `onClientRoleChangeFailed` 报上面三条文案之一 → 观众身份没建起来，收不到视频。
4. **对方未在线/未接受**：1v1 需双向，主播侧没推流自然看不到。

---

## 四、「直播地址」是什么 & 如何拿到

### 4.1 地址组成

```
appId      = ParamManager.getAgoraAppId() 返回（远程配置）
channel    = 1v1 房间对象里的 channelName
token      = /voiceChat/getAgoraToken 回包里的 agoraToken
uid        = 本地生成的整型（Smi）
role       = audience（观众） / broadcaster（主播）
```

拿到四项后，用任意 Agora 客户端（官方 Web SDK demo、另一台手机、或自写 Flutter/Agora 程序）以 `role=audience`、同一 `appId/channel`、不同 `uid` 加入频道即可观看。缺任一项都看不了。

### 4.2 运行时抓取脚本

已生成：`frida_1v1_capture.js`（同目录）

```bash
frida -U -p <pid> -l frida_1v1_capture.js
# 或冷启动： frida -U -f <包名> -l frida_1v1_capture.js --no-pause
```

脚本原理：
- **Hook `Dart_StringToCString`（libflutter.so 的 Dart C API）**：Agora 插件把 token / channel / appId 转成 C 字符串发给原生层前必经此函数，在 `onLeave` 读 C 字符串即可（比解析 Dart 对象布局稳）。过滤后只打印 http / agora / channel / token / 1v1 / base64-like 等可疑串。
- **Hook `RtcEngineImpl::joinChannel` (0xc119d4)**：进频道瞬间打印 uid，并在前后日志对应出 token 与 channel（`RX 段基址 + blutter 偏移` 方式与已验证的 DioMixin hook 一致）。
- **Hook gating 函数** `requestInvite1V1Check`(0xed58cc) / `pay1v1`(0xc35d0c) / `getAgoraToken`(0xc1087c) / `getAgoraAppId`(0xc251b4)：暴露「看不了」卡在哪一步。

运行后进 1v1 房间，日志关键行：
```
[flow] requestInvite1V1Check() ...
[flow] pay1v1() ...
[flow] getAgoraToken() -> /voiceChat/getAgoraToken 请求发出
========== [joinChannel] uid=123 token=006xxx... channel=abc123 ==========
[N][Str:Dart_StringToCString] <appId / agoraToken / channelName / 接口路径>
```

### 4.3 静态可确认的点

- Token 接口路径：`/voiceChat/getAgoraToken`（请求体含 `channelName` + `role`）。
- appId 来源：`ParamManager::getAgoraAppId`（远程配置，非硬编码常驻代码段）。
- 进频道函数：`RtcEngineImpl::joinChannel`（agora_rtc_engine 插件，编入 libapp.so，偏移 0xc119d4）。

---

## 五、如果你想要「能在播放器里打开的 URL」

本路径**不提供**直链。若确实要，需看 App 是否把 1v1 额外推到 CDN（Agora CDN 转推 / RTMP 转码）。可在 dump 中继续搜 `cdn` / `pushStream` / `rtmpPublish` / `startLiveStreaming` / `highestQuality` 确认是否暴露。未暴露则只能走 Agora 凭据方式。

---

## 六、附：相关文件 / 偏移速查

| 符号 | blutter 偏移 | 作用 |
|---|---|---|
| `RtcEngineImpl::joinChannel` | 0xc119d4 | 进 Agora 频道（核心） |
| `AnchorRequest::getAgoraToken` | 0xc1087c | `/voiceChat/getAgoraToken` |
| `ParamManager::getAgoraAppId` | 0xc251b4 | 取 appId |
| `VoiceEngineManager::_requestAgoraToken` | 0xc10778 | 组装 token 请求 |
| `AudioManager::_joinAudioChannel` | 0xc2eed8 | 设 audience 角色后 join |
| `RequestRepositoryHome::requestInvite1V1Check` | 0xed58cc | 1v1 资格校验 |
| `AudienceRequest::pay1v1` | 0xc35d0c | 1v1 扣费门禁 |

> 所有 Dart 函数偏移均为 blutter 指令段内偏移，运行时需加 `libapp.so` 的 RX 段基址（`getCodeBase()` 选最大 `r-x` 段）。已验证该方式与 DioMixin hook（0x85ffa0）一致可用。
