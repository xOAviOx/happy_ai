package com.happy.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.happy.assistant.core.HappyLog
import com.happy.assistant.data.Prefs
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Brings Happy back after a reboot or an app update.
 *
 * BOOT_COMPLETED is one of the few exemptions from the Android 12 background
 * foreground-service restriction, so the start here is legal - but only for a
 * service that enters the foreground as specialUse, which is why HappyService
 * never asks for the microphone type at start time.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var log: HappyLog
    @Inject lateinit var prefs: Prefs

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        try {
            val wanted = prefs.serviceEnabledBlocking()
            log.i(TAG, "received $action, serviceEnabled=$wanted")
            if (wanted) HappyService.start(context, fromBoot = true)
        } catch (t: Throwable) {
            log.e(TAG, "failed to restart after $action", t)
        }
    }

    companion object {
        private const val TAG = "Boot"
        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
