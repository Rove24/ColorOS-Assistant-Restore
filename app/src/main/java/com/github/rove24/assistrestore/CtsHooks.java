package com.github.rove24.assistrestore;

import android.content.Context;
import android.os.Binder;
import android.os.Process;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Restores the framework side of Circle to Search on builds that ship the service but never wire
 * it up (China-region ColorOS).
 *
 * <p>{@code com.android.server.contextualsearch.ContextualSearchManagerService} exists in this
 * build, but three things are missing or empty compared with a device that has Circle to Search:</p>
 *
 * <ol>
 *   <li>{@code SystemServer.deviceHasConfigString(context, R.string.config_defaultContextualSearchPackageName)}
 *   is false, so the framework believes no contextual-search package is configured and never starts
 *   the service.</li>
 *   <li>{@code ContextualSearchManagerService.getContextualSearchPackageName()} resolves the
 *   configured package from a framework resource that is blank here, so even a direct
 *   {@code startContextualSearch} call has no target.</li>
 *   <li>{@code enforcePermission()} demands {@code android.permission.ACCESS_CONTEXTUAL_SEARCH},
 *   which SystemUI (the component that owns the navigation gesture) does not hold. The bypass is
 *   limited to the system uid and to SystemUI, so the permission is not opened up in general.</li>
 * </ol>
 *
 * <p>The technique follows the approach used by the Oplus-Assistant-Hook project.</p>
 */
final class CtsHooks {
    private static final String CTS_SERVICE =
            "com.android.server.contextualsearch.ContextualSearchManagerService";
    private static final String SYSTEM_SERVER = "com.android.server.SystemServer";

    static final String PKG_GOOGLE = "com.google.android.googlequicksearchbox";
    private static final String PKG_SYSTEMUI = "com.android.systemui";

    /** Entry point value SystemUI/style integrations use for the "circle" gesture. */
    static final int CONTEXTUAL_SEARCH_ENTRYPOINT = 2;

