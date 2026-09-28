package com.github.rove24.assistrestore;

import java.lang.reflect.Field;

/**
 * Makes the Google app believe it runs on a device Google ships Circle to Search for.
 *
 * <p>GSA decides whether Circle to Search is available from the device identity, so on a ColorOS
 * device the feature stays off even when the framework side is fixed. The values below are the
 * Google Pixel 11 Pro XL identity, applied only inside the Google app process because that is the
 * only process this hook is injected into.</p>
 *
 * <p>{@code PRODUCT} / {@code DEVICE} are the build codenames
 * ({@code getprop ro.product.name} / {@code ro.product.device}).</p>
 *
 * <p>The technique follows the approach used by the Oplus-Assistant-Hook project.</p>
 */
final class GoogleAppHooks {
    private static final String MANUFACTURER = "Google";
    private static final String BRAND = "google";
    private static final String MODEL = "Pixel 11 Pro XL";
    private static final String PRODUCT = "kodiak";
    private static final String DEVICE = "PKK4";

    private GoogleAppHooks() {
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        if (!AssistConfig.spoofGoogleBuild(HookPrefs.get())) {
            module.logInfo("google_app_identity_spoof_skipped reason=switch_off");
            return;
        }
        try {
            Class<?> build = Class.forName("android.os.Build", true, classLoader);
            setStaticField(build, "MANUFACTURER", MANUFACTURER);
            setStaticField(build, "BRAND", BRAND);
            setStaticField(build, "MODEL", MODEL);
            setStaticField(build, "PRODUCT", PRODUCT);
            setStaticField(build, "DEVICE", DEVICE);
            module.logInfo("google_app_identity_spoofed model=" + MODEL
                    + " manufacturer=" + MANUFACTURER);
        } catch (Throwable t) {
            module.logError("google_app_identity_spoof_failed", t);
        }
    }

    private static void setStaticField(Class<?> owner, String name, String value)
            throws Throwable {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
