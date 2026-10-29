# C 方案：把 kiwi 隧道搬进我们自己的进程

> 2026-10-29 实测 + 实现。结论：**不需要复刻协议，也不需要源 App 常驻** ——
> 把源 App 的 `libkiwi.so` 和 `com.kiwi.sdk.*` 打进自己的 APK，反射调它的 Java SDK 就行。

## 0. 一句话结论

`libkiwi.so` 提供的是**能力**，不是**状态**。它需要的东西只有三样：
一个 `Context`、一个 `appKey`、一次网络握手。这三样我们自己都能给。
之前"必须源 App 常驻"只是因为 SDK 在那 17 个 `127.0.0.1` 端口后面，而端口是进程内的。

## 1. 静态分析走过的弯路（留档，别重复踩）

`libkiwi.so`（6.8 MB，arm64）是加固库：

- `DT_STRTAB` 指向的 `.dynstr` 内容是密文，静态 `dlsym` 拿不到任何导出名；
- 文件里没有可读的 `Java_*` / `JNI_OnLoad` 符号；
- 运行时第一段映射（offset 0 起 `0x4F1000`）与文件逐页不同 —— 加载时解密，ELF 头被抹成 `7f b2 b3 0f`。

**绕过方式**：模拟器有 root（KernelSU），按 `/proc/<pid>/maps` 用 `dd` 读 `/proc/<pid>/mem`：

```
400024a00000-400024f3b000 r--p 00000000  lib/arm64/libkiwi.so   ← 代码段（已解密）
400024fb3000-4000250f4000 rw-p 0054a000  lib/arm64/libkiwi.so   ← dynsym + dynstr 在这里
```

拿到 rw 段后 `0x54b8b0`（DT_SYMTAB）到 `0x596348`（DT_STRTAB）全是明文，3923 个符号可读。
关键几条：`JNI_OnLoad`@`0x22042c`、`kiwiapi_server_to_local`、`kiwiapi_init_cache`、
`kiwiapi_init_device_info`、`KiwiAppkey::ParseAppkey`、`KiwiProxy::TcpProxy`。

**但最后发现这些都用不上** —— 因为 Java 层的封装已经把该做的都做了。

## 2. Java 侧 API（`classes3.dex` 反编译结果）

包 `com.kiwi.sdk`，一共 6 个类，全是简单包装：

```java
public class Kiwi {
    static String  init_appkey;          // 当前 appKey（成功后会被清空）
    static int     init_code;            // 缓存的返回码
    static boolean monitor_network_start;

    static { System.loadLibrary("kiwi"); }

    public static int Init(String appkey);                                   // 同步
    public static int InitWithListener(String appkey, KiwiListener l);        // 起线程
    public static int ServerToLocal(String group, StringBuffer ip, StringBuffer port);
    public static int ForceRestartServer(int);
    public static void RestartAllServer();
    public static void MonitorNetworkState();
    public static int SimpleDns(String, int);
    public static int SendLog(int, String);

    static native int    init(String appkey);
    static native int    init_cache(String path);
    static native int    init_appname(String name);
    static native int    init_device_info(String json);
    static native String server_to_local(String group);   // 返回 "code|ip|port"
}

public interface KiwiListener { void onKiwiInit(int code); }

public class ContextHelp {
    private static Context CONTEXT_INSTANCE;
    public static Context getContext();   // 反射 ActivityThread.currentActivityThread().getApplication()
}

public class DeviceInfo { public String GetInfo(); }   // 一堆 Build / Telephony 字段
```

### `Init` 的完整逻辑（反汇编还原）

```java
public static int Init(String appkey) {
    if (init_code == 0 && init_appkey == null) return 0;   // 已初始化过
    Log.d("kiwitest", "Kiwi init ");
    Context ctx = ContextHelp.getContext();
    if (ctx != null) {
        File cache = ctx.getCacheDir();
        init_cache(cache != null ? cache.toString() : null);
        String appName = ctx.getPackageManager().getApplicationInfo(pkg, 0)
                            .loadLabel(pm).toString().replace(' ', '_').replace('#', '_');
        init_appname(appName + "##" + ctx.getPackageName());
    } else {
        init_cache(Environment.getExternalStorageDirectory().toString());
        init_appname("DefaultAppNameNoContext" + "##" + "DefaultPkgNameNoContext");
    }
    init_device_info(new DeviceInfo().GetInfo());
    init_code = init(appkey);
    if (init_code == 0) init_appkey = null;
    return init_code;
}
```

