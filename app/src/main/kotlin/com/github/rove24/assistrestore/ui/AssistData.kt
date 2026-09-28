package com.github.rove24.assistrestore.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** One application the system offers for the assistant role. */
data class AssistantApp(
    val packageName: String,
    val label: String,
    val detail: String,
    val icon: ImageBitmap?,
)

/**
 * Read-only snapshot of what the device currently offers. Filled with the same two queries
 * ColorOS' own default-assistant page uses, so the list matches the system one.
 */
data class AssistantSnapshot(
    val apps: List<AssistantApp>,
    val defaultLabel: String,
    val defaultPackage: String?,
    val defaultIcon: ImageBitmap?,
) {
    companion object {
        private const val VIS_ACTION = "android.service.voice.VoiceInteractionService"
        private const val ASSIST_ACTION = "android.intent.action.ASSIST"

        fun load(context: Context, iconPx: Int = 144): AssistantSnapshot {
            val pm = context.packageManager
            val found = LinkedHashMap<String, AssistantApp>()

            fun add(packageName: String, detail: String) {
                if (packageName == context.packageName || found.containsKey(packageName)) return
                val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(packageName)
                val icon = runCatching { info.loadIcon(pm).rasterize(iconPx) }.getOrNull()
                found[packageName] = AssistantApp(packageName, label, detail, icon)
            }

            runCatching {
                pm.queryIntentServices(Intent(VIS_ACTION), 0).forEach { resolved ->
                    resolved.serviceInfo?.packageName?.let { add(it, "支持助理 · 语音交互服务") }
                }
            }
            runCatching {
                pm.queryIntentActivities(Intent(ASSIST_ACTION), 0).forEach { resolved ->
                    resolved.activityInfo?.packageName?.let { add(it, "只有 ACTION_ASSIST") }
                }
            }

            val assistantComponent = runCatching {
                Settings.Secure.getString(context.contentResolver, "assistant")
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { ComponentName.unflattenFromString(it) }

            val defaultPackage = assistantComponent?.packageName
            val defaultLabel = defaultPackage?.let { pkg ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrNull()
            } ?: "未设置"
            val defaultIcon = defaultPackage?.let { pkg ->
                runCatching { pm.getApplicationIcon(pkg).rasterize(iconPx) }.getOrNull()
            }

            return AssistantSnapshot(found.values.toList(), defaultLabel, defaultPackage, defaultIcon)
        }
    }
}

/** Renders any launcher icon (including adaptive ones) into something Compose can draw. */
private fun Drawable.rasterize(sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, sizePx, sizePx)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
