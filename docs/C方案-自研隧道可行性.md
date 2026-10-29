# C 方案：把 kiwi 隧道搬进我们自己的进程

> 2026-10-29 实测。结论：**可行，且比预想简单** —— 不需要复刻协议，
> 只要在自家进程里加载源 App 的 `libkiwi.so` 并调用它的 Java SDK。

## 1. 静态分析走不通（一度以为死路）

`libkiwi.so`（6.8 MB，arm64）是加固库：

- `DT_STRTAB` 指向的 `.dynstr` 内容是密文，`dlsym` 拿不到任何导出名；
- 文件里没有可读的 `Java_*` / `JNI_OnLoad` 符号；
- 运行时第一段映射（offset 0 起 0x4F1000）与文件逐页不同 —— 加载时被解密，且 ELF 头被抹成 `7f b2 b3 0f`，防 dump 重建。

## 2. 绕过方式：直接 dump 进程内存

模拟器有 root（KernelSU）。按 `/proc/<pid>/maps` 用 `dd` 读 `/proc/<pid>/mem`：

```
grep libkiwi /proc/5036/maps
400024a00000-400024f3b000 r--p 00000000  lib/arm64/libkiwi.so   ← 代码段（已解密，头被抹）
400024fb3000-4000250f4000 rw-p 0054a000  lib/arm64/libkiwi.so   ← dynsym + dynstr 在这里
dd if=/proc/5036/mem of=/data/local/tmp/k8 bs=4096 \
   skip=$((0x400024fb3000/4096)) count=$((0x141000/4096))
```

拿到 rw 段后，`0x54b8b0`（DT_SYMTAB）到 `0x596348`（DT_STRTAB）全部是**明文**，3923 个符号直接可读。

## 3. 挖到的东西

### 3.1 Java 侧入口（`classes*.dex`）

```
com.kiwi.sdk.Kiwi          com.kiwi.sdk.KiwiListener
com.kiwi.sdk.DeviceInfo    com.kiwi.sdk.ContextHelp
com.kiwi.sdk.BuildConfig   com.kiwi.sdk.R
```

方法名 `initEx` 在 dex 字符串池里出现 5 次 —— 与 Dart 侧
`FlutterYunCengKiwi::initEx(appKey, token)` 对得上。

### 3.2 Native 导出（C API）

| 符号 | 地址 | 作用推测 |
|---|---|---|
| `JNI_OnLoad` | `0x22042c` | JNI 动态注册入口（存在，说明 `System.loadLibrary` 能正常走通） |
| `kiwiapi_server_to_local` | `0x242fec` | **服务端 → 本地端口映射**，正是我们要的能力 |
| `kiwiapi_init_cache` | `0x242f84` | 节点缓存初始化 |
| `kiwiapi_init_device_info` | `0x242fac` | 设备指纹 |
| `kiwiapi_restart_server` | `0x243168` | 重连 |
| `kiwiapi_simple_dns` | `0x243258` | 内置 DNS |
| `kiwiapi_send_log` | `0x24314c` | 日志上报 |

### 3.3 C++ 类结构（关键几项）

```
KiwiCore::InitCache / InitUseIp / DnsEx / SendInitInfoLog
KiwiAppkey::ParseAppkey(const string&)
KiwiProxy::TcpProxy(uint16_t, shared_ptr<TcpBridge>, const vector<char>&, char, bool, TRANSFER_CTL_ARG)
KiwiProxy::TcpSession / TcpRecord / FlowEater / RemoteNode
KiwiLocalCache::WriteCacheNodes / cache_path_
KiwiCellly*  (JS / HTTP / TCP Attack 相关，盾的探测逻辑)
```

也就是说 SDK 自带：节点发现（`KiwiCellly`）、本地缓存、代理端口（`TcpProxy`）、DNS。
这些就是源 App 里那 17 个 `127.0.0.1` 端口的来源。

## 4. 因此 C 的实现路径

```
我们的 App
 ├─ System.load(<源App nativeLibraryDir>/libkiwi.so)     ← 不打包进 APK，借已安装的 so
 ├─ DexClassLoader 加载源 App APK，拿 com.kiwi.sdk.Kiwi
 ├─ Kiwi.initEx(context, appkey, token, listener)        ← 参数待确认
 ├─ 轮询 /proc/net/tcp 找新建的 127.0.0.1 端口
 └─ LiveRepo 改成「多端口探测」而不是「127.0.0.1:单端口」
```

### 还差三件事

1. **`initEx` 的完整签名** —— 要反编译 `com.kiwi.sdk.Kiwi`（dex → smali）；
2. **appkey 从哪来** —— `KiwiAppkey::ParseAppkey` 的输入，可能在盾配置 / `BuildConfig`；
3. **SDK 是否会校验调用方包名/签名** —— 如果校验，需要把包名改回去或用 Xposed 类方案。

### 一个更省事的备选

`libkiwi.so` 在 `/data/app/.../lib/arm64/` 下是全局可读的，`DexClassLoader` 也能直接吃
源 App 的 APK。所以「借用」不需要复制文件，也不需要源 App 在跑 —— 只要求它**装着**。
这条路跑通后，源 App 就只剩一个「SDK 提供方」的角色，不再需要启动、不再占内存。

## 5. 复现命令

```bash
PID=$(adb shell pidof com.l95c2450d7.f68bb4e2d5)
adb shell "su -c 'grep libkiwi /proc/$PID/maps'"
# 按 maps 里 off=0x54a000 的那段 dump，再解析 DT_SYMTAB/DT_STRTAB
```
