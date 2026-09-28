package com.github.rove24.assistrestore.ui

import android.content.Context
import android.content.SharedPreferences
import com.github.rove24.assistrestore.AssistConfig
import io.github.libxposed.service.XposedService

/**
 * Reads and writes the configuration the hooks consume.
 *
 * <p>Values live in the framework's RemotePreferences so every hooked process sees them; a private
 * file is used only while the framework bridge is not connected, so the UI stays usable (and its
 * values are migrated once the bridge appears).</p>
 */
class SettingsStore(context: Context) {

    @Volatile
    private var remote: SharedPreferences? = null

    private val fallback: SharedPreferences =
        context.getSharedPreferences(AssistConfig.PREFS, Context.MODE_PRIVATE)

    var frameworkConnected: Boolean = false
        private set

    fun bind(service: XposedService?) {
        val bound = try {
            service?.getRemotePreferences(AssistConfig.PREFS)
        } catch (_: Throwable) {
            null
        }
        remote = bound
        frameworkConnected = bound != null
        if (bound != null) {
            migrateLocalValues(bound)
        }
    }

    private fun current(): SharedPreferences = remote ?: fallback

    /** Copies anything configured before the bridge appeared, so the hooks pick it up. */
    private fun migrateLocalValues(target: SharedPreferences) {
        val local = fallback.all
        if (local.isEmpty()) {
            return
        }
        target.edit().apply {
            for ((key, value) in local) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
            }
        }.apply()
    }

    private fun put(key: String, value: Any) {
        current().edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
    }

    fun skipOcrPreload(): Boolean =
        current().getBoolean(AssistConfig.KEY_SKIP_OCR_PRELOAD, AssistConfig.DEFAULT_SKIP_OCR_PRELOAD)

    fun setSkipOcrPreload(value: Boolean) = put(AssistConfig.KEY_SKIP_OCR_PRELOAD, value)

    fun unblockPageFlags(): Boolean = current()
        .getBoolean(AssistConfig.KEY_UNBLOCK_PAGE_FLAGS, AssistConfig.DEFAULT_UNBLOCK_PAGE_FLAGS)

    fun setUnblockPageFlags(value: Boolean) = put(AssistConfig.KEY_UNBLOCK_PAGE_FLAGS, value)

    fun spoofGoogleBuild(): Boolean = current()
        .getBoolean(AssistConfig.KEY_SPOOF_GOOGLE_BUILD, AssistConfig.DEFAULT_SPOOF_GOOGLE_BUILD)

    fun setSpoofGoogleBuild(value: Boolean) = put(AssistConfig.KEY_SPOOF_GOOGLE_BUILD, value)

    fun handleWhenBarHidden(): Boolean = current().getBoolean(
        AssistConfig.KEY_HANDLE_WHEN_BAR_HIDDEN,
        AssistConfig.DEFAULT_HANDLE_WHEN_BAR_HIDDEN,
    )

    fun setHandleWhenBarHidden(value: Boolean) =
        put(AssistConfig.KEY_HANDLE_WHEN_BAR_HIDDEN, value)

    fun pixelLights(): Boolean =
        current().getBoolean(AssistConfig.KEY_PIXEL_LIGHTS, AssistConfig.DEFAULT_PIXEL_LIGHTS)

    fun setPixelLights(value: Boolean) = put(AssistConfig.KEY_PIXEL_LIGHTS, value)

    fun mode(entry: String): String =
        current().getString(entry + "_mode", AssistConfig.MODE_DEFAULT) ?: AssistConfig.MODE_DEFAULT

    fun targetPackage(entry: String): String =
        current().getString(entry + "_package", "") ?: ""

    fun targetComponent(entry: String): String =
        current().getString(entry + "_component", "") ?: ""

    fun targetMethod(entry: String): String =
        current().getString(entry + "_method", AssistConfig.METHOD_AUTO) ?: AssistConfig.METHOD_AUTO

    fun targetArgs(entry: String): String =
        current().getString(entry + "_args", "") ?: ""

    /**
     * Stores one entry's target. {@code mode} is one of {@code AssistConfig.MODE_*}; every other
     * parameter is written only when it is given, so picking another mode does not wipe the fields
     * the user already filled in on the custom screen — they survive as history and can be
     * re-activated by saving again.
     */
    fun setTarget(
        entry: String,
        mode: String,
        packageName: String? = null,
        component: String? = null,
        method: String? = null,
        args: String? = null,
    ) {
        current().edit().apply {
            putString(entry + "_mode", mode)
            packageName?.let { putString(entry + "_package", it) }
            component?.let { putString(entry + "_component", it) }
            method?.let { putString(entry + "_method", it) }
            args?.let { putString(entry + "_args", it) }
        }.apply()
    }
}
