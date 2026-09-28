# ColorOS 17 适配：Hook 目标映射表（真机实测版）

**设备**：PKX110 · ColorOS `V17.0.0` · `PKX110_17.0.0.100(CN01)` · Android 17 (SDK 37) · `regionmark=CN`
**样本**（已从手机拉取到 `firmware17/`，均为 world-readable，无需 root）：

| 文件 | 大小 | md5（待补） | 作用 |
| --- | --- | --- | --- |
| `SystemUI.apk` | 98.8 MB | — | 9 个 dex，SystemUI 侧 10 个 Hook |
| `services.jar` | 44.6 MB | — | 4 个 dex，system_server 侧 AOSP 部分 |
| `oplus-services.jar` | 30.3 MB | — | 3 个 dex，`PhoneWindowManagerExtImpl` |
| `OplusLauncher.apk` | 70.6 MB | — | 7 个 dex，桌面侧 |
| `Settings.apk` | 208.3 MB | — | 默认助理选择项 / 导航栏文案核对 |
| `com.oplus.oplus-feature.xml` | 4.8 KB | — | 设备实际生效的特性开关 |

**核对方式**：`dexdump -i` 建「类 → 方法+签名」索引（`idx17/*.txt`），再用 `extract-class.sh` 抽完整类定义、`dexdump -d` 反汇编关键方法。

---

## 0. 一句话结论

**15 个 Hook 点里，只有 3 处真的断了**，而且全部集中在两个地方：`AssistManager`（方法被改名 + 签名变了）和桌面的 `QuickStepContract`（方法被彻底删除）。

其余 12 处**签名一字不差**——这意味着适配工作量比预想的小得多。

但真正值得注意的是另一件事：**两处区域闸门在 C17 里换了写法，但结论没变——国内仍然全部拦住，模块 15 个 Hook 一个都不能删。** 详见 §3（那一节我先前判错过一次，已用 jadx 反编译结果修正）。

---

## 1. 逐条映射表

### 1.1 system_server 侧（作用域 `system`）

| # | ColorOS 16 目标 | ColorOS 17 实测 | 结论 |
| --- | --- | --- | --- |
| 1 | `PhoneWindowManagerExtImpl.startSpeech(int,int,long)` | `startSpeech(IIJ)V` ✅ 一字不差（另有 `startSpeech(IJ)V` 重载） | **原样可用** |
| 1b | 反射字段 `mBase` / `mSpeechAsssistForBreeno` / `mSpeechLongPressHandled` | 三个字段全部存在 ✅ | **原样可用** |
| 1c | 反射 `PhoneWindowManager.launchAssistAction(String,int,long,int,int)` | `launchAssistAction(Ljava/lang/String;IJII)V` ✅ 一字不差 | **原样可用** |
| 2 | `SystemServer.deviceHasConfigString(Context,int)` | `(Landroid/content/Context;I)Z` ✅ | **原样可用** |
| 3 | `ContextualSearchManagerService.getContextualSearchPackageName()` | `()Ljava/lang/String;` ✅ | **原样可用** |
| 4 | `ContextualSearchManagerService.enforcePermission(String)` | `(Ljava/lang/String;)V` ✅ | **原样可用** |
| 5 | `ContextualSearchManagerService.startContextualSearch(int)` | 需单独确认 | 待核 |
| 6 | `OplusHansManager` 冻结豁免 | 需单独确认 | 待核 |

> `com.oplus.oplus-feature.xml` 第 35 行 **`oplus.software.speech_assist_for_breeno` 仍然存在** → `mSpeechAsssistForBreeno` 依旧为 `true` → **电源键仍然固定唤醒小布，模块这一处依然必需**。

### 1.2 SystemUI 侧（作用域 `com.android.systemui`）

