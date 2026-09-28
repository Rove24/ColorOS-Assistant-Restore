# ColorOS 唤语 · AssistRestore (ColorOS 17 适配版)

在 ColorOS 国内版上还原 AOSP 的数字助理行为：**长按电源键**、**长按手势条**、**屏幕底部角落内滑**
三个入口，都改为唤醒系统当前设置的默认助理应用（`Settings.Secure.assistant` /
`RoleManager.ROLE_ASSISTANT` 指向的应用），而不是固定唤醒小布。

本仓库是 [`Andrea-lyz/ColorOS-Assist-Restore`](https://github.com/Andrea-lyz/ColorOS-Assist-Restore)
的二次开发分支，**在原版 v1.0.4 基础上适配 ColorOS 17（Android 17 / SDK 37）**，
并重做了设置界面的主页与配色。

- 包名：`com.github.rove24.assistrestore`
- 版本：`0.1` (versionCode 1)
- 适配固件：ColorOS 17 国内版（已在 `PKX110` / `V17.0.0` / `PKX110_17.0.0.100(CN01)` 上验证）

---

## 一、相对原版改了什么

### 1. Hook 层（适配 ColorOS 17）

原版 15 个 Hook 点在 ColorOS 17 上有 3 处失效，本分支已处理：

| 位置 | ColorOS 17 的变化 | 本分支的做法 |
| --- | --- | --- |
| `AssistManager.startAssist(Bundle)` | 方法被改名成 `startAssist$1`（该类不再实现 `CommandQueue.Callbacks`，R8 因此可以改名） | 挂载点上移到 **`CentralSurfacesCommandQueueCallbacks.startAssist(Bundle)`**——它 `@Override` 的是跨进程接口，方法名必须保留。旧类作为回退候选。 |
| `AssistManager.startAssistInternal(Bundle,ComponentName,boolean)` | 签名变成 `(Context,Bundle,ComponentName,boolean)`，多了首个 `Context` | **多候选解析**：先试 4 参版本（从 `AssistManager.mContext` 取 Context），失败再退回 3 参版本。 |
| `QuickStepContract.isAssistantGestureDisabled(long)` | **从平台彻底删除**：桌面 7 个 dex 里整个 `com.android.systemui.shared.system` 子包都不存在；SystemUI 侧该类也只剩 4 个方法 | 无替代挂载点。**「解除页面级手势限制」功能在 ColorOS 17 上不可用**，高级页会把它显示成「不支持」。 |

其余 12 处签名一字不差，原样沿用。

### 2. 界面

- **主页去掉底部导航栏**，「高级」改为从主页的一行卡片进入
- **「模块是否生效」与「默认助理」从正方形大卡改成横向卡片**（状态徽章 + 标题 + 副标题 + 开关 / 箭头），
  与同系列的 `增强 Chrome`、`XposedSmsCode` 保持同一套版式
- **配色换成同系列模块的 Material 3 基线蓝**（`#0B57D0` 系列 + 手工挑的夜间变体），
  **不使用莫奈动态取色**——这样每台设备上看起来都一致
- **二级界面（唤醒目标 / 自定义目标 / 高级）保持原样**，只跟随主题换色

### 3. 默认值

「高级」页的四个开关**默认全部关闭**，让模块默认尽可能贴近 ColorOS 原生行为：

| 选项 | 默认 | 打开后的效果 |
| --- | --- | --- |
| 跳过识屏服务预绑定 | 关 | 长按手势条时不再白唤醒一次小布识屏服务 |
| 解除页面级手势限制 | 关 | 设置这类页面也能用滑动唤醒（**ColorOS 17 上不可用**） |
| Google 应用机型伪装 | 关 | 伪装为 `Pixel 11 Pro XL`，解锁即圈即搜 |
| 隐藏手势条时保持长按 | 关 | 手势条隐藏后，底部原位置的长按仍能召唤助理 |
| 隐藏桌面图标 | 关 | 隐藏后可从 LSPosed 模块页或系统设置的应用详情打开 |

> 注意：因为「机型伪装」默认关闭，**即圈即搜默认不可用**，需要手动打开。

---

## 二、环境要求

- ColorOS 国内版固件，**已验证 ColorOS 17**（`V17.0.0` / `PKX110` / `regionmark=CN`）
- KernelSU、Magisk 或其他 Root 方案
- LSPosed 等支持 **libxposed API 102** 的框架

## 三、安装

1. 安装 APK
2. 在 LSPosed 中启用模块，**作用域勾选以下四项**：

   ```
   system
   com.android.systemui
   com.android.launcher
   com.google.android.googlequicksearchbox
   ```

3. 重启设备（`system_server` 侧的 Hook 在开机阶段装载，必须重启才生效）
4. 打开「ColorOS 唤语」，确认主页状态卡显示「模块已生效」

> 四个作用域分别对应：电源键派发、SystemUI 手势与助理派发、桌面底角手势、即圈即搜与进程保活。
> **缺少任意一项，对应功能不会生效。**

## 四、构建

需要 **JDK 17** 与 Android SDK（`compileSdk 37`，即 Android 17 平台）。

```bash
# 1. 指向本机 SDK（local.properties 已被 .gitignore 忽略）
echo 'sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk' > local.properties

# 2. 正式包
./gradlew assembleRelease
#   产物：app/build/outputs/apk/release/app-release.apk
#   同时自动复制一份到工程根目录：AssistRestore_v0.1.apk

# 3. 调试包（仅在需要看 logcat 里的崩溃堆栈时用）
./gradlew assembleDebug
```

### 关于 release 签名

工程根目录已经放好 `keystore.properties`（已被 `.gitignore` 忽略）和 `keystore/assistrestore.jks`，
`assembleRelease` 会直接签名，无需额外参数。

```
alias          assistrestore
storePassword  assistrestore
keyPassword    assistrestore
```

> ⚠️ **这个密钥是新建的，口令是公开写在文档里的**。如果你打算分发，请换成你自己的密钥并改掉口令；
> 如果只是自用则无所谓。**签名密钥一旦用于分发就不能更换**（换了无法覆盖安装），请把
> `keystore.properties` 和 `keystore/assistrestore.jks` 备份好。
>
> 想换成你其它模块共用的密钥：改 `release.storeFile` 指向那个 `.jks`，并填上对应的 alias / 口令即可。

签名信息也支持 `-Prelease.storeFile=...` 这类属性，以及
`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`
环境变量（用于 CI）。三者都没有时会产出未签名的 APK（装不上）。

### 注意：Android 17 平台是 minor 版本

Android 17 的平台包解出来是 `platforms/android-37.0`（不是 `android-37`），所以
`app/build.gradle.kts` 里除了 `compileSdk = 37` 还必须写 `compileSdkMinor = 0`，
否则 AGP 会去找不存在的 `android-37` 目录而报
`Failed to find target with hash string 'android-37'`。

## 五、验证

对照 LSPosed 日志（TAG `AssistRestore`）逐条确认：

| 项 | 日志关键字 |
| --- | --- |
| Hook 装载 | `hook_installed` / `hook_failed` / `hook_unsupported` |
| 助理管线解析 | `assist_pipeline_resolved startAssistInternalArgs=4 needsContext=true` |
| 电源键 | `power_key_long_press startSource=1024` → `assist_dispatch invocationType=6` |
| 手势条长按 | `gesture_handle_long_press invocationType=5` |
| 滑动唤醒 | `assistant_availability available=true` → `assist_dispatch invocationType=1` |
| 隐藏手势条 | `hidden_gesture_bar_handle_unblocked` |
| 即圈即搜 | `circle_to_search_triggered` |

预期在 ColorOS 17 上看到：

- `hook_unsupported target=com.android.systemui.shared.system.QuickStepContract.isAssistantGestureDisabled`
  —— **正常**，该挂载点已被平台删除
- `assist_pipeline_resolved ... needsContext=true` —— 正常，ColorOS 17 的四参签名

## 六、已知限制

- **「解除页面级手势限制」在 ColorOS 17 上不可用**（挂载点被平台删除，桌面侧改用
  `OplusCuiInputConsumer`），高级页会显示为「不支持」。
- 系统的语音交互会话同一时刻只认一个助理应用（平台约束）。
- 页面级放开只覆盖应用可请求的那两个屏蔽位；锁屏、通知栏、QS 展开、导航栏隐藏和屏幕固定仍然保持屏蔽。
- **同一手势不要和其它接管类模块**（例如 Oplus-Assistant-Hook）同时启用，先接管的一方会直接返回。

## 七、致谢与许可

- 上游项目：[`Andrea-lyz/ColorOS-Assist-Restore`](https://github.com/Andrea-lyz/ColorOS-Assist-Restore) v1.0.4
- 本分支仅为个人使用与学习目的，未附许可证。若需分发请先与上游作者确认授权。

---
