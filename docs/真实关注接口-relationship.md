# 真实关注接口：`/relationship/*`（2026-10-31）

> 结论：**`/user_follow/add` 从来不存在**。真实关注/取消走 `relationship` 模块的两个 POST，
> 早期实现猜错了路径，服务端对未知路径返回空转的 `{"code":0}`，所以「看着成功、实际不落库」。

---

## 一、真值（来自 `tianmei/outdir` 的 pp.txt）

### 1.1 两条接口

```
POST /relationship/add      { userId, followeeId, channelId } → AddFollowReply
POST /relationship/remove   { userId, followeeId, channelId } → RemoveFollowReply
```

证据位置：

| pp 偏移 | 内容 |
|---|---|
| `pp+0x59d40` | `"/relationship/add"` |
| `pp+0x59d48` | `"Relationship.addFollow method:"` |
| `pp+0x59d50` | `"Relationship.addFollow method:POST Not defined!"` |
| `pp+0x59d58` | `"@@@@@@@@@@@@@ go api:addFollow "` |
| `pp+0x59cb8` | `"/relationship/remove"` |
| `pp+0x59cd0` | `"@@@@@@@@@@@@@ go api:removeFollow "` |
| `pp+0x59d10` | `List(11) [0, 0x4, 0x3, 0x1, "channelId", 0x3, "followeeId", 0x2, "userId", 0x1, Null]` |
| `pp+0x59d18` | `"RelationshipMgr.addFollow: "` |
| `pp+0x59d28` | `"关注失败: "` / `pp+0x59d30` `"关注成功"` |
| `pp+0x59c98` | `"RelationshipMgr.removeFollow: "` / `pp+0x59ca0` `"取消关注失败: "` |
| `pp+0x77340` | `Field <AddFollowReply._i>`（protobuf 消息） |
| `pp+0x77358` | `Field <RemoveFollowReply._i>` |

### 1.2 三个必须对齐的细节

1. **参数名是 `followeeId`（双 e）**，不是 `podcast_id`，也不是 `followee_id`。
2. **必须带 `channelId`**（`ecy`）和 **`userId`**（自己的用户 id）。三缺一服务端按未定义参数处理。
3. 日志里那句 `go api:addFollow` 说明它**走 `/go/` 加密路径**（`HttpRequest::encryptApiPath` 那一套），
   而不是普通 REST。

### 1.3 为什么 `/user_follow/add` 会「成功但不落库」

`user_follow` 模块在 pp.txt 里只有 5 条路径，**没有 add/cancel_follow**：

```
/user_follow/my_follow
/user_follow/my_follow_count
/user_follow/follow_me
/user_follow/follow_me_count
/user_follow/is_idol
```

服务端对不存在的路径返回 `{"code":0}`（网关兜底），于是客户端以为成功，
但读 `my_follow` 毫无变化 —— 这正是早期「写接口空转」结论的真相：**路径本身是错的**。

---

## 二、App 侧实现

`LiveRepo.syncFollow(podcastId, wantFollow)`：

```kotlin
val body = JSONObject()
    .put("userId", prefs.myUserId)        // 自己
    .put("followeeId", podcastId)         // 关注对象（= 房间号）
    .put("channelId", prefs.channelId)    // ecy

val path = if (wantFollow) "/relationship/add" else "/relationship/remove"
post(port, path, body.toString())
```

判定不只看 `code`，写完后**复查 `my_follow`**：服务端真把状态改了才回 true。

新增 Prefs 字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `channelId` | `ecy` | 与内置 JWE 同渠道 |
| `myUserId` | `0` | 源 App `MAINUSER_DATA.id`；0 = 让服务端按 token 推 |

---

## 三、`podcast_id` == 房间号

2026-10-29 实测：`my_follow` 返回的 `podcast_id` 拿去 `/room/get_list_by_ids`
**8/8 全部命中**，`attachment_url` 与关注列表头像逐字节一致。

所以关注链路里 `followeeId` 直接传**房间号**即可，不需要额外映射表。

---

## 四、如果仍然不落库：只可能是通道问题

`/relationship/*` 在日志里挂着 `go api:` 前缀。本机明文隧道若写不动，说明服务端只认
`ProtobufHttpTunnelDelegate`（protobuf 隧道）那条路。此时的排查顺序：

1. **先确认路径对不对**：对 `/relationship/add` 打一发，看返回是
   - `{"code":0,"result":...}` → 通道通了
   - `{"code":-1,"msg":"..."}` / 404 → 端口 vhost 不对，回到 405 判据找对端口
   - HTML 404 → 这个 vhost 不挂 `/relationship`
2. **再看鉴权**：带 `authorization: Bearer <JWE>`；`userId` 填对（0 有时能过，有时不行）。
3. **最后才怀疑通道**：如果 `code:0` 但 `my_follow` 不变，那就是走了空转。

> 建议先手动验证一次（`tools/` 里有现成隧道客户端）：
> ```
> python tools/tunnel_api.py call /relationship/add '{"userId":2094967700,"followeeId":87839418,"channelId":"ecy"}'
> ```
> 然后立刻 `python tools/tunnel_api.py call /user_follow/my_follow '{}'` 看有没有多出来。

---

## 五、顺带拿到的读接口（同样在 pp.txt）

| 接口 | 方法 | 用途 |
|---|---|---|
| `/user_follow/my_follow` | POST `{}` | 我的关注列表（`result[].podcast_id`） |
| `/user_follow/my_follow_count` | POST | 关注数 |
| `/user_follow/follow_me` | POST | 粉丝列表 |
| `/user_follow/follow_me_count` | POST | 粉丝数 |
| `/user_follow/is_idol` | POST | 是否已关注某人 |
| `/relationship/is_following/` | GET `?followeeId=&userId=` | 是否在关注（比拉全量便宜） |
| `/relationship/followees/` | GET | 关注列表（protobuf 版） |
| `/relationship/followee_count/` | GET | 关注数 |
| `/relationship/follower_count/` | GET | 粉丝数 |
