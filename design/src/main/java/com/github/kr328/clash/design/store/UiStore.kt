package com.github.kr328.clash.design.store

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.model.AppInfoSort
import com.github.kr328.clash.design.model.DarkMode

class UiStore(context: Context) {
    private val context = context.applicationContext

    private val store = Store(
        this.context
            .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var enableVpn: Boolean by store.boolean(
        key = "enable_vpn",
        defaultValue = true
    )

    var darkMode: DarkMode by store.enum(
        key = "dark_mode",
        defaultValue = DarkMode.Auto,
        values = DarkMode.values()
    )

    var hideAppIcon: Boolean by store.boolean(
        key = "hide_app_icon",
        defaultValue = false,
    )

    var hideFromRecents: Boolean by store.boolean(
        key = "hide_from_recents",
        defaultValue = false,
    )

    var proxyExcludeNotSelectable by store.boolean(
        key = "proxy_exclude_not_selectable",
        defaultValue = false,
    )

    var proxyLine: Int by store.int(
        key = "proxy_line",
        defaultValue = 2
    )

    var proxySort: ProxySort by store.enum(
        key = "proxy_sort",
        defaultValue = ProxySort.Default,
        values = ProxySort.values()
    )

    var proxyLastGroup: String by store.string(
        key = "proxy_last_group",
        defaultValue = ""
    )

    var accessControlSort: AppInfoSort by store.enum(
        key = "access_control_sort",
        defaultValue = AppInfoSort.Label,
        values = AppInfoSort.values(),
    )

    var accessControlReverse: Boolean by store.boolean(
        key = "access_control_reverse",
        defaultValue = false
    )

    var accessControlSystemApp: Boolean by store.boolean(
        key = "access_control_system_app",
        defaultValue = false,
    )

    companion object {
        private const val PREFERENCE_NAME = "ui"

        val Context.mainActivityAlias: ComponentName
            get() = resolveMainActivityAlias()
                ?: ComponentName(this, "com.github.kr328.clash.MainActivityAlias")

        private fun Context.resolveMainActivityAlias(): ComponentName? {
            val resolveInfos = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )

            return resolveInfos.firstNotNullOfOrNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@firstNotNullOfOrNull null
                if (activityInfo.targetActivity == "com.github.kr328.clash.MainActivity" ||
                    activityInfo.name == "com.github.kr328.clash.MainActivityAlias"
                ) {
                    ComponentName(activityInfo.packageName, activityInfo.name)
                } else {
                    null
                }
            }
        }
    }

    fun syncMainActivityAliasState() {
        val targetState = if (hideAppIcon) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

        val currentState = context.packageManager.getComponentEnabledSetting(context.mainActivityAlias)
        if (currentState == targetState) {
            return
        }

        context.packageManager.setComponentEnabledSetting(
            context.mainActivityAlias,
            targetState,
            PackageManager.DONT_KILL_APP,
        )
    }
}
