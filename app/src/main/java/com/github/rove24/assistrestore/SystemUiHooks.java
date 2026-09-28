package com.github.rove24.assistrestore;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI side restoration.
 *
 * <p>Verified against ColorOS 16 (SystemUI 16.99.12) and the OPlus framework jars pulled from the
 * device. Each hook repairs one diversion point instead of re-implementing the assistant stack:</p>
 *
 * <ol>
 *   <li>{@code AssistManager.startAssist} wraps its entire dispatch in
 *   {@code if (FeatureOption.isExpRegion() && ...)}, so a China build logs telemetry and returns
 *   without ever reaching {@code startAssistInternal}. The hook runs the original method and, when
 *   the region gate rejected the request, performs the same dispatch the OEM performs on an export
 *   build.</li>
 *   <li>{@code NavBarUtils.isAssistantAvailable} begins with {@code !FeatureOption.isExpRegion()},
 *   so {@code LauncherProxyService} always reports "assistant unavailable" and the launcher never
 *   enables the bottom-corner swipe region. The hook answers with AOSP semantics.</li>
 *   <li>{@code SpeedChassistMainBusiness.onLongPressed} starts the OEM assistant service directly,
 *   without touching the assist stack. The hook routes the gesture-handle long press into the
 *   assist stack.</li>
 * </ol>
 */
final class SystemUiHooks {
    private static final String ASSIST_MANAGER = "com.android.systemui.assist.AssistManager";
    /**
     * Fallback hook target: the class that implements
     * {@code CommandQueue.Callbacks.startAssist(Bundle)}.
     *
     * <p>Only used when {@code AssistManager}'s own entry point cannot be resolved. It is a weaker
     * target: it only sees requests that arrive through {@code CommandQueue} (the power key), so a
     * build that has to fall back here loses the corner gesture.</p>
     */
    private static final String COMMAND_QUEUE_CALLBACKS =
            "com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks";
    private static final String NAV_BAR_UTILS = "com.oplus.systemui.navigationbar.utils.NavBarUtils";
    /**
     * The bottom-area touch handler that feeds the gesture handle. Only its own
     * {@code NavBarUtils.isSideGestureBarHide()} call is answered with {@code false}, so the bar
     * stays hidden for every other consumer of that flag.
     */
    private static final String SIDE_GESTURE_DETECTOR =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector";
    /**
     * The bar itself. It is a plain view inside the navigation-bar window, so handing its bounds to
     * the window's touchable region is what makes the bar area belong to the system again.
     */
    private static final String SIDE_GESTURE_HANDLE =
            "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle";
    /** Builds the navigation-bar window parameters, including the alpha used to hide the bar. */
    private static final String NAVIGATION_BAR =
            "com.android.systemui.navigationbar.views.NavigationBar";
    /** The bar view that re-writes that alpha when the bar is hidden or shown again. */
    private static final String OPLUS_NAV_BAR_VIEW =
            "com.oplusos.systemui.navigationbar.OplusNavigationBarView";
    private static final String FEATURE_OPTION = "com.oplusos.systemui.common.feature.FeatureOption";
    private static final String CUSTOMIZE_FEATURE_OPTION =
            "com.oplusos.systemui.common.feature.CustomizeFeatureOption";
    private static final String SPEED_CHASSIST =
            "com.oplus.systemui.navigationbar.gesture.otherbusiness.SpeedChassistMainBusiness";
    /**
     * The gesture-handle long press on this build is handled by the OCR-screen business, not by
     * {@code SpeedChassistMainBusiness}: the nav bar handle dispatches
     * {@code GestureHomeHandleEventController.onLongClick()} to its listeners and the registered
     * listener is {@code OplusOcrScreenBusiness}, which forwards to
     * {@code OplusOcrScreenServiceHandler}, whose {@code onPreLongPress()} binds and preloads the OEM
     * screen-recognition service and whose {@code handleLongPressAction()} finally starts it.
     * Confirmed from a device log where every bottom-centre long press produced
     * {@code OcrScreenService-->getServiceIntent} and no {@code SpeedChassist} activity.
     */
    private static final String OCR_SCREEN_HANDLER =
            "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenServiceHandler";
    private static final String QUICK_STEP_CONTRACT =
            "com.android.systemui.shared.system.QuickStepContract";
    private static final String ASSIST_UTILS = "com.android.internal.app.AssistUtils";
    private static final String DEPENDENCY = "com.android.systemui.Dependency";
    private static final String INTERNAL_BOOL_RES = "com.android.internal.R$bool";
    private static final String CTS_INTERFACE = "android.app.contextualsearch.IContextualSearchManager";

    private static final String ASSIST_TOUCH_GESTURE_ENABLED = "assist_touch_gesture_enabled";
    private static final String EXTRA_INVOCATION_TYPE = "invocation_type";
    private static final String SETTING_ASSISTANT = "assistant";

    /**
     * The China-only nav-bar switch "长按手势指示条唤醒小布识屏" ({@code gesture_side_wake_cui} in
     * Settings) writes one of these two keys, depending on whether the build supports screen
     * recognition. SystemUI itself never reads them, so without honouring them here the switch would
     * stop having any visible effect once the module routes the gesture to the system assistant.
     */
    private static final String KEY_HANDLE_WAKE_OCR = "oplus_home_handle_wake_up_ocr_enable";
    private static final String KEY_HANDLE_WAKE_CUI = "oplus_gesture_handle_cui_enable";

    /** Mirrors the OEM and AOSP numbering for a long press on the gesture handle. */
    private static final int INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5;
    /** {@code launchAssistAction} invocation type produced by the power key. */
    private static final int INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6;

    /**
     * The OCR handler can reach {@code handleLongPressAction()} from two places (the long-press
     * runnable and the late service connection), so keep the dispatch single-shot per gesture.
     */
    private static volatile long lastHandleDispatchAtMs;
    private static final long HANDLE_DISPATCH_DEBOUNCE_MS = 800L;

    /**
     * Window alpha used while the gesture bar is hidden: above zero so WindowManager keeps the
     * window in input dispatch, and far below what the eye can pick up.
     */
    private static final float HIDDEN_BAR_WINDOW_ALPHA = 0.01f;

    /* The touchable region currently applied to the navigation-bar window (null: OEM default). */
    private static volatile Region appliedHandleRegion;
    /** True while a hidden navigation bar window is held in the input pipeline by the module. */
    private static volatile boolean windowAlphaForced;
    /** Last bar view seen by the region hook; the window hooks reuse it. */
    private static volatile View lastHandleView;
    private static volatile Method viewRootImplGetter;
    private static volatile Method touchableRegionSetter;
    private static volatile Field paramsForRotationField;
    private static volatile String lastRegionSkipReason;