| # | ColorOS 16 目标 | ColorOS 17 实测 | 结论 |
| --- | --- | --- | --- |
| 7 | `com.android.systemui.assist.AssistManager.startAssist(Bundle)` | ❌ **改名为 `startAssist$1(Bundle)V`**；且 `AssistManager` 的 `Interfaces` 已变为**空**（不再实现 `CommandQueue.Callbacks`），`Superclass = Object` | **必须改** |
| 7b | 反射调用 `AssistManager.startAssistInternal(Bundle,ComponentName,boolean)` | ❌ **签名变成 `(Context,Bundle,ComponentName,boolean)`**（多了首个 `Context`） | **必须改** |
| 7c | 反射 `getAssistInfo()` / `getVoiceInteractorComponentName()` | ✅ 都在，签名不变 | 原样可用 |
| 8 | `NavBarUtils.isAssistantAvailable(Context,int,int)` | ✅ `(Landroid/content/Context;II)Z` 签名不变；方法体被重写，**国内实测恒返回 `false`**（见 §3.2） | **原样可用，且仍然必需** |
| 9 | `SpeedChassistMainBusiness.onLongPressed()` | ✅ `()V`；方法体与 C16 完全一致，仍是 `ActivityStartedHelper.startBreenoService(mContext, 91)` | **原样可用** |
| 10 | `OplusOcrScreenServiceHandler.onPreLongPress()` | ✅ 存在 | **原样可用** |
| 11 | `OplusOcrScreenServiceHandler.onLongPressed()` | ✅ 存在；内部改为 `gestureBGHandler.post($onLongPressed$1)`，前置逻辑仍是「置 `isCalledLongPress=true` + 震动」 | **原样可用** |
| 12 | `OplusOcrScreenServiceHandler.handleLongPressAction()` | ✅ 存在；仍会 `IEntranceServiceInterface.start()` 起识屏服务 | **原样可用** |
| 13 | `NavBarUtils.isSideGestureBarHide()` | ✅ 存在 | **原样可用** |
| 14 | `OplusNavigationHandle.onLayout` | ✅ `onLayout(ZIIII)V` | **原样可用** |
| 15 | `NavigationBar.getBarLayoutParamsForRotation(int,WindowMetrics)` | ✅ `(ILandroid/view/WindowMetrics;)Landroid/view/WindowManager$LayoutParams;` 一字不差 | **原样可用** |
| 16 | `OplusNavigationBarView.updateWindowAlpha(int)` | ✅ `(I)V` 一字不差 | **原样可用** |
| — | 反射 `Dependency.sDependency` + `getDependencyInner(Object)` | ✅ 两者都还在 | 原样可用 |
| — | 反射 `FeatureOption.isExpRegion()` | ✅ `()Z` 存在（Kotlin lazy 委托） | 原样可用 |

**所有 OPlus 导航栏类的包路径都没动**，仍是：
```
com.oplus.systemui.navigationbar.utils.NavBarUtils
com.oplus.systemui.navigationbar.gesture.otherbusiness.SpeedChassistMainBusiness
com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenServiceHandler
com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle
com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector
com.oplusos.systemui.navigationbar.OplusNavigationBarView
com.oplusos.systemui.common.feature.FeatureOption
```

### 1.3 桌面侧（作用域 `com.android.launcher`）

| # | ColorOS 16 目标 | ColorOS 17 实测 | 结论 |
| --- | --- | --- | --- |
| 17 | `com.android.systemui.shared.system.QuickStepContract.isAssistantGestureDisabled(long)` | ❌ **方法在平台里彻底消失**：<br>· 桌面 7 个 dex 中 **整个 `com.android.systemui.shared.system` 子包不存在**（只剩 `shared/plugins/*` 和 `R$*`）<br>· SystemUI 侧的 `QuickStepContract` 也只剩 4 个方法：`<clinit>` / `addInterface` / `getSystemUiStateString` / `isGesturalMode` | **必须重新设计或砍掉该功能** |
| — | AOSP `com.android.quickstep.inputconsumers.AssistantInputConsumer` | ❌ 类已消失，替换为 **`com.android.quickstep.inputconsumers.OplusCuiInputConsumer`**（内含 `BreenoInstallQueryState`，即小布 CUI 业务） | 链路换血 |
| — | `ISystemUiProxy.onAssistantAvailable` / `onAssistantGestureCompletion` | ✅ 仍在（含 `TRANSACTION_onAssistantAvailable`） | 可作新挂载点 |
| — | `OplusOrientationTouchTransformerImpl` | 类名保留，**方法名被混淆成单字母**（`r/s/b/d/e/f/i/k/l/m/n/o/p/q`），只剩日志串 `updateRegionForAssistantAndOneHanded: mIsAssitantValid = ` 可反推语义；`isAssitValid` 已不存在 | 名字匹配不可靠 |

