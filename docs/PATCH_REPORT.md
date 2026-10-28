# 91直播 4.3.3 · 首页倒计时广告（Splash Ad）禁用 —— 静态分析与补丁报告

生成时间：2026-10-27
目标文件：`libapp.so`（Dart AOT，Flutter，ARM64，file offset == VA）
工具链：IDA Pro 9.3 + ida-pro-mcp（`127.0.0.1:13337`）、blutter 产物（`pp.txt` / `objs.txt` / `addNames.py`）、自研 ARM64 解码器 `a64.py`

---

## 1. 结论（TL;DR）

| 项 | 值 |
|---|---|
| 补丁位置（VA） | `0x5E9324` |
| 原指令 | `MOV X2, #1` → `D2800022` → 字节 `22 00 80 D2` |
| 补丁后 | `MOV X2, #0xFFFF` → `D29FFFE2` → 字节 `E2 FF 9F D2` |
| 所属函数 | `sub_5E9310`（= `AdManager::getStartPageAd`） |
| 作用 | 调用 `_getAdsByBelongModule(0xFFFF)`，module 永不匹配 → 返回空列表 → SplashPage 走「无广告」分支，不再出现 2 秒倒计时 / 手动点 X |
| 影响面 | 仅首页开屏广告；其它 5 个广告位（module 2~6）不受影响 |

产物：
- `libapp_patched.so` —— 已打补丁的 SO
- `patch_splash_ad.py` —— 可复现补丁脚本（带原始字节校验，防止打错版本）

---

## 2. 4.3.3 与 6.24 的对应关系

4.3.3 全量混淆（无 `getStartPageAd` / `_getAdsByBelongModule` 符号），因此改用**行为特征 + 结构指纹**定位。

| 语义 | 6.24（可读符号） | 4.3.3（混淆） | 判定依据 |
|---|---|---|---|
| `AdManager::getStartPageAd()` | `0xad5e68`（size 0xE4） | **`sub_5E9310`**（0x28） | 单指令包装：`MOV X2,#1; BL sub_5E9340; RET`，与 6.24 结构同构 |
| `AdManager::_getAdsByBelongModule()` | `0x9109f4`（size 0x1E8） | **`sub_5E9340`**（0x1DC） | 见 §3 |
| 唯一调用方 | `0xad4e3c`（splash_page） | `sub_5DAA04` @ `0x5daa44` | `xrefs_to 0x5e9310` 仅 1 条 |

4.3.3 的 `getStartPageAd` 反汇编（完整）：

```
0x5e9310  STP X29, X30, [X15,#-0x10]!
0x5e9314  MOV X29, X15
0x5e9318  LDR X16, [X26,#0x38]          ; THR::stack_limit
0x5e931c  CMP X15, X16
0x5e9320  B.LS  loc_5E9338              ; 栈溢出跳板
0x5e9324  MOV X2, #1                    ; <-- module 常量（补丁点）
0x5e9328  BL  sub_5E9340                ; _getAdsByBelongModule
0x5e932c  MOV X15, X29
0x5e9330  LDP X29, X30, [X15],#0x10
0x5e9334  RET
0x5e9338  BL  StackOverflowSharedWithoutFPURegsStub_10bcc30
0x5e933c  B   loc_5E9324
```

Hex-Rays 在补丁后确认：`return sub_5E9340(a1, a2, 0xFFFF, ...)`。

---

## 3. `sub_5E9340` 语义还原（关键：无匹配 = 空列表，不抛异常）

6.24 可读源码（`asm/jj_live/utils/ad/ad_manager.dart:2460`）给出了等价实现：

```dart
List<AdModel> _getAdsByBelongModule(dynamic module) {
  final list = <AdModel>[];                       // _GrowableList
  if (CommonUtils.isListNotEmpty(this._adList)) {  // field_7 -> field_7
    final m = module << 1;                         // 旧 Dart: Smi 装箱
    for (final item in this._adList) {
      if (m == item.belongModule) list.add(item);  // field_23
    }
  }
  return list;
}
```

