package com.happy.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import com.happy.assistant.core.HappyLog
import com.happy.assistant.data.Prefs
import com.happy.assistant.service.HappyService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import javax.inject.Inject

/**
 * The permission checklist from spec section 9, plus the switch that starts and
 * stops the service.
 *
 * It exists because half the failure modes of an always-on assistant are not
 * bugs but ungranted toggles, and the accessibility toggle in particular resets
 * on every reinstall. The list has to make that obvious.
 */
@AndroidEntryPoint
class SetupActivity : ComponentActivity() {

    @Inject lateinit var log: HappyLog
    @Inject lateinit var prefs: Prefs

    // Hoisted for the same reason as the log flow: a new Flow per recomposition
    // would restart collection every frame.
    private val thresholdFlow by lazy { prefs.wakeThreshold }

    /** Bumped on resume so the ticks re-read after a trip to Settings. */
    private var refreshTick by mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            log.i(TAG, "permission result: " + result.entries.joinToString { "${shortName(it.key)}=${it.value}" })
            refreshTick++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        watchForBlockedMic()
        setContent {
            HappyTheme {
                val threshold by thresholdFlow.collectAsStateWithLifecycle(
                    initialValue = Prefs.DEFAULT_WAKE_THRESHOLD
                )
                SetupScreen(
                    refreshTick = refreshTick,
                    threshold = threshold,
                    onThresholdChange = { lifecycleScope.launch { prefs.setWakeThreshold(it) } },
                    onFix = ::fix,
                    onStart = ::startHappy,
                    onStop = ::stopHappy,
                    onOpenLog = { startSafely(Intent(this, LogActivity::class.java)) },
                )
            }
        }
    }

    /**
     * Kicks the service whenever it reports a muted microphone while this screen is
     * up.
     *
     * onResume alone is not enough: if Happy is already open when the service gives
     * up - which is exactly what happens after a reinstall - no resume ever fires,
     * and the service cannot recover on its own. Capped, so a phone that genuinely
     * refuses the microphone does not spin forever.
     */
    private fun watchForBlockedMic() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var attempts = 0
                HappyService.micBlockedFlow.collect { blocked ->
                    if (!blocked) {
                        attempts = 0
                        return@collect
                    }
                    if (attempts++ >= MAX_MIC_KICKS) {
                        log.w(TAG, "microphone still blocked after $attempts attempts, giving up")
                        return@collect
                    }
                    log.i(TAG, "microphone blocked, restarting the service from the foreground")
                    HappyService.restartListening(this@SetupActivity)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
        // Being in the foreground is what licenses the microphone service type on
        // Android 14 and later, so this is the moment a service that came back
        // from a reboot can finally start hearing. Only when it is not already
        // listening: retrying unconditionally would drop and reopen a working mic
        // on every resume.
        // Not just "is it listening": right after a background restart it is
        // listening to a muted mic, and only a proven non-zero frame distinguishes
        // that from a genuinely working one.
        if (HappyService.isRunning && !(HappyService.isListening && HappyService.micProven)) {
            HappyService.restartListening(this)
        }
    }

    private fun startHappy() {
        lifecycleScope.launch { prefs.setServiceEnabled(true) }
        try {
            HappyService.start(this)
            log.i(TAG, "service start requested from setup")
        } catch (t: Throwable) {
            log.e(TAG, "could not start the service", t)
        }
    }

    private fun stopHappy() {
        HappyService.stop(this)
        log.i(TAG, "service stop requested from setup")
    }

    private fun fix(item: CheckItem) {
        log.d(TAG, "fix tapped for ${item.id}")
        when (val kind = item.kind) {
            is CheckKind.Runtime ->
                if (kind.permissions.isEmpty()) openAppDetails()
                else permissionLauncher.launch(kind.permissions.toTypedArray())

            CheckKind.BatteryOptimisation -> startSafely(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )

            CheckKind.WriteSettings -> startSafely(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )

            CheckKind.NotificationAccess ->
                startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

            CheckKind.Accessibility ->
                startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

            CheckKind.DisplayOverApps -> startSafely(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )

            CheckKind.DoNotDisturb ->
                startSafely(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

            CheckKind.Autostart -> startFirstThatOpens(OemAutostart.intentsFor(this))
        }
    }

    /**
     * Vendor screens fail in three different ways - absent, not exported, or
     * present but refusing an explicit component - so try them in order and let
     * each failure fall through to the next. Only when every candidate is gone do
     * we land on app info, and the log says which ones were tried.
     */
    private fun startFirstThatOpens(intents: List<Intent>) {
        for (intent in intents) {
            try {
                startActivity(intent)
                log.i(TAG, "opened ${intent.action ?: intent.component}")
                return
            } catch (t: Throwable) {
                log.w(TAG, "could not open ${intent.action ?: intent.component} (${t.javaClass.simpleName})")
            }
        }
        log.w(TAG, "no vendor screen would open, falling back to app info")
        startSafely(appDetailsIntent())
    }

    private fun appDetailsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    )

    private fun openAppDetails() = startSafely(appDetailsIntent())

    /**
     * Vendor settings screens come and go between skin versions, so every deep link
     * is a best effort with the app details page as the floor.
     */
    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // Not just ActivityNotFoundException: vendor screens are frequently
            // present but not exported, which throws SecurityException instead.
            // Either way the answer is the same - fall back rather than dead-end.
            log.w(TAG, "could not open ${intent.action ?: intent.component} (${t.javaClass.simpleName}), falling back")
            try {
                startActivity(appDetailsIntent())
            } catch (t2: Throwable) {
                log.e(TAG, "no settings screen could be opened at all", t2)
            }
        }
    }

    private fun shortName(permission: String) = permission.substringAfterLast('.')

    companion object {
        private const val TAG = "Setup"
        private const val MAX_MIC_KICKS = 3
        val deviceLabel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    }
}
