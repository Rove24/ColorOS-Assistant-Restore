# ColorOS 唤语（AssistRestore）

> **⚠️ 本文档描述的是上游 v1.0.4 / ColorOS 16 的分析结论。**
> 本分支已适配 ColorOS 17，`ColorOS 17` 下的实测差异、Hook 目标映射与新的挂载点见
> [`ColorOS17-Hook映射表.md`](./ColorOS17-Hook映射表.md)。
>
> 已知本文档中**有一处机制描述是错的**：第 1 节称 `FeatureOption.isExpRegion()` 与
> `VENDOR_REGIONMARK` / `getRmRegionName()` 相关。ColorOS 17 上的实际实现是
> `!TextUtils.equals(SystemProperties.get("ro.oplus.image.system_ext.area", "domestic"), "domestic")`，
> 读的是 `ro.oplus.image.system_ext.area`，与 `regionmark` 无关。
> 本机该属性值为 `domestic`，因此 `isExpRegion()` 返回 `false`。

 LSPosed 模块，在 ColorOS 国内版系统上还原 AOSP 的默认数字助理行为：
长按电源键、长按手势条、屏幕底部左右角落内滑，都改为唤醒系统当前设置的默认助理应用
（`Settings.Secure.assistant` / `RoleManager.ROLE_ASSISTANT` 指向的应用），而不是固定唤醒小布助手。

模块只连接被 OEM 改写的那几个分派点，不替换 AOSP 的助理栈。

## 1. 证据基础

结论来自本机从设备只读拉取的框架产物与反编译结果：

| 产物 | 用途 |
| --- | --- |
| `services.jar`（`/system/framework`） | `com.android.server.policy.PhoneWindowManager` 的 `launchAssistAction` |
| `oplus-services.jar`（`/system/framework`） | `PhoneWindowManagerExtImpl.startSpeech`、`StrategyShutdown` |
| `系统界面_16.99.12_New.apk` | `AssistManager`、`NavBarUtils`、`SpeedChassistMainBusiness`、`LauncherProxyService` |
| `设置_16.1.0.apk` | `DefaultVoiceassistPreferenceController`（默认助理选择项） |
| `/my_region/etc/extension/com.oplus.oplus-feature.xml` | 第 35 行 `oplus.software.speech_assist_for_breeno` |

设备：ColorOS 16（`ro.build.version.oplusrom=V16.1.0`，机型 PJZ110）。
设备当前状态：`cmd role get-role-holders android.app.role.ASSISTANT` 已经是
`com.google.android.googlequicksearchbox`，`settings get secure assistant` 同样指向 GSA，
说明 Framework 侧的助理绑定完全健康，被拦掉的只是各入口的分派。

四个被分析的文件都与设备上实际安装的一致（md5 校验通过），因此 Hook 的方法签名就是运行时的签名：

| 文件 | 设备路径 | md5 |
| --- | --- | --- |
| SystemUI | `/system_ext/priv-app/SystemUI/SystemUI.apk` | `e249e527020ee80547dd8200179b0656` |
| OPlus framework | `/system/framework/oplus-services.jar` | `9658e6361030204c749d74d2fb7a5ae7` |
| AOSP framework | `/system/framework/services.jar` | `ce501d636dcee11e2bef77a2270eaead` |
| 桌面 | `/system_ext/priv-app/OplusLauncher/OplusLauncher.apk` | `e77c29b92cc2df3aece19d5dc46b45d2` |

区域背景：`ro.vendor.oplus.regionmark = CN`（`FeatureOption` 的 `isExpRegion()` 与
`VENDOR_REGIONMARK`/`getRmRegionName()` 相关，国内值即非 exp），因此下面两道 SystemUI 闸门在本机
确实都处于关闭状态，模块的补派发路径就是实际生效的路径。

### 1.1 长按电源键

`PhoneWindowManagerExtImpl.startSpeech(int deviceID, int startSource, long eventTime)` 是按键语义的唯一漏斗
（`OplusSpeechHandler` 的 `what = 1011` 消息、`startSource = 1024`）。方法内部：

- `mSpeechAsssistForBreeno = OplusFeatureConfigManager.hasFeature("oplus.software.speech_assist_for_breeno")`
  在国内固件为 `true`；
- 于是 `if (!mSpeechAsssistForBreeno && mhasGoogleAssistant)` 的 AOSP 分支（走
  `mBase.getWrapper().launchAssistAction(...)`）被跳过，直接构造显式 Intent 启动
  `com.heytap.speechassist` 的前台服务。

### 1.2 长按手势条

`SpeedChassistMainBusiness.onLongPressed()` 只做一件事：
`ActivityStartedHelper.startBreenoService(context, 91)`，即
`heytap.intent.action.ACTIVATE_SPEECH_ASSIST` + 写死的 `ComponentName`，全程不经过助理栈。

这个手势的开关链路在设置与 SystemUI 之间是断开的，读清楚才知道要不要额外处理：

