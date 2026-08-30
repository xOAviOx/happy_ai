package com.happy.assistant.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class CheckStatus {
    /** Granted, and Happy can use it now. */
    GRANTED,

    /** Needed, and not granted. */
    MISSING,

    /** Cannot be read programmatically; the user has to look. */
    UNKNOWN,

    /** The component that consumes this arrives in a later phase. */
    LATER,
}

sealed interface CheckKind {
    /** Ordinary runtime permissions, granted by a system dialog. */
    data class Runtime(val permissions: List<String>) : CheckKind

    /** Battery optimisation whitelist. */
    data object BatteryOptimisation : CheckKind

    /** WRITE_SETTINGS, which needs its own settings screen. */
    data object WriteSettings : CheckKind

    /** Notification listener access, a Settings toggle. Phase 5. */
    data object NotificationAccess : CheckKind

    /** Accessibility service, a Settings toggle. Phase 6. */
    data object Accessibility : CheckKind

    /** Vendor autostart or background-execution screen. */
    data object Autostart : CheckKind

    /** Display over other apps, which is what lets a background service launch one. */
    data object DisplayOverApps : CheckKind

    /** Do Not Disturb access, needed to set the ringer to silent. */
    data object DoNotDisturb : CheckKind
}

data class CheckItem(
    val id: String,
    val title: String,
    val detail: String,
    /** Which build phase actually consumes this. Shown as a chip. */
    val phase: Int,
    val kind: CheckKind,
    /**
     * False when the thing being toggled does not exist yet - the accessibility
     * and notification-listener services are only declared in Phases 6 and 5, and
     * until then Happy will not even appear in those Settings lists. Saying so is
     * the difference between a checklist and a mystery.
     */
    val implemented: Boolean = true,
)

/**
 * Everything from spec section 9, in the order it matters.
 *
 * Later-phase permissions are listed from Phase 0 on purpose: granting them early
 * is harmless, and a half-granted checklist is the single most common reason an
 * assistant like this looks broken.
 */
fun happyChecklist(): List<CheckItem> = buildList {
    add(
        CheckItem(
            id = "notifications",
            title = "Notifications",
            detail = "Without this the foreground notification is hidden and Android may not let the service start at all.",
            phase = 0,
            kind = CheckKind.Runtime(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
            ),
        )
    )
    add(
        CheckItem(
            id = "battery",
            title = "Ignore battery optimisation",
            detail = "Doze will otherwise suspend the wake-word loop within minutes of the screen going off.",
            phase = 0,
            kind = CheckKind.BatteryOptimisation,
        )
    )
    add(
        CheckItem(
            id = "autostart",
            title = "Autostart and background execution",
            detail = OemAutostart.hintFor(),
            phase = 0,
            kind = CheckKind.Autostart,
        )
    )
    add(
        CheckItem(
            id = "microphone",
            title = "Microphone",
            detail = "The wake word and speech recognition both need it.",
            phase = 1,
            kind = CheckKind.Runtime(listOf(Manifest.permission.RECORD_AUDIO)),
        )
    )
    add(
        CheckItem(
            id = "overlay",
            title = "Display over other apps",
            detail = "Without this, opening an app or setting an alarm by voice fails while the screen is elsewhere.",
            phase = 4,
            kind = CheckKind.DisplayOverApps,
        )
    )
    add(
        CheckItem(
            id = "dnd",
            title = "Do Not Disturb access",
            detail = "Only needed to put the phone on silent by voice.",
            phase = 4,
            kind = CheckKind.DoNotDisturb,
        )
    )
    add(
        CheckItem(
            id = "phone",
            title = "Phone and call log",
            detail = "Placing calls, answering, and answering who called me.",
            phase = 5,
            kind = CheckKind.Runtime(
                listOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE,
                )
            ),
        )
    )
    add(
        CheckItem(
            id = "contacts",
            title = "Contacts",
            detail = "Turning a spoken name into a number.",
            phase = 5,
            kind = CheckKind.Runtime(listOf(Manifest.permission.READ_CONTACTS)),
        )
    )
    add(
        CheckItem(
            id = "sms",
            title = "Send SMS",
            detail = "Sending a text without opening the messaging app.",
            phase = 5,
            kind = CheckKind.Runtime(listOf(Manifest.permission.SEND_SMS)),
        )
    )
    add(
        CheckItem(
            id = "notification_access",
            title = "Notification access",
            detail = "Reading notifications aloud and replying to them inline.",
            phase = 5,
            kind = CheckKind.NotificationAccess,
            implemented = false,
        )
    )
    add(
        CheckItem(
            id = "accessibility",
            title = "Accessibility service",
            detail = "Screen reading, back and home, and sending a WhatsApp message.",
            phase = 6,
            kind = CheckKind.Accessibility,
            implemented = false,
        )
    )
    add(
        CheckItem(
            id = "write_settings",
            title = "Modify system settings",
            detail = "Screen brightness.",
            phase = 4,
            kind = CheckKind.WriteSettings,
        )
    )
    add(
        CheckItem(
            id = "calendar",
            title = "Calendar",
            detail = "Reading the day ahead and adding events.",
            phase = 9,
            kind = CheckKind.Runtime(
                listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            ),
        )
    )
    add(
        CheckItem(
            id = "camera",
            title = "Camera",
            detail = "Reading text, scanning codes, translating what the camera sees.",
            phase = 9,
            kind = CheckKind.Runtime(listOf(Manifest.permission.CAMERA)),
        )
    )
    add(
        CheckItem(
            id = "location",
            title = "Location",
            detail = "Where am I, and navigation.",
            phase = 9,
            kind = CheckKind.Runtime(listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
        )
    )
}

/**
 * Reads live status. Called on every resume, because the user leaves the app to
 * flip these switches and comes back expecting the ticks to be right.
 */
fun statusOf(context: Context, item: CheckItem): CheckStatus {
    if (!item.implemented) return CheckStatus.LATER
    return when (val kind = item.kind) {
        is CheckKind.Runtime ->
            if (kind.permissions.all { granted(context, it) }) CheckStatus.GRANTED
            else CheckStatus.MISSING

        CheckKind.BatteryOptimisation -> {
            val power = context.getSystemService(PowerManager::class.java)
            if (power.isIgnoringBatteryOptimizations(context.packageName)) CheckStatus.GRANTED
            else CheckStatus.MISSING
        }

        CheckKind.WriteSettings ->
            if (Settings.System.canWrite(context)) CheckStatus.GRANTED else CheckStatus.MISSING

        CheckKind.NotificationAccess ->
            if (enabledNotificationListener(context)) CheckStatus.GRANTED else CheckStatus.MISSING

        CheckKind.Accessibility ->
            if (enabledAccessibilityService(context)) CheckStatus.GRANTED else CheckStatus.MISSING

        CheckKind.DisplayOverApps ->
            if (Settings.canDrawOverlays(context)) CheckStatus.GRANTED else CheckStatus.MISSING

        CheckKind.DoNotDisturb -> {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.isNotificationPolicyAccessGranted) CheckStatus.GRANTED
            else CheckStatus.MISSING
        }

        // No API exists to read a vendor autostart whitelist, so this one is
        // permanently unknowable and the user has to confirm it by eye.
        CheckKind.Autostart -> CheckStatus.UNKNOWN
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun enabledNotificationListener(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    return flat.split(":").any { it.startsWith("${context.packageName}/") }
}

private fun enabledAccessibilityService(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return flat.split(":").any { it.startsWith("${context.packageName}/") }
}
