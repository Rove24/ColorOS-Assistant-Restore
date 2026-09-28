package com.github.rove24.assistrestore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * {@code system_server} side restoration of the power-key long press.
 *
 * <p>{@code PhoneWindowManagerExtImpl.startSpeech} is the single funnel for "wake the assistant"
 * key gestures. On a China build the guard {@code mSpeechAsssistForBreeno} (driven by the
 * {@code oplus.software.speech_assist_for_breeno} feature) is true, so the method starts the OEM
 * assistant service with an explicit {@code ComponentName} and never consults the configured
 * assistant. This hook replays the AOSP branch of the very same method and lets
 * {@code PhoneWindowManager.launchAssistAction} route the request through
 * {@code SearchManager -> StatusBarManagerService -> SystemUI AssistManager}.</p>
 */
final class SystemServerHooks {
    private static final String PHONE_WINDOW_MANAGER_EXT =
            "com.android.server.policy.PhoneWindowManagerExtImpl";

    /** {@code startSpeech} sources observed in the OEM implementation. */
    private static final int SOURCE_POWER_KEY_LONG_PRESS = 1024;
    private static final int SOURCE_HOME_KEY_LONG_PRESS = 4;
    private static final int SOURCE_SHORTCUT = 102;

    /** AOSP invocation types passed to {@code launchAssistAction}. */
    private static final int INVOCATION_TYPE_OTHER = 0;
    private static final int INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS = 5;
    private static final int INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6;
    private static final int INVOCATION_TYPE_SHORTCUT = 7;

    /**
     * Haptic the OEM performs right before dispatching the assistant on the power key:
     * {@code mBase.getWrapper().performHapticFeedback(0, "Speech - Long Press")}. Replacing
     * {@code startSpeech} drops that call, which would leave the power key as the only assistant
     * entry without haptic feedback, so it is replayed here.
     */
    private static final int HAPTIC_EFFECT_LONG_PRESS = 0;
    private static final String HAPTIC_REASON = "Speech - Long Press";

    private SystemServerHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> windowManagerExt = Class.forName(PHONE_WINDOW_MANAGER_EXT, true, classLoader);
            Method startSpeech =
                    windowManagerExt.getDeclaredMethod("startSpeech", int.class, int.class, long.class);
            startSpeech.setAccessible(true);

            Field baseField = windowManagerExt.getDeclaredField("mBase");
            baseField.setAccessible(true);
            Field handledField = windowManagerExt.getDeclaredField("mSpeechLongPressHandled");
            handledField.setAccessible(true);

            // Resolved on first use so that the hook never pays reflection cost twice.
            final Method[] getWrapperRef = new Method[1];
            final Method[] launchAssistActionRef = new Method[1];
            final HapticCall[] hapticRef = new HapticCall[1];

            module.hook(startSpeech)
                    .setId("power_key_long_press")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (AssistConfig.MODE_NONE.equals(
                                AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_POWER))) {
                            // The entry is off: swallow the key so neither the OEM assistant nor the
                            // configured one starts, and skip the haptics that would imply otherwise.
                            module.logInfo("power_key_skip reason=disabled");
                            return null;
                        }
                        Object windowManagerExtInstance = chain.getThisObject();
                        Object base = baseField.get(windowManagerExtInstance);
                        if (base == null) {
                            module.logWarn("power_key_long_press_fallback reason=no_phone_window_manager");
                            return chain.proceed();
                        }
                        int deviceId = (Integer) chain.getArg(0);
                        int startSource = (Integer) chain.getArg(1);
                        long eventTime = (Long) chain.getArg(2);

                        if (getWrapperRef[0] == null) {
                            getWrapperRef[0] = base.getClass().getMethod("getWrapper");
                        }
                        Object wrapper = getWrapperRef[0].invoke(base);
                        if (wrapper == null) {
                            module.logWarn("power_key_long_press_fallback reason=no_wrapper");
                            return chain.proceed();
                        }
                        if (launchAssistActionRef[0] == null) {
                            Method launch = wrapper.getClass().getMethod(
                                    "launchAssistAction",
                                    String.class, int.class, long.class, int.class, int.class);
                            launch.setAccessible(true);
                            launchAssistActionRef[0] = launch;
                        }

                        int invocationType = invocationTypeFor(startSource);
                        module.logInfo("power_key_target mode="
                                + AssistConfig.mode(HookPrefs.get(), AssistConfig.ENTRY_POWER)
                                + " package="
                                + AssistConfig.targetPackage(HookPrefs.get(),
                                        AssistConfig.ENTRY_POWER));
                        // The OEM sets the flag first so that the key-up path does not fire twice.
                        handledField.setBoolean(windowManagerExtInstance, true);
                        module.logInfo("power_key_long_press startSource=" + startSource
                                + " deviceId=" + deviceId
                                + " invocationType=" + invocationType);
                        if (hapticRef[0] == null) {
                            hapticRef[0] = resolveHaptic(wrapper, base);
                        }
                        if (hapticRef[0] != null) {
                            try {
                                hapticRef[0].perform();
                                module.logInfo("power_key_haptic effect=" + HAPTIC_EFFECT_LONG_PRESS
                                        + " reason=" + HAPTIC_REASON);
                            } catch (Throwable t) {
                                // Feedback is cosmetic: never let it block the assistant dispatch.
                                module.logWarn("power_key_haptic_failed " + t);
                            }
                        }
                        // AOSP passes a null hint for the power key; the OEM hint would open the
                        // first-run navigation dialog instead of starting the assistant.
                        launchAssistActionRef[0].invoke(
                                wrapper, null, deviceId, eventTime, invocationType, 1);
                        return null;
                    });
            module.logInfo("hook_installed target=" + PHONE_WINDOW_MANAGER_EXT + ".startSpeech");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + PHONE_WINDOW_MANAGER_EXT + ".startSpeech", t);
        }
    }

    /** Wraps the resolved {@code performHapticFeedback(int, String)} call and its receiver. */
    private static final class HapticCall {
        private final Method method;
        private final Object target;

        private HapticCall(Method method, Object target) {
            this.method = method;
            this.target = target;
        }

        void perform() throws Throwable {
            method.invoke(target, HAPTIC_EFFECT_LONG_PRESS, HAPTIC_REASON);
        }
    }

    private static HapticCall resolveHaptic(Object wrapper, Object base) {
        Method onWrapper = findPerformHapticFeedback(wrapper.getClass());
        if (onWrapper != null) {
            return new HapticCall(onWrapper, wrapper);
        }
        Method onBase = findPerformHapticFeedback(base.getClass());
        if (onBase != null) {
            return new HapticCall(onBase, base);
        }
        return null;
    }

    private static Method findPerformHapticFeedback(Class<?> owner) {
        try {
            Method method = owner.getMethod("performHapticFeedback", int.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int invocationTypeFor(int startSource) {
        switch (startSource) {
            case SOURCE_POWER_KEY_LONG_PRESS:
                return INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS;
            case SOURCE_HOME_KEY_LONG_PRESS:
                return INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS;
            case SOURCE_SHORTCUT:
                return INVOCATION_TYPE_SHORTCUT;
            default:
                return INVOCATION_TYPE_OTHER;
        }
    }
}