抓到的日志完全对得上：

```
D kiwitest: Kiwi init
I KiwiSDK : kiwi init with version A521
D kiwitest: Kiwi init return 0
```

### `ServerToLocal` 的完整逻辑

```java
public static int ServerToLocal(String group, StringBuffer ip, StringBuffer port) {
    String[] a = server_to_local(group).split("\\|");   // 必须是 3 段
    if (a.length != 3) return -12;
    ip.setLength(0);   ip.append(a[1]);
    port.setLength(0); port.append(a[2]);
    return Integer.parseInt(a[0]);
}
```

也就是 native 返回 `"0|127.0.0.1|41305"`，`0` 是返回码。

### 谁调它（Flutter 侧）

`classes3.dex` 里有个 MethodChannel handler（混淆名 `g0.a`），注册 channel `flutter_yun_ceng_kiwi`：

```java
case "initEx":                 // arguments = {"appKey": "..."}
    Kiwi.Init((String) args.get("appKey"));
    break;
case "getProxyTcpByDomain":    // arguments = {token, group_name, ddomain, dport}
    StringBuffer ip = new StringBuffer(), port = new StringBuffer();
    int code = Kiwi.ServerToLocal(group_name, ip, port);
    result.success({target_ip, target_port, code});
    break;
```

注意 `token` / `ddomain` / `dport` 三个参数**取了但没用**（R8 优化后的残留），
`ServerToLocal` 只需要 `group_name`。

## 3. appKey：从源 App 运行内存里捞出来的真值

### 3.1 走过的弯路

最早是 `blutter` 从 `libapp.so` 对象池 dump 出的 `main.dart` 常量区，几条紧挨着：

```
[pp+0x2830] String: "kiwi_"
[pp+0x2838] String: "dNLmP4x5bcvPKZess6gqv6MzS+2bg2tW…"        ← 长 Base64
[pp+0x2840] String: "cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4"       ← 32 位，当时最像 appKey
[pp+0x2848] String: "http://kiwi_live/tunnel/a033b072?z=1&nobase64=1"
```

按这些候选逐个试，`Init` 一直返回 **-2**，日志 `[appkey]parse exception unexpected token`。
用 IDA 反编译 `KiwiAppkey::ParseAppkey`（`0x243f20`）后才知道为什么 —— **它要的根本不是这种串**。

### 3.2 ParseAppkey 要的结构（IDA 反编译还原）

```c
// sub_243F20(appkey_obj *this, const std::string &text)
json j = json::parse(text);                       // 失败 -> 打 "[appkey]parse exception %s"，返回 -2
if (type_of(j["app_id"]) == 6 || type_of(j["content"]) == 6) return -2;   // 6 = 缺失
if (type_of(j["app_id"]) != 0) err("json value must be integer");         // 0 = number
this->app_id = (uint32) j["app_id"];
if (j.has("port_key")) {
    if (type_of(j["port_key"]) != 0) err("json value must be integer");
    this->port_key = (int) j["port_key"];
}
if (type_of(j["content"]) != 3) err("json value must be array");          // 3 = array
for (auto &e : j["content"]) {                                            // 每项 {t, v} 且都是字符串
    if (type_of(e["t"]) != 2 || type_of(e["v"]) != 2) err("json value must be string");
    this->entries.push_back({e["t"], e["v"]});                            // a1+40 的 vector<pair<string,string>>
}
return 0;   // 成功：*(byte*)this = 1
```

也就是说入参是**明文 JSON**：

```json
{"app_id": 53, "port_key": <int 可选>, "content": [{"t": "…", "v": "…"}]}
```

之前那句 `{"app_id":53,"port_key":"AAAA"}` 有两个错：`port_key` 是字符串（要求整数）、`content` 整个缺失。

### 3.3 最终怎么拿到的

`ParseAppkey` 收到的既然是明文 JSON，那源 App 内存里必然有它。
源 App 冷启动后 `libkiwi.so` 已经加载，模拟器有 root（KernelSU），于是：

```bash
# 1. 找段：scudo / dalvik 堆才是数据所在
su -c 'cat /proc/<pid>/maps'
# 2. 逐段流式 dump，边读边搜（不落盘，1.3 GB 也只要几分钟）
adb exec-out su -c 'dd if=/proc/<pid>/mem bs=4096 skip=<start/4096> count=<len/4096>' \
  | <本地搜 "appKey" / "initEx" / "port_key">
```

