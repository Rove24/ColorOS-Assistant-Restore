package com.github.rove24.assistrestore.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Backs the advanced page's "hide the launcher icon" switch.
 *
 * <p>The launcher entry is the {@code .LauncherEntry} activity alias, so the icon can be switched
 * off without touching [MainActivity]. MainActivity keeps a MAIN + INFO filter, which is what
 * {@code PackageManager.getLaunchIntentForPackage()} resolves when no launcher entry is enabled -
 * and that is also what LSPosed and the system app-info page use - so the settings screen stays
 * reachable while the icon is hidden.</p>
 *
 * <p>The component state is the only source of truth: the switch always shows what the package
 * manager currently reports, including after a reinstall.</p>
 */
internal object LauncherIcon {
    private const val LAUNCHER_ENTRY = ".LauncherEntry"

    fun isHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun component(context: Context) =
        ComponentName(context.packageName, context.packageName + LAUNCHER_ENTRY)
}