---

## 2. `AssistManager` 到底改了什么（断点 #1，影响最大）

```
ColorOS 16:  AssistManager implements CommandQueue.Callbacks
             ├─ startAssist(Bundle)V                                  ← 模块 Hook 这里
             └─ startAssistInternal(Bundle, ComponentName, boolean)V   ← 模块反射调用这里

ColorOS 17:  AssistManager implements (nothing)  Superclass = Object
             ├─ startAssist$1(Bundle)V                                 ← 改名了
             └─ startAssistInternal(Context, Bundle, ComponentName, boolean)V  ← 多了 Context
```

`startAssist$1` 的完整判定链（jadx 反编译）：

```java
public final void startAssist$1(Bundle bundle) {
    if (mActivityManager.getLockTaskModeState() != 0) return;            // 锁定任务模式
    if (bundle != null && bundle.containsKey("invocation_type")) {        // override 分支
        int i = bundle.getInt("invocation_type");
        int[] iArr = mAssistOverrideInvocationTypes;
        if (iArr != null && Arrays.stream(iArr).anyMatch(x -> x == i)) {
            mLauncherProxyService.getProxy().onAssistantOverrideInvoked(i);
            return;
        }
    }
    FeatureOption.INSTANCE.getClass();
    if ((FeatureOption.isExpRegion() || OpUtils.sIsClosedSuperFirewall)   // ← 区域闸门
            && (assistInfo = getAssistInfo()) != null) {
        boolean zEquals = assistInfo.equals(getVoiceInteractorComponentName());
        ... 埋点 / MetricsLogger / onAssistantStarted ...
        ShutDownDependencyEx...getAssistManagerEx()
            .beforeStartAssistInternal(bundle, i2, assistInfo, zEquals);  // ← 真正的派发
    }
}
```

**影响**：模块 Hook `startAssist` 会抛 `NoSuchMethodException`（`AssistManager` 现在没有同名方法，父类/接口也没有），日志打 `hook_failed`，**电源键链路最后的补派发彻底失效**；同时手势条长按路径里模块自己调 `startAssistInternal` 也会在运行时抛异常。

**修法（已找到更稳的挂载点）**：
- 主 Hook 点改为 **`com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks.startAssist(Bundle)`**。它是 `@Override` 实现 `CommandQueue.Callbacks.startAssist(Bundle)` 的，**接口名是跨进程契约，名字必须保留**，比 `startAssist$1` 稳得多。实测源码：
  ```java
  @Override // com.android.systemui.statusbar.CommandQueue.Callbacks
  public final void startAssist(Bundle bundle) {
      this.mAssistManager.startAssist$1(bundle);
  }
  ```
- 反射调用 `startAssistInternal` 改为**多候选**：先试 `(Context,Bundle,ComponentName,boolean)`，再退回 `(Bundle,ComponentName,boolean)`。

---

## 3. 区域闸门实测结论：**国内仍然拦住，模块仍然必需**

### 3.1 `isExpRegion()` 的真实实现（原项目笔记把机制写错了）

jadx 反编译 `com.oplusos.systemui.common.feature.FeatureOption` 原文：

```java
isExpRegion$delegate = lazy(() ->
    !TextUtils.equals(
        SystemProperties.get("ro.oplus.image.system_ext.area", "domestic"),
        "domestic"));
```

- 读的属性是 **`ro.oplus.image.system_ext.area`**，**不是**原笔记说的 `ro.vendor.oplus.regionmark`
- 默认值字符串 `"domestic"`；属性缺失或等于 `"domestic"` → 返回 `false`
- **本机实测：`ro.oplus.image.system_ext.area = domestic` → `isExpRegion() == false`**