    private CtsHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        int packageNameResId = resolvePackageNameResId(module, classLoader);
        installDeviceHasConfigString(module, classLoader, packageNameResId);
        installContextualSearchPackageName(module, classLoader);
        installPermissionBypass(module, classLoader);
        installStartContextualSearch(module, classLoader);
    }

    /** Reads {@code com.android.internal.R.string.config_defaultContextualSearchPackageName}. */
    private static int resolvePackageNameResId(AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> rString = Class.forName("com.android.internal.R$string", true, classLoader);
            int id = rString.getField("config_defaultContextualSearchPackageName").getInt(null);
            module.logInfo("cts_resource_ids packageNameId=" + id);
            return id;
        } catch (Throwable t) {
            module.logError("cts_resource_ids_failed", t);
            return 0;
        }
    }

    private static void installDeviceHasConfigString(
            AssistRestoreModule module, ClassLoader classLoader, int packageNameResId) {
        try {
            Class<?> systemServer = Class.forName(SYSTEM_SERVER, true, classLoader);
            Method method = method(systemServer, "deviceHasConfigString", Context.class, int.class);
            if (method == null) {
                module.logError("hook_skipped target=" + SYSTEM_SERVER + ".deviceHasConfigString"
                        + " reason=not_found");
                return;
            }
            module.hook(method)
                    .setId("cts_device_has_config_string")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        int resId = (Integer) chain.getArg(1);
                        if (packageNameResId != 0 && resId == packageNameResId) {
                            module.logInfo("cts_device_has_config_string forced=true");
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    });
            module.logInfo("hook_installed target=" + SYSTEM_SERVER + ".deviceHasConfigString");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + SYSTEM_SERVER + ".deviceHasConfigString", t);
        }
    }

    private static void installContextualSearchPackageName(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> service = Class.forName(CTS_SERVICE, true, classLoader);
            Method method = method(service, "getContextualSearchPackageName");
            if (method == null) {
                module.logError("hook_skipped target=" + CTS_SERVICE
                        + ".getContextualSearchPackageName reason=not_found");
                return;
            }
            module.hook(method)
                    .setId("cts_package_name")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> PKG_GOOGLE);
            module.logInfo("hook_installed target=" + CTS_SERVICE + ".getContextualSearchPackageName");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + CTS_SERVICE
                    + ".getContextualSearchPackageName", t);
        }
    }

    private static void installPermissionBypass(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> service = Class.forName(CTS_SERVICE, true, classLoader);
            Method method = method(service, "enforcePermission", String.class);
            if (method == null) {
                module.logError("hook_skipped target=" + CTS_SERVICE + ".enforcePermission"
                        + " reason=not_found");
                return;
            }
            module.hook(method)
                    .setId("cts_enforce_permission")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (isTrustedCaller(module)) {
                            // Only the navigation-gesture owner may skip the check; everything else
                            // keeps the original SecurityException behaviour.
                            return null;
                        }
                        return chain.proceed();
                    });
            module.logInfo("hook_installed target=" + CTS_SERVICE + ".enforcePermission");
        } catch (Throwable t) {
            module.logError("hook_failed target=" + CTS_SERVICE + ".enforcePermission", t);
        }
    }

    /**
     * A trusted {@code startContextualSearch} runs with a cleared calling identity, so the media
     * projection and screenshot-wrapped activity start inside the service are attributed the same
     * way a platform caller would be.
     */
    private static void installStartContextualSearch(
            AssistRestoreModule module, ClassLoader classLoader) {
        try {
            Class<?> service = Class.forName(CTS_SERVICE, true, classLoader);
            int hooked = hookStartContextualSearch(module, service);
            for (Class<?> inner : service.getDeclaredClasses()) {
                hooked += hookStartContextualSearch(module, inner);
            }
            if (hooked == 0) {
                module.logError("hook_skipped target=" + CTS_SERVICE
                        + ".startContextualSearch reason=not_found");
            } else {
                module.logInfo("hook_installed target=" + CTS_SERVICE
                        + ".startContextualSearch count=" + hooked);
            }
        } catch (Throwable t) {
            module.logError("hook_failed target=" + CTS_SERVICE + ".startContextualSearch", t);
        }
    }

    private static int hookStartContextualSearch(AssistRestoreModule module, Class<?> owner) {
        int count = 0;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (!"startContextualSearch".equals(candidate.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length != 1 || parameterTypes[0] != int.class) {
                continue;
            }
            candidate.setAccessible(true);
            try {
                module.hook(candidate)
                        .setId("cts_start_contextual_search")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!isTrustedCaller(module)) {
                                return chain.proceed();
                            }
                            long token = Binder.clearCallingIdentity();
                            try {
                                module.logInfo("cts_start_contextual_search entrypoint="
                                        + chain.getArg(0));
                                return chain.proceed();
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        });
                count++;
            } catch (Throwable t) {
                module.logError("hook_failed target=" + owner.getName()
                        + ".startContextualSearch", t);
            }
        }
        return count;
    }

    /**
     * @return {@code true} for {@code system} or for SystemUI, the two callers that legitimately
     *         drive this module's contextual-search entry point
     */
    static boolean isTrustedCaller(AssistRestoreModule module) {
        int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID) {
            return true;
        }
        String[] packages = packagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String candidate : packages) {
            if (PKG_SYSTEMUI.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String[] packagesForUid(int uid) {
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Object packageManager = appGlobals.getMethod("getPackageManager").invoke(null);
            if (packageManager == null) {
                return null;
            }
            Method getPackagesForUid =
                    packageManager.getClass().getMethod("getPackagesForUid", int.class);
            return (String[]) getPackagesForUid.invoke(packageManager, uid);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
        Method found = Refl.method(owner, name, parameterTypes);
        if (found != null) {
            found.setAccessible(true);
        }
        return found;
    }
}
