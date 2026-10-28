jj_live 直播 App
接口加密分析与解密方案

基于 libapp.so 的静态逆向（blutter + IDA Pro MCP）

样本：libapp.so（Flutter AOT，package:jj_live 6.24）

IDB：D:\WorkSpace\Android\91zb\libapp.so.i64

分析产物：blutter 反编译输出 out_dir/asm/

动态验证：IDA Pro 9.2 + ida-pro-mcp v1.0.0（127.0.0.1:13337）

文档日期：2026-10-26

## 1  执行摘要

该 App 的网络层采用「四层叠加」的保护：动态域名 + 路径混淆 + 参数签名 + 请求体 AES 加密。四层均由 Dart 侧自行实现（非标准 TLS 加固），因此可在应用层完整还原。

关键结论如下：

- 请求体： 全部接口走 POST，请求体统一包裹为 {"data": params} 后做对称加密；
- 签名： 签名算法为 MD5，使用硬编码盐值 oknzoiwe@23，参与签名的字段为「全部请求头 + 全部业务参数」按字典序拼接；
- 密钥： 对称加密密钥为「appSecret + #@^@# + 秒级时间戳」的 MD5 截断值，属时间派生密钥，每秒变化；
- 密钥来源： appSecret 由 /param/list 接口下发（字段 hlwCustomerAppSecret），App 内置默认值用于首包；
- 路径： 接口路径被替换为 base64→十六进制 的不透明串，前置一段随机串，服务端可逆解码。

由于签名与加密均为可逆的对称运算，且盐值、分隔符、算法全部硬编码在二进制中，在获得 appSecret 后即可完整实现请求构造与响应解密。当前仅剩「AES 分组模式」与「密钥截断长度」两处需用一条真实样本做一次性定参（第 10 章给出自动判定脚本）。

## 2  分析环境与方法

### 2.1  工具链

