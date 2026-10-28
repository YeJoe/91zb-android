# 91直播 4.3.3 API 文档

> 逆向自 `libapp.so`（Flutter AOT）的 Dart 对象池。所有路径、字段名、请求头从 `pp.txt` 对象池字符串提取，部分字段类型从 `asm/` 反汇编推断。
> **共 357 个接口**，按功能分类。

---

## 目录

1. [基础设施](#1-基础设施)
2. [认证与用户](#2-认证与用户)
3. [首页与房间列表](#3-首页与房间列表)
4. [直播间核心](#4-直播间核心)
5. [礼物与背包](#5-礼物与背包)
6. [充值与提现](#6-充值与提现)
7. [游戏/博彩](#7-游戏博彩)
8. [主播管理](#8-主播管理)
9. [社交与消息](#9-社交与消息)
10. [活动中心](#10-活动中心)
11. [代理系统](#11-代理系统)
12. [直播开播](#12-直播开播)
13. [投票/心愿单](#13-投票心愿单)
14. [客服系统（独立 API）](#14-客服系统独立-api)
15. [数据结构](#15-数据结构)
16. [鉴权与签名](#16-鉴权与签名)

---

## 1. 基础设施

### 域名池（动态解析）

App 内置 6 个后端 + 多通道域名下发：

| 域名 | 用途 |
|---|---|
| `https://app1.xuyao.live` | 主 API |
| `https://trc.jxhaiping.com:9968` | 备用 |
| `https://gmz.6q3zg81.xyz` | 备用 |
| `https://jlk.r4z4dxx.xyz` | 备用 |
| `https://fdw.tndtvyn.com` | 备用 |
| `http://10.10.20.131:9999` | 内网测试 |

### 域名下发接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/domain/list` | 拉取可用域名列表 |
| GET | `/domain/listDetection` | 域名探测检测 |
| GET | `/inter/getJson` | 拉取交互配置（含 `jcSecretKey`、`agoraAppId` 等） |
| GET | `/are/you/ok` | 健康检查（通道测速） |
| GET | `/s/g` | 未知用途（可能是"秒测"） |
| POST | `/app/domainReport` | 上报域名解析结果 |

### 域名备用下发通道

| 通道 | URL |
|---|---|
| GitHub | `https://github.com/iamzhibo/iamzhibo/blob/main/README.md` |
| GitHub | `https://github.com/jyzbgit/jyzbgit/blob/main/README.md` |
| RAP2 | `http://rap2api.taobao.org/app/mock/313573/refAddress` |
| RAP2 | `http://rap2api.taobao.org/app/mock/313573/refAddressTest` |
| 腾讯 COS | `https://getbak-1328058668.cos-website.ap-guangzhou.myqcloud.com` |
| 请求路径 | `/refAddress.json`、`/refAddressTest.json` |

### 域名下发返回的配置字段（`/inter/getJson`）

> 这些字段在 `pp.txt:edf0-ee70` 区间，是全局配置项。

| 字段 | 说明 |
|---|---|
| `agoraAppId` | 声网 App ID |
| `agoraAppCertificate` | 声网证书 |
| `zegoAppId` | 即构 App ID |
| `zegoAppSign` | 即构签名 |
| `jcSecretKey` | 客服系统 AES 加密密钥 |
| `jcApiKey` | 客服系统 API key |
| `jcAppFlag` | 客服系统标识 |
| `jcWebviewHost` | 客服 WebView 域名 |
| `jcWebSocketHost` | 客服 WebSocket 域名 |
| `jcApplicationHost` | 客服 API 域名 |
| `sentryDNS` | Sentry DSN |
| `speedTestUrl` | CDN 测速地址（`eo-edgefunctions1.com/__down`） |
| `pushStreamType` | 推流类型 |
| `smsAvailable` | 短信可用 |
| `appCenterUrl` | 应用中心 URL |
| `isForeign` | 是否海外版 |
| `previewTime` | 预览时长（你改掉的那个限制） |
| `shareDomain` | 分享域名 |
| `officialAddr` | 官网地址 |
| `anchorDownloadAddress` | 主播下载地址 |
| `bobiDownloadUrl` | 波币下载 |
| `bobiTutorialUrl` | 波币教程 |
| `huiOneDownloadUrl` | HuiOne 支付下载 |
| `toPayDownloadUrl` | ToPay 下载 |
| `toPayVideo` | ToPay 视频 |
| `hlwCustomerAppId` | 客服 App ID |
| `hlwCustomerApiHost` | 客服 API 域名 |
| `hlwCustomerIframeUrl` | 客服 iframe URL |
| `hlwCustomerBlackIframeUrl` | 客服黑名单 iframe |
| `hlwCustomerAppSecret` | 客服 App Secret |
| `hlwCustomerAIUrl` | AI 客服 URL |
| `merchantHomeUrl` | 商户首页 |
| `merchantHomeIconUrl` | 商户图标 |
| `minioClientUpload` | MinIO 上传 |
| `minioAccessKey` | MinIO Access Key |
| `minioSecretKey` | MinIO Secret Key |
| `minioBucket` | MinIO Bucket |
| `googleClientId` | Google OAuth |
| `googleRedirectUrl` | Google 回调 |
| `promoteMaterialImgUrlList` | 推广素材 |
| `promoteMaterialContent` | 推广内容 |
| `h5UrlForAnchorApply` | 主播申请 H5 |
| `isEmailRegister` / `isGoogleRegister` / `isPhoneRegister` | 注册方式开关 |
| `isEmailPassLogin` / `isGooglePassLogin` / `isPhonePassLogin` | 登录方式开关 |
| `passwordRegisterSwitch` | 密码注册开关 |
| `isSdkMerchant` | 是否 SDK 商户 |
| `xingBiWalletH5Url` | 星币钱包 H5 |
| `xingBiAdvShowStep` | 星币广告步骤 |

### DAU 上报

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/open` | DAU 上报（带 `equiModel`、`equiCompany` 设备信息） |
| POST | `/app/install` | 安装上报 |
| POST | `/app/analytics/save` | 分析数据保存 |
| POST | `/app/errorReport` | 错误上报 |

### 版本与设置

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/appversion/getNewVersion` | 检查更新 |
| GET | `/app/myset` | 获取设置 |
| GET | `/app/myTab` | 获取 Tab 配置 |
| GET | `/app/secretCenter` | 安全中心 |
| POST | `/app/changeLanguage` | 切换语言 |
| GET | `/app/appLock/info` | 应用锁信息 |
| POST | `/app/appLock/save` | 保存应用锁 |
| POST | `/app/appLock/close` | 关闭应用锁 |
| GET | `/app/appLock/check` | 检查应用锁 |

---

## 2. 认证与用户

### 注册

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/phoneRegister` | 手机注册 |
| POST | `/app/mailRegister` | 邮箱注册 |
| POST | `/app/passwordRegister` | 密码注册 |
| GET | `/app/getSmsCode` | 获取短信验证码 |
| GET | `/app/getMailVerificationCode` | 获取邮箱验证码 |
| POST | `/app/accountVerificationCode` | 账号验证码 |
| POST | `/app/phoneVerification` | 手机验证 |
| POST | `/app/emailVerification` | 邮箱验证 |

### 登录

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/accountLogin` | 账号密码登录 |
| POST | `/app/googleLogin` | Google 登录 |
| POST | `/app/idCard/login` | 身份证登录 |
| GET | `/app/xb/getLoginInfo` | 获取登录信息 |
| GET | `/app/xb/balance` | 获取余额 |

### 绑定

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/phoneBind` | 绑定手机 |
| POST | `/app/mailBind` | 绑定邮箱 |
| POST | `/app/googleBind` | 绑定 Google |
| POST | `/app/setEmail` | 设置邮箱 |
| POST | `/app/bindphone` | 绑定手机（v2） |
| GET | `/app/getUserConnectInfo` | 获取已绑定的联系方式 |

### 密码

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/setLoginPassword` | 设置登录密码 |
| POST | `/app/changeLoginPassword` | 修改登录密码 |
| POST | `/app/changePassword` | 修改密码 |
| POST | `/app/firstSetWithdrawPassword` | 首次设置提现密码 |
| POST | `/app/changeWithdrawPassword` | 修改提现密码 |
| POST | `/app/setDestroyPassword` | 设置销毁密码 |
| POST | `/app/withdraw/bind/getVerificationCode` | 提现绑定验证码 |

### 用户信息

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/info/userBase` | 用户基础信息 |
| GET | `/user/info/anchorBase` | 主播基础信息 |
| GET | `/user/info/firstRecharge` | 首充信息 |
| GET | `/user/info/download` | 下载信息 |
| GET | `/user/blackStatus` | 封禁状态 |
| GET | `/user/wallet` | 钱包 |
| GET | `/user/wallet/showExperienceFirst` | 体验金优先 |
| GET | `/user/home/page` | 用户主页 |
| GET | `/user/rights` | 用户权益 |
| GET | `/user/member` | 会员信息 |
| GET | `/user/memberById` | 按 ID 查会员 |
| GET | `/user/hasPermissionModify` | 修改权限检查 |
| POST | `/user/modify/info` | 修改信息 |
| GET | `/user/checkModifyNickName` | 检查改昵称 |
| POST | `/app/user/nickName` | 修改昵称 |
| POST | `/app/accountLogin` | 账号登录 |
| GET | `/user/getNewMsgNum` | 新消息数 |

### 黑名单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/blacklist` | 黑名单列表 |
| POST | `/user/joinInBlacklist` | 加入黑名单 |
| GET | `/user/isInBlacklist` | 是否在黑名单 |
| POST | `/user/deleteConverse` | 删除会话 |
| POST | `/user/userReport` | 用户举报 |

### 关注

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/attention/add` | 关注 |
| POST | `/user/attention/cancel` | 取消关注 |
| GET | `/user/attention/list` | 关注列表 |
| GET | `/user/fans/list` | 粉丝列表 |
| GET | `/home/user/notattention/list` | 未关注推荐 |
| GET | `/home/user/attention/list` | 已关注列表 |

### 安全设置

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/anchor/setSecurityAnswer` | 设置安全问题 |
| GET | `/app/anchor/listQuestion` | 问题列表 |
| GET | `/app/anchor/getQuestion` | 获取问题 |
| POST | `/app/anchor/checkSecurityAnswer` | 验证安全问题 |
| GET | `/user/anchor/msgSwitch/get` | 消息开关 |
| POST | `/user/anchor/msgSwitch/save` | 保存消息开关 |
| POST | `/user/set/startBroadcastRemind` | 开播提醒 |

### 国家

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/country/list` | 国家列表 |
| GET | `/country/anchor/list` | 主播国家列表 |
| POST | `/country/select` | 选择国家 |

---

## 3. 首页与房间列表

### 推荐流

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/home/rec` | **首页推荐房间列表** |
| GET | `/home/recommend/cls` | 推荐分类 |
| GET | `/home/default/cls/query` | 默认分类查询 |
| GET | `/home/cls/queryById` | 按 ID 查分类 |
| GET | `/home/rankList` | 首页排行榜 |
| GET | `/home/giftList` | 首页礼物 |
| GET | `/home/gameMessage` | 游戏消息 |

### 房间推荐

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/room/recommend/anchor/list` | 推荐主播列表 |
| GET | `/country/anchor/list` | 各国主播列表 |

---

## 4. 直播间核心

### 进房与房间数据

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/live/joinRoom` | **进入直播间** |
| POST | `/room/staticData/live` | **获取直播间静态数据（含拉流地址）** |
| POST | `/room/dynamicData` | 直播间动态数据（轮询刷新） |
| POST | `/app/live/leaveRoom` | 离开直播间 |
| POST | `/app/live/onlineList` | 在线列表 |

### `/room/staticData/live` 返回结构（`LiveRoomDataEntity`）

```
{
  "userId": "12345",
  "feeType": 1,              // 付费类型
  "feePrice": 100,            // 价格
  "cover": "https://...",     // 封面
  "liveState": 1,             // 直播状态
  "durationTime": 3600,       // 时长
  "endTime": "2026-01-01",    // 结束时间
  "pullStreamUrl": "https://pull.xxx/xxx.flv",    // 拉流地址（主）
  "pullStreamAddr": "...",    // 拉流地址（通用）
  "pullStreamAddr360": "...", // 360P
  "pullStreamAddr720": "...", // 720P
  "streamEncrypted": false,   // 流是否加密
  "isStreamEncrypted": false,  // 流是否加密
  "barrageCnt": 100,          // 弹幕数
  "moneyFen": 0,              // 金额
  "vibratingEgg": false,      // 跳蛋
  "gameIcon": "...",          // 游戏图标
  "inviteCode": "...",        // 邀请码
  "weekStar": ...             // 周星
}
```

### 房间子接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/room/announcement` | 公告（你改掉的那个弹框） |
| POST | `/room/staticData/desire` | 欲望值/心愿单 |
| GET | `/room/staticData/fansguard` | 守护信息 |
| GET | `/room/gifts` | 礼物列表 |
| GET | `/room/gifts/blindBox/detail` | 盲盒礼物详情 |
| GET | `/room/managerUser` | 房管用户 |
| GET | `/room/userRank` | 用户榜 |
| GET | `/room/roomRank` | 房间榜 |
| GET | `/room/familyIncomeRank` | 家族收入榜 |
| GET | `/room/lastHourAnchorIncomeRank` | 上小时主播收入榜 |
| GET | `/room/anchorIncomeHourRankInRealTime` | 实时主播收入榜 |
| GET | `/room/listImAndKickOutRecord` | IM 和踢出记录 |
| POST | `/room/delImAndKickOutRecord` | 删除记录 |
| GET | `/room/jumpEgg/list` | 跳蛋列表 |
| GET | `/room/gameGuide/ReceiveExperience` | 游戏引导领取体验 |
| GET | `/room/gameGuide/experience` | 游戏引导体验 |

### 房间设置

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/live/updateLiveRoomTitle` | 修改房间标题 |
| POST | `/app/live/tipMsg` | 提示消息 |
| POST | `/app/live/changeFee` | 修改付费 |
| GET | `/app/live/priceConfig` | 价格配置 |
| GET | `/app/live/getLivePendant` | 直播挂件 |
| GET | `/app/live/getAnchorCard` | 主播名片 |
| POST | `/app/live/pay` | 直播付费 |

### 房管

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/app/anchor/listRoomManager` | 房管列表 |
| POST | `/app/anchor/deleteManager` | 删房管 |

---

## 5. 礼物与背包

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/room/gifts` | 礼物列表 |
| GET | `/room/gifts/blindBox/detail` | 盲盒详情 |
| GET | `/userBackPack/backPackList` | 背包列表 |
| GET | `/userBackPack/mallItem` | 商城物品 |
| GET | `/userBackPack/mallPropsType` | 道具类型 |
| POST | `/userBackPack/buyMallItem` | 购买商城物品 |
| POST | `/userBackPack/vehicleUseAndDisabled` | 坐骑使用/禁用 |
| POST | `/userBackPack/movieTicketUse` | 电影票使用 |
| POST | `/userBackPack/nobilityApply` | 贵族申请 |
| GET | `/userBackPack/getNobilityVehiclePic` | 贵族坐骑图 |
| GET | `/userBackPack/getUserProps` | 用户道具 |

---

## 6. 充值与提现

### 充值

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/recharge/paytype/list` | 支付方式列表 |
| GET | `/recharge/channel/list` | 充值渠道列表 |
| GET | `/recharge/getRechargeUrlNew` | 获取充值 URL |
| GET | `/recharge/currencyList` | 币种列表 |
| GET | `/recharge/record/flow` | 充值流水 |
| GET | `/coin/consume/list` | 金币消费记录 |

### 提现

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/withdraw/check/list` | 提现检查列表 |
| GET | `/withdraw/typeList` | 提现类型 |
| GET | `/withdraw/currencyList` | 提现币种 |
| GET | `/withdraw/accountList` | 提现账户 |
| GET | `/withdraw/myIncome` | 我的收入 |
| POST | `/withdraw/process` | 提现处理 |
| GET | `/withdraw/order/list` | 提现订单 |
| GET | `/withdraw/order/detail` | 订单详情 |
| POST | `/withdraw/order/del` | 删订单 |
| GET | `/withdraw/listAllBank` | 银行列表 |
| GET | `/withdraw/countryList` | 国家列表 |
| GET | `/withdraw/pixKey` | Pix Key |
| POST | `/withdraw/bindPhoneAccount` | 绑定手机账户 |
| POST | `/withdraw/bindPixAccount` | 绑定 Pix |
| POST | `/withdraw/bindPayWalletAccount` | 绑定支付钱包 |
| POST | `/withdraw/bindWalletAccount` | 绑定钱包 |
| POST | `/withdraw/bindAlipayAccount` | 绑定支付宝 |
| POST | `/withdraw/user/bindBank` | 绑定银行卡 |
| POST | `/withdraw/bindAndJump` | 绑定并跳转 |

### 钱包

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/wallet` | 钱包 |
| GET | `/app/xb/balance` | 余额 |
| POST | `/app/live/pay` | 直播付费 |

---

## 7. 游戏/博彩

### 通用

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/game/getGameUrl` | 游戏 URL |
| GET | `/game/getLiveGameList` | 直播游戏列表 |
| GET | `/game/getLiveGameListNew` | 直播游戏列表（新） |
| GET | `/game/getTabGameListNew` | Tab 游戏列表 |
| GET | `/game/getTabGameListByClsIdNew` | 按分类查 |
| GET | `/game/getGameBrowseList` | 浏览列表 |
| GET | `/game/getGameChips` | 筹码 |
| GET | `/game/getGameRecordByDayType` | 按日记录 |
| GET | `/game/getGameRecordDetail` | 记录详情 |
| POST | `/game/transferAllAmount` | 转入全部 |
| POST | `/game/transferAllGameOut` | 转出全部 |

### 下注

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/game/live/playerBet/simple` | 正式下注 |
| POST | `/game/experience/playerBet/simple` | 体验下注 |
| POST | `/game/live/playerBet4WorldCupMultiple` | 世界杯下注 |
| GET | `/game/live/getFollowBetOrders` | 跟投订单 |
| GET | `/game/experience/getFollowBetOrders` | 体验跟投 |
| GET | `/game/live/getFollowBetOrders4Third` | 三方跟投 |
| GET | `/game/live/getContinueGameOrders` | 续注 |
| GET | `/game/experience/getContinueGameOrders` | 体验续注 |
| GET | `/game/live/getPeriodBetResultList` | 期间投注结果 |
| GET | `/game/experience/getPeriodBetResultList` | 体验 |

### 游戏结果

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/game/live/getGamePeriod` | 游戏期次 |
| GET | `/game/live/getGamePeriodResult` | 期次结果 |
| GET | `/game/live/getGameResultLong` | 长结果 |
| GET | `/game/live/getGameResultChart` | 结果图表 |
| GET | `/game/live/getGameResultHistory` | 历史结果 |
| GET | `/game/experience/getGameResultHistory` | 体验历史 |
| GET | `/game/live/getGameOrdersHistory` | 投注历史 |
| GET | `/game/experience/getGameOrdersHistory` | 体验投注 |
| GET | `/game/live/getGameOrdersHistory4WorldCup` | 世界杯投注 |
| GET | `/game/getFullScreenLuckyList` | 全屏中奖 |

### 大富翁

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/game/live/spinMonopoly` | 转盘 |
| POST | `/game/experience/spinMonopoly` | 体验转盘 |
| POST | `/game/live/enterMonopoly` | 进入大富翁 |
| POST | `/game/experience/enterMonopoly` | 体验进入 |

### 进入游戏

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/game/live/enterGame` | 正式进入 |
| POST | `/game/experience/enterGame` | 体验进入 |

### 主播推广游戏

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/anchor/promote/gameRateList` | 游戏费率 |
| GET | `/user/anchor/promote/gameList` | 推广游戏 |

---

## 8. 主播管理

### 主播基础

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/info/anchorBase` | 主播基础信息 |
| GET | `/app/anchor/countDesireItem` | 欲望物品数 |
| GET | `/app/anchor/listDesireItem` | 欲望物品列表 |
| POST | `/app/anchor/saveDesireItem` | 保存欲望物品 |
| POST | `/app/anchor/updateDesireItem` | 更新 |
| POST | `/app/anchor/deleteDesireItem` | 删除 |
| GET | `/app/anchor/blocked` | 封禁 |
| GET | `/app/anchor/warning` | 警告 |
| GET | `/user/anchor/anchorObsWhite` | OBS 白名单 |
| POST | `/app/anchor/message/save` | 主播消息保存 |
| GET | `/app/anchor/message/getConfig` | 消息配置 |
| GET | `/user/anchor/broadcast/detail` | 广播详情 |
| GET | `/user/anchor/broadcast/record` | 广播记录 |
| POST | `/user/anchor/broadcast/delRecord` | 删记录 |
| GET | `/user/anchor/broadcast/otherIncomeDetail` | 其他收入 |
| POST | `/user/anchor/broadcast/otherIncomeDetailDel` | 删收入 |
| GET | `/user/anchor/broadcast/liveGiftDetail` | 礼物收入 |
| GET | `/user/anchor/broadcast/currentIncomeJournal` | 当前收入流水 |
| GET | `/user/anchor/msgInfo/detail` | 消息详情 |

### 主播动态/帖子

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/anchor/postCnt/select` | 帖子数 |
| GET | `/anchor/post/select` | 帖子列表 |
| POST | `/anchor/post/publish` | 发帖 |
| POST | `/anchor/delete/post` | 删帖 |

### 主播商品

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/anchor/goods/list` | 商品列表 |
| POST | `/anchor/goods/add` | 添加商品 |
| POST | `/anchor/goods/edit` | 编辑商品 |
| POST | `/anchor/goods/remove` | 删除商品 |
| GET | `/anchor/goodsLog/list` | 商品日志 |

### 主播关注计划

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/anchor/followPlan/list` | 列表 |
| POST | `/anchor/followPlan/add` | 添加 |
| POST | `/anchor/followPlan/editSave` | 编辑保存 |
| POST | `/anchor/followPlan/remove` | 删除 |

### 匹配

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/anchor/match/save` | 保存匹配 |
| GET | `/anchor/match/info` | 匹配信息 |

### 名片

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/getMyBusinessCard` | 我的名片 |
| GET | `/user/getUpLevelBusinessCard` | 上级名片 |
| POST | `/user/saveMyBusinessCard` | 保存名片 |
| GET | `/user/business/card` | 名片 |
| POST | `/user/bindLowerLevel` | 绑定下级 |
| GET | `/user/getDefaultPercent` | 默认分成 |
| POST | `/user/saveDefaultPercent` | 保存分成 |
| POST | `/user/saveDefaultPercentNew` | 保存分成（新） |
| POST | `/user/anchor/saveDefaultPercent` | 主播分成 |

### 语言主播

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/languageAnchorList` | 语言主播列表 |
| GET | `/user/languageAnchor` | 语言主播 |
| POST | `/user/languageAnchorSave` | 保存 |
| GET | `/user/anchorMobileAndSecretKey` | 主播手机与密钥 |

---

## 9. 社交与消息

### 私信

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/converseList` | 会话列表 |
| POST | `/user/sendMsg` | 发消息 |
| GET | `/user/getHistoryMessage` | 历史消息 |
| GET | `/user/getNewMessage` | 新消息 |
| GET | `/user/getCustomerList` | 客服列表 |
| GET | `/user/getNewMsgNum` | 新消息数 |

### 任务与签到

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/task/red/dot` | 任务红点 |
| GET | `/user/task/info/list` | 任务列表 |
| GET | `/user/task/type/red/dot` | 任务类型红点 |
| GET | `/user/task/extra/record` | 额外记录 |
| POST | `/user/task/award/receive` | 领取奖励 |
| GET | `/user/sign/list` | 签到列表 |
| POST | `/user/sign/doSign` | 签到 |
| GET | `/user/sign/popUp` | 签到弹窗 |

### 衣柜

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/app/clothes/saveOrUpdate` | 保存衣柜 |
| GET | `/app/clothes/listItem` | 衣柜物品 |
| GET | `/app/clothes/selectOneByUserId` | 按用户查 |
| GET | `/app/clothes/listLogByAnchor` | 主播日志 |
| GET | `/app/clothes/listLogByUser` | 用户日志 |
| POST | `/app/clothes/saveOrUpdateItem` | 保存物品 |
| POST | `/app/clothes/deleteItem` | 删物品 |
| POST | `/app/clothes/userPay` | 用户支付 |

### 用户展示商品

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/user/show/goods/list` | 展示商品 |
| GET | `/user/goods/list` | 用户商品 |
| GET | `/user/goodsLog/list` | 商品日志 |
| POST | `/user/buyAnchorGoods` | 购买主播商品 |

---

## 10. 活动中心

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/activityCenter/activityList` | 活动列表 |
| GET | `/activityCenter/activityTypeList` | 活动类型 |
| GET | `/activityCenter/activityApplyList` | 申请列表 |
| POST | `/activityCenter/applyReward` | 申请奖励 |
| GET | `/activityCenter/getActivityAmount` | 活动金额 |
| GET | `/activityCenter/toLivingRoom` | 跳转直播间 |
| GET | `/activityCenter/floatScreenMsg` | 滚屏消息 |
| GET | `/activityCenter/red/flag` | 红旗 |
| GET | `/activityCenter/totalExchangeActivity` | 总兑换 |
| GET | `/activityCenter/getTotalExchangeActivityProps` | 兑换道具 |
| GET | `/activityCenter/firstChargeRebate` | 首充返利 |
| GET | `/activityCenter/getFirstChargeRebate` | 获取首充返利 |
| GET | `/activity/download/info` | 下载活动 |
| POST | `/activity/download/receive` | 领取下载奖励 |
| GET | `/activity/h5/accessToken` | H5 Token |
| GET | `/activityBet/entranceList` | 投注入口 |
| POST | `/activityBet/receivePrize` | 领奖 |
| GET | `/activityRecharge/awardLog` | 充值奖励日志 |
| GET | `/activityRecharge/getAwardStatus` | 奖励状态 |
| GET | `/activityRecharge/getLevel` | 等级 |
| GET | `/activityRecharge/getRule` | 规则 |
| POST | `/activityRecharge/award` | 领取 |
| GET | `/app/activity/registerGift/getConfig` | 注册礼配置 |
| POST | `/app/activity/registerGift/award` | 注册礼领取 |

---

## 11. 代理系统

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/app/agent/info` | 代理信息 |
| GET | `/app/agent/centerDataInfoNew` | 中心数据 |
| POST | `/app/agent/pay` | 代理支付 |
| POST | `/app/agent/confirm` | 确认 |
| POST | `/app/agent/receiveNew` | 领取 |
| GET | `/app/agent/getTransOutRecord` | 转出记录 |
| GET | `/app/agent/getTranRecordByInviteCode` | 按邀请码查 |
| GET | `/app/agent/superChildInfo` | 超级下级 |
| POST | `/app/normal/bindAndJump` | 普通绑定跳转 |

### 家族

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/app/family/queryV2` | 家族信息 |
| GET | `/app/family/applyList` | 申请列表 |
| POST | `/app/family/handleApply` | 处理申请 |
| GET | `/app/family/getRate` | 家族费率 |

---

## 12. 直播开播

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/app/live/getAnchorLiveState` | 主播开播状态 |
| POST | `/app/live/startBroadcast` | 开始直播 |
| POST | `/app/live/startPush` | 开始推流 |
| POST | `/app/live/retry` | 重试 |
| POST | `/app/live/turnOffStreaming` | 关闭直播 |
| GET | `/app/live/getLiveFinishInfo` | 直播结束信息 |
| POST | `/app/live/setSpecialGiftState` | 特殊礼物状态 |
| POST | `/app/live/log` | 直播日志 |

---

## 13. 投票/心愿单

### 欲望值

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/room/staticData/desire` | 欲望值 |
| GET | `/app/desire/getPayInfo` | 支付信息 |
| GET | `/app/desire/getPayList` | 支付列表 |
| POST | `/app/desire/saveDesire` | 保存 |
| POST | `/app/desireItem/pay` | 欲望物品支付 |

### 投票

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/vote/anchor/initQuery` | 初始化查询 |
| POST | `/vote/anchor/Create` | 创建投票 |
| GET | `/vote/anchor/getVoteInfo` | 投票信息 |
| GET | `/vote/anchor/getVoteRecordList` | 投票记录 |
| GET | `/vote/anchor/getVoteDetailRecord` | 详细记录 |
| POST | `/vote/anchor/finish` | 结束投票 |
| GET | `/vote/user/noFee` | 免费投票 |

---

## 14. 客服系统（独立 API）

> 域名：运行时从 `/inter/getJson` 的 `jcApplicationHost` 字段获取（抓包见 `api.haoghe.xyz`）
> **请求体/响应体均为 AES 加密后 Base64**

### 请求头

| 头 | 说明 |
|---|---|
| `X-App-Identity` | `91xl001` |
| `X-Auth-Token` | 登录 token |
| `X-TimeStamp` | 13 位毫秒时间戳 |
| `accept-language` | `zh-cn` |
| `content-type` | `application/json` |

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/cs/workorder/login` | 客服登录（返回 `hlwToken`） |
| GET | `/api/cs/workorder/workOrderMsgCount` | 工单消息数 |

### 返回字段（`pp.txt:e9d0-ea08`）

| 字段 | 说明 |
|---|---|
| `hlwToken` | 客服 token |
| `ptDownloadUrl` | Telegram 下载 |
| `tgDownloadUrl` | Telegram 下载 |
| `tgMgrAccount` | Telegram 客服 |
| `ptMgrAccount` | 客服账号 |
| `ptMgrAccountLink` | 客服链接 |
| `exchangeGroup` | 交流群 |
| `rankingSwitch` | 排行开关 |
| `officialAddr` | 官网 |
| `anchorDownloadAddress` | 主播下载 |
| `merchantPromoteBgPicUrl` | 推广背景图 |
| `modifyNickNameNeedCoin` | 改名费 |
| `anchorAuditor` | 主播审核 |
| `merchantHomeUrl` / `merchantHomeDesc` / `merchantHomeIconUrl` | 商户首页 |

### AES 加密

- 算法：AES（模式未知，CBC/ECB 待定）
- 密钥来源：`/inter/getJson` 返回的 `jcSecretKey` 字段（**非硬编码**，运行时获取）
- IV 来源：未知（可能是全零或密钥本身）
- 填充：PKCS7（推测，因密文长度均为 16 的倍数）

---

## 15. 数据结构

### `LiveRoomDataEntity`（直播间静态数据）

```
userId          String    主播 ID
feeType         int       付费类型
feePrice        int       付费价格
cover           String    封面 URL
liveState       int       直播状态 (1=直播中)
durationTime    int       直播时长（秒）
endTime         String    结束时间
pullStreamUrl   String    拉流地址（主）
pullStreamAddr  String    拉流地址（通用）
pullStreamAddr360  String 360P 地址
pullStreamAddr720  String 720P 地址
streamEncrypted    bool   流是否加密
isStreamEncrypted bool   流是否加密
barrageCnt      int       弹幕数
moneyFen        int       金额（分）
vibratingEgg    bool      跳蛋
gameIcon        String    游戏图标
inviteCode      String    邀请码
weekStar        dynamic    周星
```

### 用户信息（`UserBase`，`pp.txt:f6e8-fbb0`）

```
accountType         int      账号类型
anchorIcon          String   主播图标
anchorLevel         int      主播等级
attenCnt            int      关注数
fansCnt             int       粉丝数
gender              int      性别
levelIcon           String   等级图标
myInviteCode        String   邀请码
phone               String   手机
userCls             String   用户分类
userIcon            String   头像
userLevel           int      用户等级
userName            String   昵称
isMigrate           bool     是否迁移
userApplyAnchorState String  申请主播状态
goldFamily          bool     金主家族
nobilityType        String   贵族类型
nobilityExpiresTime String   贵族到期
nobilityStatus      String   贵族状态
superManager        bool     超管
fansTeam           String    粉丝团
anchorType          String   主播类型
worldCupAnchor     bool      世界杯主播
email               String   邮箱
facebook            String   Facebook
google              String   Google
currency            String   币种
currencySymbol     String    货币符号
currencyLogo       String    货币 logo
bindBankCountry    String    绑卡国家
bindInfo           dynamic   绑定信息
firstRecharge      bool      首充
languageAnchor    bool      语言主播
languageSwitch    bool      语言切换
anchorDepositIsEnough bool   主播保证金充足
anchorTargetDeposit String   主播目标保证金
securityQuestion   String   安全问题
gmtCreate          String   创建时间
downloadReceive    dynamic   下载领取
downLoadReceivePropsInfoDTO dynamic 下载领取信息
isBlack            bool     是否封禁
blackTime          String   封禁时间
blackReason        String   封禁原因
customerEnabled    bool     客服可用
customerMsgType    String   客服消息类型
targetUserId       String   目标用户
```

### Simonsdk 统计上报（`api.ndasnd.co`）

```
POST /api/v2_5/android/{APP_KEY}/stats/events?{混淆参数}

请求体（明文 CSV）：
时间戳,时长;时间戳,时长;...

示例：
1784293350996,18;1784293730310,302;...
```

---

## 16. 鉴权与签名

### 主 API 鉴权头

| 头 | 值 | 来源 |
|---|---|---|
| `authorization` | `Bearer {token}` 或直接 token | 登录返回 |
| `x-auth-timestamp` | 13 位毫秒时间戳 | 当前时间 |
| `x-auth-sign` | 请求签名 | 见下 |
| `deviceUid` | 设备唯一标识 | `KEY_STR_DEVICE_ID` 存储 |
| `terminal` | `jyzb` | 硬编码 |
| `channel` | `jyzb` | 硬编码 |
| `ak` | `jyzb` | 硬编码 |
| `Content-Language` | `zh-CN` | 用户设置 |

### 签名

| 项 | 值 |
|---|---|
| 密钥 | `oknzoiwe@23`（`pp.txt:9988`） |
| 算法 | **未知**（在 AOT native 中，静态不可见） |
| 签名头 | `x-auth-sign` |
| 时间戳头 | `x-auth-timestamp` |
| 设备 ID 头 | `deviceUid` |

> **签名算法在 `libapp.so` 的 AOT 编译 native 代码中**，Blutter 只能提取字符串引用，看不到具体实现。要拿到完整签名算法，需要用 Frida hook `Qxb` 类（`pp.txt:e840`，offset 0x14d8）的签名方法，或在 IDA 里跟到对应函数。
>
> **当前可用的替代方案**：抓一次主 API 请求的完整请求头，网页复用其中的 `x-auth-sign` + `x-auth-timestamp`。对同一个接口短时间内可复用，但换接口或时间戳过期会 401。

### 响应拦截器

```
authorization          ← 登录后写入
x-domain-ver           ← /domain/list 返回的版本号
x-inter-ver            ← /inter/getJson 返回的版本号
```

日志标记：`intercept auth >>> from: / new auth: / old auth: / responseInterceptor update auth:`

### 域名版本

| 头 | 说明 |
|---|---|
| `x-domain-ver` | 域名版本号，`/domain/list` 返回 |
| `x-inter-ver` | 交互配置版本号，`/inter/getJson` 返回 |

### 存储键

| 键 | 说明 |
|---|---|
| `KEY_STR_AUTHORIZATION` | 登录 token 存储 |
| `KEY_STR_DEVICE_ID` | 设备 ID 存储 |
| `KEY_IS_RED_DOT` | 红点状态 |
| `KEY_RED_DOT` | 红点数据 |
| `KEY_JSON_USER_BLOCKED_INFO` | 封禁信息 |
| `domainVersion` | 域名版本 |
| `interVersion` | 交互版本 |
| `DAUHistory` | DAU 上报历史 |

### 客服 API 鉴权（独立体系）

| 头 | 值 |
|---|---|
| `X-App-Identity` | `91xl001` |
| `X-Auth-Token` | `/api/cs/workorder/login` 返回的 `hlwToken` |
| `X-TimeStamp` | 13 位毫秒时间戳 |

---

## 附录：DeepLink

| Scheme | Host | 说明 |
|---|---|---|
| `rtgy0bb7` | - | Simonsdk 归因/分享 |
| `huionetoonelive` | `huionetoonelive` | HuiOne 支付 |

---

## 附录：Simonsdk 统计 API

```
POST https://api.ndasnd.co/api/v2_5/android/{APP_KEY}/stats/events
APP_KEY = rtgy0bb7

请求头：
  content-type: text/plain;charset=utf-8
  User-Agent: Dalvik/2.1.0 (Linux; U; Android 12; ...)
  
URL 参数（混淆名）：
  f3ef / hwef / kjfe / opof / fefb / kfgf / jpaw / mthe / qmvzs

请求体（明文 CSV）：
  时间戳,时长;时间戳,时长;...
```

---

## 附录：所有 API 路径速查（357 条）

<details>
<summary>点击展开全部</summary>

```
/domain/list
/domain/listDetection
/inter/getJson
/are/you/ok
/s/g
/app/domainReport
/app/open
/app/install
/app/analytics/save
/app/errorReport
/appversion/getNewVersion
/app/myset
/app/myTab
/app/secretCenter
/app/changeLanguage
/app/appLock/info
/app/appLock/save
/app/appLock/close
/app/appLock/check
/app/phoneRegister
/app/mailRegister
/app/passwordRegister
/app/getSmsCode
/app/getMailVerificationCode
/app/accountVerificationCode
/app/phoneVerification
/app/emailVerification
/app/accountLogin
/app/googleLogin
/app/idCard/login
/app/xb/getLoginInfo
/app/xb/balance
/app/phoneBind
/app/mailBind
/app/googleBind
/app/setEmail
/app/bindphone
/app/getUserConnectInfo
/app/setLoginPassword
/app/changeLoginPassword
/app/changePassword
/app/firstSetWithdrawPassword
/app/changeWithdrawPassword
/app/setDestroyPassword
/app/withdraw/bind/getVerificationCode
/user/info/userBase
/user/info/anchorBase
/user/info/firstRecharge
/user/info/download
/user/blackStatus
/user/wallet
/user/wallet/showExperienceFirst
/user/home/page
/user/rights
/user/member
/user/memberById
/user/hasPermissionModify
/user/modify/info
/user/checkModifyNickName
/app/user/nickName
/user/getNewMsgNum
/user/blacklist
/user/joinInBlacklist
/user/isInBlacklist
/user/deleteConverse
/user/userReport
/user/attention/add
/user/attention/cancel
/user/attention/list
/user/fans/list
/home/user/notattention/list
/home/user/attention/list
/app/anchor/setSecurityAnswer
/app/anchor/listQuestion
/app/anchor/getQuestion
/app/anchor/checkSecurityAnswer
/user/anchor/msgSwitch/get
/user/anchor/msgSwitch/save
/user/set/startBroadcastRemind
/country/list
/country/anchor/list
/country/select
/home/rec
/home/recommend/cls
/home/default/cls/query
/home/cls/queryById
/home/rankList
/home/giftList
/home/gameMessage
/room/recommend/anchor/list
/app/live/joinRoom
/room/staticData/live
/room/dynamicData
/app/live/leaveRoom
/app/live/onlineList
/room/announcement
/room/staticData/desire
/room/staticData/fansguard
/room/gifts
/room/gifts/blindBox/detail
/room/managerUser
/room/userRank
/room/roomRank
/room/familyIncomeRank
/room/lastHourAnchorIncomeRank
/room/anchorIncomeHourRankInRealTime
/room/listImAndKickOutRecord
/room/delImAndKickOutRecord
/room/jumpEgg/list
/room/gameGuide/ReceiveExperience
/room/gameGuide/experience
/app/live/updateLiveRoomTitle
/app/live/tipMsg
/app/live/changeFee
/app/live/priceConfig
/app/live/getLivePendant
/app/live/getAnchorCard
/app/live/pay
/app/anchor/listRoomManager
/app/anchor/deleteManager
/userBackPack/backPackList
/userBackPack/mallItem
/userBackPack/mallPropsType
/userBackPack/buyMallItem
/userBackPack/vehicleUseAndDisabled
/userBackPack/movieTicketUse
/userBackPack/nobilityApply
/userBackPack/getNobilityVehiclePic
/userBackPack/getUserProps
/recharge/paytype/list
/recharge/channel/list
/recharge/getRechargeUrlNew
/recharge/currencyList
/recharge/record/flow
/coin/consume/list
/withdraw/check/list
/withdraw/typeList
/withdraw/currencyList
/withdraw/accountList
/withdraw/myIncome
/withdraw/process
/withdraw/order/list
/withdraw/order/detail
/withdraw/order/del
/withdraw/listAllBank
/withdraw/countryList
/withdraw/pixKey
/withdraw/bindPhoneAccount
/withdraw/bindPixAccount
/withdraw/bindPayWalletAccount
/withdraw/bindWalletAccount
/withdraw/bindAlipayAccount
/withdraw/user/bindBank
/withdraw/bindAndJump
/game/getGameUrl
/game/getLiveGameList
/game/getLiveGameListNew
/game/getTabGameListNew
/game/getTabGameListByClsIdNew
/game/getGameBrowseList
/game/getGameChips
/game/getGameRecordByDayType
/game/getGameRecordDetail
/game/transferAllAmount
/game/transferAllGameOut
/game/live/playerBet/simple
/game/experience/playerBet/simple
/game/live/playerBet4WorldCupMultiple
/game/live/getFollowBetOrders
/game/experience/getFollowBetOrders
/game/live/getFollowBetOrders4Third
/game/live/getContinueGameOrders
/game/experience/getContinueGameOrders
/game/live/getPeriodBetResultList
/game/experience/getPeriodBetResultList
/game/live/getGamePeriod
/game/live/getGamePeriodResult
/game/live/getGameResultLong
/game/live/getGameResultChart
/game/live/getGameResultHistory
/game/experience/getGameResultHistory
/game/live/getGameOrdersHistory
/game/experience/getGameOrdersHistory
/game/live/getGameOrdersHistory4WorldCup
/game/getFullScreenLuckyList
/game/live/spinMonopoly
/game/experience/spinMonopoly
/game/live/enterMonopoly
/game/experience/enterMonopoly
/game/live/enterGame
/game/experience/enterGame
/user/anchor/promote/gameRateList
/user/anchor/promote/gameList
/app/anchor/countDesireItem
/app/anchor/listDesireItem
/app/anchor/saveDesireItem
/app/anchor/updateDesireItem
/app/anchor/deleteDesireItem
/app/anchor/blocked
/app/anchor/warning
/user/anchor/anchorObsWhite
/app/anchor/message/save
/app/anchor/message/getConfig
/user/anchor/broadcast/detail
/user/anchor/broadcast/record
/user/anchor/broadcast/delRecord
/user/anchor/broadcast/otherIncomeDetail
/user/anchor/broadcast/otherIncomeDetailDel
/user/anchor/broadcast/liveGiftDetail
/user/anchor/broadcast/currentIncomeJournal
/user/anchor/msgInfo/detail
/anchor/postCnt/select
/anchor/post/select
/anchor/post/publish
/anchor/delete/post
/anchor/goods/list
/anchor/goods/add
/anchor/goods/edit
/anchor/goods/remove
/anchor/goodsLog/list
/anchor/followPlan/list
/anchor/followPlan/add
/anchor/followPlan/editSave
/anchor/followPlan/remove
/anchor/match/save
/anchor/match/info
/user/getMyBusinessCard
/user/getUpLevelBusinessCard
/user/saveMyBusinessCard
/user/business/card
/user/bindLowerLevel
/user/getDefaultPercent
/user/saveDefaultPercent
/user/saveDefaultPercentNew
/user/anchor/saveDefaultPercent
/user/languageAnchorList
/user/languageAnchor
/user/languageAnchorSave
/user/anchorMobileAndSecretKey
/user/converseList
/user/sendMsg
/user/getHistoryMessage
/user/getNewMessage
/user/getCustomerList
/user/getNewMsgNum
/user/task/red/dot
/user/task/info/list
/user/task/type/red/dot
/user/task/extra/record
/user/task/award/receive
/user/sign/list
/user/sign/doSign
/user/sign/popUp
/app/clothes/saveOrUpdate
/app/clothes/listItem
/app/clothes/selectOneByUserId
/app/clothes/listLogByAnchor
/app/clothes/listLogByUser
/app/clothes/saveOrUpdateItem
/app/clothes/deleteItem
/app/clothes/userPay
/user/show/goods/list
/user/goods/list
/user/goodsLog/list
/user/buyAnchorGoods
/activityCenter/activityList
/activityCenter/activityTypeList
/activityCenter/activityApplyList
/activityCenter/applyReward
/activityCenter/getActivityAmount
/activityCenter/toLivingRoom
/activityCenter/floatScreenMsg
/activityCenter/red/flag
/activityCenter/totalExchangeActivity
/activityCenter/getTotalExchangeActivityProps
/activityCenter/firstChargeRebate
/activityCenter/getFirstChargeRebate
/activity/download/info
/activity/download/receive
/activity/h5/accessToken
/activityBet/entranceList
/activityBet/receivePrize
/activityRecharge/awardLog
/activityRecharge/getAwardStatus
/activityRecharge/getLevel
/activityRecharge/getRule
/activityRecharge/award
/app/activity/registerGift/getConfig
/app/activity/registerGift/award
/app/agent/info
/app/agent/centerDataInfoNew
/app/agent/pay
/app/agent/confirm
/app/agent/receiveNew
/app/agent/getTransOutRecord
/app/agent/getTranRecordByInviteCode
/app/agent/superChildInfo
/app/normal/bindAndJump
/app/family/queryV2
/app/family/applyList
/app/family/handleApply
/app/family/getRate
/app/live/getAnchorLiveState
/app/live/startBroadcast
/app/live/startPush
/app/live/retry
/app/live/turnOffStreaming
/app/live/getLiveFinishInfo
/app/live/setSpecialGiftState
/app/live/log
/room/staticData/desire
/app/desire/getPayInfo
/app/desire/getPayList
/app/desire/saveDesire
/app/desireItem/pay
/vote/anchor/initQuery
/vote/anchor/Create
/vote/anchor/getVoteInfo
/vote/anchor/getVoteRecordList
/vote/anchor/getVoteDetailRecord
/vote/anchor/finish
/vote/user/noFee
/api/cs/workorder/login
/api/cs/workorder/workOrderMsgCount
/app/mock/313573/refAddress
/app/mock/313573/refAddressTest
/refAddress.json
/refAddressTest.json
/user/cleanRank
```

</details>

---

*文档生成自 91直播 4.3.3 Flutter AOT 逆向产物（`pp.txt` 对象池 + `asm/` 反汇编 + smali 静态分析 + 抓包对照）。签名算法与 AES 加密细节需动态调试 `libapp.so` 补全。*