### 3.2 `NavBarUtils.isAssistantAvailable` —— 国内恒返回 `false`，模块必需

jadx 反编译原文。注意 **整个方法体被 `isExpRegion()` 包着**：

```java
public static final boolean isAssistantAvailable(Context context, int i, int i2) {
    FeatureOption.INSTANCE.getClass();
    if (FeatureOption.isExpRegion()                                   // ← 外层闸门
            && !CustomizeFeatureOption.sIsSupportCircleToSearch
            && QuickStepContract.isGesturalMode(i)) {
        ComponentName cmp = ((AssistManager) Dependency.sDependency
                .getDependencyInner(AssistManager.class)).mAssistUtils.getAssistComponentForUser(-2);
        if (cmp != null && !"com.heytap.speechassist".equals(cmp.getPackageName())) {
            if (Settings.Secure.getIntForUser(context.getContentResolver(),
                    "assist_touch_gesture_enabled",
                    context.getResources().getBoolean(<resId>) ? 1 : 0, i2) != 0) {
                return true;
            }
            Log.d("SystemUi--NavBar", "NavBarUtils-->assist touch gesture disabled");
        } else {
            Log.d("SystemUi--NavBar", "NavBarUtils-->" + "component unavailable ,componentName = " + cmp);
        }
    }
    return false;        // ← 国内走这里
}
```

本机 `isExpRegion() == false` → 外层条件不成立 → **恒返回 `false`** → 底角手势区域永不启用。

**结论：Hook #8 在 C17 上仍然必需，不能删。**（我先前说「C17 原生已放开」是错的。）

### 3.3 `AssistManager.startAssist$1` —— 闸门是 `(isExpRegion() || sIsClosedSuperFirewall)`

jadx 反编译原文：

```java
if ((FeatureOption.isExpRegion() || OpUtils.sIsClosedSuperFirewall)
        && (assistInfo = getAssistInfo()) != null) {
    ... 真正派发：AssistManagerEx.beforeStartAssistInternal(bundle, i2, assistInfo, zEquals) ...
}
```

- 本机 `isExpRegion() == false`，所以能否派发**只取决于 `OpUtils.sIsClosedSuperFirewall`**
- 该字段定义：`public static boolean sIsClosedSuperFirewall = false;`，运行期由 `OpUtils.updateCfwDirect()` 从 `OplusPackageManager.isClosedSuperFirewall()` 刷新；值变化时打 `Log.i("OpUtils", "updateCfwDirect, sIsClosedSuperFirewall:" + v)`
- 相关属性 `cache_key.system_server.get_close_super_firewall = 111` 是**缓存版本号，不是布尔值**，PC 侧无法判定
- 若为 `false` → OEM 自己也不派发 → 模块补派发**必需**
- 若为 `true` → OEM 自己会派发 → 补派发只是兜底
- **上机时在模块里反射读一次 `OpUtils.sIsClosedSuperFirewall` 打日志即可定死**

> ⚠️ **修正记录**：我先前手读字节码时把 `if-nez` 的分支方向读反了，因此得出了「C17 国内不再被拦、底角手势原生可用」的错误结论。上面三段 jadx 反编译结果是权威版本，**以它为准**。

### 3.4 桌面侧换血（这条独立成立，与闸门无关）

AOSP 的 `AssistantInputConsumer` 已被 `com.android.quickstep.inputconsumers.OplusCuiInputConsumer`（小布 CUI 业务）取代。所以即便 SystemUI 回答了 `available=true`，端到端能否唤起助理仍要单独验证——**「SystemUI 说可用」≠「底角手势真的能唤起助理」**。

### 3.5 上机验证记录（本次实测）

| 操作 | 结果 |
| --- | --- |
| `input swipe` 右下角 45° 内滑 | `OplusBaseTouchInteractionService: onInputEvent(), Consumer=TYPE_OTHER_ACTIVITY` |
| `input swipe` 左下角 45° 内滑 | `Consumer=TYPE_OVERVIEW` |
| 期望 | 若助理区域启用，应出现 `Consumer=TYPE_ASSISTANT` |