| 环节 | 键 / 判定 | 设备当前值 |
| --- | --- | --- |
| 设置项 `gesture_side_wake_cui`（系统导航方式里那一行；标题由 `NavBarUtil.isSupportOcr()` 在两条静态字符串之间二选一） | 识屏受支持时写 `oplus_home_handle_wake_up_ocr_enable`，否则写 `oplus_gesture_handle_cui_enable`（secure，写入用户 -2） | `oplus_home_handle_wake_up_ocr_enable=1`、`oplus_gesture_handle_cui_enable=0` |
| 该设置项是否显示（`mGestureSideWakeCuiSwitchIsShow()`） | `isSupportOcr()` 或 `oplus.speechassist.main.type == 2` | `oplus.speechassist.main.type=2` |
| SystemUI 是否注册手势条长按（`SpeedChassistMainObserver`） | 全局键 `oplus.speechassist.main.type == 2` | 2，即已注册 |
| 长按后的动作 | 只把 `start_type=91` 交给小布服务，具体执行识屏还是语音由小布侧读上面的 OCR/CUI 键自行决定 | - |

也就是说：**设置里那个开关与 SystemUI 的手势注册没有关系**，SystemUI 全程不读这两个 OCR/CUI 键，它只看
`oplus.speechassist.main.type`，后者才是“长按手势条是否触发”的真正开关。所以模块启用后，长按手势条会直接
走到 `AssistManager` 并唤醒当前默认助理，不需要动设置里的开关；设置项那行文案是静态字符串资源
（两种变体由 `NavBarUtil.isSupportOcr()` 选择），不随默认助理变化，属于纯文案层面的差异。

模块另外补了一步：既然 SystemUI 不读那个开关，开关一旦被关掉就会“点了没反应”，所以
`SystemUiHooks.isHandleWakeSwitchOff()` 按设置的同一规则读这两个键，在“两个键都不是 1、且至少有一个是 0”
（用户显式关过）时不再派发助理，让开关保持有效；键未设置（-1）时维持原有行为。

注意：上面这段是 "SpeedChassistMainBusiness" 这条业务自身的逻辑，本机实测发现真正被注册并处理手势条长按的是
识屏业务（见第 8.1 节），模块两边都做了处理。

### 1.3 屏幕底部角落内滑

底角手势由桌面（Launcher/Quickstep）实现，SystemUI 只负责两件事：告诉桌面“助理是否可用”，
以及在自己这一侧真正启动助理。

- `LauncherProxyService.onNavigationModeChanged` 调用
  `UtilsStaticToolsExImpl.isAssistantAvailable(...)` → `NavBarUtils.isAssistantAvailable(Context,int,int)`，
  然后用 `ILauncherProxy.onAssistantAvailable(available, longPressHome)` 通知桌面。
  该方法第一行就是 `if (!FeatureOption.isExpRegion() || ...) return false;`，国内固件恒为 `false`，
  于是桌面永远不会启用底角滑动区域。
- 桌面在手势完成时回调 `ISystemUiProxy.startAssistant(Bundle)` →
  `NavBarHelper.startAssistant` → `AssistManager.startAssist(Bundle)`。
- `AssistManager.startAssist` 的整段派发都写在 `if (FeatureOption.isExpRegion() && ...)` 里面，
  国内固件下该方法记完日志就返回，**不会**调用 `startAssistInternal`。这也是电源键链路最后的断点：
  `launchAssistAction` → `SearchManager.launchAssist` → `SearchManagerService.launchAssist` →
  `StatusBarManagerService$1.startAssist` → `IStatusBar.startAssist` →
  SystemUI `CommandQueue.startAssist` → `AssistManager.startAssist`。
  中间这几跳都是 AOSP 原样转发（`services.jar` 中未找到 OEM 覆写），区域判断只出现在首尾两处。

桌面侧的开关逻辑已从设备上拉取的 `/system_ext/priv-app/OplusLauncher/OplusLauncher.apk` 确认，桌面没有
自己的区域闸门，完全依赖 SystemUI 送来的可用性：

- `com.android.quickstep.TouchInteractionService$TISBinder` 实现 `ILauncherProxy.onAssistantAvailable(available, longPressHome)`，
  收到后 `DeviceState.setAssistantAvailable(available)` + `notifyUpdateRegionForAssistantAndOneHanded()`；
- 该调用把 `isAssitantValid()` 交给
  `OplusOrientationTouchTransformerImpl.updateRegionForAssistantAndOneHanded(...)` 计算助理手势区域，
  `isAssitantValid()` 的定义是 `mAssistantAvailable && (!QuickStepContract.isAssistantGestureDisabled(flags) || ...)`，
  即**只由这一个标志决定**；
- 手势本身由 `com.android.quickstep.inputconsumers.AssistantInputConsumer` 处理，完成时经
  `SystemUiProxy.startAssistant` → `ISystemUiProxy.startAssistant` 回调 SystemUI。

另外，`NavBarUtils.isAssistantAvailable` 里的圈选搜索分支在设备上也不会干扰：`FeatureOption` 中
`CustomizeFeatureOption.sIsSupportCircleToSearch = isExpRegion() && hasSystemFeature("com.google.android.feature.CONTEXTUAL_SEARCH")`，
国内固件恒为 `false`，所以底角手势始终归助理使用。

### 1.4 隐藏手势条后底部中央长按失效

手势条视图本身不挂触摸监听：底部中央的触摸先由 `SideGestureDetector`（日志 TAG `NoBackGesture`）接收，
只有同时满足“落点在下手势区域 + `!NavBarUtils.isSideGestureBarHide()` + `!NavBarUtils.getTaskbarStatus()` +
该视图是 `OplusNavigationHandle`”时，才会调用 `OplusNavigationHandle.handleValidTouchEvent(event)`。
这段判定在 `SideGestureDetector` 里以两个已投递的 Runnable 各写了一份（ACTION_DOWN 分支与其它事件分支），
之后才是 `NavigationGestureDetector` 的 `onDown/onShowPress/onPreLongPress/onLongPress` →
`GestureHomeHandleEventController.onLongClick()`。