4.3.3 指令级验证（4 条独立证据）：

1. **累加器是新空列表**：`0x5e9368 ADD X1,X27,#0x2A,LSL#12` / `0x5e936c LDR X1,[X1,#0x8E0]` → `pp+0x2A8E0` = `TypeArguments: <gQa>`；随后 `sub_4D8134(<gQa>, 0)` 即 `_GrowableList::_GrowableList`，与 6.24 的 `<AdModel>[]` 一致。
2. **过滤循环**：`0x5e9414` 取元素数组、`0x5e942c LDUR X1,[X6,#0x1F]` 读 `item.belongModule`、`0x5e9430 CMP X4,X1` 与 module 参数比较。
3. **追加路径**：`0x5e9454/0x5e9458` 判容量相等 → `0x5e9460 BL sub_4D7EE0`（= `_growToNextCapacity`）→ `0x5e948c STR W0,[X25]`（ArrayStore）+ `0x5e94a8 ArrayWriteBarrierStub`。
4. **无匹配直接返回空表**：循环结束 `0x5e94c8 MOV X2,X3` → `0x5e94d4` 返回，X3 即步骤 1 的空列表。

> 注意：4.3.3 的 `belongModule` 已改为**未装箱 int**（`CMP` 直接比较裸立即数，无旧版的 `lsl x2,x1,#1` 装箱）。合法 module 集合经全量 `xrefs_to 0x5e9340` 枚举确认为 **{1,2,3,4,5,6}**：
>
> | 调用点 | module |
> |---|---|
> | `0x5e9328`（首页） | 1（本补丁改为 0xFFFF） |
> | `0x839fe0` | 2 |
> | `0x870340` | 3 |
> | `0x840688` | 4 |
> | `0x83dd08` | 5 |
> | `0x5ea020` | 6 |
>
> 因此 `0xFFFF` 不可能命中任何广告项。

### 3.1 「其它 5 个广告位（module 2~6）」究竟是什么

4.3.3 是混淆版（无可读符号），但 `belongModule` 是**服务端下发**的字段、客户端直接比对，编号跨版本稳定。用 6.24 可读源码（`asm/jj_live/utils/ad/ad_manager.dart` 中 17 个 getter 的 `r2 = N` 常量）可确定前 6 个编号的语义——而 4.3.3 经 xref 枚举恰好也只保留 module {1..6} 这 6 个 getter，编号一一对应：

| module | 6.24 可读 getter | 广告位含义 | 补丁影响 |
|---|---|---|---|
| 1 | `getStartPageAd` | **开屏 / 启动页广告**（2 秒倒计时 + 手动点 X） | ✅ 本次禁用 |
| 2 | `getHomeBannerAds` | 首页 Banner 广告 | 不受影响 |
| 3 | `getMineBannerAds` | 「我的」页 Banner 广告 | 不受影响 |
| 4 | `jumpForMarquee` | 跑马灯 / 滚动公告广告 | 不受影响 |
| 5 | `getPromoteBannerAds` | 推广 Banner 广告 | 不受影响 |
| 6 | `getHomeAlertAd` | 首页弹窗广告 | 不受影响 |

> 注意版本差异：6.24 一共有 **17** 个广告位编号（module 1~17，含 gameBanner / rechargeAlert / mineAlert / activeCenterAlert / anchorHomeAlert / homeLiveListFloating 等），但 **4.3.3 只保留到 module 6**（即只有上表这 6 个 getter）。module 7~17 在 4.3.3 没有对应 getter——说明这些广告位在该版本被移除或改由其它机制下发，不在本次补丁讨论范围内。
>
> 结论：**本补丁只把 module 1 的取广告请求改成非法值 `0xFFFF`，module 2~6 五个广告位（首页 Banner、我的页 Banner、跑马灯、推广 Banner、首页弹窗）完全照常工作。**