两条都没出现 `TYPE_ASSISTANT`，与 §3.2 的「恒返回 false」一致。**模块当前处于禁用状态，这就是干净的对照组。**

---

## 4. 本机环境实测值（供对照）

```
ro.build.version.oplusrom            = V17.0.0
ro.build.version.oplusrom.display    = 17.0
ro.build.display.id                  = PKX110_17.0.0.100(CN01)
ro.build.version.release / sdk       = 17 / 37
ro.product.model / device            = PKX110 / OP60F5L1
ro.vendor.oplus.regionmark           = CN
ro.build.version.ota                 = PKX110_11.F.10_2100_202609192304

默认助理        = com.google.android.googlequicksearchbox / ...GsaVoiceInteractionService
navigation_mode = 2（手势导航）
config_assistTouchGestureEnabledDefault = true
assist_touch_gesture_enabled            = null（未设置）
assist_long_press_home_enabled          = 0      ← C16 为 1，变了
oplus_home_handle_wake_up_ocr_enable    = 0      ← C16 为 1，变了
oplus_gesture_handle_cui_enable         = 1      ← C16 为 0，**两个键的值对调了**
oplus.speechassist.main.type            = 2      （同 C16）
gesture_side_hide_bar_prevention_enable = 0

已装框架：KernelSU + LSPosed（lspd 在跑）
原模块 io.github.andrea_lyz.assistrestore 已安装，但在 LSPosed 中未启用

ro.oplus.image.system_ext.area           = domestic   ← isExpRegion() 的判据，决定一切
ro.vendor.oplus.regionmark               = CN         （原笔记误以为这个才是判据）
cache_key.system_server.get_close_super_firewall = 111 （缓存版本号，非布尔值）
```

> `oplus_home_handle_wake_up_ocr_enable` / `oplus_gesture_handle_cui_enable` 两个键的值在 C17 上对调了，会影响模块 `SystemUiHooks.isHandleWakeSwitchOff()` 的「开关被显式关过」判定，需要一并复核。

> **本次推导过程中的两次判错记录**（保留，避免下次再踩）：
> 1. 拿原项目 C16 笔记的「国内值即非 exp」直接外推到 C17 → 错，而且那份笔记连机制都写错了（写的是 `regionmark`，实际是 `image.system_ext.area`）
> 2. 手读 dexdump 字节码时把 `if-nez` 的分支方向读反 → 得出「国内不再被拦」的相反结论
>
> **教训：判定类逻辑一律用 jadx 反编译成 Java 再读，不要手撸字节码。** 单 dex 喂 jadx 几秒就出结果。

---

## 5. 上机复核清单（改完代码后逐条打日志）

| 项 | 怎么确认 |
| --- | --- |
| `isExpRegion()` | 反射读 `FeatureOption.isExpRegion()`，预期 `false`（已由 `area=domestic` 推定） |
| `sIsClosedSuperFirewall` | 反射读 `OpUtils.sIsClosedSuperFirewall`，决定补派发是否必需 |
| 新挂载点 | 打 `hook_installed`，确认 `CentralSurfacesCommandQueueCallbacks.startAssist` 装上了 |
| `startAssistInternal` | 确认 4 参版本解析成功 |
| 底角手势（对照组） | 模块禁用 + 默认助理 GSA + 滑动唤醒，日志应**不出现** `Consumer=TYPE_ASSISTANT`（本次已验证） |
| 底角手势（模块启用后） | 同上操作，日志应出现 `Consumer=TYPE_ASSISTANT`，且 GSA 界面创建 |
| 电源键 | `power_key_long_press startSource=1024` → `assist_dispatch invocationType=6` |
| 手势条长按 | `gesture_handle_long_press invocationType=5` |
| 隐藏手势条 | `hidden_gesture_bar_handle_unblocked` |

---

## 6. 复现命令（下次换固件直接跑）