| 工具 | 用途 | 说明 |
| --- | --- | --- |
| blutter | Dart AOT 反编译 | 还原类/方法结构、调用关系与字符串引用，产出 out_dir/asm/*.dart |
| IDA Pro 9.2 | 原生层校验 | 对 .text 段做 Hex-Rays 反编译，交叉验证关键函数 |
| ida-pro-mcp | 动态查询 | JSON-RPC over HTTP（127.0.0.1:13337/mcp），66 个工具 |
| PowerShell | MCP 客户端 | 以 Invoke-WebRequest 直连 MCP，绕开工具注册，直接调用 decompile/find_regex |

说明：本次分析未依赖 MCP 工具注册，而是直接以 HTTP JSON-RPC 调用 IDA MCP 的 initialize / tools/list / tools/call，因此可在任意环境中复用。

### 2.2  定位思路

- 第一步 以 grep 在 blutter 产物中检索 live / room / anchor 等业务词，锁定 package:jj_live。
- 第二步 从 RequestRepository 的方法名追溯到 DioManager.request 总入口。
- 第三步 沿 request 的字符串常量与调用点，抽出 encodeApiPath / getAuthorizationHeader / aesEncrypt 四个关键分支。
- 第四步 用 IDA MCP 的 find_regex 在二进制中确认盐值、分隔符等字面量地址，排除反编译误差。

## 3  加密链路总览

```
明文业务参数 params
   |
   +--[Body] jsonEncode({"data": params})
   |            -> AES(key = IV = 派生密钥, PKCS7)
   |            -> base64, 空格替换为 '+'
   |            -> HTTP Body
   |
   +--[Headers] terminal / version / ak / channel / authorization
                x-auth-timestamp / deviceUid / Content-Language
                -> x-auth-sign = md5( path + "?" + 排序 k=v 串 + "oknzoiwe@23" )

URL = https://<动态域名> + "/" + 随机串 + hex(base64(apiPath))

派生密钥 = md5( appSecret + "#@^@#" + 秒级时间戳 ).substring(8)   # 24 字符
```

四层保护相互独立，可分别剥离：路径混淆只影响 URL，签名只影响请求头，AES 只影响 Body。这为分步验证提供了便利——即使暂时不确定 AES 模式，也可先用签名算法通过服务端的鉴权校验。

## 4  动态域名层

域名不硬编码于接口调用处，而由 DomainManager 统一调度，并提供多源兜底同步机制，目的是应对域名封禁。

### 4.1  调用关系

```
DioManager.request(...)
    -> DomainManager.getDomain(DomainType)        [0x8aab6c]
       -> 读取 DomainManager._domains[type]  (Map<DomainType, List<String>>)
       -> DomainManager.nextDomain(type)       [0x8345b4] 轮换
    -> fullUrl = (host 参数 ?? 该域名) + encodedPath
```

### 4.2  内置域名池

| 序号 | 域名 | 角色 |
| --- | --- | --- |
| 1 | https://app1.xuyao.live | 主域名（basic_domain_sync） |
| 2 | https://trc.jxhaiping.com:9968 | 备用 |
| 3 | https://gmz.6q3zg81.xyz | 备用 |
| 4 | https://jlk.r4z4dxx.xyz | 备用 |
| 5 | https://fdw.tndtvyn.com | 备用 |
| - | https://91zbzb.com / https://ptcc.in/jiuyizb | 推广落地页 |

### 4.3  域名同步源

域名列表可被远程更新，同步实现有四种，均位于 jj_live/domain/impl/：

| 实现 | 上游地址 |
| --- | --- |
| tencent_domain_sync | https://getbak-1328058668.cos-website.ap-guangzhou.myqcloud.com |
| git_domain_sync | https://github.com |
| taobao_domain_sync | http://rap2api.taobao.org |
| server_domain_sync | 服务端接口 |

反向意义：若主域名不可达，可在 DNS/代理层固定到任一备用域名，或直接以 host 参数覆盖，不影响后续协议分析。

## 5  请求头与鉴权

由 DioManagerHeader.getAuthorizationHeader(path, params, method) 统一构造（0x8a4afc）。

| Header | 取值来源 | 备注 |
| --- | --- | --- |
| terminal | 常量 4 | 终端类型标识 |
| version | PackageManager.getAppVersionForHeader() | App 版本号 |
| ak | 常量 "jyzb" | 0xe728，固定 AppKey |
| channel | 常量 "1" | 渠道号 |
| authorization | UserManager.getAuth() | 登录态 token，未登录为空 |
| x-auth-timestamp | DateTime.now().millisecondsSinceEpoch | 毫秒时间戳，参与签名 |
| deviceUid | DeviceManager.getDeviceId() | 设备唯一号 |
| Content-Language | I18nManager.getContentLanguageForHeader() | 如 zh-cn |
| x-auth-sign | 见第 6 章 | 签名，最后写入 |

注意：x-auth-sign 自身不参与签名计算；其余全部请求头（含时间戳）均参与。这一点常被忽略，会导致签名校验失败。

## 6  参数签名算法（x-auth-sign）

由 buildJJAuthorizationSignSource（0x8a4f28）生成源串，再由 encodeMd5ForJJ（0x8a4ec0）计算摘要。

### 6.1  算法步骤

```
1) keys = sort( keys(headerMap) ∪ keys(params) )      // Dart 默认字符串升序
2) 逐个 key 拼 "k=v"：
     v 优先取 headerMap[k]，若缺失则取 params[k]
     v 为 List/Map 时用 jsonEncode 序列化
3) 以 "&" 连接，尾部再接一个字面量 "?"
     signSource = path + "?" + "k1=v1&k2=v2&..."
4) x-auth-sign = md5( signSource + "oknzoiwe@23" )
```

### 6.2  实现要点

- 要点 1 path 为「未混淆的原始业务路径」，如 /home/rec，而非 URL 中的十六进制串。
- 要点 2 那个 "?" 是字面量拼接，不是查询分隔符；即使没有任何参数也要拼上。
- 要点 3 排序为码位升序，与 Python 默认字符串排序一致，不需特殊处理。
- 要点 4 空值处理：值为 null 或空串时该键不写入源串。

### 6.3  参考实现

```
import hashlib, json
SALT = "oknzoiwe@23"

def md5h(s): return hashlib.md5(s.encode()).hexdigest()

def make_sign(path, headers, params):
    keys = sorted(set(headers) | set(params))
    parts = []
    for k in keys:
        if k in headers and headers[k] not in (None, ""):
            v = headers[k]
        elif k in params:
            v = params[k]
        else:
            continue
        if isinstance(v, (list, dict)):
            v = json.dumps(v, separators=(",", ":"), ensure_ascii=False)
        parts.append(f"{k}={v}")
    sign_source = path + "?" + "&".join(parts)
    return md5h(sign_source + SALT), sign_source
```

## 7  请求体加密（AES）

总入口 DioManager.request（0x830c1c）在 encryptDataEnabled 为 true（默认）时执行以下流程。

### 7.1  密钥派生

```
ts   = floor( DateTime.now().millisecondsSinceEpoch / 1000 )     // 秒级  [0x831278]
seed = appSecret + "#@^@#" + ts.toString()
h    = md5Hex(seed)                                              // 32 字符
key  = h.substring(8)                                            // 24 字符 = 24 字节
IV   = key                                                       // IV 与 Key 同值
```

这是典型的「时间派生对称密钥」：服务端以同样的秒级时间窗口重算密钥，因此请求携带的 x-auth-timestamp 可为其提供时间基准。这也意味着：

- 影响 1 密钥每秒变化，重放窗口极短；
- 影响 2 离线解密必须知道该请求对应的秒级时间戳（可从 x-auth-timestamp 取整得到）；
- 影响 3 同一秒内的所有请求共用同一密钥。

### 7.2  报文体构造

```
plaintext = jsonEncode({ "data": params })        // 业务参数原样放入 data
cipher    = AES( plaintext, key, IV, padding = PKCS7 )
body      = base64(cipher).replaceAll(" ", "+")    // aesEncryptReplaceBlank
```

注意 data 中放的是「原始业务参数」，并非加密或编码后的形态；业务参数本身不做二次混淆。

### 7.3  加密组件

| 组件 | 实现 | 位置 |
| --- | --- | --- |
| AES 入口 | EncryptUtils.aesEncrypt（Key 与 IV 均取同参） | 0x8aa334 |
| 空格替换 | aesEncryptReplaceBlank：Base64 后 " " → "+" | 0x8aa298 |
| 分组密码 | pointycastle PaddedBlockCipher，算法名 "AES/<mode>/PKCS7" | 0x839568 |
| 解密 | EncryptUtils.aesDecrypt | 0x8390e8 |
| MD5 | EncryptUtils.encodeMd5（MD5 + HexCodec） | 0x8aa5e0 |

由于 Key 与 IV 使用同一 24 字节字符串，而 CBC/CTR 要求 IV 恰为 16 字节（否则抛 Invalid IV length），可推断实际分组模式极大概率为 ECB。第 10 章提供自动判定方法。

## 8  响应解密

响应同样为密文，解密在 request 的回调分支内完成（aesDecrypt 调用点 0x831b58）。

```
raw  = base64decode( responseBody )      // 注意 '+' 已被替换为空格的字面量处理
json = AES_Decrypt(raw, key, IV)          // 与请求同 key、同模式
data = jsonDecode(json)                   // 部分链路再叠一层 xorCode 还原
```

因此抓包看到的响应体是 Base64 密文，不能直接读字段；必须先完成第 7 章的密钥派生。

## 9  路径混淆与其他变换

### 9.1  encodeApiPath（0x8aaed8）

由 pathConfuseEnabled（默认 true）控制，在 pathConfuseEnabled 为真时替换 URL 路径。

```
b64  = base64( apiPath )                                  // "/home/rec" -> "L2hvbWUvcmVj"
hex  = b64.codeUnits.map( c => c.toRadixString(16) ).join()  // 每字符两位十六进制
path = "/" + randomString() + hex                          // noRandom3 为空时插入随机段
```

逆运算：丢弃首段随机串 → 两两一组还原 codeUnit → Base64 解码 → 原始路径。由于 Base64 字符码位均落在 0x2B–0x7A，十六进制恒为两位，可安全按两位切分。

### 9.2  xorCode（0x8aa6a0）

用于 encryptDataEnabled = false 的轻量分支（0x8aa72c 确认常量值 202030 = 0x3152E）。关键细节：结果写入的是 Uint16Array（0x8aa760 AllocateUint16Array），即按 16 位截断，因此等价于逐字符异或 0x152E。第 15 章已用真实抓包逐字节验证该等价关系。

```
xorCode(s) = String.fromCharCodes( s.codeUnits.map( c => (c ^ 202030) & 0xFFFF ) )
// 因截断，(c ^ 202030) & 0xFFFF  ===  c ^ 0x152E
// 异或自反，故同一函数既可用于加密也可用于解密
```

### 9.3  Base64 变体

encodeBase64（0x8ab1fc）为标准 Base64；传输前由 aesEncryptReplaceBlank 将空格换为 '+'，解密时需先还原为空格或直接按 '+' 处理。

## 10  密钥来源 appSecret

appSecret 是整套方案的唯一外部依赖，其读取逻辑位于 ParamManager.getHlwCustomerAppSecret（0x8aa63c）：

```
secret = ParamModel?.hlwCustomerAppSecret ?? 内置默认值（静态字段，偏移 0x140）
```

| 项 | 说明 |
| --- | --- |
| 字段名 | hlwCustomerAppSecret（ParamModel，JSON Key 同名） |
| 下发接口 | /param/list（应用启动早期拉取） |
| 回退机制 | ParamModel 为空时使用内置默认常量，用于首包加密 |
| 同类字段 | hlwCustomerAppId、hlwCustomerApiHost、jcSecretKey、minioSecretKey |

获取途径（三选一，推荐第 1 种）：

- 1) App 日志：ParamModel 的 toString() 会打印 hlwCustomerAppSecret（模板串见 0x11ea97c），可直接从 logcat 提取。
- 2) 静态提取：反编译 ParamModel 的默认构造/静态初始化，取出该字段的内置常量，可解首包。
- 3) 动态 Hook：按 blutter 给出的 RVA 挂 libapp.so + 0x8aa63c，读取返回值（Dart String 需按其内存布局解析）。

## 11  直播相关接口清单

以下路径均已在二进制中核对到字符串常量；标注「推断」者由函数名与参数结构推定，尚未逐一取到字面量。

| 业务路径 | 函数 | 参数 | 用途 |
| --- | --- | --- | --- |
| /home/rec | requestHomeRecommendList | type | 推荐/热门直播间列表（核心） |
| /country/anchor/list | homeCountryAnchorList | 分页参数 | 按国家筛选主播 |
| /near/anchor/list | homeNearAnchorList | 分页参数 | 附近主播 |
| /home/cls/queryById（推断） | requestHomeClsQueryById | id, pageNo, pageSize=20 | 按分类 ID 查直播间 |
| /home/default/cls/query（推断） | requestHomeDefaultClsQuery | type, pageNo, pageSize=20 | 默认分类列表 |
| /home/user/*（推断） | requestHomeUserAttentionList 等 | 分页参数 | 关注 / 换一批 |
| /game/getLiveGameList | requestLiveGame | 分页参数 | 直播游戏列表 |
| /game/getLiveGameListNew | requestLiveGame | 分页参数 | 直播游戏列表（新版） |
| /room/roomRank | requestGame | roomId 等 | 房间榜 |
| /room/userRank | requestGame | roomId 等 | 用户榜 |

### 11.1  直播间实体 LiveRoomModel 字段

定义见 bean/live_room_model.dart，为 /home/rec 等列表接口的元素类型。

| 字段 | 含义 | 字段 | 含义 |
| --- | --- | --- | --- |
| roomId | 房间 ID | roomCover | 封面图 |
| roomName | 房间标题 | userId | 主播 ID |
| userName | 主播昵称 | currentHot | 当前热度 |
| moneyFen | 付费金额（分） | payType | 收费类型 |
| state | 直播状态 | liveType | 直播类型 |
| pullStreamAddr | 拉流地址 | pullStreamAddr360 | 360P 地址 |
| pullStreamAddr720 | 720P 地址 | streamEncrypted | 流是否加密 |
| gameIcon | 游戏图标 | icon | 图标 |
| inviteCode | 邀请码 | newFlag | 新主播标记 |
| voiceNum | 语音房人数 | microFirstPrice | 麦位首价 |
| vibratingEgg | 振动蛋配置 | icon/roomCover | 展示资源 |

### 11.2  「获取全部直播间」的遍历策略

- 1) /home/rec 的 type 为分类枚举（首页热门 Tab 以多个 type 反复调用），遍历全部 type 即可覆盖各类榜单。
- 2) 分类类接口按 pageNo 自增翻页，pageSize 默认 20，直至返回空列表。
- 3) /country/anchor/list 与 /near/anchor/list 为补充维度，可按国家、地理位置扩展覆盖率。
- 4) 列表结果以 roomId 去重，即为全量直播间集合。

## 12  完整复现脚本

以下脚本覆盖签名、加密、解密三段，依赖 pycryptodome（AES 部分）。

```
import hashlib, json, base64, time
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

SEP, SALT = "#@^@#", "oknzoiwe@23"

def md5h(s):
    return hashlib.md5(s.encode()).hexdigest()

def body_key(app_secret, ts=None):
    ts = int(ts if ts is not None else time.time())
    return md5h(f"{app_secret}{SEP}{ts}")[8:32], ts

def encrypt_body(params, app_secret, mode="ECB"):
    key, ts = body_key(app_secret)
    pt = json.dumps({"data": params}, separators=(",", ":"),
                    ensure_ascii=False).encode()
    if mode == "ECB":
        ct = AES.new(key.encode(), AES.MODE_ECB).encrypt(pad(pt, 16))
    else:
        ct = AES.new(key.encode(), AES.MODE_CBC, key.encode()).encrypt(pad(pt, 16))
    return base64.b64encode(ct).decode().replace(" ", "+"), ts

def decrypt_body(b64, app_secret, ts, mode="ECB"):
    raw = base64.b64decode(b64.replace("+", " "))
    key = md5h(f"{app_secret}{SEP}{int(ts)}")[8:32]
    if mode == "ECB":
        cipher = AES.new(key.encode(), AES.MODE_ECB)
    else:
        cipher = AES.new(key.encode(), AES.MODE_CBC, key.encode())
    return json.loads(unpad(cipher.decrypt(raw), 16).decode())
```

## 13  待确认项与自动定参

| 待确认项 | 现状判断 | 影响 |
| --- | --- | --- |
| AES 分组模式 | Key=IV=24 字节，CBC/CTR 会因 IV 长度非法报错，故极大概率为 ECB | 决定解密 API 调用 |
| 密钥截断长度 | substring(8, 48) 对 32 字符 MD5 会越界，实际应为 substring(8) → 24 字符 | 决定 AES-192 还是 AES-128 |
| 两个分类接口的路径字面量 | 由函数名与参数推定，需取一次常量确认 | 仅影响命名，不影响算法 |

用一条真实密文即可一次性定参（无需猜测）：

```
def identify(cipher_b64, app_secret, ts, expect="data"):
    raw = base64.b64decode(cipher_b64.replace("+", " "))
    base = md5h(f"{app_secret}{SEP}{int(ts)}")
    for ks in (base[8:32], base[8:48], base[:16], base[:24], base[:32]):
        for name, mk in (("ECB", lambda k: AES.new(k, AES.MODE_ECB)),
                         ("CBC", lambda k: AES.new(k, AES.MODE_CBC, k))):
            try:
                pt = unpad(mk(ks.encode()).decrypt(raw), 16)
                if expect in pt.decode("utf-8", "ignore"):
                    print(f"命中 keylen={len(ks)} mode={name} 明文={pt[:120]!r}")
                    return
            except Exception:
                pass
    print("全部候选失败：请核对 appSecret 与时间戳")
```

调用方式：传入任一被截获的请求体（Base64）、对应 x-auth-timestamp 取整后的秒值、以及从 logcat 取得的 appSecret，脚本会直接给出命中的 key 长度与工作模式。

## 14  风险提示与合规边界

- 定位 本文为协议与算法层面的静态分析记录，用于安全评估与互操作性研究。
- 凭证 AES 密钥由服务端下发的 appSecret 派生，属动态凭证；不应将提取到的凭证公开或用于非授权访问。
- 风控 高频遍历 /home/rec 全量 type 会触发风控（时间戳+签名具备防重放与设备绑定特征），实盘调用需遵守服务条款。
- 建议 若需在生产环境验证，建议仅在自有测试账号与低频条件下进行。

## 15  实测抓包验证

以下为对一条真实抓包（版本号 433、terminal=2、域名 ytt.jvjzmc.com）的解密结果。该样本的价值在于：它把纸面推导变成了逐字节可验证的结论。

### 15.1  样本

| 项 | 值 |
| --- | --- |
| 请求 | POST https://ytt.jvjzmc.com/6674c3246776343397361585a6c4c32786c59585a6c556d397662513d3d |
| x-auth-timestamp | 1793017376887（= 1793017376 秒） |
| x-auth-sign | e6117f34644fbeede3c69a0dcdac91b6（32 hex，确认 MD5） |
| 请求体 | ᕕᔌᕂᕇᕘᕋᕧᕊᔌᔔᔌᔜᔞᔜᔘᔟᔞᔜᔘᔟᔖᔞᔙᔞᔘᔞᔞᔞᔟᔘᔌᕓ |
| 请求体长度 | 32 字符 / 96 字节（每字符 3 字节 UTF-8） |
| 响应体 | ᕕᔌᕍᕁᕊᕋᔌᔔᔞᔂᔌᕊᕏᕚᕏᔌᔔᕀᕛᕂᕂᔂᔌᕋᕖᕚᔌᔔᕀᕛᕂᕂᔂᔌᕃᕝᕉᔌᔔᔌᕡᕥᔌᔂᔌᕝᕛᕍᕍᕋᕝᕝᔌᔔᕚᕜᕛᕋᕓ |
| 响应体长度 | 59 字符 / 177 字节 |

### 15.2  解密结果

请求体与响应体均为 xorCode 输出，逐字符异或 0x152E 后得到：

```
请求体明文:
{"liveId":"2026102618070600016"}

响应体明文:
{"code":0,"data":null,"ext":null,"msg":"OK","success":true}
```

交叉验证：明文中的 2026102618070600016 与同一抓包中 WebSocket 帧的 {"type":"1002","toLiveId":"2026102618070600016"} 完全一致，说明这是一次「进入直播间」的配套 HTTP 调用，data 为 null 属正常（服务端只回报成功）。

### 15.3  xorCode 的 16 位截断（已验证）

此前从反编译推断的常量 202030（0x3152E）与实测不符，原因不是推断错误，而是漏掉了截断环节：blutter 输出显示结果交由 AllocateUint16Array 组装，即写入前按 16 位截断。验证如下：

```
(c ^ 202030) & 0xFFFF   ==   c ^ 0x152E          # 对任意 ASCII 成立
# 二者生成的字符串与抓包内容逐字符一致（已用脚本比对 True）
```

因此：文档中出现 0x3152E 与 0x152E 两种写法并不矛盾，前者是源码常量，后者是等效运算。实现时直接使用 XOR 0x152E 即可。

### 15.4  该样本暴露的两个分支事实

- 事实一 本条请求体是 xorCode 而非 Base64 密文，说明该接口走的是 encryptDataEnabled = false 分支。即第 7 章（AES）与第 9.2 章（xorCode）是并存的两条分支，由调用方按接口指定。
- 事实二 同一抓包中 WebSocket 帧为 Base64 且长度均为 16 的倍数（64 / 160 字节），与第 7 章 AES 结论一致，故实时消息通道使用 AES，需 appSecret 才能解。

### 15.5  仍未闭合的部分

| 项 | 状态 | 结论 |
| --- | --- | --- |
| 请求体 / 响应体 | 已破解 | xorCode ^ 0x152E，可双向转换 |
| WebSocket 帧 | 算法已定，缺密钥 | Base64 + AES，需 appSecret 与秒级时间戳 |
| URL 路径 token | 未破解 | 长度 59（奇数），不符合 hex(base64(路径)) 应有的偶数长度；既非 UTF-8 解码也非常见单字节异或。倾向判断为该构建更换了路径混淆方案 |
| x-auth-sign | 未复现 | 已对「全部请求头 + 业务参数」的所有子集（2047 种组合）× 多种分隔与盐值位置做穷举，均不匹配，说明路径确实是签名源串的一部分，而路径未知 |

这两个未闭合项互相绑定：路径既参与签名，又是 URL 的组成部分。破解路径即可同时拿下签名。两条可行路径：

- 路线一 取得与版本 433 对应的 libapp.so，直接读该版本的 encodeApiPath 与签名实现；
- 路线二 对同一接口连抓两次：若 token 相同则为确定性映射（可用已知路径反推），若每次都变则需运行时 Hook 观察入参出参。

### 15.6  对「能否解密」的结论

能。就数据可读性而言，抓包已完全可解：请求体、响应体用一行 XOR 即可还原明文，WebSocket 需补一个 appSecret（可从 logcat 的 ParamModel.toString() 取）。未闭合的路径 token 与签名只影响「自己伪造请求」，不影响「读懂已发生的通信」。

## 16  4.3.3 线上版本最终结论（已实测打通）

4.3.3 是实际在线的版本，符号名被混淆，但字符串字面量与算法结构不变。借助抓包样本与线上验证，三要素（路径、签名、报文体）已全部还原，第 13 章与 15.5 章列出的待定项至此全部闭合。

### 16.1  路径混淆的真实算法（此前卡点的根因）

```
编码: token = "/" + 随机前缀(长度不定) + hex( base64(apiPath) )
      其中 hex 为逐字符转小写十六进制（每个字符两位）

解码: 因前缀长度不定，必须扫描偏移量，
      找到「从该偏移起每两位可还原为 base64 字符、且解码结果以 / 开头」的位置
```

关键点：随机前缀长度并不固定（两个样本均为 3 个字符），因此任何按偶数位固定对齐的解析都会失败——这正是此前用哈希/加密方向穷举却始终无解的原因。

| 样本 | token 长度 | 随机前缀 | 还原出的真实路径 |
| --- | --- | --- | --- |
| 抓包一 | 59 | 667 | /app/live/leaveRoom |
| 抓包二（request.txt） | 83 | 121 | /firstTopUp/firstTopUpEntrance |

验证强度：用前缀 "121" 正向编码 /firstTopUp/firstTopUpEntrance，生成的 token 与抓包逐字符完全一致。

### 16.2  签名最终公式

```
参与签名的键 = ak, authorization, channel, terminal, version,
              x-auth-timestamp, + 全部业务参数（body 里的键值）

signSource  = path + "?" + 按字典序拼接的 "k=v" 串（用 & 连接）
x-auth-sign = md5( signSource + "oknzoiwe@23" )
```

与 6.24 版的差异（也是此前对不上的原因）：

- 差异一 不包含 Content-Language。
- 差异二 不包含 deviceUid（该头仍会发送，但不参与签名）。
- 差异三 业务参数参与签名，body 为空时不追加。已用两个样本交叉验证：leaveRoom 只有含 liveId 才命中，firstTopUp 空 body 直接命中。

### 16.3  报文体与响应体

实测所有探测接口（/param/list、/home/rec、/home/cls/queryById 等）均使用 xorCode 分支，而非 AES。即：

```
body     = xorCode( jsonEncode(params) )     # params 即业务参数对象本身
response = xorCode( 服务端返回的字符串 )       # 逐字符异或 0x152E

与第 7 章 AES 分支的区别：没有 {"data": {...}} 包装，也没有 Base64。
```

### 16.4  实测数据

/param/list 一次性下发了全部服务端配置，含密钥类字段：

| 字段 | 值 | 用途 |
| --- | --- | --- |
| hlwCustomerAppSecret | 6b6931fdcf3482246b15f6689cccbdce | AES 派生密钥的种子 |
| jcSecretKey | 3f902e98956f9ea4e8e7a13cda8202b5 | 业务签名密钥 |
| facebookAppSecret | 63ee6e2432dcaaee1b73ec536e842712 | 第三方登录 |
| hlwCustomerAppId | 91xl001 | 客服系统 AppId |
| agoraAppId | e9f75671e2db4c5e852633c048b38f4d | 声网 RTC |
| zegoAppId | 1791728032 | 即构 RTC |
| hlwCustomerApiHost | https://api.haoghe.xyz | 客服接口域名 |
| jcWebSocketHost | wss://tinode.jjzb2.live | 长连接 |

接口探测结果：

| 接口 | 参数 | 结果 |
| --- | --- | --- |
| /param/list | {} | 200，3922 字节，返回全量配置 |
| /home/rec | {type: 1..60} | 200，每 type 固定 10 条，去重后 27 个直播间 |
| /home/cls/queryById | {id:1, pageNo:1, pageSize:20} | 200，data 为空数组 |
| /country/anchor/list | {pageNo, pageSize} | 200，空列表（需定位权限） |
| /near/anchor/list | {pageNo, pageSize} | 200，空列表（需定位权限） |
| /home/default/cls/query | {type:1} | 参数有误，需另配参数 |

/home/rec 不支持分页（pageNo=2 起返回空），每个 type 固定返回 10 条，type 1–60 共享同一房间池，去重后共 27 个在线直播间，全部含 pullStreamAddr 拉流地址。完整数据已导出为 rooms_433.json。样例：

| roomId | 主播 | 热度 | 拉流地址 |
| --- | --- | --- | --- |
| 2026102619325000055 | 珀耳塞福涅 | 2801 | https://pl.ycycts.com/h001/6718137423019 |
| 2026102620574300036 | 兔宝 | 3500 | https://pl.ycycts.com/h001/7294079970682 |
| 2026102620552900033 | 温霜降 | 1455 | https://pl.ycycts.com/h001/8170997831696 |
| 2026102618070600016 | 六月 | 421 | https://pl.ycycts.com/h001/8177419286816 |

### 16.5  最终结论

- 算法层面 路径混淆、签名、报文体三要素全部还原，并用真实抓包双向验证。
- 实现层面 用 Python 客户端独立构造请求，两个域名均返回 200 与真实业务数据。
- 健壮性 随机前缀可任意填写（实测 d3a / 82c 均被接受），说明其仅作噪声。
- 业务层面 /home/rec 可稳定枚举全部在线直播间（27 个）及其拉流地址。

完整可运行客户端见 jj_client.py，抓包解密工具见 jj_decrypt.py。

## 17  全量接口清单与房间枚举修正

前一版认为 /home/rec 就是房间列表的全部来源，只能拿到 27 个房间。从 4.3.3 的 pp.txt（Dart 对象池转储）中提取出全部 603 个接口路径后，找到了真正支持分页的主力接口，房间数提升到 116 个。

### 17.1  从 pp.txt 提取全量接口

4.3.3 的 blutter 产物是扁平化 + 文件名混淆的（1330 个 aAo.dart / Aap.dart 之类的文件，没有包路径结构），但对象池转储 pp.txt 中的字符串常量未参与混淆，格式为 [pp+0x2fa40] String: "/home/rec"，可完整枚举 App 的全部 API。

```
提取规则：
  - 匹配 [pp+0x<hex>] String: "<内容>"
  - 内容以 / 开头、无空格、仅含路径字符
  - 排除静态资源后缀(.png/.jpg/.apk/.html/...)

结果：603 个接口路径
```

分布最密的分组：

| 前缀分组 | 数量 | 说明 |
| --- | --- | --- |
| /app/live | 18 | 开播、下播、连麦、日志 |
| /game/live | 16 | 游戏直播与投注 |
| /app/anchor | 15 | 主播端设置、欲望单、审核 |
| /user/anchor | 13 | 主播资料与分成 |
| /app/agent | 8 | 代理/推广体系 |
| /voiceChat/* | 17 | 语音房连麦全流程 |
| /treasure/* | 10 | 宝箱抽奖 |

完整清单见 api_endpoints.md（分组版）与 api_endpoints.txt（纯列表），提取脚本为 extract_endpoints.py。

### 17.2  主力房间接口修正

关键修正：/home/default/cls/query 之前被判为「参数有误」，实际参数是 {type, pageNo, pageSize}，只是 pageSize 不能超过 20（填 50 会静默返回空）。

| 接口 | 分页 | 单次上限 | 单 type 总量 | 去重后 |
| --- | --- | --- | --- | --- |
| /home/rec | 不支持 | 10 | 固定 10 条 | 27（旧结论） |
| /home/default/cls/query | 支持 | 20 | 最高 115 | 116（新结论） |

两者关系：/home/rec 返回的是 cls/query 的子集，兜底扫描新增 0 个，因此可直接以 cls/query 为唯一数据源。

各 type 的服务端总数（pageSize=20 实测）：

| type | 1 | 2 | 4 | 5 | 6 | 10 | 11~40 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| count | 115 | 115 | 14 | 12 | 111 | 61 | 114~115 |

type=4 / 5 是差异化频道（14 / 12 个），type 11 以上内容高度重合，说明服务端按推荐池返回而非严格按分类切分。

### 17.3  最终枚举结果

- 数量 116 个在线直播间（旧结论 27 个，提升 4.3 倍）。
- 可用性 114 个带 pullStreamAddr，2 个新建房间尚未下发流地址。
- 完整度 每个房间含 36 个字段，含原画/720P/360P 三档流地址。
- 方法 以 /home/default/cls/query 遍历 type 1..40 + 每页 20 翻页。

热度 Top6：

| 主播 | 热度 | 主播 | 热度 |
| --- | --- | --- | --- |
| 珀耳塞福涅 | 3185 | 野山茶 | 1258 |
| 宇儿 | 1579 | 小七七 | 1242 |
| 田澄澄 | 1401 | 高中生活 | 1327 |

### 17.4  工具链更新

| 文件 | 作用 |
| --- | --- |
| extract_endpoints.py | 从 pp.txt 提取全量接口路径 |
| jj_rooms.py | 房间枚举（cls/query 分页 + rec 兜底，支持 --types/--page-size） |
| build_web.py | 生成房间列表网页（内嵌数据，potplayer:// 唤起播放） |
| docx_to_md.py | docx 转 markdown（保留标题/表格/代码块） |
| 刷新并打开.bat | 一键刷新数据并打开网页 |

### 17.5  类别体系与最终枚举

类别名来自 /home/recommend/cls（由 HomeClsManager._requestHomeRecommendCls 调用，地址 0xbc0894），返回 8 个分类，其 fixedType 字段即 /home/default/cls/query 的 type 参数。

| 类别 | fixedType | 房间数 | 固定 | 热门 |
| --- | --- | --- | --- | --- |
| 关注 | 1 | 116 | 是 | 否 |
| 热门 | 2 | 109 | 是 | 否 |
| 颜值 | 0 | 115 | 否 | 否 |
| 新人 | 4 | 12 | 是 | 否 |
| 收费 | 5 | 11 | 是 | 否 |
| 游戏 | 6 | 111 | 否 | 是 |
| 帖子 | 9 | 116 | 是 | 否 |
| 1V1表演 | 10 | 66 | 是 | 否 |

注意 fixedType 与 clsName 的 id 并不一致（如「颜值」id=3 但 fixedType=0），调用接口时须用 fixedType。

按类别遍历 + 分页后的最终结果：118 个直播间，其中 117 个来自 cls/query，1 个由 /home/rec 补充（未归入任何类别）。

另外从 /domain/list 拿到了此前缺失的资源域名表：

| 字段 | 域名 | 用途 |
| --- | --- | --- |
| hkCoverCdnDomain | https://orh.ysqcbxw.com | 封面 CDN（港澳） |
| usaCoverCdnDomain | https://orh.ysqcbxw.com | 封面 CDN（美区） |
| imgEncryDomain | https://rgf.rucqnij.xyz | 加密图片（实测可响应） |
| hkVideoDomain | https://hk.yfhx.live | 视频流 |
| usqVideoDomain | https://zb.yfhx.live | 视频流 |
| webSocketDomain | wss://yzj.opuxrcm.com/live | 长连接 |
| m3u8InterfaceDomain | https://hjm.aynjxxw.com | HLS 接口 |
| pushStreamDomain | https://push.gzboan.com | 推流 |

## 附录 A  关键函数地址索引

| RVA | 函数 / 符号 | 作用 |
| --- | --- | --- |
| 0x830c1c | DioManager.request | 请求总入口（加密/签名/发送） |
| 0x8d1354 | DioManager.requestList | 列表型请求封装 |
| 0x8a4afc | DioManagerHeader.getAuthorizationHeader | 构造鉴权头 |
| 0x8a4f28 | buildJJAuthorizationSignSource | 生成签名源串 |
| 0x8a4ec0 | EncryptUtils.encodeMd5ForJJ | 加盐 MD5 签名 |
| 0x8aa5e0 | EncryptUtils.encodeMd5 | MD5 → Hex |
| 0x8aa334 | EncryptUtils.aesEncrypt | AES 加密（Key/IV 同源） |
| 0x8aa298 | EncryptUtils.aesEncryptReplaceBlank | AES 加密 + 空格替换 |
| 0x8390e8 | EncryptUtils.aesDecrypt | AES 解密 |
| 0x8aaed8 | EncryptUtils.encodeApiPath | 路径混淆 |
| 0x8ab1fc | EncryptUtils.encodeBase64 | Base64 编码 |
| 0x8aa6a0 | EncryptUtils.xorCode | XOR 编码（轻量分支） |
| 0x8ab0ac | EncryptUtils._generateRandomString | 路径随机段 |
| 0x8aa63c | ParamManager.getHlwCustomerAppSecret | 读取 appSecret |
| 0x8aab6c | DomainManager.getDomain | 取当前域名 |
| 0x8345b4 | DomainManager.nextDomain | 域名轮换 |
| 0x839568 | encrypt.AES 构造 | PaddedBlockCipher 组装 |
| 0xa6fbe8 | requestHomeRecommendList | 推荐直播间列表 |
| 0xd99fd4 | homeCountryAnchorList | 国家主播列表 |
| 0x11f5bc4 | homeNearAnchorList | 附近主播列表 |
| 0xa69688 | requestHomeClsQueryById | 分类查询 |
| 0xa6a4fc | requestHomeDefaultClsQuery | 默认分类查询 |

## 附录 B  硬编码常量

| 常量 | 值 | 出现位置 |
| --- | --- | --- |
| 签名盐值 | oknzoiwe@23 | 0x1699c1 |
| AES 密钥分隔符 | #@^@# | 0x102687 |
| AppKey（ak 头） | jyzb | 0xe728 |
| terminal 头 | 4 | 0x8a4b60 |
| channel 头 | 1 | — |
| XOR 常量 | 202030（0x3152E） | 0x8aa72c |
| 接口路径 | /home/rec | 0xa6fc9c |
| 接口路径 | /country/anchor/list | 0xd9a058 |
| 接口路径 | /near/anchor/list | 0x11f5c48 |
| Content-Type | application/json | 0x9260 |

## 附录 C  请求样例（结构示意）

```
POST https://<动态域名>/<随机串><hex(base64("/home/rec"))> HTTP/1.1
Content-Type: application/json
terminal: 4
version: 6.24.0
ak: jyzb
channel: 1
authorization: <token>
x-auth-timestamp: 1761xxxxxxx123
deviceUid: <device-id>
Content-Language: zh-cn
x-auth-sign: md5("/home/rec?ak=jyzb&authorization=...&channel=1&...&type=1" + "oknzoiwe@23")

<base64(AES({"data":{"type":1}}, key=md5(appSecret+"#@^@#"+ts)[8:32]))>
```

说明：以上为结构示意，header 顺序不影响签名（签名为字典序），但参与签名的键集合必须与实现一致。