因此 `isSideGestureBarHide()` 为真时，底部中央的触摸根本不会进入手势条，长按无从发生。该方法的定义是：

```java
public static final boolean isSideGestureBarHide() {
    return isGestureSideMode() && SwipeSideGestureBarTypeObserver.INSTANCE.getSwipeSideGestureBarType() == 1;
}
```

- `isGestureSideMode()` = `getNavState() == 3`（`isGestureUpMode()` 则是 `== 2`，那一档的下手势条走
  `GestureUpGuideBarView` 那条链路，不受这两个判定影响）；
- `getSwipeSideGestureBarType()` 读 secure 键 `gesture_side_hide_bar_prevention_enable`（只接受 0/1）。
  这一项与“把手势条画出来还是藏起来”共用同一个标志——下面几处可见性判定读的就是它，所以它就是设置里
  “隐藏手势条”那一行写的键（键名到界面的对应关系没有在设置包 `设置_16.1.0.apk` 里逐字核对过）。

同一个标志还被用来把手势条藏起来：`NavigationBar.getBarLayoutParams()` 在
`isHideNavBarGestureMode()`（= `isGestureUpMode() || isSideGestureBarHide()`）为真且不是上滑手势模式时，
把整个导航栏窗口的 `layoutParams.alpha` 置 0；`OplusNavigationBarView.updateViewVisible$1()` 与
`OplusNavigationBarInflaterView.resizeLayout()` 也按它决定各子视图的可见性。此外 QS 特殊模式
（`OplusQSSpecialModeProvider.isSideGestureBarHide()`）与 `UtilsStaticToolsExImpl.canSamplingRegionMode()` 同样读它。
所以模块只改“触摸转发”这一处判定，不动标志本身、也不动窗口透明度。

设备日志（`log/log.txt`，8.3 复测，当时手势条可见）里同一条链路的证据是：

```
NoBackGesture-->gestureBar animation: gestureBarNotHide = true, isCorrectHomeHandle = true
NoBackGesture-->send down event to NavigationBarHandle
```

OxygenOS 在隐藏手势条后仍能在原位置长按呼出助理，缺的正是这道判定；模块只在 `SideGestureDetector`
自己的调用上把 `isSideGestureBarHide()` 回答为 `false`，其余调用方照旧读原值。

### 1.5 长按手势条时页面同时触发长按

国内固件的手势条不是可触摸窗口：`OplusNavigationHandle extends View` 且不处理触摸，窗口默认
`touchableRegion=<empty>`（设备实测），底部这一条的按压实际由页面（应用、桌面或输入法）接收，SystemUI 只是通过
gesture monitor 旁听后再决定是否交给手势条。于是就会出现“助理和页面长按同时发生”：页面自己的长按在 500ms 触发，
手势条要到 800ms 才判定（`NavigationGestureDetector`：SHOW_PRESS 200ms、onPreLongPress 300ms、LONG_PRESS 800ms），
页面必然先响。

模块把手势条自己的那一块交还给导航栏窗口：

- **触摸区域**：用 `ViewRootImpl.setTouchableRegion` 把窗口可触摸区设为“手势条那一列 × 底部手势区”，实测为
  x∈[480,960]、y∈[3080,3168]（窗口内坐标 x∈[480,960]、y∈[88,176]）。宽度取 `oplus` 包的
  `navigation_gesture_view_width`（实测 480px），高度取 SystemUI 的 `bottom_gesture_area_height`（88px，与
  `SideGestureDetector` 判定用的值一致），窗口尺寸取导航栏窗口自身，因此隐藏手势条、view 不参与布局时也算得出来。
- **窗口可见性**：隐藏手势条时 OEM 把窗口 alpha 置 0，WindowManager 会把完全透明的窗口从输入分发里剔除
  （`inputConfig=NOT_VISIBLE`），区域随之失效。模块在窗口参数生成（`NavigationBar.getBarLayoutParamsForRotation`）
  与实时开关（`OplusNavigationBarView.updateWindowAlpha`）两处把 0 改写为 0.01，窗口留在输入链路里，画面上依旧不可见。
- **例外**：输入法可见时主动置空区域（`handle_touch_region_skipped reason=ime_visible`），键盘底部那一排仍归键盘。

验证（PJZ110 / ColorOS 16，注入一次底部中央 1.8s 长按后读 `dumpsys input` 的 TouchStates）：手势条显示与隐藏两种状态下，
触摸目标都是 `NavigationBar_displayId_0`（`targetFlags=FOREGROUND`），应用窗口不在触摸列表里；同时模块日志照常出现
`hidden_gesture_bar_handle_unblocked` 与 `circle_to_search_triggered`，从手势条位置往上滑回桌面也仍然生效。

## 2. 模块实现