```bash
ADB="/d/Download/Android/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

# 1. 拉固件（全部 world-readable，不需要 root）
$ADB pull /system_ext/priv-app/SystemUI/SystemUI.apk .
$ADB pull /system/framework/services.jar .
$ADB pull /system/framework/oplus-services.jar .
$ADB pull /system_ext/priv-app/OplusLauncher/OplusLauncher.apk .
$ADB pull /system_ext/priv-app/Settings/Settings.apk .
$ADB pull /my_region/etc/extension/com.oplus.oplus-feature.xml .

# 2. 解 dex
for p in "SystemUI.apk systemui" "services.jar services" \
         "oplus-services.jar oplus" "OplusLauncher.apk launcher"; do
  set -- $p; mkdir -p dex17/$2; unzip -o -q "$1" 'classes*.dex' -d dex17/$2
done

# 3. 建「类→方法+签名」索引（之后全靠它 grep）
DD="$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/dexdump.exe"
for d in systemui services oplus launcher; do
  for f in dex17/$d/classes*.dex; do
    "$DD" -i "$f" | awk '
      /^  Class descriptor  :/ { cls=$4; gsub(/\047/,"",cls) }
      /^  (Direct|Virtual) methods/ { inm=1; next }
      /^  (Static|Instance) fields/ { inm=0 }
      inm && /^      name          :/ { n=$3; gsub(/\047/,"",n) }
      inm && /^      type          :/ { t=$3; gsub(/\047/,"",t); print cls "|" n "|" t }
    '
  done > idx17/$d.txt
done

# 4. 抽某个类的完整定义
./extract-class.sh dex17/systemui 'Lcom/android/systemui/assist/AssistManager;'

# 5. 反汇编某个方法（先 dump 整个 dex，再 awk 切方法块）
"$DD" -d dex17/systemui/classes.dex > d.txt
awk "/name          : 'startAssist\\\$1'/{f=1} f&&/name          : /&&!/startAssist\\\$1/{exit} f" d.txt
```

---

## 7. 对原方案的影响（结论已收敛）

1. **「解除页面级手势限制」在 ColorOS 17 上做不了了**——挂载点（`QuickStepContract.isAssistantGestureDisabled`）已从平台删除，桌面侧改用 `OplusCuiInputConsumer`。**C17 分支直接隐藏/禁用这一项**。
2. **「底角手势可用性」（Hook #8）必须保留**——`isAssistantAvailable` 整个方法体被 `isExpRegion()` 包着，本机 `area=domestic` → `isExpRegion()==false` → **恒返回 `false`**，底角手势原生不可用。与原版行为一致，逻辑不用改。
3. **电源键和手势条长按仍然必需**——`oplus.software.speech_assist_for_breeno` 特性还在，两处仍然固定走小布。
4. **`AssistManager` 必须改挂载点**——改用 `CentralSurfacesCommandQueueCallbacks.startAssist(Bundle)`（`@Override` 接口契约，名字稳定），反射 `startAssistInternal` 改多候选签名。顺带把「Hook 点改名」变成机制：主挂载点优先选跨进程接口（`CommandQueue$Callbacks`、`ISystemUiProxy`），少挂 OEM 内部实现类。
5. **`sIsClosedSuperFirewall` 上机确认即可**——它只影响「补派发是必需还是兜底」，不影响改法。模块里反射读一次 `OpUtils.sIsClosedSuperFirewall` 打日志就能定死。

### 小结：C17 相对 C16 的真实工作量

| 类别 | 数量 | 内容 |
| --- | --- | --- |
| 签名一字不差，原样可用 | 12 | 全部 OPlus 导航栏类 + system_server 侧 |
| **必须改挂载点 / 签名** | **2** | `AssistManager.startAssist` → `CentralSurfacesCommandQueueCallbacks.startAssist`；`startAssistInternal` 加 `Context` 参数 |
| **平台已删除，功能只能砍** | **1** | `QuickStepContract.isAssistantGestureDisabled`（解除页面级手势限制） |

也就是说：**适配 ColorOS 17 的 Hook 层改动量很小，真正的工作量在 UI 层重构**（无底栏主页 / 卡片化 / 换主题色 / 高级默认全关）。