    private SystemUiHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        AssistPipeline pipeline = resolveAssistPipeline(module, classLoader);
        CtsPipeline cts = resolveCtsPipeline(module, classLoader);
        installAssistDispatch(module, classLoader, pipeline, cts);
        installAssistantAvailability(module, classLoader);
        installGestureHandleLongPress(module, classLoader, pipeline, cts);
        installOcrScreenHandleLongPress(module, classLoader, pipeline, cts);
        installHiddenGestureBarHandleTouch(module, classLoader);
        installHandleTouchRegion(module, classLoader);
        PixelLightHooks.install(module, classLoader);
    }

    /**
     * Circle to Search is addressed through the framework service rather than an assist
     * invocation: on this build the SystemUI side of that integration is stubbed out
     * ({@code OplusCircleToSearchManagerEx.interceptStartAssistInternal} always returns false), so
     * the service is the only working entry point.
     */
    private static final class CtsPipeline {
        private final Method getService;
        private final Method asInterface;
        private final Method startContextualSearch;
        /** Parameter count of the resolved overload; the trailing one is the config. */
        private final int startContextualSearchArity;

        private CtsPipeline(Method getService, Method asInterface, Method startContextualSearch,
                int startContextualSearchArity) {
            this.getService = getService;
            this.asInterface = asInterface;
            this.startContextualSearch = startContextualSearch;
            this.startContextualSearchArity = startContextualSearchArity;
        }

        /** @return {@code true} when the service accepted the request */
        boolean trigger() throws Throwable {
            Object binder = getService.invoke(null, "contextual_search");
            if (binder == null) {
                return false;
            }
            Object service = asInterface.invoke(null, binder);
            if (service == null) {
                return false;
            }
            Object[] args = new Object[startContextualSearchArity];
            args[0] = CtsHooks.CONTEXTUAL_SEARCH_ENTRYPOINT;
            // Anything past the entry point is the ContextualSearchConfig added in Android 17.
            // `ContextualSearchManagerStub.startContextualSearch` reads
            // `config != null ? config : ContextualSearchConfig.DEFAULT_CONFIG`, so a null is
            // accepted and keeps this class off the framework's new config type entirely.
            startContextualSearch.invoke(service, args);
            return true;
        }
    }

    private static CtsPipeline resolveCtsPipeline(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Method getService = Class.forName("android.os.ServiceManager", false, classLoader)
                    .getMethod("getService", String.class);
            Class<?> iface = Class.forName(CTS_INTERFACE, false, classLoader);
            Class<?> stub = Class.forName(CTS_INTERFACE + "$Stub", false, classLoader);
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            // Resolved by name and first-parameter type rather than by an exact signature: Android
            // 17 changed this AIDL method from `startContextualSearch(int)` to
            // `startContextualSearch(int, ContextualSearchConfig)`, and an exact lookup made the
            // whole pipeline come back null — which used to send every "即圈即搜" entry silently
            // down the default-assistant branch instead.
            Method startContextualSearch = null;
            int arity = 0;
            for (Method candidate : iface.getMethods()) {
                if (!"startContextualSearch".equals(candidate.getName())) {
                    continue;
                }
                Class<?>[] parameterTypes = candidate.getParameterTypes();
                if (parameterTypes.length >= 1 && parameterTypes[0] == int.class) {
                    startContextualSearch = candidate;
                    arity = parameterTypes.length;
                    break;
                }
            }
            if (startContextualSearch == null) {
                throw new NoSuchMethodException(CTS_INTERFACE + ".startContextualSearch(int, ...)");
            }
            module.logInfo("circle_to_search_pipeline_resolved startContextualSearchArgs=" + arity);
            return new CtsPipeline(getService, asInterface, startContextualSearch, arity);
        } catch (Throwable t) {
            module.logError("circle_to_search_pipeline_failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Assist stack access
    // ---------------------------------------------------------------------------------------------

    /**
     * Holds the {@code AssistManager} members that perform the real dispatch. The gesture handle
     * hook calls them directly so the result never depends on whether a reflection based invocation
     * of an already hooked method passes through the hook chain.
     *
     * <p>ColorOS 17 added a leading {@code Context} parameter to {@code startAssistInternal}; the
     * pipeline resolves whichever overload the build carries and replays the matching call.</p>
     */
    private static final class AssistPipeline {
        private final Method getAssistInfo;
        private final Method getVoiceInteractorComponentName;
        private final Method startAssistInternal;
        private final boolean needsContext;
        private final Field contextField;

        private AssistPipeline(Method getAssistInfo, Method getVoiceInteractorComponentName,
                Method startAssistInternal, boolean needsContext, Field contextField) {
            this.getAssistInfo = getAssistInfo;
            this.getVoiceInteractorComponentName = getVoiceInteractorComponentName;
            this.startAssistInternal = startAssistInternal;
            this.needsContext = needsContext;
            this.contextField = contextField;
        }

        /** @return the component the request was sent to, or {@code null} when none is configured */
        Object dispatch(AssistRestoreModule module, Object assistManager, Bundle args)
                throws Throwable {
            Object assistInfo = getAssistInfo.invoke(assistManager);
            if (assistInfo == null) {
                module.logWarn("assist_dispatch_skipped reason=no_assistant_configured");
                return null;
            }
            boolean isService =
                    assistInfo.equals(getVoiceInteractorComponentName.invoke(assistManager));
            module.logInfo("assist_dispatch component=" + assistInfo
                    + " isService=" + isService
                    + " invocationType=" + args.getInt(EXTRA_INVOCATION_TYPE, 0));
            invokeInternal(assistManager, args, assistInfo, isService);
            return assistInfo;
        }

        /**
         * Runs the very same dispatch for an explicitly chosen component: this is how a pinned app
         * that ships a voice interaction service gets a real assist session even while the system
         * default assistant stays untouched.
         */
        Object dispatchTo(AssistRestoreModule module, Object assistManager, Bundle args,
                ComponentName component, boolean isService) throws Throwable {
            module.logInfo("assist_dispatch component=" + component
                    + " isService=" + isService
                    + " invocationType=" + args.getInt(EXTRA_INVOCATION_TYPE, 0)
                    + " pinned=true");
            invokeInternal(assistManager, args, component, isService);
            return component;
        }

        private void invokeInternal(Object assistManager, Bundle args, Object component,
                boolean isService) throws Throwable {
            if (!needsContext) {
                startAssistInternal.invoke(assistManager, args, component, isService);
                return;
            }
            Object context = contextField != null ? contextField.get(assistManager) : null;
            if (context == null) {
                // Without the Context the four-argument overload cannot be replayed; surfacing it
                // is better than silently dropping the dispatch.
                throw new IllegalStateException(
                        "startAssistInternal requires a Context but AssistManager.mContext is null");
            }
            startAssistInternal.invoke(assistManager, context, args, component, isService);
        }
    }

    private static AssistPipeline resolveAssistPipeline(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> assistManager = Class.forName(ASSIST_MANAGER, true, classLoader);
            Method getAssistInfo = assistManager.getMethod("getAssistInfo");
            Method getVoiceInteractorComponentName =
                    assistManager.getMethod("getVoiceInteractorComponentName");

            Method startAssistInternal = null;
            boolean needsContext = false;
            try {
                startAssistInternal = assistManager.getMethod("startAssistInternal",
                        Context.class, Bundle.class, ComponentName.class, boolean.class);
                needsContext = true;
            } catch (NoSuchMethodException ignored) {
                // ColorOS 16 and older: the three-argument form is the only one.
            }
            if (startAssistInternal == null) {
                startAssistInternal = assistManager.getMethod("startAssistInternal",
                        Bundle.class, ComponentName.class, boolean.class);
            }

            Field contextField = null;
            if (needsContext) {
                contextField = assistManager.getDeclaredField("mContext");
                contextField.setAccessible(true);
            }
            module.logInfo("assist_pipeline_resolved startAssistInternalArgs="
                    + startAssistInternal.getParameterCount()
                    + " needsContext=" + needsContext);
            return new AssistPipeline(getAssistInfo, getVoiceInteractorComponentName,
                    startAssistInternal, needsContext, contextField);
        } catch (Throwable t) {
            module.logError("assist_pipeline_resolve_failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. The entry point that carries an assist request into SystemUI
    // ---------------------------------------------------------------------------------------------

    /**
     * Where an incoming assist request is hooked, plus how to reach the {@code AssistManager} from
     * the hooked receiver. On the callback class the manager sits in a field; on the legacy
     * {@code AssistManager.startAssist} the receiver <em>is</em> the manager.
     */
    private static final class AssistStartTarget {
        private final String name;
        private final Method method;
        private final Field managerField;

        private AssistStartTarget(String name, Method method, Field managerField) {
            this.name = name;
            this.method = method;
            this.managerField = managerField;
        }

        Object managerOf(Object holder) {
            if (managerField == null) {
                return holder;
            }
            try {
                return managerField.get(holder);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static AssistStartTarget resolveAssistStartTarget(
            AssistRestoreModule module, ClassLoader classLoader) {
        // AssistManager is the funnel every assist request passes through: the power key arrives via
        // CommandQueue, while the corner gesture comes straight from the launcher through
        // ISystemUiProxy and never touches the callbacks. Hooking the callbacks therefore only ever
        // saw the power key — which is exactly why the corner gesture stopped being dispatched.
        // The method is matched by *shape*, not by name: Android 17 renamed it to `startAssist$1`,
        // and the parameter list is the part that stays stable.
        try {
            Class<?> assistManager = Class.forName(ASSIST_MANAGER, true, classLoader);
            Method method = findAssistEntry(assistManager);
            if (method == null) {
                throw new NoSuchMethodException(ASSIST_MANAGER + ".<single Bundle argument>");
            }
            module.logInfo("assist_entry_resolved target=" + ASSIST_MANAGER + "."
                    + method.getName());
            return new AssistStartTarget(ASSIST_MANAGER + "." + method.getName(), method, null);
        } catch (Throwable t) {
            module.logWarn("hook_candidate_missing target=" + ASSIST_MANAGER
                    + ".startAssist reason=" + t);
        }
        try {
            Class<?> callbacks = Class.forName(COMMAND_QUEUE_CALLBACKS, true, classLoader);
            Method method = callbacks.getMethod("startAssist", Bundle.class);
            // Matched by type rather than by name: the field name is not part of any contract.
            Field holder = null;
            for (Field candidate : callbacks.getDeclaredFields()) {
                if (ASSIST_MANAGER.equals(candidate.getType().getName())) {
                    candidate.setAccessible(true);
                    holder = candidate;
                    break;
                }
            }
            if (holder == null) {
                module.logWarn("assist_manager_field_missing target=" + COMMAND_QUEUE_CALLBACKS);
            }
            return new AssistStartTarget(COMMAND_QUEUE_CALLBACKS + ".startAssist", method, holder);
        } catch (Throwable t) {
            module.logError("hook_candidate_missing target=" + COMMAND_QUEUE_CALLBACKS
                    + ".startAssist reason=" + t);
            return null;
        }
    }

    /**
     * The assist entry point on {@code AssistManager}, matched by shape: exactly one {@code Bundle}
     * parameter and nothing else. A {@code startAssist}-prefixed name is preferred when several
     * candidates match, but the name is never required.
     */
    private static Method findAssistEntry(Class<?> owner) {
        Method fallback = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length != 1 || parameterTypes[0] != Bundle.class) {
                continue;
            }
            candidate.setAccessible(true);
            if (candidate.getName().startsWith("startAssist")) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    private static void installAssistDispatch(
            AssistRestoreModule module, ClassLoader classLoader, AssistPipeline pipeline,
            CtsPipeline cts) {
        if (pipeline == null) {
            module.logError("hook_skipped target=assist_start_assist"
                    + " reason=assist_pipeline_unavailable");
            return;
        }
        try {
            AssistStartTarget target = resolveAssistStartTarget(module, classLoader);
            if (target == null) {
                module.logError("hook_skipped target=assist_start_assist reason=no_candidate");
                return;
            }
            Class<?> assistManager = Class.forName(ASSIST_MANAGER, true, classLoader);
            Field overrideInvocationTypes = assistManager.getField("mAssistOverrideInvocationTypes");
            Field activityManager = assistManager.getField("mActivityManager");
            Method isExpRegion = Refl.staticMethod(classLoader, FEATURE_OPTION, "isExpRegion");
            if (isExpRegion == null) {
                module.logWarn("region_gate_unresolved target=" + FEATURE_OPTION
                        + ".isExpRegion -> treating the region gate as closed");
            }

            module.hook(target.method)
                    .setId("assist_start_assist")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object manager = target.managerOf(chain.getThisObject());
                        Bundle bundle = (Bundle) chain.getArg(0);
                        Object result = chain.proceed();

                        if (manager == null) {
                            module.logWarn("assist_skip reason=no_assist_manager holder="
                                    + chain.getThisObject().getClass().getName());
                            return result;
                        }
                        if (isExpRegionEnabled(isExpRegion)) {
                            // Export-region behaviour is already active: the OEM path dispatched.
                            module.logInfo("assist_skip reason=exp_region_active");
                            return result;
                        }
                        if (isLockTaskMode(activityManager.get(manager))) {
                            module.logInfo("assist_skip reason=lock_task_mode");
                            return result;
                        }
                        if (isHandledByLauncherOverride(overrideInvocationTypes.get(manager), bundle)) {
                            module.logInfo("assist_skip reason=launcher_override invocationType="
                                    + (bundle != null ? bundle.getInt(EXTRA_INVOCATION_TYPE, 0) : 0));
                            return result;
                        }
                        String entry = entryForInvocationType(bundle);
                        String mode = AssistConfig.mode(HookPrefs.get(), entry);
                        // The entry is *derived* from the bundle, so an assist request that arrives
                        // without `invocation_type` is attributed to the corner gesture and the power
                        // key ends up reading the corner's setting. Logged so that misattribution is
                        // visible instead of silent.
                        module.logInfo("assist_entry entry=" + entry + " mode=" + mode
                                + " invocationType=" + (bundle == null
                                        ? "no-bundle"
                                        : bundle.getInt(EXTRA_INVOCATION_TYPE, Integer.MIN_VALUE))
                                + " keys=" + (bundle == null ? "[]" : bundle.keySet()));
                        if (AssistConfig.MODE_NONE.equals(mode)) {
                            module.logInfo("assist_skip reason=disabled entry=" + entry);
                            return result;
                        }
                        if (AssistConfig.MODE_OEM.equals(mode)) {
                            // The entry is left to ColorOS; the OEM branch above already ran.
                            module.logInfo("assist_skip reason=oem_mode entry=" + entry);
                            return result;
                        }
                        // A pinned target is the whole instruction: run it, then stop. Falling back to
                        // the default assistant here would answer a request the user never made —
                        // picking 即圈即搜 and getting the assistant is exactly the symptom that
                        // produces.
                        if (AssistConfig.MODE_CTS.equals(mode)) {
                            if (!triggerCircleToSearch(module, cts)) {
                                module.logWarn("circle_to_search_unavailable entry=" + entry);
                            }
                            return result;
                        }
                        if (AssistConfig.MODE_APP.equals(mode)
                                || AssistConfig.MODE_CUSTOM.equals(mode)) {
                            if (!startConfiguredTarget(module, TargetIntents.appContext(), entry,
                                    pipeline, manager)) {
                                module.logWarn("assist_target_unavailable entry=" + entry
                                        + " mode=" + mode);
                            }
                            return result;
                        }
                        // Only "跟随系统默认" reaches the assistant dispatch.
                        pipeline.dispatch(module, manager, bundle != null ? bundle : new Bundle());
                        return result;
                    });
            module.logInfo("hook_installed target=" + target.name);
        } catch (Throwable t) {
            module.logError("hook_failed target=assist_start_assist", t);
        }
    }

    private static boolean isExpRegionEnabled(Method isExpRegion) {
        if (isExpRegion == null) {
            return false;
        }
        try {
            Object value = isExpRegion.invoke(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isLockTaskMode(Object activityManager) {
        if (activityManager == null) {
            return false;
        }
        try {
            Method getState = activityManager.getClass().getMethod("getLockTaskModeState");
            Object state = getState.invoke(activityManager);
            return state instanceof Integer && (Integer) state == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isHandledByLauncherOverride(Object invocationTypes, Bundle bundle) {
        if (!(invocationTypes instanceof int[]) || bundle == null
                || !bundle.containsKey(EXTRA_INVOCATION_TYPE)) {
            return false;
        }
        int requested = bundle.getInt(EXTRA_INVOCATION_TYPE, 0);
        for (int candidate : (int[]) invocationTypes) {
            if (candidate == requested) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 2. NavBarUtils.isAssistantAvailable
    // ---------------------------------------------------------------------------------------------

    private static void installAssistantAvailability(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> navBarUtils = Class.forName(NAV_BAR_UTILS, true, classLoader);
            Method isAssistantAvailable =
                    navBarUtils.getMethod("isAssistantAvailable", Context.class, int.class, int.class);

            Method quickStepIsGesturalMode =
                    Refl.staticMethod(classLoader, QUICK_STEP_CONTRACT, "isGesturalMode", int.class);
            Class<?> assistUtilsClass = Class.forName(ASSIST_UTILS, false, classLoader);
            Constructor<?> assistUtilsConstructor = assistUtilsClass.getConstructor(Context.class);
            Method getAssistComponentForUser =
                    assistUtilsClass.getMethod("getAssistComponentForUser", int.class);

            module.hook(isAssistantAvailable)
                    .setId("assistant_availability")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (AssistConfig.MODE_NONE.equals(AssistConfig.mode(
                                HookPrefs.get(), AssistConfig.ENTRY_CORNER))) {
                            // Corner entry disabled: report it unavailable so the launcher does not
                            // even arm the corner region.
                            module.logInfo("assistant_availability available=false reason=disabled");
                            return Boolean.FALSE;
                        }
                        Context context = (Context) chain.getArg(0);
                        int navBarMode = (Integer) chain.getArg(1);
                        int userId = (Integer) chain.getArg(2);
                        Boolean available = evaluateAssistantAvailable(module, classLoader, context,
                                navBarMode, userId, quickStepIsGesturalMode, assistUtilsConstructor,
                                getAssistComponentForUser);
                        if (available == null) {
                            return chain.proceed();
                        }
                        return available;
                    });
            module.logInfo("hook_installed target=" + NAV_BAR_UTILS + ".isAssistantAvailable");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + NAV_BAR_UTILS + ".isAssistantAvailable", t);
        }
    }

    /**
     * Reimplements the AOSP availability rule without the region gate.
     *
     * @return the availability to report, or {@code null} to fall back to the original method
     */
    private static Boolean evaluateAssistantAvailable(
            AssistRestoreModule module,
            ClassLoader classLoader,
            Context context,
            int navBarMode,
            int userId,
            Method quickStepIsGesturalMode,
            Constructor<?> assistUtilsConstructor,
            Method getAssistComponentForUser) {
        try {
            Boolean circleToSearch = Refl.staticBooleanField(
                    classLoader, CUSTOMIZE_FEATURE_OPTION, "sIsSupportCircleToSearch");
            if (Boolean.TRUE.equals(circleToSearch)) {
                // Circle to Search owns the corner gesture on builds that ship it.
                return Boolean.FALSE;
            }
            if (quickStepIsGesturalMode != null
                    && !Boolean.TRUE.equals(quickStepIsGesturalMode.invoke(null, navBarMode))) {
                return Boolean.FALSE;
            }
            Object assistUtils = assistUtilsConstructor.newInstance(context);
            Object component = getAssistComponentForUser.invoke(assistUtils, userId);
            if (component == null) {
                module.logInfo("assistant_availability available=false"
                        + " reason=no_assistant_configured");
                return Boolean.FALSE;
            }
            int stored = getSecureSettingForUser(
                    context,
                    ASSIST_TOUCH_GESTURE_ENABLED,
                    assistTouchGestureDefaultValue(context),
                    userId);
            boolean enabled = stored != 0;
            module.logInfo("assistant_availability available=" + enabled
                    + " component=" + component
                    + " navBarMode=" + navBarMode
                    + " userId=" + userId);
            return enabled;
        } catch (Throwable t) {
            module.logError("assistant_availability_failed", t);
            return null;
        }
    }

    /** Reads the framework default that the OEM method itself would fall back to. */
    private static int assistTouchGestureDefaultValue(Context context) {
        try {
            Class<?> boolRes = Class.forName(INTERNAL_BOOL_RES);
            int resourceId =
                    boolRes.getField("config_assistTouchGestureEnabledDefault").getInt(null);
            if (resourceId != 0) {
                return context.getResources().getBoolean(resourceId) ? 1 : 0;
            }
        } catch (Throwable ignored) {
            // Falls through to the AOSP default.
        }
        return 1;
    }

    /**
     * {@code Settings.Secure.getIntForUser} is a hidden API. SystemUI is a platform process, but this
     * module compiles against the public SDK, so the hidden overload is reached reflectively with
     * the documented calling-user overload as a fallback.
     */
    private static int getSecureSettingForUser(
            Context context, String name, int defaultValue, int userId) {
        ContentResolver resolver = context.getContentResolver();
        try {
            Method getIntForUser = Settings.Secure.class.getMethod(
                    "getIntForUser", ContentResolver.class, String.class, int.class, int.class);
            Object value = getIntForUser.invoke(null, resolver, name, defaultValue, userId);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
            // Falls through to the public API.
        }
        return Settings.Secure.getInt(resolver, name, defaultValue);
    }

    // ---------------------------------------------------------------------------------------------
    // 3. SpeedChassistMainBusiness.onLongPressed
    // ---------------------------------------------------------------------------------------------

    private static void installGestureHandleLongPress(
            AssistRestoreModule module, ClassLoader classLoader, AssistPipeline pipeline,
            CtsPipeline cts) {
        if (pipeline == null) {
            module.logError("hook_skipped target=" + SPEED_CHASSIST + ".onLongPressed"
                    + " reason=assist_pipeline_unavailable");
            return;
        }
        try {
            Class<?> business = Class.forName(SPEED_CHASSIST, true, classLoader);
            Method onLongPressed = business.getMethod("onLongPressed");
            Class<?> assistManagerClass = Class.forName(ASSIST_MANAGER, true, classLoader);
            Field contextField = business.getDeclaredField("mContext");
            contextField.setAccessible(true);

            module.hook(onLongPressed)
                    .setId("gesture_handle_long_press")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Context context = (Context) contextField.get(chain.getThisObject());
                        return dispatchGestureHandleLongPress(
                                module, classLoader, pipeline, assistManagerClass, cts, context)
                                ? null : chain.proceed();
                    });
            module.logInfo("hook_installed target=" + SPEED_CHASSIST + ".onLongPressed");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + SPEED_CHASSIST + ".onLongPressed", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. OplusOcrScreenServiceHandler.handleLongPressAction
    // ---------------------------------------------------------------------------------------------

    /**
     * The gesture-handle long press that actually runs on the tested build. Replacing the action
     * keeps the OEM haptics and first-run dialog logic intact while sending the gesture to the
     * configured assistant instead of the screen-recognition service.
     */
    private static void installOcrScreenHandleLongPress(
            AssistRestoreModule module, ClassLoader classLoader, AssistPipeline pipeline,
            CtsPipeline cts) {
        if (pipeline == null) {
            module.logError("hook_skipped target=" + OCR_SCREEN_HANDLER + ".onLongPressed"
                    + " reason=assist_pipeline_unavailable");
            return;
        }
        try {
            Class<?> handler = Class.forName(OCR_SCREEN_HANDLER, true, classLoader);
            Class<?> assistManagerClass = Class.forName(ASSIST_MANAGER, true, classLoader);
            Field contextField = handler.getDeclaredField("context");
            contextField.setAccessible(true);

            // The OEM long press runs: onShowPress (new handler + haptics) -> onPreLongPress (bind and
            // preload the screen-recognition service) -> onLongPressed (haptics, set the handled flag,
            // then post a task) -> handleLongPressAction.
            //
            // That posted task calls handleLongPressAction() ONLY when
            // getEntranceServiceInterface() != null, i.e. only when onPreLongPress already bound the
            // service. Skipping the preload therefore also cuts off handleLongPressAction(), which is
            // why the dispatch hangs off onLongPressed() instead: proceed() keeps the OEM haptics and
            // flags, and the posted task then finds no connected service and does nothing.
            Method onPreLongPress = handler.getMethod("onPreLongPress");
            module.hook(onPreLongPress)
                    .setId("ocr_screen_handle_preload")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!AssistConfig.skipOcrPreload(HookPrefs.get())
                                || isHandleOemMode()) {
                            return chain.proceed();
                        }
                        module.logInfo("gesture_handle_ocr_preload_skipped");
                        return null;
                    });
            module.logInfo("hook_installed target=" + OCR_SCREEN_HANDLER + ".onPreLongPress");

            Method onLongPressed = handler.getMethod("onLongPressed");
            module.hook(onLongPressed)
                    .setId("ocr_screen_handle_long_press")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Context context = (Context) contextField.get(chain.getThisObject());
                        Object result = chain.proceed();
                        dispatchGestureHandleLongPress(
                                module, classLoader, pipeline, assistManagerClass, cts, context);
                        return result;
                    });
            module.logInfo("hook_installed target=" + OCR_SCREEN_HANDLER + ".onLongPressed");
            // Skipping the preload only stops *this* press from connecting the screen-recognition
            // service; the service may already be connected from an earlier OEM press, and then the
            // OEM action runs anyway. Neutralise the action itself for every non-OEM mode.
            Method handleLongPressAction = null;
            try {
                handleLongPressAction = handler.getMethod("handleLongPressAction");
            } catch (NoSuchMethodException notPublic) {
                try {
                    handleLongPressAction = handler.getDeclaredMethod("handleLongPressAction");
                    handleLongPressAction.setAccessible(true);
                } catch (Throwable ignored) {
                    // Not present on this build.
                }
            }
            if (handleLongPressAction != null) {
                module.hook(handleLongPressAction)
                        .setId("ocr_screen_handle_action")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!isHandleOemMode()) {
                                module.logInfo("gesture_handle_ocr_action_skipped");
                                return null;
                            }
                            return chain.proceed();
                        });
                module.logInfo("hook_installed target=" + OCR_SCREEN_HANDLER
                        + ".handleLongPressAction");
            } else {
                module.logWarn("hook_skipped target=" + OCR_SCREEN_HANDLER
                        + ".handleLongPressAction reason=method_missing");
            }
        } catch (Throwable t) {
            module.logError("hook_failed target=" + OCR_SCREEN_HANDLER
                    + ".onLongPressed", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. NavBarUtils.isSideGestureBarHide
    // ---------------------------------------------------------------------------------------------

    /**
     * Keeps the bottom-centre long press alive while the gesture bar is hidden.
     *
     * <p>{@code SideGestureDetector} hands its bottom-area motion events to the gesture handle only
     * while {@code !NavBarUtils.isSideGestureBarHide()}. That flag means "the gesture bar is
     * hidden": side-gesture navigation ({@code getNavState() == 3}) plus the
     * {@code gesture_side_hide_bar_prevention_enable} switch, the setting the user turns on to hide
     * the bar. Turning the bar off therefore also drops the handle out of the touch chain - no
     * {@code onDown} / {@code onShowPress} / {@code onLongClick}, and no assistant at the position
     * the handle occupies. Export builds do not suppress it, which is the behaviour being
     * restored.</p>
     *
     * <p>The flag itself is left untouched for everyone else:
     * {@code NavigationBar.getBarLayoutParams()} turns the whole navigation-bar window transparent
     * from it, and the QS special-mode provider and the sampling-region check read it as well.
     * Only the detector's own
     * call is answered with {@code false}, so the bar stays hidden while the handle keeps receiving
     * touches - the OEM press animation, haptics and long-press timing all stay in place.</p>
     */
    private static void installHiddenGestureBarHandleTouch(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> navBarUtils = Class.forName(NAV_BAR_UTILS, true, classLoader);
            Method isSideGestureBarHide = navBarUtils.getDeclaredMethod("isSideGestureBarHide");
            module.hook(isSideGestureBarHide)
                    .setId("hidden_gesture_bar_handle_touch")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!Boolean.TRUE.equals(result)) {
                            // The bar is visible; the OEM gate is not in the way.
                            return result;
                        }
                        if (!AssistConfig.handleWhenBarHidden(HookPrefs.get())) {
                            return result;
                        }
                        String configured =
                                AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE);
                        if (AssistConfig.MODE_NONE.equals(configured)
                                || AssistConfig.MODE_OEM.equals(configured)) {
                            // This entry is not taken over, so the OEM behaviour stands: a hidden
                            // bar keeps the handle silent instead of waking screen recognition.
                            return result;
                        }
                        if (!isCalledFromSideGestureDetector()) {
                            return result;
                        }
                        module.logInfo("hidden_gesture_bar_handle_unblocked mode=" + configured);
                        return Boolean.FALSE;
                    });
            module.logInfo("hook_installed target=" + NAV_BAR_UTILS + ".isSideGestureBarHide");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + NAV_BAR_UTILS + ".isSideGestureBarHide", t);
        }
    }

    /**
     * @return {@code true} when the caller is the side-gesture detector, i.e. one of the two posted
     *         runnables that decide whether the handle receives the bottom-area touch
     */
    private static boolean isCalledFromSideGestureDetector() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (int i = 1; i < frames.length && i <= 8; i++) {
            if (frames[i].getClassName().startsWith(SIDE_GESTURE_DETECTOR)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 6. The gesture bar area belongs to the navigation-bar window
    // ---------------------------------------------------------------------------------------------

    /**
     * Gives the bar's own area to the navigation-bar window, the way AOSP does.
     *
     * <p>The bar is only a drawing: {@code OplusNavigationHandle} is a plain {@code View} inside
     * the navigation-bar window, that window's touchable region is empty
     * ({@code touchableRegion=<empty>} in {@code dumpsys input}), so every press in the bottom
     * strip goes to whatever is underneath - a page, the launcher or the keyboard. The bar learns
     * about the press from a gesture monitor afterwards, and a page long press has already fired at
     * 500 ms by the time the bar commits at 800 ms, which is the double trigger this fixes.</p>
     *
     * <p>Setting the window's touchable region to the bar's own bounds makes the window the touch
     * target for exactly the presses the bar can act on, so the page never sees them; the gesture
     * monitor still receives its copy and the whole bar pipeline - press animation, haptics, the
     * 800 ms long press - stays untouched. Swipes and taps elsewhere in the strip keep going to the
     * page, and while the keyboard is up the region stays empty so its bottom row keeps working.</p>
     *
     * <p>Re-applied on every layout pass of the bar (rotation, navigation-mode change, insets) and
     * only when the result differs from what is already applied.</p>
     */
    private static void installHandleTouchRegion(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> handle = Class.forName(SIDE_GESTURE_HANDLE, true, classLoader);
            Method onLayout = handle.getMethod(
                    "onLayout", boolean.class, int.class, int.class, int.class, int.class);
            module.hook(onLayout)
                    .setId("handle_touch_region")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        View view = (View) chain.getThisObject();
                        lastHandleView = view;
                        applyHandleTouchRegion(module, view);
                        return result;
                    });
            module.logInfo("hook_installed target=" + SIDE_GESTURE_HANDLE + ".onLayout");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + SIDE_GESTURE_HANDLE + ".onLayout", t);
        }
        installHiddenBarWindowHooks(module, classLoader);
    }

    /**
     * Stops the OEM from taking the bar's window out of input dispatch.
     *
     * <p>Hiding the bar sets the navigation-bar window's alpha to 0, and WindowManager drops a fully
     * transparent window from input dispatch: {@code dumpsys input} then reports
     * {@code inputConfig=NOT_VISIBLE}, the window stops being a touch target, and the page gets the
     * bar-area press again. Both places that write that alpha are wrapped - the window parameters
     * built at creation time and the live toggle - so the window stays in the pipeline while
     * remaining invisible on screen.</p>
     */
    private static void installHiddenBarWindowHooks(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> bar = Class.forName(NAVIGATION_BAR, true, classLoader);
            Method forRotation = bar.getMethod(
                    "getBarLayoutParamsForRotation", int.class, WindowMetrics.class);
            module.hook(forRotation)
                    .setId("bar_window_alpha_created")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        patchCreatedWindowAlpha(module, result);
                        return result;
                    });
            module.logInfo("hook_installed target=" + NAVIGATION_BAR
                    + ".getBarLayoutParamsForRotation");
        } catch (Throwable t) {
            module.logWarn("hook_skipped target=" + NAVIGATION_BAR
                    + ".getBarLayoutParamsForRotation " + t);
        }
        try {
            Class<?> barView = Class.forName(OPLUS_NAV_BAR_VIEW, true, classLoader);
            Method updateWindowAlpha = barView.getMethod("updateWindowAlpha", int.class);
            module.hook(updateWindowAlpha)
                    .setId("bar_window_alpha_toggled")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        View bar = (View) chain.getThisObject();
                        Object result = chain.proceed();
                        // The OEM has just written the alpha; re-assert ours and the region.
                        bar.post(() -> {
                            if (keepHiddenWindowReachable(module, bar, true)) {
                                module.logInfo("handle_window_alpha reasserted after toggle");
                            }
                            View handle = lastHandleView;
                            if (handle == null) {
                                handle = findHandleView(bar);
                                lastHandleView = handle;
                            }
                            if (handle != null) {
                                applyHandleTouchRegion(module, handle);
                            }
                        });
                        return result;
                    });
            module.logInfo("hook_installed target=" + OPLUS_NAV_BAR_VIEW + ".updateWindowAlpha");
        } catch (Throwable t) {
            module.logWarn("hook_skipped target=" + OPLUS_NAV_BAR_VIEW
                    + ".updateWindowAlpha " + t);
        }
    }

    /** Keeps the window parameters built for a hidden bar inside input dispatch. */
    private static void patchCreatedWindowAlpha(AssistRestoreModule module, Object params) {
        if (!handleOwnedByModule() || !(params instanceof WindowManager.LayoutParams)) {
            return;
        }
        WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) params;
        boolean patched = patchHiddenAlpha(layoutParams);
        try {
            if (paramsForRotationField == null) {
                try {
                    paramsForRotationField = WindowManager.LayoutParams.class
                            .getField("paramsForRotation");
                } catch (NoSuchFieldException hidden) {
                    paramsForRotationField = WindowManager.LayoutParams.class
                            .getDeclaredField("paramsForRotation");
                    paramsForRotationField.setAccessible(true);
                }
            }
            Object[] perRotation = (Object[]) paramsForRotationField.get(layoutParams);
            if (perRotation != null) {
                for (Object each : perRotation) {
                    if (each instanceof WindowManager.LayoutParams) {
                        patched |= patchHiddenAlpha((WindowManager.LayoutParams) each);
                    }
                }
            }
        } catch (Throwable t) {
            module.logWarn("bar_window_alpha_rotation_failed " + t);
        }
        if (patched) {
            module.logInfo("handle_window_alpha=" + HIDDEN_BAR_WINDOW_ALPHA
                    + " reason=created_hidden");
        }
    }

    private static boolean patchHiddenAlpha(WindowManager.LayoutParams layoutParams) {
        if (layoutParams == null || layoutParams.alpha != 0f) {
            return false;
        }
        layoutParams.alpha = HIDDEN_BAR_WINDOW_ALPHA;
        return true;
    }

    /** {@code true} when the module answers the gesture-handle entry itself. */
    private static boolean handleOwnedByModule() {
        String configured = AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE);
        return !AssistConfig.MODE_NONE.equals(configured)
                && !AssistConfig.MODE_OEM.equals(configured);
    }

    /** Applies, refreshes or clears the navigation bar's touchable region. */
    private static void applyHandleTouchRegion(AssistRestoreModule module, View handle) {
        try {
            Region desired = handleRegionFor(module, handle);
            boolean alphaTouched = keepHiddenWindowReachable(module, handle, desired != null);
            if (!alphaTouched
                    && (desired == null
                    ? appliedHandleRegion == null
                    : desired.equals(appliedHandleRegion))) {
                return;
            }
            if (viewRootImplGetter == null) {
                // View#getViewRootImpl is hidden but callable; the region setter lives on the
                // returned ViewRootImpl, which is why both steps are reflective.
                viewRootImplGetter = View.class.getMethod("getViewRootImpl");
            }
            Object viewRoot = viewRootImplGetter.invoke(handle);
            if (viewRoot == null) {
                return;
            }
            if (touchableRegionSetter == null) {
                touchableRegionSetter = viewRoot.getClass()
                        .getMethod("setTouchableRegion", Region.class);
            }
            touchableRegionSetter.invoke(viewRoot, desired != null ? desired : new Region());
            appliedHandleRegion = desired;
            module.logInfo(desired == null
                    ? "handle_touch_region cleared (bar area goes back to the page)"
                    : "handle_touch_region applied=" + desired);
        } catch (Throwable t) {
            module.logWarn("handle_touch_region_failed " + t);
        }
    }

    /**
     * Keeps a visually hidden gesture bar inside the input pipeline.
     *
     * <p>The OEM hides the bar by setting the navigation-bar window alpha to 0, and WindowManager
     * drops a fully transparent window from input dispatch: {@code dumpsys input} then reports
     * {@code inputConfig=NOT_VISIBLE}, the window stops being a touch target, and the page receives
     * the bar-area press again - which is why the page long press came back as soon as the bar was
     * hidden. Holding the window alpha just above zero keeps the window in the pipeline while it
     * stays invisible on screen, and the touchable region below then decides who owns the gesture
     * area.</p>
     *
     * @return {@code true} when the window alpha was scheduled to change
     */
    private static boolean keepHiddenWindowReachable(
            AssistRestoreModule module, View handle, boolean wantTouchable) {
        try {
            View owner = windowParamOwner(handle);
            if (owner == null || !(owner.getLayoutParams() instanceof WindowManager.LayoutParams)) {
                return false;
            }
            final WindowManager.LayoutParams layoutParams =
                    (WindowManager.LayoutParams) owner.getLayoutParams();
            if (wantTouchable) {
                if (layoutParams.alpha != 0f) {
                    // The OEM keeps the bar visible; there is nothing to hold open.
                    windowAlphaForced = false;
                    return false;
                }
                layoutParams.alpha = HIDDEN_BAR_WINDOW_ALPHA;
                windowAlphaForced = true;
            } else if (windowAlphaForced) {
                layoutParams.alpha = 0f;
                windowAlphaForced = false;
            } else {
                return false;
            }
            // This runs inside a layout pass, so the window update has to wait for it to finish.
            handle.post(() -> {
                try {
                    WindowManager windowManager = (WindowManager)
                            handle.getContext().getSystemService(Context.WINDOW_SERVICE);
                    if (windowManager == null) {
                        module.logWarn("handle_window_alpha_skipped reason=no_window_manager");
                        return;
                    }
                    windowManager.updateViewLayout(owner, layoutParams);
                    module.logInfo("handle_window_alpha=" + layoutParams.alpha
                            + " reason=" + (wantTouchable ? "keep_input" : "restore"));
                } catch (Throwable t) {
                    module.logWarn("handle_window_alpha_failed " + t);
                }
            });
            return true;
        } catch (Throwable t) {
            module.logWarn("handle_window_alpha_probe_failed " + t);
            return false;
        }
    }

    /** The view whose layout params are the navigation bar window's. */
    private static View windowParamOwner(View handle) {
        View current = handle;
        while (current != null) {
            if (current.getLayoutParams() instanceof WindowManager.LayoutParams) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * @return the region the navigation-bar window should own, or {@code null} when the OEM default
     *         (an empty region, i.e. the bar area stays with the page) is the right answer
     */
    private static Region handleRegionFor(AssistRestoreModule module, View handle) {
        if (!handleOwnedByModule()) {
            // Not taken over: the OEM screen recognition needs the press to reach the page, exactly
            // as it does today.
            return skipRegion(module, "not_owned");
        }
        WindowInsets insets = handle.getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
            // The keyboard owns the bottom of the screen while it is up; its bottom row must keep
            // receiving touches, which the OEM leaves to it as well.
            return skipRegion(module, "ime_visible");
        }
        View windowView = windowParamOwner(handle);
        if (windowView == null) {
            return skipRegion(module, "no_window_view");
        }
        int windowWidth = windowView.getWidth();
        int windowHeight = windowView.getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            return skipRegion(module, "window_not_laid_out");
        }
        lastRegionSkipReason = null;
        int strip = bottomGestureAreaHeight(handle);
        if (strip <= 0) {
            strip = windowHeight;
        }
        int left;
        int right;
        if (handle.getWidth() > 0 && handle.getHeight() > 0) {
            int[] location = new int[2];
            handle.getLocationInWindow(location);
            left = location[0];
            right = location[0] + handle.getWidth();
        } else {
            // A hidden bar is not laid out at all, so rebuild its area from what the OEM draws with:
            // the bar has its own width and sits centred in the window.
            int barWidth = gestureBarWidth(handle);
            if (barWidth <= 0) {
                return skipRegion(module, "no_bar_geometry");
            }
            left = (windowWidth - barWidth) / 2;
            right = left + barWidth;
        }
        // Only the strip the OEM's own long press reacts to (bottom_gesture_area_height, the height
        // SideGestureDetector compares against). Everything above it - a folder's bottom icon row,
        // for instance - keeps going to the page exactly as before.
        int bottom = windowHeight;
        int top = Math.max(0, bottom - strip);
        return new Region(left, top, right, bottom);
    }

    /** Width the bar is drawn with, i.e. the area its long press listens to. */
    private static int gestureBarWidth(View view) {
        return oplusDimension(view, "navigation_gesture_view_width");
    }

    /** The bar view, when it is not laid out and the layout hook never cached it. */
    private static View findHandleView(View root) {
        if (root == null) {
            return null;
        }
        if (root.getClass().getName().startsWith(SIDE_GESTURE_HANDLE)) {
            return root;
        }
        if (!(root instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findHandleView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int oplusDimension(View view, String name) {
        try {
            int id = view.getResources().getIdentifier(name, "dimen", "oplus");
            return id != 0 ? view.getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Logs why the bar area is left to the page, once per reason. */
    private static Region skipRegion(AssistRestoreModule module, String reason) {
        if (!reason.equals(lastRegionSkipReason)) {
            lastRegionSkipReason = reason;
            module.logInfo("handle_touch_region_skipped reason=" + reason);
        }
        return null;
    }

    /** The OEM's bottom gesture area height, i.e. how far up the handle's long press reacts. */
    private static int bottomGestureAreaHeight(View handle) {
        try {
            int id = handle.getResources()
                    .getIdentifier("bottom_gesture_area_height", "dimen", "com.android.systemui");
            if (id != 0) {
                return handle.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
            // Falls back to the handle's own height, which is the whole navigation bar window.
        }
        return 0;
    }

    /**
     * Shared gesture-handle handling for both OEM variants.
     *
     * @return {@code true} when the assistant replaced the OEM action; {@code false} when the caller
     *         should let the original implementation run (callers that already invoked
     *         {@code chain.proceed()} simply do nothing in that case)
     */
    private static boolean dispatchGestureHandleLongPress(
            AssistRestoreModule module,
            ClassLoader classLoader,
            AssistPipeline pipeline,
            Class<?> assistManagerClass,
            CtsPipeline cts,
            Context context) throws Throwable {
        long now = SystemClock.uptimeMillis();
        if (now - lastHandleDispatchAtMs < HANDLE_DISPATCH_DEBOUNCE_MS) {
            module.logInfo("gesture_handle_long_press_skipped reason=debounce");
            return true;
        }
        if (isHandleOemMode()) {
            // "小布识屏" is the OEM behaviour itself, so the module stays out of the way entirely:
            // the OEM service preload and the OEM action both run as shipped.
            module.logInfo("gesture_handle_long_press_skipped reason=oem_mode");
            return false;
        }
        if (AssistConfig.MODE_NONE.equals(
                AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE))) {
            // Nothing is configured for this entry: the gesture stays silent, including the OEM
            // screen recognition the preload skip already cut off.
            module.logInfo("gesture_handle_long_press_skipped reason=disabled");
            return true;
        }
        if (context != null && isHandleWakeSwitchOff(module, context)) {
            // The user turned the nav-bar switch off; the gesture stays silent, matching what the
            // OEM switch is expected to do.
            return true;
        }
        String configured = AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE);
        Object assistManager = lookupAssistManager(module, classLoader, assistManagerClass);
        // A pinned target is the whole instruction: run it, then stop. Falling back to the default
        // assistant here would answer a request the user never made.
        if (AssistConfig.MODE_CTS.equals(configured)) {
            if (!triggerCircleToSearch(module, cts)) {
                module.logWarn("circle_to_search_unavailable entry=" + AssistConfig.ENTRY_HANDLE);
            }
            lastHandleDispatchAtMs = now;
            return true;
        }
        if (AssistConfig.MODE_APP.equals(configured)
                || AssistConfig.MODE_CUSTOM.equals(configured)) {
            if (!startConfiguredTarget(module, context, AssistConfig.ENTRY_HANDLE, pipeline,
                    assistManager)) {
                module.logWarn("gesture_handle_target_unavailable entry="
                        + AssistConfig.ENTRY_HANDLE + " mode=" + configured);
            }
            lastHandleDispatchAtMs = now;
            return true;
        }
        // "跟随系统默认": AOSP dispatch on this gesture simply goes to whatever the default assistant
        // is; Circle to Search appears because Google's assistant turns that invocation into it. This
        // build, however, routes a Google assistant invocation to the voice UI instead, so Circle to
        // Search is triggered through its own service - but only while the Google app really is the
        // configured assistant, otherwise the gesture must keep following the user's setting.
        if (cts != null && isGoogleAssistantConfigured(module, classLoader, context)) {
            try {
                if (cts.trigger()) {
                    module.logInfo("circle_to_search_triggered entrypoint="
                            + CtsHooks.CONTEXTUAL_SEARCH_ENTRYPOINT);
                    lastHandleDispatchAtMs = now;
                    return true;
                }
                module.logWarn("circle_to_search_unavailable reason=no_service");
            } catch (Throwable t) {
                module.logWarn("circle_to_search_failed " + t);
            }
        }
        if (assistManager == null) {
            module.logWarn("gesture_handle_long_press_fallback reason=no_assist_manager");
            return false;
        }
        Bundle args = new Bundle();
        args.putInt(EXTRA_INVOCATION_TYPE, INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        module.logInfo("gesture_handle_long_press invocationType="
                + INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        pipeline.dispatch(module, assistManager, args);
        lastHandleDispatchAtMs = now;
        return true;
    }

    /**
     * @return {@code true} when the configured default assistant is the Google app, i.e. when the
     *         navigation-handle gesture is expected to become Circle to Search
     */
    private static boolean isGoogleAssistantConfigured(
            AssistRestoreModule module, ClassLoader classLoader, Context context) {
        try {
            Class<?> assistUtilsClass = Class.forName(ASSIST_UTILS, false, classLoader);
            Constructor<?> constructor = assistUtilsClass.getConstructor(Context.class);
            Method getAssistComponentForUser =
                    assistUtilsClass.getMethod("getAssistComponentForUser", int.class);
            // -2 is the value the OEM code itself passes when it wants the current user's assistant.
            Object component = getAssistComponentForUser.invoke(constructor.newInstance(context), -2);
            if (component != null) {
                boolean google = component.toString().contains(CtsHooks.PKG_GOOGLE);
                module.logInfo("gesture_handle_assistant component=" + component
                        + " circleToSearch=" + google);
                return google;
            }
        } catch (Throwable t) {
            module.logWarn("gesture_handle_assistant_lookup_failed " + t);
        }
        if (context != null) {
            try {
                String configured = Settings.Secure.getString(
                        context.getContentResolver(), SETTING_ASSISTANT);
                if (configured != null) {
                    boolean google = configured.contains(CtsHooks.PKG_GOOGLE);
                    module.logInfo("gesture_handle_assistant setting=" + configured
                            + " circleToSearch=" + google);
                    return google;
                }
            } catch (Throwable t) {
                module.logWarn("gesture_handle_assistant_setting_failed " + t);
            }
        }
        // Assistant unknown: keep the Circle to Search path, which is what this gesture does on a
        // Google-assistant device, and fall back to the assistant dispatch if the service fails.
        module.logWarn("gesture_handle_assistant_unknown assuming google");
        return true;
    }

    /**
     * Mirrors the Settings rule for {@code gesture_side_wake_cui}: the switch stores its state in the
     * screen-recognition key when the build supports it, otherwise in the CUI key. Only an explicit
     * {@code 0} disables the gesture; an unset key keeps the pre-existing behaviour.
     */
    private static boolean isHandleWakeSwitchOff(AssistRestoreModule module, Context context) {
        // Settings writes both keys with user -2 (all users), so the calling-user lookup is enough.
        ContentResolver resolver = context.getContentResolver();
        int ocr = Settings.Secure.getInt(resolver, KEY_HANDLE_WAKE_OCR, -1);
        int cui = Settings.Secure.getInt(resolver, KEY_HANDLE_WAKE_CUI, -1);
        boolean anyOn = ocr == 1 || cui == 1;
        boolean anyOff = ocr == 0 || cui == 0;
        boolean off = !anyOn && anyOff;
        if (off) {
            module.logInfo("gesture_handle_long_press_skipped reason=nav_bar_switch_off"
                    + " ocr=" + ocr + " cui=" + cui);
        }
        return off;
    }

    /** {@code true} when the gesture handle is left to ColorOS' own screen recognition. */
    private static boolean isHandleOemMode() {
        return AssistConfig.MODE_OEM.equals(
                AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_HANDLE));
    }


    /** Maps the AOSP invocation type back to the entry that produced it. */
    private static String entryForInvocationType(Bundle bundle) {
        int invocationType = bundle == null ? 0 : bundle.getInt(EXTRA_INVOCATION_TYPE, 0);
        if (invocationType == INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS) {
            return AssistConfig.ENTRY_POWER;
        }
        if (invocationType == INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS) {
            return AssistConfig.ENTRY_HANDLE;
        }
        return AssistConfig.ENTRY_CORNER;
    }

    /** Runs the Circle to Search entry point; {@code false} tells the caller to fall back. */
    private static boolean triggerCircleToSearch(AssistRestoreModule module, CtsPipeline cts) {
        if (cts == null) {
            return false;
        }
        try {
            if (cts.trigger()) {
                module.logInfo("circle_to_search_triggered entrypoint="
                        + CtsHooks.CONTEXTUAL_SEARCH_ENTRYPOINT);
                return true;
            }
        } catch (Throwable t) {
            module.logWarn("circle_to_search_failed " + t);
        }
        return false;
    }

    /** The package's voice interaction service, when it declares one. */
    private static ComponentName resolveVoiceInteractionComponent(
            Context context, String packageName) {
        try {
            Intent probe = new Intent("android.service.voice.VoiceInteractionService")
                    .setPackage(packageName);
            List<ResolveInfo> matches =
                    context.getPackageManager().queryIntentServices(probe, 0);
            if (matches == null || matches.isEmpty()) {
                return null;
            }
            ServiceInfo info = matches.get(0).serviceInfo;
            return info == null ? null : new ComponentName(info.packageName, info.name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The AOSP invocation type an entry produces, reused for pinned assist sessions. */
    private static int invocationTypeForEntry(String entry) {
        if (AssistConfig.ENTRY_POWER.equals(entry)) {
            return INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS;
        }
        if (AssistConfig.ENTRY_HANDLE.equals(entry)) {
            return INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS;
        }
        return 1;
    }

    /** Starts the app pinned to {@code entry}; {@code false} tells the caller to fall back. */
    private static boolean startConfiguredTarget(
            AssistRestoreModule module, Context context, String entry, AssistPipeline pipeline,
            Object assistManager) {
        String packageName = AssistConfig.targetPackage(HookPrefs.get(), entry);
        if (context == null || packageName.isEmpty()) {
            module.logWarn("target_start_skipped entry=" + entry
                    + " reason=" + (context == null ? "no_context" : "no_package"));
            return false;
        }
        // Pinned voice interaction services are intentionally NOT dispatched through the assist
        // stack: startAssistInternal ignores the component for the service branch and opens the
        // session on whichever assistant currently holds the role, i.e. it would wake the wrong
        // app. Such a target therefore falls through to its own assist activity below.
        Intent intent = TargetIntents.build(context, HookPrefs.get(), entry);
        if (intent == null) {
            module.logWarn("target_start_skipped entry=" + entry + " reason=no_intent package="
                    + packageName);
            return false;
        }
        try {
            context.startActivity(intent);
            module.logInfo("target_started entry=" + entry
                    + " method=" + AssistConfig.targetMethod(HookPrefs.get(), entry)
                    + " component=" + intent.getComponent()
                    + " action=" + intent.getAction()
                    + " package=" + packageName);
            return true;
        } catch (Throwable t) {
            module.logWarn("target_start_failed entry=" + entry + " " + t);
            return false;
        }
    }

    private static Object lookupAssistManager(
            AssistRestoreModule module, ClassLoader classLoader, Class<?> assistManagerClass) {
        try {
            Class<?> dependency = Class.forName(DEPENDENCY, false, classLoader);
            Object container = dependency.getField("sDependency").get(null);
            if (container == null) {
                return null;
            }
            Method getDependency = dependency.getMethod("getDependencyInner", Object.class);
            return getDependency.invoke(container, assistManagerClass);
        } catch (Throwable t) {
            module.logError("assist_manager_lookup_failed", t);
            return null;
        }
    }
}
