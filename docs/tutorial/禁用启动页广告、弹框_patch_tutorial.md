# 禁用启动页广告、弹框_patch_tutorial

> 本文档基于特征码搜索定位，适用于版本更新后偏移量变化的情况。

---

## 一、关键函数信息

| 函数名 | 说明 | 地址 |
|--------|------|------|
| `AdvDialog::show` | 广告弹框显示函数 | 0x910328 |
| `UpdateManager::checkAppNeedUpdate` | 检查更新函数 | 0x8d0f44 |
| `_SplashPageState::_initSplash` | 初始化开屏逻辑 | 0xacf3c0 |
| `_SplashPageState::_startAdTimer` | 启动广告倒计时 | 0xad4f0c |
| `_SplashPageState::_buildAdView` | 构建广告视图 | 0x12321e0 |

---

## 二、修改方案对比

| 方案 | 目标函数 | 风险 | 效果 |
|------|---------|------|------|
| 1 | `AdvDialog::show` | 低 | 广告弹框不显示 |
| 2 | `UpdateManager::checkAppNeedUpdate` | 低 | 不检查更新 |
| 3 | `_buildAdView` | 低 | 不显示广告视图 |
| 4 | `_startAdTimer` | 中 | 跳过广告倒计时 |

---

## 三、修改点 1：禁用广告弹框

### 3.1 定位方法

#### 方法 A：通过函数名定位（推荐）

1. 在 IDA Pro 中打开 `libapp.so`
2. 按 `Ctrl+F` 搜索函数名：`AdvDialog::show`
3. 进入函数后，找到函数开头

#### 方法 B：通过 pp.txt 定位

在 pp.txt 中搜索：
```
AdvDialog::show (0x910328)
```

### 3.2 修改步骤

1. 跳转到地址 `0x910328`
2. 将前 4 字节替换为 `C0 03 5F D6` (RET 指令)
3. 保存修改

**效果**：AdvDialog::show 不显示弹窗

---

## 四、修改点 2：禁用更新检查

### 4.1 定位方法

#### 方法 A：通过函数名定位

1. 在 IDA Pro 中搜索：`UpdateManager::checkAppNeedUpdate`
2. 进入函数后，找到函数开头

#### 方法 B：通过 pp.txt 定位

在 pp.txt 中搜索：
```
UpdateManager::checkAppNeedUpdate (0x8d0f44)
```

### 4.2 修改步骤

1. 跳转到地址 `0x8d0f44`
2. 将前 4 字节替换为 `C0 03 5F D6` (RET 指令)
3. 保存修改

**效果**：不检查更新，不弹出更新提示

---

## 五、修改点 3：禁用广告视图构建

### 5.1 定位方法

在 pp.txt 中搜索：
```
_SplashPageState::_buildAdView (0x12321e0)
```

### 5.2 修改步骤

1. 跳转到地址 `0x12321e0`
2. 将前 4 字节替换为 `C0 03 5F D6` (RET 指令)
3. 保存修改

**效果**：不显示广告视图，但页面正常

---

## 六、修改点 4：禁用广告倒计时

### 6.1 定位方法

在 pp.txt 中搜索：
```
_SplashPageState::_startAdTimer (0xad4f0c)
```

### 6.2 修改步骤

1. 跳转到地址 `0xad4f0c`
2. 将前 4 字节替换为 `C0 03 5F D6` (RET 指令)
3. 保存修改

**效果**：不启动广告倒计时

**注意**：可能影响页面跳转逻辑，建议优先使用方案 1-3

---

## 七、通用修改步骤

### 7.1 使用 IDA Pro

1. 打开 `libapp.so`，等待分析完成
2. 按 `G` 跳转到目标地址
3. 按 `F2` 编辑
4. 修改字节为 `C0 03 5F D6`
5. 按 `F2` 保存

### 7.2 使用 Python 脚本

```python
import sys

so_path = r'path/to/libapp.so'

def patch_ret(so_path, address, description):
    """将函数开头替换为 RET 指令"""
    with open(so_path, 'r+b') as f:
        f.seek(address)
        f.write(bytes.fromhex('C0035FD6'))
        print(f'[+] 修改完成: {description} at 0x{address:x}')

# 修改点 1：禁用广告弹框
patch_ret(so_path, 0x910328, 'AdvDialog::show')

# 修改点 2：禁用更新检查
patch_ret(so_path, 0x8d0f44, 'UpdateManager::checkAppNeedUpdate')

# 修改点 3：禁用广告视图构建
patch_ret(so_path, 0x12321e0, '_buildAdView')

# 修改点 4：禁用广告倒计时
patch_ret(so_path, 0xad4f0c, '_startAdTimer')
```

---

## 八、ARM64 指令参考

| 指令 | 机器码 |
|------|--------|
| `STP FP, LR, [SP,#-0x10]!` | `FD 7B BF A9` |
| `RET` | `C0 03 5F D6` |
| `NOP` | `1F 20 03 D5` |

---

## 九、版本更新适配

当版本更新后偏移量变化时：

1. 重新提取 pp.txt
2. 搜索新的函数地址：
   - `AdvDialog::show`
   - `UpdateManager::checkAppNeedUpdate`
   - `_SplashPageState::_buildAdView`
3. 确认函数 prologue 未变化
4. 应用相同的修改

---

## 十、推荐修改方案

### 最安全（推荐）

只修改 `AdvDialog::show` (0x910328)：
- 开屏广告正常显示
- 广告弹框不弹出

### 完整禁用

1. `AdvDialog::show` (0x910328)
2. `UpdateManager::checkAppNeedUpdate` (0x8d0f44)
3. `_buildAdView` (0x12321e0)

---

## 十一、注意事项

1. 修改前务必备份原始 so 文件
2. 确保修改的是 arm64-v8a 架构
3. 修改后充分测试应用稳定性
4. 部分应用有签名验证，可能需要额外处理
