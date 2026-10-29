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

## 3. appKey 从哪来

`blutter` 从 `libapp.so` 的对象池 dump 出来的 `main.dart` 常量区，几条紧挨着：

```
[pp+0x2830] String: "kiwi_"
[pp+0x2838] String: "dNLmP4x5bcvPKZess6gqv6MzS+2bg2tW…"        ← 长 Base64
[pp+0x2840] String: "cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4"       ← 32 位，最像 appKey
[pp+0x2848] String: "http://kiwi_live/tunnel/a033b072?z=1&nobase64=1"
[pp+0x2850] String: "OIWKj3hGjOKA6pU1lMcZvC0…"                ← 长 Base64
[pp+0x2880] String: "00000000000000000000000000000000"        ← 占位默认值
```

> 这几个串在磁盘上的 `libapp.so` 里**搜不到明文**（`grep` 命中 0），
> 说明它们是运行时构造/解密的，所以只能从 blutter 的对象池 dump 里拿。
> 候选不止一个 —— 设置页有「试候选 key」按钮，逐个试到 `Init` 返回 0 为止。

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

1. **appKey 是否就是 `cZ1dAC6cE5wlH9E0mg4fly6B5ASMXBU4`** —— 靠 `Init` 返回值判断，
   0 = 对，非 0 = 换下一个候选（设置页有按钮一次试完）。
2. **SDK 是否校验调用方包名/签名** —— `init_appname` 传的是我们自己的包名
   （`豆彩直播##com.duocai.live`）。如果云端按 appKey+包名 校验，会返回非 0。
   这是唯一可能"代码全对但跑不通"的点。

## 7. 复现命令

```bash
# 1. 拿符号（需要 root）
PID=$(adb shell pidof com.l95c2450d7.f68bb4e2d5)
adb shell "su -c 'grep libkiwi /proc/$PID/maps'"
# 按 maps 里 off=0x54a000 的那段 dump，再解析 DT_SYMTAB / DT_STRTAB

# 2. 反编译 Java 层（本仓库自带的三个小工具，纯标准库，不依赖 androguard）
python tools/dex_inspect.py cls com.kiwi.sdk.Kiwi
python tools/dalvik_dump.py com.kiwi.sdk.Kiwi --meth Init
python tools/dex_xref.py meth com.kiwi.sdk.Kiwi Init

# 3. 提取资源（一次性）
python - <<'PY'
import zipfile
z = zipfile.ZipFile('assets/channel.apk')
open('app/src/main/assets/libkiwi-arm64.so','wb').write(z.read('lib/arm64-v8a/libkiwi.so'))
with zipfile.ZipFile('app/src/main/assets/kiwi-sdk.jar','w',zipfile.ZIP_DEFLATED) as out:
    out.writestr('classes.dex', z.read('classes3.dex'))
PY
```