| 进程 | Hook 目标 | 作用 |
| --- | --- | --- |
| `system_server` | `PhoneWindowManagerExtImpl.startSpeech(int,int,long)` | 按 AOSP 分支调用 `PhoneWindowManager.launchAssistAction(null, deviceId, eventTime, invocationType, 1)`，并置位 `mSpeechLongPressHandled`；同时补回 OEM 在派发前的震动 `performHapticFeedback(0, "Speech - Long Press")` |
| `com.android.systemui` | `AssistManager.startAssist(Bundle)` | 先执行原方法；当区域闸门拦下请求（或 `isExpRegion()` 不可解析）时，用同一个 `componentName`/`isService` 调用 `startAssistInternal` 完成派发 |
| `com.android.systemui` | `NavBarUtils.isAssistantAvailable(Context,int,int)` | 用 AOSP 语义回答：可手势导航 + 已配置助理 + `assist_touch_gesture_enabled` 打开（默认值读框架 `config_assistTouchGestureEnabledDefault`） |
| `com.android.systemui` | `SpeedChassistMainBusiness.onLongPressed()` | 改为通过 `AssistManager.startAssist`（`invocation_type = 5`）派发 |
| `com.android.launcher` | `QuickStepContract.isAssistantGestureDisabled(long)` | 只保留屏幕固定/导航栏隐藏/锁屏/下拉/QS 的屏蔽，放开应用可请求的页面级标记（128/1024），让底角手势在设置等页面也能用 |
| `com.android.systemui` | `OplusOcrScreenServiceHandler.onLongPressed()` | 本机手势条长按的真正入口（震动 + 标志位 + 投递动作），改为在这里走助理派发 |
| `com.android.systemui` | `OplusOcrScreenServiceHandler.onPreLongPress()` | 长按前的识屏服务预绑定；直接跳过以避免白唤醒识屏服务（它同时是 handleLongPressAction 能被调用的前提，故派发改挂在 onLongPressed） |
| `com.android.systemui` | `NavBarUtils.isSideGestureBarHide()` | 仅当调用方是 `SideGestureDetector`（底部触摸转发判定）且手势条确实处于隐藏态时回答 `false`，让隐藏手势条后长按仍进入手势条；窗口透明、QS 特殊模式、截屏采样区域等其它调用方保持原值 |
| `com.android.systemui` | `OplusNavigationHandle.onLayout`、`NavigationBar.getBarLayoutParamsForRotation(int,WindowMetrics)`、`OplusNavigationBarView.updateWindowAlpha(int)` | 把导航栏窗口的可触摸区设为手势条自身那一条；隐藏手势条时把窗口 alpha 从 0 保持为 0.01，避免窗口被剔除出输入分发后页面又拿到这条区域、触发自己的长按 |

设计约束：

- 不整体翻转 `FeatureOption.isExpRegion()`。该判定被 SystemUI 大量功能复用（信号图标、运营商
  定制、面板行为等），整体置真会带来与助理无关的副作用；这里只重写“助理可用性”这一个结论，
  并在 `AssistManager.startAssist` 中读一次原闸门以决定是否需要补派发。
- 每个 Hook 单独 try/catch 并绑定 hook id，任一目标类缺失只记录日志，不影响其它 Hook。
- 全部使用 `ExceptionMode.PROTECTIVE`，异常由框架记录并放行原逻辑，不吞掉调用。
- 不区分“小布”与第三方助理：`Settings.Secure.assistant` 指向谁就唤醒谁，这正是 AOSP 语义。

## 3. 工程结构

```
LSP_AssistRestore/
├─ app/                             模块 APK（`com.github.rove24.assistrestore`）
│  ├─ src/main/java/io/github/andrea_lyz/assistrestore/
│  │  ├─ AssistRestoreModule.java   入口，按进程与包名路由
│  │  ├─ SystemUiHooks.java         SystemUI 三个 Hook
│  │  ├─ SystemServerHooks.java     system_server 电源键派发
│  │  ├─ CtsHooks.java              system_server 侧补齐 ContextualSearch 服务
│  │  ├─ LauncherHooks.java         桌面侧按页面放开底角手势
│  │  ├─ GoogleAppHooks.java        Google 应用进程内的机型伪装
│  │  └─ Refl.java                  反射小工具
│  ├─ src/main/kotlin/io/github/andrea_lyz/assistrestore/ui/
│  │  ├─ MainActivity.kt            设置界面的宿主 Activity
│  │  ├─ AssistRestoreApp.kt        五个页面：入口 / 唤醒目标 / 自定义 / 高级 / 诊断
│  │  ├─ AssistData.kt              读设备上的助理候选与当前默认助理
│  │  └─ Theme.kt                   Material 3 主题
│  ├─ src/main/res/values{,-night}/themes.xml   界面主题（日夜两套）
│  └─ src/main/resources/META-INF/xposed/{module.prop,java_init.list,scope.list}
├─ libxposed-api/                   compileOnly 用的 API 102 源码模块
│  ├─ src/api/java/                 随仓库提供的 API 102 源码（见 src/api/README.md）
│  └─ build.gradle.kts              sourceSets.java.srcDir 指向 src/api/java
└─ gradle/wrapper/                  Gradle 8.13 + AGP 8.13.2
```

`:libxposed-api` 以 `sourceSets.java.srcDir` 指向随仓库提供的 `src/api/java`（取自上游
`libxposed/api` 的 commit `79b75b4`，即 tag `102.0.0` 后三个提交，`XposedInterface.API_102 = 102`），
因此编译用的就是那份 API 102 源码本身，而不是下载的二进制；
`io.github.libxposed.annotation.SinceApi/InternalApi` 未随上游提供，用 `src/annotation/java` 下的
两个编译期注解补齐。API 始终是 `compileOnly`，不会进入 APK。

