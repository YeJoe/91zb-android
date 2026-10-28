# MuMu 模拟器抓 BASE / TOKEN 实战指南（天美系 App）

> 目标：拿到 `webui/config.json` 的 `base`（接口域名）和 `token`（JWT）。
> MuMu 自带 root，比真机省一大步。

## 0. 采集源选择（重要）

| App | 包名 | 版本 | 可否用 |
|---|---|---|---|
| 天美旧版（本仓库逆向对象） | `com.ufnhzkflua.eyybasftmi` | 8.1.7 | 闪退，装不上 |
| 天美新版 | — | 8.3.x | 闪退，装不上 |
| 同源**不同渠道**包（采集源） | `com.l95c2450d7.f68bb4e2d5` | 8.5.3 | **能跑、已登录（渠道 `ecy`）** |

> **更正**：`com.l95c2450d7.f68bb4e2d5` **不是天美的同款套壳**，而是同一套代码基线的**另一个渠道包**
> （`MAINUSER_DATA` 里 `app_name`/`channel_id` 均为它自己）。因此：
> - **BASE**：可以从它身上拿，但那是 `ecy` 渠道解析出来的 host，必须用天美的包名/渠道头实测确认（服务端按
>   `appId/channel/version` 头分租户）。
> - **TOKEN**：**不能移植**，它属于套壳账号。天美的 token 走 `/login/quick` 游客登录换，不用短信。
>
> 完整方案见 **[`BASE与TOKEN获取方案.md`](./BASE与TOKEN获取方案.md)**（含 `/proc/net/tcp` 证书反查、
> WebView `getDunUrl` 桥、盾配置离线重放、闪退定位）。

注意版本差异：`version` 头用 **8.5.3**；SALT 常量、Frida 偏移可能不同（见 §5）。

## 1. 连接模拟器

```bat
adb connect 127.0.0.1:7555
adb shell su -c id        :: 确认 root
```
连不上换 `16384 / 16416 / 16448`（多开步长 32），或 `MuMuManager.exe adb -v 0` 查。

## 2. 先拿设备号和用户信息（shared_prefs，10 秒）

```bat
adb shell su -c "cat /data/data/com.l95c2450d7.f68bb4e2d5/shared_prefs/FlutterSharedPreferences.xml" > prefs.xml
```

实测（套壳版 v8.5.3）关键内容：

| 键 | 值 | 用途 |
|---|---|---|
| `flutter.deviceUUID` | `29e9eb7c-b442-350b-b325-3427cd26f85b` | **deviceId 请求头**（= apns_code） |
| `flutter.main_user_id` | `423344474` | 用户 ID |
| `flutter.MAINUSER_DATA` | 整串 JSON | 内含 `"token"`（**JWE 加密串**，见下）、`nickname`、`channel_id` 等 |

`MAINUSER_DATA.token` 形如：
```
<JWE>
```
这是 `alg=dir, enc=A128CBC-HS256` 的 **JWE**（App 对敏感字段做了加密存储，
`mobile`/`token` 都是密文）。**不一定等于请求头里的明文 token**，
先试着填进 `config.json` 的 `token`，请求 401/解密失败再按 §3 拿明文。

## 3. 拿 BASE + 明文 TOKEN（logcat，最省事）

```bat
adb logcat -c
adb logcat | findstr /i "initMainUser token 正在连接服务器 当前版本 过盾 host domain"
```
- `LoginMgr.initMainUser token: <...>` → **明文 TOKEN**
- `正在连接服务器[域名:端口]` / `当前版本：` → **BASE 线索**

## 4. BASE 为什么不在 prefs 里（架构说明）

配置里所有 URL 都是**占位符**，运行时才被原生层改写：

```
rank_url     = http://kiwi_hd
im_url       = http://kiwi_im/tunnel/open?z=1
game_url     = http://kiwi_yx
cdn_bobi_url = http://kiwi_bobi
file_rz_url  = http://kiwi_rz
kedan_url    = http://kiwi_kd
pp_admin_url = http://10.6.12.202:11888     (对内后台, 非 API)
```

`kiwi_*` → 真实域名 的映射（`kiwi_starts`）由 `_ApiInfoManager` 动态下发，
**所以真实 BASE 只能运行时拿**。三个来源：

1. **logcat**（§3，最快）
2. **tcpdump/Wireshark**：抓模拟器发出的 DNS 查询 + TCP 连接目标 IP
   （改写后的真实域名就在这里），过滤器 `dns` / `tcp.flags.syn==1`
3. **Frida**：直接 dump 请求 URL（§5）

## 5. Frida（要明文/密文比对、或 logcat 拿不到时）

套壳版是 8.5.3，**本仓库 `tools/frida_hook_crypto.js` 里的偏移是 8.1.7 的，不能直接用**。
两条路：

**A. 重新 blutter 套壳版 APK，更新偏移（最稳）**
```bat
blutter com.l95c2450d7.f68bb4e2d5-8.5.3.apk out853
:: 在 out853/asm 里找同款函数, 记新地址:
::   HttpTunnelLive4::updateSocketParams   (URL + token)
::   RabbitCipher::encryptDecrypt          (明文/密文)
::   HttpRequest::encryptApiPath           (原始路径)
:: 然后把 tools/frida_hook_crypto.js 里 0x1923e98 / 0x111c2c4 / 0x111d870 换成新地址
```
**B. 只 blutter 拿 SALT**（如果请求报解密错）：
在 `out853/pp.txt` 里搜 initCipher 附近的字符串常量（8.1.7 是
`jQBUO&2pfS5sC」¥\?AtglXv*w+PcRK8%i`），不同就填：
```bat
set KEY_SALT=<新SALT>                :: Python 客户端
:: 或 webui/config.json 的 "keySalt"
```

MuMu 12 是 x86_64：
```bat
adb push frida-server /data/local/tmp/
adb shell su -c "chmod 755 /data/local/tmp/frida-server"
adb shell su -c "/data/local/tmp/frida-server &"
frida -U -f com.l95c2450d7.f68bb4e2d5 -l tools\frida_hook_crypto.js --no-pause
```

## 6. 拿到之后

```bat
set BASE=https://<日志里的真实域名>
set TOKEN=<明文token>
set DEVICE_ID=29e9eb7c-b442-350b-b325-3427cd26f85b
set VERSION=8.5.3
python tools\live_client.py rooms
```
或编辑 `webui\config.json`（`base`/`token`/`deviceId`/`version`/`keySalt`）后双击 `start.bat`。

## 7. 排障

| 现象 | 处理 |
|---|---|
| `adb connect` 失败 | 换端口 / `MuMuManager.exe adb -v 0` |
| logcat 没有 token 行 | 套壳版日志级别变了 → 走 §5 Frida，或先试 prefs 里的 JWE |
| 请求 401 / `code!=0` | token 过期或拿错了（JWE≠明文）→ 重抓 logcat |
| 响应解密出来是乱码 | **SALT 或版本不匹配** → §5-B 重新取 SALT；确认 `version` 头 |
| 404 / 路径错 | `pathPrefix` 不对 → 抓包看真实前缀，改 `config.json` |
| 403 过盾失败 | 缺 `sign`/盾 token → 需要完整抓一次 headers |
| frida hook 没输出 | 偏移是 8.1.7 的 → §5-A 重新 blutter 套壳版 |
