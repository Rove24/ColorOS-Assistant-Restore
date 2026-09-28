package com.github.rove24.assistrestore;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Restores the AOSP default-assistant plumbing on a China-region ColorOS build.
 *
 * <p>The build keeps AOSP's {@code AssistUtils} / {@code VoiceInteractionManagerService} intact and
 * only diverts each entry point, so the module hooks the diversion points instead of replacing the
 * assistant stack:</p>
 *
 * <ul>
 *   <li>{@code system_server}: {@code PhoneWindowManagerExtImpl.startSpeech} sends the power-key long
 *   press straight to the OEM assistant.</li>
 *   <li>{@code com.android.systemui}: {@code AssistManager.startAssist} is region-gated and silently
 *   drops every assist request; {@code NavBarUtils.isAssistantAvailable} tells the launcher that the
 *   corner gesture must stay disabled; {@code SpeedChassistMainBusiness.onLongPressed} hardcodes the
 *   OEM assistant for the gesture handle.</li>
 * </ul>
 */
public final class AssistRestoreModule extends XposedModule {
    static final String TAG = "AssistRestore";

    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher";
    private static final String GOOGLE_APP_PACKAGE = CtsHooks.PKG_GOOGLE;

    private volatile String processName = "unknown";
    private volatile boolean systemUiInstalled;
    private volatile boolean launcherInstalled;
    private volatile boolean googleAppInstalled;
    private volatile boolean systemServerInstalled;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        logInfo("module_loaded process=" + processName
                + " systemServer=" + param.isSystemServer()
                + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + "/" + getFrameworkVersion());
        HookPrefs.attach(this);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (GOOGLE_APP_PACKAGE.equals(packageName) && GOOGLE_APP_PACKAGE.equals(processName)) {
            if (googleAppInstalled) {
                return;
            }
            try {
                GoogleAppHooks.install(this, param.getClassLoader());
                googleAppInstalled = true;
            } catch (Throwable t) {
                logError("google_app_install_failed", t);
            }
            return;
        }
        if (LAUNCHER_PACKAGE.equals(packageName) && LAUNCHER_PACKAGE.equals(processName)) {
            if (launcherInstalled) {
                return;
            }
            try {
                LauncherHooks.install(this, param.getClassLoader());
                launcherInstalled = true;
            } catch (Throwable t) {
                logError("launcher_install_failed", t);
            }
            return;
        }
        if (!SYSTEM_UI_PACKAGE.equals(packageName) || !SYSTEM_UI_PACKAGE.equals(processName)) {
            return;
        }
        if (systemUiInstalled) {
            return;
        }
        try {
            SystemUiHooks.install(this, param.getClassLoader());
            systemUiInstalled = true;
        } catch (Throwable t) {
            logError("systemui_install_failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        if (systemServerInstalled) {
            return;
        }
        try {
            SystemServerHooks.install(this, param.getClassLoader());
            CtsHooks.install(this, param.getClassLoader());
            HansProcessFreezeHooks.install(this, param.getClassLoader());
            systemServerInstalled = true;
        } catch (Throwable t) {
            logError("system_server_install_failed", t);
        }
    }

    void logInfo(String message) {
        log(Log.INFO, TAG, message);
    }

    void logWarn(String message) {
        log(Log.WARN, TAG, message);
    }

    void logError(String message) {
        log(Log.ERROR, TAG, message);
    }

    void logError(String message, Throwable t) {
        log(Log.ERROR, TAG, message, t);
    }
}