命中点很干净 —— Dalvik 主堆里 `getPlatformVersion` / `initEx` / `appKey` / `Kiwi init ` / `kiwitest`
这几个 MethodChannel 常量**紧挨着**，就在它们前面一段是：

```
0x21aff80  "360wjdqlAsjhpkqV7pq3Mzj6mfrZdxM4yt39ASqwjWgc3Eq4gjbRp4/qzeH3mlgzQ…"  （1432 字符）
0x21b0527  "RgetPlatformVersion"
0x21b0550  "initEx"
0x21b0568  "appKey"
```

这串 base64 就是 `initEx` 的 `map["appKey"]`。解码后是 1072 字节二进制（AES 密文，
不是 JSON）—— 说明 **`Init` 内部先解密再交给 `ParseAppkey`**，所以派生结构猜不出来，
只能拿真值。

实测：`Kiwi.Init(<这串>)` → **返回 0**。

```
D kiwitest: Kiwi init
I KiwiSDK : kiwi init with version A521
D kiwitest: Kiwi init return 0
```

> 注意 appKey 是**按 app_id 绑定的密文**，不是通用口令；换 app 就换 key。
> 它现在已经写进 `Prefs.Defaults.KIWI_APP_KEY`，设置页也能直接改。

## 4. 落地实现

```
我们的 App
 ├─ assets/libkiwi-arm64.so            ← 从源 APK 提出来，运行时解压到 filesDir/kiwi-lib/
 ├─ assets/kiwi-sdk.jar (2.2MB)        ← classes3.dex 打包，含 com.kiwi.sdk.* 及其依赖
 ├─ KiwiSdk.ensureLoaded()
 │    ├─ System.load(filesDir/kiwi-lib/libkiwi.so)
 │    ├─ DexClassLoader(jar, optDir, filesDir/kiwi-lib, ...)   ← librarySearchPath 必须指对
 │    └─ 反射写 ContextHelp.CONTEXT_INSTANCE = 我们的 Application
 ├─ KiwiSdk.init(appKey)               → 0 = 握手成功
 ├─ KiwiSdk.serverToLocal("kiwi_live") → host + port
 └─ LiveRepo.preferPort = port         → 之后所有请求直接打 127.0.0.1:port
```

三个必须踩准的点：

1. **`System.loadLibrary` 在 DexClassLoader 的类里调用会失败**，除非 `DexClassLoader` 的
   第三个参数（librarySearchPath）指向 so 所在目录 —— `Kiwi.<clinit>` 里那句
   `System.loadLibrary("kiwi")` 就靠它找库。
2. **`Init` 是同步的**，会做网络握手（几百毫秒到几秒），必须放 IO 线程。
3. **so 放 assets 而不是 jniLibs**：放 jniLibs 会让 APK 声明自己只有 arm64-v8a，
   x86_64 模拟器直接 `INSTALL_FAILED_NO_MATCHING_ABIS`。放 assets + 运行时解压，
   APK 不含 native 代码，任何设备都能装；真机走隧道，模拟器自动降级。

## 5. 限制（必须知道）

| 场景 | 结果 |
|---|---|
| arm64 真机 | 可用（这是目标场景） |
| MuMu 等 x86_64 模拟器 | `System.load` 加载 arm64 so 失败 → 自动降级为「借源 App 端口」，**内置隧道不可用** |
| 开了 ARM 兼容/转译的模拟器 | 有可能可用，取决于转译层是否允许 dlopen arm64 |
| 源 App 已卸载 | 不影响（so 和 dex 都在我们自己包里） |

## 6. 还没验证的两件事

1. ~~**appKey 是否就是 `cZ1dAC6…`**~~ —— 已解决：真值是那段 1432 字符 base64，
   `Init` 实测返回 0。见 §3。
2. **SDK 是否校验调用方包名/签名** —— `Init` 返回 0 说明 **init 阶段不校验包名**
   （我们传的是 `豆彩直播##com.duocai.live`，源 App 是 `天美##com.l95c2450d7.f68bb4e2d5`）。
   但 `ServerToLocal` 阶段是否按 appname 下发节点还没验证 —— 如果所有 vhost 都返 -5，
   下一步就用反射调 `Kiwi.init_appname("天美##com.l95c2450d7.f68bb4e2d5")` 覆盖再重试。

