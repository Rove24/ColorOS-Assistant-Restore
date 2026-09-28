package com.github.rove24.assistrestore;

import android.content.SharedPreferences;

/**
 * Process-local handle on the module's RemotePreferences.
 *
 * <p>{@code XposedInterface.getRemotePreferences} is read-only inside hooked processes, which is
 * all the hooks need: the settings UI is the only writer.</p>
 */
final class HookPrefs {
    private static volatile SharedPreferences prefs;
    private static volatile boolean attached;

    private HookPrefs() {
    }

    static void attach(AssistRestoreModule module) {
        try {
            prefs = module.getRemotePreferences(AssistConfig.PREFS);
            attached = true;
            module.logInfo("hook_prefs_attached group=" + AssistConfig.PREFS);
        } catch (Throwable t) {
            prefs = null;
            attached = false;
            module.logWarn("hook_prefs_failed " + t);
        }
    }

    static SharedPreferences get() {
        return prefs;
    }

    static boolean isAttached() {
        return attached;
    }
}
