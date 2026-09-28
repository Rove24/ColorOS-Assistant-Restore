package com.github.rove24.assistrestore;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Launcher side of the corner gesture's page-level unlock.
 *
 * <p>The launcher learns which system-UI state flags are active through
 * {@code ILauncherProxy.onSystemUiStateChanged(long, int)}. On this build the implementation is
 * {@code OplusAbsOverviewProxyImpl}, whose {@code onSystemUiStateChanged} forwards straight into
 * {@code interceptOnSystemUiStateChanged(long, int)} — the build's own interception point, and the
 * place the flags are stored for the gesture pipeline. Filtering the argument there is what keeps
 * the corner gesture alive on pages that ask for it to be suppressed.</p>
 *
 * <p>ColorOS 17 deleted {@code com.android.systemui.shared.system.QuickStepContract} from the
 * launcher, and with it {@code isAssistantGestureDisabled(long)} — the hook this module used on
 * ColorOS 16. That is why the filter moved here.</p>
 *
 * <p>Two of the flags are requested by the focused application — {@code SYSUI_STATE_OVERVIEW_DISABLED}
 * (128) and {@code SYSUI_STATE_SEARCH_DISABLED} (1024) — and are what make individual system pages,
 * such as pages inside Settings, refuse the gesture. They carry no gesture conflict of their own, so
 * only they are cleared; the state-based blocks (screen pinning, nav bar hidden, quick settings, the
 * lock screen, the notification shade) are left exactly as the system reported them.</p>
 */
final class LauncherHooks {
    private static final String OVERVIEW_PROXY =
            "com.oplus.quickstep.proxy.OplusAbsOverviewProxyImpl";

    /** Application-requested flags; they only suppress the gesture, nothing else. */
    private static final long PAGE_LEVEL_STATES = 128L /* SYSUI_STATE_OVERVIEW_DISABLED */
            | 1024L /* SYSUI_STATE_SEARCH_DISABLED */;

    private LauncherHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> proxy = Class.forName(OVERVIEW_PROXY, true, classLoader);
            // Matched by shape rather than by name alone: it is a Kotlin method whose parameter
            // names are not part of any contract, but (long, int) -> boolean is.
            Method intercept = null;
            for (Method candidate : proxy.getDeclaredMethods()) {
                if (!"interceptOnSystemUiStateChanged".equals(candidate.getName())) {
                    continue;
                }
                Class<?>[] parameterTypes = candidate.getParameterTypes();
                if (parameterTypes.length == 2
                        && parameterTypes[0] == long.class
                        && parameterTypes[1] == int.class) {
                    candidate.setAccessible(true);
                    intercept = candidate;
                    break;
                }
            }
            if (intercept == null) {
                throw new NoSuchMethodException(OVERVIEW_PROXY + ".interceptOnSystemUiStateChanged");
            }

            module.hook(intercept)
                    .setId("assistant_gesture_disabled")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        long flags = (Long) chain.getArg(0);
                        if (!AssistConfig.unblockPageFlags(HookPrefs.get())) {
                            return chain.proceed();
                        }
                        long pageOnly = flags & PAGE_LEVEL_STATES;
                        if (pageOnly == 0) {
                            // The caller only sent state-based flags, which this hook deliberately
                            // does not touch.
                            return chain.proceed();
                        }
                        module.logInfo("assist_gesture_unblocked pageFlags=0x"
                                + Long.toHexString(pageOnly)
                                + " flags=0x" + Long.toHexString(flags));
                        // getArgs() is immutable, so the filtered value has to go through proceed().
                        return chain.proceed(new Object[] {
                                flags & ~PAGE_LEVEL_STATES,
                                chain.getArg(1),
                        });
                    });
            module.logInfo("hook_installed target=" + OVERVIEW_PROXY
                    + ".interceptOnSystemUiStateChanged");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + OVERVIEW_PROXY
                    + ".interceptOnSystemUiStateChanged", t);
        }
    }
}