---

## 4. 「空列表」分支为何安全

4.3.3 开屏页：`sub_5DAA04` → `sub_5E9310()` → `sub_5DAA6C(state, ads)`；`sub_5DAA6C` 取 `ads.length`（偏移 +0xB 的 Smi 长度），**为 0 时进入与 `length>0` 完全不同的分支**（不再构造广告组件闭包）。

对照 6.24 可读源码（`asm/jj_live/page/splash_page.dart:1812-1876`）：

```dart
ad = AdManager.initWithLocalData();      // 0xad5e4c
ad = getStartPageAd();                   // 0xad5e68
this.field_3b = ad;
if (ad != null) {
  this.field_33 = max(ad.field_27, 3);   // 倒计时秒数
  _setViewType(SplashViewTypeEnum.AD);   // pp+0x363e0
  _getAndroidOldPkg(false);
  _startAdTimer();                       // <-- 倒计时 + 手动点 X
  _removeNativeSplash();
} else {
  _setViewType(SplashViewTypeEnum.HOME); // pp+0x23c58
  _getAndroidOldPkg(true);               // 直接移除原生闪屏，不启定时器
}
```

**「无广告」是全新安装 / 清缓存后的正常可达状态**，所以走该分支不会卡死开屏——这是本补丁最主要的安全依据。

---

## 5. 验证链

| 环节 | 方法 | 结果 |
|---|---|---|
| 补丁点字节 | IDA `get_bytes 0x5e9324` | `22 00 80 D2` |
| IDA 内打补丁 | `patch` + `force_recompile` | 反汇编 → `MOV X2, #0xFFFF`；伪代码 → `sub_5E9340(..., 0xFFFF, ...)` |
| 文件补丁 | `patch_splash_ad.py`（校验原始字节后才写） | 差异字节数 = 3（`0x5e9324~0x5e9326`，第 4 字节 `D2` 不变） |
| 独立复核 | 自研解码器 `a64.py` 读 `libapp_patched.so` | `0x5E9324 D29FFFE2 mov x2, #0xffff` ✅ |
| 设备实测 | `adb devices` | **未执行**：本机无在线设备/无 4.3.3 APK |

---

## 6. 风险与回滚

- **风险等级：低。** 单指令、单函数、单广告位；不改任何控制流结构，不触碰校验逻辑。
- **不建议**改 `sub_5E9340` 本体：那会同时废掉 module 2~6 的其它 5 个广告位（首页 Banner、我的页 Banner、跑马灯、推广 Banner、首页弹窗）。
- **回滚**：把 `0x5E9324` 的 4 字节改回 `22 00 80 D2`，或直接用原始 `libapp.so` 覆盖。
- 若后续版本 `belongModule` 回到 Smi 装箱编码，则 `0xFFFF` 需要写成 `MOV X2, #0xFFFF` 仍然有效（Smi 后为 `0x1FFFE`，同样越界），逻辑不变。

---

## 7. 落地步骤（需自备 APK）

```bash
# 1) 解包
apktool d -f -s 91zhibo_4.3.3.apk -o apk_out
# 2) 覆盖 arm64 so（按实际 ABI 目录）
cp libapp_patched.so apk_out/lib/arm64-v8a/libapp.so
# 3) 重打包
apktool b apk_out -o patched.apk
# 4) 对齐 + 签名
zipalign -f 4 patched.apk patched_aligned.apk
apksigner sign --ks my.keystore --out patched_signed.apk patched_aligned.apk
# 5) 安装验证：冷启动应直接进首页，无 2 秒倒计时、无关闭叉号
adb install -r patched_signed.apk
```

> 若原 APK 有签名校验/VMP 保护，需额外处理（本报告未涉及，本次只做 `libapp.so` 的 Dart AOT 层补丁）。