### 6.1 `ServerToLocal` 返回 -5：缺了 `RestartAllServer`

`Init` 返回 0 之后直接调 `ServerToLocal("kiwi_live")`，拿到 **-5**，日志：

```
I KiwiSDK : [kiwicore]dns query failed kiwi_live     ← 每次 ServerToLocal 打一条
```

从源 App 的运行内存里把 libkiwi 的明文符号表恢复出来之后（做法见 §8），
看到相关符号就明白了：

```
KiwiCore::Dns(char const*, unsigned short*)          ← ServerToLocal 内部靠它找节点
KiwiCore::RestartAllServer()                          ← 建 listener
KiwiCore::ForceRestartServer(unsigned short)
KiwiProxy::TcpProxy::ForceRestart()
KiwiProxy::TcpProxy::Lport()                          ← 本地端口就是它
```

`Init` 只完成握手（appKey 校验 + 节点下发），**本地 listener 是 `RestartAllServer`
才起来的**。组表为空时 `KiwiCore::Dns(group)` 当然查不到东西 → -5。
所以 `Tunnel.start()` 现在是 `Init → RestartAllServer → 等 1.5s → 逐个 vhost 试 ServerToLocal`。


## 7. 从「能编译」到「能运行」：两次真实闪退

### 7.1 加载顺序：绝不能先 `System.load`

第一版是「先 `System.load(解压路径)`，再 `DexClassLoader` 加载类」。装到机器上**打开即闪退**：

```
JNI DETECTED ERROR IN APPLICATION: JNI NewGlobalRef called with pending exception
  java.lang.ClassNotFoundException: Didn't find class "com.kiwi.sdk.Kiwi"
  on path: DexPathList[[zip file ".../base.apk"], ...]
    at Runtime.nativeLoad(String, ClassLoader, Class)
    at System.load(String)
    at KiwiSdk.ensureLoaded(KiwiSdk.kt:107)
```

`System.load(path)` 内部是 `Runtime.load0(null, path)` —— 传 `null` 意味着
`JNI_OnLoad` 拿到的是**系统 ClassLoader**，它只看得到 `base.apk`。
而 `libkiwi.so` 的 `JNI_OnLoad` 里会 `FindClass("com.kiwi.sdk.Kiwi")` 做动态注册，
找不到就抛异常；它没清 pending exception 就接着调 `NewGlobalRef`，ART 直接
`abort()` 掉整个进程。

**这类崩溃 `try/catch` 拦不住**，因为它是 native 层 `SIGABRT`。

正确做法：不要自己加载 so，让 `Kiwi` 的 `<clinit>` 去 `System.loadLibrary("kiwi")`。
`Kiwi.<clinit>` 反汇编出来只有三条指令：

```
const-string v0, "kiwi"
invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
return-void
```

此时 `nativeLoad` 收到的 ClassLoader 就是我们传入的 `DexClassLoader`，
`JNI_OnLoad` 的 `FindClass` 才找得到类。

**代价**：`DexClassLoader` 的 `librarySearchPath` 必须指到 so 所在目录，否则
`loadLibrary` 找不到库。现在用 `applicationInfo.nativeLibraryDir`。

### 7.2 ABI：进程不是 arm64，so 永远加载不了

第二版修好顺序后不崩了，但隧道起不来。`dumpsys package` 一看：

```
primaryCpuAbi=x86_64
```

原因：APK 里同时有 `x86_64` 和 `arm64-v8a` 的库（`librtmp-jni.so` 等），
`PackageManager` 按设备首选 ABI（`x86_64` 排第一）把整个进程定成 x86_64。
x86_64 的 linker 打不开 arm64 的 ELF，`libkiwi.so` 也不会被释放到 `lib/x86_64`。

解决办法：`abiFilters` 只留 `arm64-v8a`。这样 `primaryCpuAbi` 变成 arm64-v8a，
进程以 arm64 拉起。

**关键前提**：这台模拟器其实支持 ARM 转译，之前判断错了：

```
ro.product.cpu.abilist      = x86_64,arm64-v8a,x86
ro.dalvik.vm.native.bridge  = libnb.so
```

源 App 装的就是 arm64 包，一直以 arm64 进程在跑。所以「x86 模拟器跑不了 arm64 so」
这个结论是错的 —— **能跑，前提是整个进程被定成 arm64**。

