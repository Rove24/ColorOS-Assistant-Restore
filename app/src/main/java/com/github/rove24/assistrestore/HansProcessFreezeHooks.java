package com.github.rove24.assistrestore;

import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/** Google exemption at the OEM scene decision, before cgroup and firewall mutations. */
final class HansProcessFreezeHooks {
    private static final String SCENE = "com.android.server.hans.scene.HansSceneManager";

    private HansProcessFreezeHooks() {}

    static void install(AssistRestoreModule module, ClassLoader loader) {
        try {
            Class<?> scene = Class.forName(SCENE, false, loader);
            Class<?> pkg = Class.forName("com.android.server.hans.OplusHansPackage", false, loader);
            Class<?> restriction = Class.forName("com.android.server.hans.OplusHansRestriction", false, loader);
            Class<?> result = Class.forName(SCENE + "$Freezing", false, loader);
            Object important = result.getField("IMPORTANT").get(null);
            Method getPackage = pkg.getMethod("getPkgName");
            Method getUid = pkg.getMethod("getUid");
            installMethod(module, scene.getDeclaredMethod("freezeForSceneCombo", pkg, restriction),
                    getPackage, getUid, important);
            installMethod(module, scene.getDeclaredMethod("freezeDirectlyForSceneCombo", pkg),
                    getPackage, getUid, important);
        } catch (Throwable error) {
            module.logError("hook_failed target=" + SCENE, error);
        }
    }

    private static void installMethod(AssistRestoreModule module, Method method,
            Method getPackage, Method getUid, Object important) {
        method.setAccessible(true);
        module.hook(method)
                .setId("google_hans_scene_" + method.getName())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object pkg = chain.getArg(0);
                    if (pkg != null
                            && CtsHooks.PKG_GOOGLE.equals(getPackage.invoke(pkg))) {
                        module.logInfo("google_hans_scene_exempt method=" + method.getName()
                                + " uid=" + getUid.invoke(pkg));
                        // IMPORTANT is distinct from TRUE/HOLD: callers must not mark it frozen.
                        return important;
                    }
                    return chain.proceed();
                });
        module.logInfo("hook_installed target=" + SCENE + "." + method.getName());
    }
}
