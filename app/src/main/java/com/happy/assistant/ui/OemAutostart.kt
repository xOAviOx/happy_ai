package com.happy.assistant.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Vendor skins kill background services regardless of the battery-optimisation
 * whitelist, and they do it silently. Spec section 5: deep-link the user to the
 * right screen, because otherwise this reads as a bug in Happy rather than a
 * setting on the phone.
 *
 * Vendor screens are hostile to link to. Verified on a vivo V2502 running
 * Android 16, where the power-manager activities exist, resolve, and still throw
 * SecurityException when launched by explicit component - they are only reachable
 * through the private actions their intent filters declare, if at all. So this
 * builds an ordered list of candidates rather than picking one, the caller tries
 * them until something opens, and the standard Android battery screen is the last
 * stop before plain app info.
 *
 * Never trust a single component name here. Vendors rename these between skin
 * versions and the failure is always silent.
 */
object OemAutostart {

    /** Explicit vendor components, best first. */
    private val COMPONENTS: Map<String, List<Pair<String, String>>> = mapOf(
        "xiaomi" to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "redmi" to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "poco" to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "oppo" to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        "realme" to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        "vivo" to listOf(
            "com.iqoo.powersaving" to "com.iqoo.powersaving.activity.ExcessivePowerManagerActivity",
            "com.vivo.abe" to "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
            "com.iqoo.powersaving" to "com.iqoo.powersaving.PowerManagerSettingsActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        "oneplus" to listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        "huawei" to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        "honor" to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        "samsung" to listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
    )

    /**
     * Private actions the vendor activities declare in their intent filters. An
     * implicit intent sometimes gets through where an explicit component does not,
     * because the filter is what the vendor actually intended to be entered by.
     */
    private val ACTIONS: Map<String, List<String>> = mapOf(
        "vivo" to listOf(
            "com.vivo.abe.highpower.search.powermanageractivity",
            "com.iqoo.powersaving.PowerManagerSettingsActivity.search",
            "com.iqoo.powersaving.PowerSavingManagerActivity.search",
        ),
        "xiaomi" to listOf("miui.intent.action.OP_AUTO_START"),
        "redmi" to listOf("miui.intent.action.OP_AUTO_START"),
        "poco" to listOf("miui.intent.action.OP_AUTO_START"),
    )

    /** Human-readable hint for whatever skin this phone is running. */
    fun hintFor(manufacturer: String = Build.MANUFACTURER): String =
        when (manufacturer.lowercase()) {
            "xiaomi", "redmi", "poco" ->
                "MIUI: Settings, Apps, Manage apps, Happy. Turn on Autostart and set Battery saver to No restrictions."
            "oppo", "realme" ->
                "ColorOS: Settings, Battery, Background power consumption. Allow Happy, and turn on Auto-startup."
            "vivo" ->
                "Funtouch: Fix opens the vivo power manager. Allow background power usage for Happy, and turn on Auto-start if this build has it."
            "oneplus" ->
                "OxygenOS: Settings, Battery, Battery optimisation. Set Happy to Do not optimise, and lock it in recents."
            "huawei", "honor" ->
                "EMUI: Settings, Apps, Happy, App launch. Switch to Manage manually with all three switches on."
            "samsung" ->
                "One UI: Settings, Battery, Background usage limits. Put Happy in Never sleeping apps."
            else ->
                "Exclude Happy from any battery or startup manager this phone ships with."
        }

    /**
     * Every screen worth trying for this phone, best first, already filtered to the
     * ones that exist and are launchable by a normal app.
     *
     * The exported check matters: a vendor activity that resolves is not
     * necessarily one a third-party app may start, and launching it anyway throws
     * SecurityException. Filtering here means a blocked candidate falls through to
     * the next one instead of ending the search.
     */
    fun intentsFor(context: Context, manufacturer: String = Build.MANUFACTURER): List<Intent> {
        val key = manufacturer.lowercase()
        val candidates = mutableListOf<Intent>()

        COMPONENTS[key].orEmpty().forEach { (pkg, cls) ->
            candidates += Intent().setComponent(ComponentName(pkg, cls))
        }
        ACTIONS[key].orEmpty().forEach { action ->
            candidates += Intent(action)
        }

        val launchable = candidates.filter { launchable(context, it) }.toMutableList()

        // Not vendor-specific and always present: the system list of apps exempt
        // from battery optimisation. A worse screen than the vendor one, but a far
        // better landing place than app info.
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .takeIf { launchable(context, it) }
            ?.let { launchable += it }

        return launchable.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun launchable(context: Context, intent: Intent): Boolean {
        val info = context.packageManager.resolveActivity(intent, 0) ?: return false
        return info.activityInfo?.exported == true
    }

    fun isKnownSkin(manufacturer: String = Build.MANUFACTURER): Boolean =
        COMPONENTS.containsKey(manufacturer.lowercase())
}