`scope.list` 是四项：`system`、`com.android.systemui`、`com.android.launcher` 与
`com.google.android.googlequicksearchbox`，分别对应电源键与区域闸门、手势链路、底角手势的页面级放开、
以及 Google 应用进程内的机型伪装。
`module.prop` 使用 `minApiVersion=102`、`targetApiVersion=102`、`exceptionMode=protective`、
`autoHotReload=false`。

界面部分是 Kotlin + Jetpack Compose（material3）写的，目前只做界面：列表里的助理候选与「当前默认
助理」是真从设备读的（`PackageManager` 查 `VoiceInteractionService` 与 `ACTION_ASSIST`，按包名
去重后取应用自己的图标和 label；默认助理读 `Settings.Secure.assistant`），开关和选择只改界面状态、
还没有写入任何配置，「三个入口各指定一个目标」那套逻辑尚未接入。构建带 `--offline`，所以 Compose /
AndroidX 的版本固定在 `app/build.gradle.kts` 的 `resolutionStrategy` 里，对应本机 Gradle 缓存中实际
存在的版本（Compose 运行时 1.10.5、lifecycle 2.9.4 等）；换机器构建前要先确认这些版本在目标缓存里。

## 4. 构建

```powershell
cd "D:\Users\Andrea-TB\Desktop\ColorOS Assistant\LSP_AssistRestore"
.\gradlew.bat --offline assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> 注意：本机 `GRADLE_USER_HOME=E:\Buildcache\gradle`，Gradle 需要写该目录；在受限沙盒里会因
> `gradle-8.13-bin.zip.lck` 访问被拒而失败，需在沙盒外运行。

## 5. 安装与验证

1. 安装 APK，在 LSPosed 中启用模块，作用域勾选 **系统框架（system）**、**系统界面（com.android.systemui）** 与 **桌面（com.android.launcher）**（桌面这一项是放开“按页面限制”新增的）。
2. 重启设备（`system_server` 的 Hook 在开机阶段装载，必须重启才生效）。仅重启 SystemUI 只够验证手势链路。
3. 确认默认助理：
   ```bash
   cmd role get-role-holders android.app.role.ASSISTANT
   settings get secure assistant
   settings put secure assist_touch_gesture_enabled 1
   ```

   参考值（已在设备上核实）：默认助理为 GSA，`assist_long_press_home_enabled=1`，
   `navigation_mode=2`（手势导航），`assist_touch_gesture_enabled` 未设置；
   `cmd overlay lookup android android:bool/config_assistTouchGestureEnabledDefault` 返回 `true`，
   即底角手势默认开启，**不需要**再执行 `settings put`。
4. 分别验证三条链路，并对照 LSPosed 日志（TAG `AssistRestore`）：
   - 长按电源键 → 日志 `power_key_long_press startSource=1024 ...` → `power_key_haptic effect=0 reason=Speech - Long Press` → `assist_dispatch component=...`；
   - 长按手势条 → 日志 `gesture_handle_long_press invocationType=5` → `assist_dispatch ...`；
   - 底部角落内滑 → 日志 `assistant_availability available=true ...` 之后出现 `assist_dispatch ...`。
  导航栏“长按手势指示条”这一项（设置键 `gesture_side_wake_cui`）在设备上的取值为
   `oplus_home_handle_wake_up_ocr_enable=1`、`oplus_gesture_handle_cui_enable=0`，注册键
   `oplus.speechassist.main.type=2`，即该手势当前处于开启状态；模块在该开关被显式关闭时会让手势不动作。

## 6. 已知限制

- **区域闸门的读取方式。** `AssistManager.startAssist` 的补派发以一次
  `FeatureOption.isExpRegion()` 调用为条件；若该静态方法在目标版本上不存在，模块会按“闸门关闭”处理，
  与国内固件行为一致。
- `startAssistInternal` 是 OEM 区域分支真正调用的下层方法，直接调用它会跳过
  `AssistManagerImpl.beforeStartAssistInternal` 里的锁屏设备检查与圈选搜索拦截。国内固件本就不会走到那里，
  差异仅出现在这些边缘场景。
- 模块不修改 `Settings.Secure.assistant`，也不安装助理应用；助理必须已经在系统“默认应用”里可选项中出现
  （例如已安装并支持 `VoiceInteractionService` / `ACTION_ASSIST`）。
- 未处理 3 秒长按关机、SOS 连按等电源键其它语义，这些路径不经过 `startSpeech`。
- 三条链路的静态证据已完整（含桌面侧与框架默认值），但**尚未在设备上安装运行**：模块的实际行为
  仍需要按第 5 节做一次设备验证。
- **隐藏手势条时的长按**：结论来自静态分析（`SideGestureDetector` + `NavBarUtils`）与设备日志里的
  `gestureBarNotHide` 判定，尚未在真机上打开“隐藏手势条”复测。验证时打开该开关后长按底部中央，日志应先出现
  `hidden_gesture_bar_handle_unblocked mode=...`，随后是原有的 `gesture_handle_long_press invocationType=5`。
  这条链只覆盖侧滑返回手势（`getNavState() == 3`）；上滑手势模式下的下手势条长按是另一条链路，未做改动。
- 设置里“长按手势指示条唤醒小布识屏”的**文案仍然是写死的静态字符串资源**（`NavBarUtil.isSupportOcr()` 决定用
  “小布识屏”还是非识屏那条），不会随默认助理变化。行为已由模块改为唤醒默认助理，但这一行文字需要在意的
  话有三条路：保持不动；给 `com.android.settings` 做 RRO 覆盖替换那两条字符串（一个独立 overlay APK，最干净，
  不依赖 Hook）；或者把 `com.android.settings` 加入模块作用域、Hook 该设置项按默认助理名动态改标题。后两者
  都还没有做。

## 7. 排错

| 现象 | 检查 |
| --- | --- |
| 日志里没有任何 `AssistRestore` 输出 | 模块是否启用；作用域是否包含 `system` 与 `com.android.systemui`；是否已重启 |
| 只有 SystemUI 的 Hook，电源键无效 | `system_server` 的 Hook 需要重启；确认 `hook_installed target=PhoneWindowManagerExtImpl.startSpeech` |
| `assist_dispatch_skipped reason=no_assistant_configured` | 未设置默认助理，先绑定 `android.app.role.ASSISTANT` |
| `assistant_availability available=false` | `assist_touch_gesture_enabled` 被设为 0，或当前非手势导航，或该版本真的启用了圈选搜索（国内固件不会） |
| `hook_failed ... NoSuchMethodException` | 目标类/方法签名随版本变化，按日志里的类名重新核对反编译结果 |
| `gesture_handle_long_press_skipped reason=nav_bar_switch_off` | 设置里“长按手势指示条”开关被显式关过（两个 OCR/CUI 键的值都是 0），模块按开关语义不派发 |
| `assist_skip reason=exp_region_active / lock_task_mode / launcher_override` | 派发被主动跳过，日志已给出原因；override 只在该类型被桌面占位时出现 |
| `gesture_handle_long_press_skipped reason=debounce` | 同一次手势被两处回调重复触发，模块已去重 |
| 隐藏手势条后底部中央长按没反应 | 看日志里有没有 `hidden_gesture_bar_handle_unblocked`：没有则可能是高级页“隐藏手势条时保持长按”被关闭、该入口选了“小布识屏/全部关闭”，或当前不是侧滑返回手势（`getNavState() == 3`） |
| `hidden_gesture_bar_handle_unblocked mode=...` | 正常：手势条隐藏时模块放开了长按转发，`mode` 为该入口当前配置 |
| 长按手势条时页面也触发自己的长按 | 读 `dumpsys input` 里 NavigationBar 窗口的 `touchableRegion` 与 `inputConfig`：区域应为手势条那一条、且不带 `NOT_VISIBLE`；模块日志对应 `handle_touch_region applied=...` 与 `handle_window_alpha=0.01` |
| `handle_touch_region_skipped reason=ime_visible` | 正常：输入法弹起时该区域交还键盘 |
| `handle_touch_region_skipped reason=not_owned` | 该入口选了「小布识屏」或「全部关闭」，模块不接管这一条区域 |
| `gesture_handle_ocr_preload_skipped` | 正常：本次长按由助理接管，已跳过识屏服务预绑定 |
| `assist_gesture_unblocked pageFlags=0x...` | 正常：该页面只设置了应用可请求的页面级标记，模块放开了底角手势 |
| `assist_gesture_keep_disabled flags=0x...` | 当前处于锁屏/密码界面、通知栏或 QS 展开、导航栏隐藏或屏幕固定，模块保持屏蔽 |
| 有 `assist_dispatch` 但屏幕没出现助理 | 派发链路正常，助理进程被冻结/回收（见第 8.2 节） |
| `power_key_haptic_failed ...` | 震动调用失败（不影响派发，仅日志提醒）；`power_key_haptic effect=0` 才是成功 |

## 8. 设备实测记录（2026-09-13）

真机验证暴露了两个问题，日志 `log/log.txt`（19:16–19:21）给出了明确证据。

### 8.1 长按手势条仍然唤醒小布识屏

日志里 `gesture_handle_long_press`（当时挂在 `SpeedChassistMainBusiness.onLongPressed`）一次都没触发，
而底部中央（x≈780~960）的长按对应的是：

```
NoBackGesture-->send down event to NavigationBarHandle
OcrScreenService-->getServiceIntent bundle: Bundle[{StartUpType=0}]
```

桌面侧的输入消费者也只在**底角**出现 `TYPE_...:TYPE_ASSISTANT:...`，底部长按只有 `TYPE_ONE_HANDED`。
反编译确认真实链路是 `GestureHomeHandleEventController.onLongClick()` → 监听器 `OplusOcrScreenBusiness` →
`OplusOcrScreenServiceHandler.onLongPressed()` → `handleLongPressAction()` → 识屏服务 `start()`。
模块在这一族类上挂两处 Hook：`onPreLongPress()` 直接跳过预绑定，派发改挂在 `onLongPressed()`（先 `chain.proceed()` 保留震动与标志位，再派发并去重）；`handleLongPressAction()` 只在识屏服务已连接时才会被调用，所以跳过预绑定后它必然空转，派发不能挂在那里。`SpeedChassistMainBusiness` 的 Hook 继续保留，
用于其它注册了该业务的版本；两者共用同一段派发与开关判断。

### 8.2 底角手势与电源键“一段时间后无反应”

同一份日志显示这与模块无关：**Google 应用被 ColorOS 冻结/回收，语音交互绑定随之失效**。

```
19:21:04  power_key_long_press + assist_dispatch（派发正常）→ 没有任何 GSA 界面被创建
19:21:09  assist_dispatch invocationType=1          → 同样没有 GSA 界面
19:21:19  ActivityManager: Killing 29305:...:interactor (adj 100): permissions revoked
19:21:19  VoiceInteractionServiceManager: onBindingDied to ...GsaVoiceInteractionService
19:21:19  OplusHansManager: uid=10413 ... F exit(), F stay=51   ← 此前一直处于冻结态
19:21:21  am_proc_start ...GsaVoiceInteractionService（bindService）；GsaVoiceInteractionSrv: onReady
19:21:24  power_key_long_press + assist_dispatch → wm_create_activity ...FloatyActivity ✔
19:21:26  assist_dispatch invocationType=1        → wm_create_activity ...FloatyActivity ✔
```

结论：模块的派发每次都被调用（`assist_dispatch` 全部有记录），但助理进程被冻结时
`GsaVoiceInteractionService` 无法响应，会话不会出现；重新选择默认助理会重新绑定并启动该服务，
于是“有效一段时间”，随后再次被冻结/回收就“无反应”。20:12 前后的日志里还能看到 PermissionController
对 `SEND_SMS`/`READ_CALL_LOG`/`READ_SMS` 的撤销（`sysui_multi_action`）紧跟 `am_kill`，
这是 ColorOS 的权限回收/后台冻结机制，不是 LSPosed 层面能改的。

可操作的处理（都在系统/应用侧）：

1. 设置 → 电池/省电（或“手机管家 → 后台冻结/智能省电”）里把 **Google 应用** 设为允许后台运行、不冻结；
2. 设置 → 权限与隐私 → 权限管理里关闭对该应用的**权限自动回收**；
3. 验证前先手动打开一次 Google 助理，确认进程已解冻，再试长按电源与底角。

模块为排错新增了原因日志：`assist_skip reason=exp_region_active|lock_task_mode|launcher_override`、
`gesture_handle_long_press_skipped reason=debounce|nav_bar_switch_off`。下次抓日志时，如果是
“有 `assist_dispatch` 但系统里没有助理界面”，就说明卡在助理进程本身，而不是派发链路。


### 8.3 复测结果（2026-09-13 19:36–19:38）

重装并重启后复测，三条入口全部走到助理，9 次 `assist_dispatch` 与 9 次 GSA `FloatyActivity` 创建一一对应：

| 时间 | 入口 | 模块日志 | 结果 |
| --- | --- | --- | --- |
| 19:37:40.317 | 手势条长按 | `gesture_handle_long_press invocationType=5` | GSA 创建 19:37:40.469 ✔ |
| 19:37:44.041 | 滑动唤醒 | `assist_dispatch invocationType=1` | GSA 创建 19:37:44.106 ✔ |
| 19:37:47.415 | 长按电源键 | `power_key_long_press startSource=1024` → `invocationType=6` | GSA 创建 19:37:47.467 ✔ |
| 19:37:53.582 | 滑动唤醒 | `assist_dispatch invocationType=1` | GSA 创建 19:37:53.653 ✔ |
| 19:37:57.408 | 手势条长按 | `gesture_handle_long_press invocationType=5` | GSA 创建 19:37:57.462 ✔ |
| 19:38:00.898 | 手势条长按 | `gesture_handle_long_press invocationType=5` | GSA 创建 19:38:00.969 ✔ |
| 19:38:04.520 | 滑动唤醒 | `assist_dispatch invocationType=1` | GSA 创建 19:38:04.581 ✔ |
| 19:38:09.577 | 长按电源键 | `power_key_long_press startSource=1024` → `invocationType=6` | GSA 创建 19:38:09.635 ✔ |
| 19:38:12.882 | 手势条长按 | `gesture_handle_long_press invocationType=5` | GSA 创建 19:38:12.930 ✔ |

同一份日志里没有再出现 `assist_skip`、`gesture_handle_long_press_skipped`、GSA 的 `am_kill` / `onBindingDied`，
8.2 的冻结/回收现象在这段时间内没有复现（重启后进程处于活动状态）。

这一点已在该版本处理：曾经残留的是 `OcrScreenService-->getServiceIntent`（长按刚按下时的识屏服务预绑定，由
`OplusOcrScreenServiceHandler.onPreLongPress()` 发起），它虽然不会启动识屏（日志里 `assistantscreen` 没有任何可见性变化），但会白唤醒一次识屏服务。
现在 `onPreLongPress()` 也加了 Hook：无条件跳过预绑定，日志出现
`gesture_handle_ocr_preload_skipped`，`OcrScreenService-->getServiceIntent` 不再出现；
而在模块不接管的路径（开关关闭、或助理管线解析失败）仍然保留 OEM 的原始预绑定行为。

### 8.4 按页面解除底角手势限制

桌面判断底角手势能不能用，靠的是 `com.android.systemui.shared.system.QuickStepContract.isAssistantGestureDisabled(long)`，本机掩码为 `3083`：

| 位 | 常量 | 含义 | 模块处理 |
| --- | --- | --- | --- |
| 1 | SYSUI_STATE_SCREEN_PINNING | 屏幕固定 | 保持屏蔽 |
| 2 | SYSUI_STATE_NAV_BAR_HIDDEN | 导航栏隐藏 | 保持屏蔽（ALLOW_GESTURE 置位时按原逻辑忽略该位） |
| 8 | SYSUI_STATE_BOUNCER_SHOWING | 锁屏密码界面 | 保持屏蔽 |
| 128 | SYSUI_STATE_OVERVIEW_DISABLED | **由前台应用请求** | 放开 |
| 1024 | SYSUI_STATE_SEARCH_DISABLED | **由前台应用请求** | 放开 |
| 2048 | SYSUI_STATE_QUICK_SETTINGS_EXPANDED | QS 展开 | 保持屏蔽 |
| 4（且 64 未置位） | SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED | 通知栏展开且不在锁屏 | 保持屏蔽 |

128 与 1024 是前台应用通过 `StatusBarManager.disable()` 一类接口请求的，也正是“某些页面（如设置里的部分页面）底角手势没反应”的来源；这两个位与手势本身没有冲突，所以模块只清零它们，其余状态照旧屏蔽。
对应日志：放开时打 `assist_gesture_unblocked pageFlags=0x...`，保持屏蔽时打 `assist_gesture_keep_disabled flags=0x...`（该行会带上完整 flags，便于下次核对是哪一位在起作用）。
注意这一项需要把 `com.android.launcher` 加入模块作用域；电源键链路不受页面影响（`StrategyIngoreKeyInFocusedWindow` 只拦 HOME/MENU，不拦电源键）。

### 8.5 手势条长按改为 Circle to Search（即圈即搜）

CN 固件的 SystemUI 里 CTS 那套是空实现（`OplusCircleToSearchManagerEx.interceptStartAssistInternal()` 恒返回 false、Impl 为空类），
所以不靠助理路由，而是补上系统服务链路。做法参考了 `E:\\我开发的模块\\Gemini2\\Oplus-Assistant-Hook` 的实现思路：

| 层 | 进程 | 模块动作 |
| --- | --- | --- |
| 框架 CTS 服务 | `system` | `SystemServer.deviceHasConfigString()` 对 `config_defaultContextualSearchPackageName` 强制 true；`ContextualSearchManagerService.getContextualSearchPackageName()` 返回 Google 包名；`enforcePermission()` 仅对 system 与 SystemUI 放行；`startContextualSearch(int)` 对可信调用方清空调用身份 |
| Google 应用身份 | `com.google.android.googlequicksearchbox` | 进程内把 `Build.MANUFACTURER/BRAND/MODEL/PRODUCT/DEVICE` 伪装为 Samsung SM-S928B（e3s），解锁 GSA 侧即圈即搜 |
| 手势触发 | `com.android.systemui` | 手势条长按**先看默认助理是不是 Google 应用**：是则调 `contextual_search` 服务的 `IContextualSearchManager.startContextualSearch(2)`；不是（例如设成小布）或服务不可用时，改为**派发给当前默认助理** |

作用域因此新增一项：`com.google.android.googlequicksearchbox`（见 `scope.list`）。

日志关键字：`cts_device_has_config_string forced=true`、`cts_package_name`、`cts_enforce_permission`、
`cts_start_contextual_search entrypoint=2`、`google_app_identity_spoofed`、`circle_to_search_triggered`、`gesture_handle_assistant component=... circleToSearch=true|false`（后者说明该次手势跟随的是默认助理还是 Google 的即圈即搜）；
回落到助理时会打 `circle_to_search_unavailable` 或 `circle_to_search_failed`。

与 Oplus-Assistant-Hook 的关系：两边都做同一件事，**同一手势不要同时启用**——如果启用对方模块的手势条或电源键接管（它挂在 `OplusOcrScreenBusiness.onLongPressed` / `OplusSpeechHandler.handleMessage` 等更外层），它会先接管并直接返回，本模块对应 Hook 就不会执行。

## 9. English summary

ColorOS 唤语 (AssistRestore) is a minimal libxposed API 102 module that restores AOSP default-assistant behaviour on
China-region ColorOS builds. The build keeps the AOSP assistant stack intact and only diverts each
entry point, so the module reconnects those four diversion points: the power-key funnel
(`PhoneWindowManagerExtImpl.startSpeech` in `system_server`), the gesture-handle long press
(`SpeedChassistMainBusiness.onLongPressed`), the corner-swipe availability signal
(`NavBarUtils.isAssistantAvailable`), and the region-gated `AssistManager.startAssist`, which
currently drops every assist request on a China build. Scope is limited to `system` and
`com.android.systemui`. The launcher side was verified from the device's OplusLauncher APK: it carries
no region gate of its own and enables the corner gesture purely from the availability flag SystemUI
sends, and the framework default for that gesture resolves to true on this build. Installing and
running the module on the device is still the remaining validation step.

A fourth, narrower gate sits in `SideGestureDetector`: the bottom-area motion events are only
handed to the gesture handle while `NavBarUtils.isSideGestureBarHide()` is false, so hiding the
gesture bar also removes the handle from the touch chain and the long press at that position does
nothing. The module answers that one call - and only the one made by the detector, leaving the
flag's other readers such as the transparent navigation-bar window untouched - with `false`, which
is the behaviour OxygenOS keeps. It can be turned off on the advanced page.
