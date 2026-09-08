package com.happy.assistant.handlers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.Command
import com.happy.assistant.service.HappyAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screen control and screen reading, both through the accessibility service.
 *
 * Every path here first checks the service is actually connected, because the
 * accessibility toggle resets on reinstall and the resulting failure is silent
 * otherwise - the action simply does nothing.
 */
@Singleton
class ScreenHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    private fun unavailable(): String? = when {
        !HappyAccessibilityService.isEnabled(context) ->
            "I need the accessibility permission for that. It is on the setup checklist."

        !HappyAccessibilityService.isConnected ->
            "Accessibility is on but not connected yet. Try again in a moment."

        else -> null
    }

    fun globalAction(command: Command.GlobalAction): String {
        unavailable()?.let { return it }

        val (action, spoken) = when (command.action) {
            Command.Screen.BACK -> AccessibilityService.GLOBAL_ACTION_BACK to "Back."
            Command.Screen.HOME -> AccessibilityService.GLOBAL_ACTION_HOME to "Home."
            Command.Screen.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Recents."
            Command.Screen.LOCK -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locked."
            Command.Screen.SCREENSHOT ->
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to "Screenshot taken."
            Command.Screen.NOTIFICATIONS ->
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Here you go."
        }

        return if (HappyAccessibilityService.perform(action)) {
            spoken
        } else {
            log.w(TAG, "global action ${command.action} was refused")
            "That did not work."
        }
    }

    /**
     * Reads the current screen aloud.
     *
     * Truncated hard: a dense screen yields far more text than anyone wants read
     * to them. Phase 7 replaces this with a summary from Gemini, at which point
     * the full text goes to the model instead of the speaker.
     */
    fun readScreen(): String {
        unavailable()?.let { return it }

        val lines = HappyAccessibilityService.screenText()
        if (lines.isEmpty()) return "I cannot read anything on this screen."

        val spoken = lines.take(MAX_SPOKEN_LINES).joinToString(". ")
        return if (lines.size > MAX_SPOKEN_LINES) {
            "$spoken. And ${lines.size - MAX_SPOKEN_LINES} more."
        } else {
            spoken
        }
    }

    companion object {
        private const val TAG = "Screen"
        private const val MAX_SPOKEN_LINES = 12
    }
}