代价：本 App 在 x86 模拟器上也会整体走转译（和源 App 一样），性能略有损耗；
纯 x86 且没有转译层的设备则装不上。真机 arm64 无影响。

### 7.3 其它两条硬约束

- **targetSdk 34+ 不许从可写文件加载代码**：`DexClassLoader` 吃 `filesDir` 下的 jar
  会抛 `SecurityException`，解包后必须 `setReadOnly()`。
- **`bindContext` 要提前到触发 `<clinit>` 之前**：加固库的 `JNI_OnLoad` 里也可能取
  Context，那时还没有 Java 层入口可以补救。

## 8. 复现命令

### 8.1 恢复 libkiwi 的符号表（静态文件里全是密文）

`libkiwi.so` 的 `.dynstr` 和 `.dynsym` 在磁盘上是密文，但**加载后会被解密**。
关键是自己算对偏移，别信手抄的地址：

```python
# 静态文件里 ELF 头前 4 字节被抹了，但 e_phoff / program header 是好的
e_phoff, = struct.unpack_from("<Q", so, 0x20)
for i in range(e_phnum):
    p_type, p_flags, p_offset, p_vaddr, *_ = struct.unpack_from("<IIQQQQQQ", so, e_phoff + i*56)
    if p_type == 2:                     # PT_DYNAMIC
        ...
# 读出 DT_STRTAB / DT_SYMTAB / DT_STRSZ / DT_SYMENT
#   -> DT_STRTAB = 0x5ff348   DT_SYMTAB = 0x5b48b0   DT_STRSZ = 0x635c0
```

然后从运行中的进程 dump 那个 rw 段（`maps` 里 `off=0x54a000` 那段），
`dynstr` 就在 `dump[DT_STRTAB - 0x54a000]`，此时是明文：

```
0x0c4627  _ZN9KiwiProxy9TcpBridge13UpdateRemotesE...
0x0d6758  _ZN8KiwiCore3DnsEPKcPt              KiwiCore::Dns(char const*, ushort*)
0x0d676f  kiwiapi_server_to_local
0x0d67ab  _ZN8KiwiCore16RestartAllServerEv     KiwiCore::RestartAllServer()
0x0d67cc  _ZN8KiwiCore18ForceRestartServerEt   KiwiCore::ForceRestartServer(ushort)
```

### 8.2 从运行内存里捞 appKey

```bash
PID=$(adb shell pidof com.l95c2450d7.f68bb4e2d5)
# 只看 rw 段：scudo / dalvik 堆 / libkiwi 的 rw
adb shell "su -c 'cat /proc/$PID/maps'"
# 逐段流式 dump 边搜（exec-out 才能传二进制，普通 shell 会把 \n 变 \r\n）
adb exec-out su -c "dd if=/proc/$PID/mem bs=4096 skip=$((0x2000000/4096)) count=131072" \
  | grep -aob "appKey"
# 命中后取前后各 2KB，里面就是 initEx 相邻的那串 base64
```

### 8.3 反编译 Java 层（本仓库自带的三个小工具，纯标准库，不依赖 androguard）

```bash
python tools/dex_inspect.py cls com.kiwi.sdk.Kiwi
python tools/dalvik_dump.py com.kiwi.sdk.Kiwi --meth Init
python tools/dex_xref.py meth com.kiwi.sdk.Kiwi Init
```

### 8.4 提取资源（一次性）

```bash
python - <<'PY'
import zipfile
z = zipfile.ZipFile('assets/channel.apk')
open('app/src/main/jniLibs/arm64-v8a/libkiwi.so','wb').write(z.read('lib/arm64-v8a/libkiwi.so'))
with zipfile.ZipFile('app/src/main/assets/kiwi-sdk.jar','w',zipfile.ZIP_DEFLATED) as out:
    out.writestr('classes.dex', z.read('classes3.dex'))
PY
```

### 8.5 在设备上快速迭代 appKey（不用每次等 CI）

```bash
# 直接改 prefs 再冷启动，30 秒一轮
adb shell "su -c 'cp /data/local/tmp/tm_live.xml /data/data/com.duocai.live.debug/shared_prefs/tm_live.xml'"
adb shell am force-stop com.duocai.live.debug
adb logcat -G 32M                       # 先加大缓冲，否则会被 MESA 刷屏冲掉
adb logcat -s KiwiSDK:V kiwitest:V &
adb shell monkey -p com.duocai.live.debug -c android.intent.category.LAUNCHER 1
```
