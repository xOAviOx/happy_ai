package com.happy.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.happy.assistant.R
import com.happy.assistant.audio.AudioCapture
import com.happy.assistant.audio.Earcon
import com.happy.assistant.audio.Speaker
import com.happy.assistant.audio.SpeechToText
import com.happy.assistant.audio.WakeWordDetector
import com.happy.assistant.core.HappyLog
import com.happy.assistant.handlers.CommandExecutor
import com.happy.assistant.handlers.Reply
import com.happy.assistant.router.IntentRouter
import com.happy.assistant.data.Prefs
import com.happy.assistant.ui.SetupActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * The long-lived heart of Happy.
 *
 * Phase 0 does nothing but stay alive and say so: it owns the foreground
 * notification, the state machine, the restart-on-boot contract, and the
 * heartbeat that proves it survived the night. Audio, routing and speech land on
 * top of this later without changing the survival contract.
 */
@AndroidEntryPoint
class HappyService : Service() {

    @Inject lateinit var log: HappyLog
    @Inject lateinit var prefs: Prefs
    @Inject lateinit var audio: AudioCapture
    @Inject lateinit var detector: WakeWordDetector
    @Inject lateinit var earcon: Earcon
    @Inject lateinit var stt: SpeechToText
    @Inject lateinit var speaker: Speaker
    @Inject lateinit var router: IntentRouter
    @Inject lateinit var executor: CommandExecutor

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        log.e(TAG, "unhandled coroutine failure", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)

    private var startedAt = 0L
    private var heartbeatStarted = false
    private var listenJob: Job? = null

    /** Serialises every start and restart of the pipeline. */
    private val pipelineLock = Mutex()

    /** Kept in a field so the loop reads it per frame without touching DataStore. */
    @Volatile private var threshold = Prefs.DEFAULT_WAKE_THRESHOLD
    private var lastDetectionAt = 0L
    private var peakScore = 0f
    private var peakLoggedAt = 0L
    private var silentFrames = 0
    private var lastTranscript: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startedAt = System.currentTimeMillis()
        log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log.i(TAG, "stop requested")
            // An explicit stop is a decision, not a crash: remember it so the boot
            // receiver does not undo it on the next reboot.
            scope.launch { prefs.setServiceEnabled(false) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESTART_LISTENING) {
            // Sent when Happy reaches the foreground. The microphone service type
            // can only be claimed from there, so this is the one moment a retry
            // has any chance of succeeding.
            log.i(TAG, "restarting the listening loop from the foreground")
            restartPipeline()
            return START_STICKY
        }

        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        val redelivered = intent == null
        if (!enterForeground()) return START_NOT_STICKY
        log.i(TAG, "service started (fromBoot=$fromBoot, restartedBySystem=$redelivered)")
        setState(HappyState.IDLE)
        startHeartbeat()
        watchThreshold()
        startListening()
        // START_STICKY: if Android kills us for memory, it recreates the service.
        return START_STICKY
    }

    /**
     * Enters the foreground as specialUse.
     *
     * Not microphone, deliberately: Android 15 rejects a microphone foreground
     * service started from a BOOT_COMPLETED receiver, and coming back after a
     * reboot is the whole point of this service. Phase 1 promotes the type at the
     * moment the mic actually opens, which is legal because the service is already
     * in the foreground by then. Both types are declared in the manifest so that
     * promotion needs no manifest change.
     */
    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(HappyState.IDLE, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        true
    } catch (t: Throwable) {
        // Most likely a background-start restriction or a missing notification
        // permission. Record it rather than dying quietly: this is exactly the
        // failure that reads as "the app just does not work" on OEM skins.
        log.e(TAG, "startForeground failed, stopping", t)
        _state.value = HappyState.OFF
        stopSelf()
        false
    }

    /**
     * Proves survival. Every five minutes it writes uptime to the log and refreshes
     * the notification, so "did it live through the night" is answerable from the
     * phone with no cable attached.
     */
    private fun startHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        scope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(HEARTBEAT_MINUTES))
                val uptime = uptimeText()
                log.d(TAG, "heartbeat, up $uptime")
                updateNotification(state.value, uptime)
            }
        }
    }

    private fun uptimeText(): String {
        val ms = System.currentTimeMillis() - startedAt
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun setState(next: HappyState) {
        if (_state.value == next) return
        _state.value = next
        if (next != HappyState.OFF) updateNotification(next, uptimeText())
    }

    private fun updateNotification(state: HappyState, uptime: String?) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(state, uptime))
        } catch (t: Throwable) {
            log.e(TAG, "could not refresh the notification", t)
        }
    }

    private fun watchThreshold() {
        scope.launch {
            prefs.wakeThreshold.collectLatest { value ->
                if (value != threshold) {
                    log.i(TAG, "wake threshold set to $value")
                    threshold = value
                }
            }
        }
    }

    /**
     * The always-on half of Happy: open the mic, push every 80 ms frame through
     * openWakeWord, and react when the score crosses the threshold.
     *
     * Phase 1 reacts by playing an earcon and logging the score, nothing more.
     */
    /**
     * Starts the pipeline unless it is already running.
     *
     * Every change of the pipeline job goes through [pipelineLock]. Two restart
     * requests arriving together each used to cancel and start, leaving two loops
     * sharing one microphone and one set of unsynchronised detector buffers - which
     * showed up as a mic leak and a BufferOverflowException rather than anything
     * that read like a race.
     */
    private fun startListening() {
        scope.launch {
            pipelineLock.withLock {
                if (listenJob?.isActive == true) return@withLock
                listenJob = scope.launch(Dispatchers.IO) { pipelineLoop() }
            }
        }
    }

    private fun restartPipeline() {
        scope.launch {
            pipelineLock.withLock {
                // cancelAndJoin, not cancel: the old loop only hands the mic back
                // in its finally block.
                listenJob?.cancelAndJoin()
                listenJob = scope.launch(Dispatchers.IO) { pipelineLoop() }
            }
        }
    }

    /**
     * The whole pipeline, as two phases that strictly alternate.
     *
     * Only one of them ever holds the microphone. The wake phase releases it in a
     * finally before returning, so by the time the capture phase asks
     * SpeechRecognizer to start, AudioRecord is provably gone. That ordering is
     * the entire reason this is written as two functions instead of one loop.
     */
    private suspend fun pipelineLoop() {
        if (!audio.hasPermission()) {
            log.w(TAG, "microphone permission missing, staying idle")
            return
        }
        if (!detector.load()) {
            log.e(TAG, "wake word models would not load, staying idle")
            earcon.play(Earcon.Tone.ERROR)
            return
        }

        // Warm both engines now, not on the first command.
        stt.prepare()
        speaker.prepare()

        var micRetries = 0
        while (currentCoroutineContext().isActive) {
            if (!wakeWordPhase()) {
                // A blocked mic is recoverable: the promotion succeeds the moment
                // Happy is in the foreground. Retry a few times rather than dying
                // silently, since the user may be looking at the app right now.
                if (micBlocked && micRetries < MAX_MIC_RETRIES) {
                    micRetries++
                    log.w(TAG, "mic blocked, retrying in ${MIC_RETRY_MS}ms (attempt $micRetries)")
                    delay(MIC_RETRY_MS)
                    continue
                }
                return
            }
            micRetries = 0
            capturePhase()
        }
    }

    /**
     * Holds the mic and listens for the wake phrase. Returns true when it fires,
     * false when the loop should stop entirely - a blocked mic, a dead recorder,
     * or cancellation.
     */
    private suspend fun wakeWordPhase(): Boolean {
        promoteToMicrophoneType()

        val session = audio.start()
        if (session == null) {
            log.e(TAG, "could not open the microphone, staying idle")
            return false
        }

        setState(HappyState.LISTENING_WAKE)
        silentFrames = 0
        _micBlocked.value = false
        micProven = false
        val frame = ShortArray(AudioCapture.FRAME_SAMPLES)
        try {
            while (currentCoroutineContext().isActive) {
                if (!session.readFrame(frame)) {
                    log.w(TAG, "microphone stopped delivering audio")
                    return false
                }
                if (isDeadSilent(frame)) return false
                val score = detector.accept(frame) ?: continue
                reportPeak(score)
                if (score < threshold) continue

                val now = System.currentTimeMillis()
                if (now - lastDetectionAt < DEBOUNCE_MS) continue
                lastDetectionAt = now
                log.i(TAG, "wake word detected, score ${"%.3f".format(score)}")
                // Clear the rolling history so the same utterance cannot score
                // twice as it drains out of the buffers.
                detector.reset()
                return true
            }
            return false
        } catch (t: Throwable) {
            log.e(TAG, "wake word phase failed", t)
            return false
        } finally {
            // Every exit path hands the mic back, including cancellation and
            // crashes. SpeechRecognizer cannot start until this has run.
            session.release()
        }
    }

    /**
     * The seconds after the wake word. Runs with the mic released, so
     * SpeechRecognizer can take it.
     *
     * Phase 4 will route the transcript to a handler. For now it is logged and
     * shown in the notification, which is all Phase 2 promises.
     */
    private suspend fun capturePhase() {
        setState(HappyState.CAPTURING)
        earcon.play(Earcon.Tone.LISTENING)

        when (val result = stt.listen()) {
            is SpeechToText.Result.Heard -> {
                setState(HappyState.PROCESSING)
                lastTranscript = result.text
                log.i(TAG, "transcript: ${result.text}", result.millis)
                updateNotification(HappyState.PROCESSING, uptimeText())
                handle(result.text)
            }

            SpeechToText.Result.Silence -> {
                log.i(TAG, "nothing said after the wake word")
                earcon.play(Earcon.Tone.DONE)
            }

            is SpeechToText.Result.Failed -> {
                log.w(TAG, "recognition failed: ${result.reason}")
                earcon.play(Earcon.Tone.ERROR)
            }
        }
    }

    /**
     * Transcript to action. The router is regex only - no model decides what a
     * command means (spec section 6). An unmatched phrase is not a failure, it is
     * a question, and Phase 7 hands those to the knowledge router.
     */
    private suspend fun handle(transcript: String) {
        val command = router.match(transcript)
        log.i(TAG, "matched $command")

        // Phase 7 replaces the fallback with Wikipedia, weather, maths and Gemini.
        var reply = executor.execute(command) ?: Reply("I cannot answer that yet.")

        var rounds = 0
        while (true) {
            speak(reply.speak)
            val pending = reply.pending ?: return
            if (rounds++ >= MAX_FOLLOW_UPS) {
                log.w(TAG, "stopping after $rounds follow-up rounds")
                return
            }
            val answer = captureFollowUp() ?: return
            reply = executor.resolve(pending, answer)
        }
    }

    /**
     * Listens again without needing the wake phrase.
     *
     * Happy has just asked a question, so the answer is coming in the next couple
     * of seconds. Making the user say "hey Jarvis" again to answer a question
     * Happy itself asked is what made the disambiguation prompt useless.
     */
    private suspend fun captureFollowUp(): String? {
        setState(HappyState.CAPTURING)
        earcon.play(Earcon.Tone.FOLLOW_UP)
        return when (val result = stt.listen()) {
            is SpeechToText.Result.Heard -> {
                log.i(TAG, "follow-up answer: ${result.text}")
                result.text
            }

            else -> {
                log.i(TAG, "no answer to the follow-up")
                null
            }
        }
    }

    /**
     * Says something, while listening for the user to talk over it.
     *
     * Spec section 8: barge-in. The wake phase has already handed the mic back by
     * the time this runs, so the monitor can hold it for the duration of the
     * utterance. Echo cancellation, enabled in AudioCapture, is what stops Happy
     * hearing itself and cutting itself off mid-sentence.
     */
    private suspend fun speak(text: String) {
        setState(HappyState.SPEAKING)
        val monitor = scope.launch(Dispatchers.IO) { bargeInMonitor() }
        try {
            speaker.say(text)
        } finally {
            // cancelAndJoin, so the monitor has provably released the mic before
            // the wake phase tries to open it again.
            monitor.cancelAndJoin()
        }
    }

    private suspend fun bargeInMonitor() {
        val session = audio.start() ?: return
        val frame = ShortArray(AudioCapture.FRAME_SAMPLES)
        var loudFrames = 0
        var peak = 0
        try {
            while (currentCoroutineContext().isActive) {
                if (!session.readFrame(frame)) return
                val rms = rmsOf(frame)
                if (rms > peak) peak = rms
                loudFrames = if (rms >= BARGE_IN_RMS) loudFrames + 1 else 0
                if (loudFrames >= BARGE_IN_FRAMES) {
                    log.i(TAG, "barge-in, rms $rms over ${BARGE_IN_FRAMES * 80}ms")
                    speaker.stop()
                    return
                }
            }
        } catch (t: Throwable) {
            log.e(TAG, "barge-in monitor failed", t)
        } finally {
            // Logged so the threshold can be tuned against what the mic actually
            // hears while the speaker is playing.
            log.d(TAG, "peak rms while speaking was $peak, barge-in threshold $BARGE_IN_RMS")
            session.release()
        }
    }

    private fun rmsOf(frame: ShortArray): Int {
        var sum = 0.0
        for (sample in frame) {
            val v = sample.toDouble()
            sum += v * v
        }
        return sqrt(sum / frame.size).toInt()
    }

    /**
     * Records the loudest score in each window and logs it, so the threshold can
     * be tuned from real data instead of guesswork. A quiet room should sit far
     * below the threshold; if it does not, the threshold is too low for this phone.
     */
    private fun reportPeak(score: Float) {
        if (score > peakScore) peakScore = score
        val now = System.currentTimeMillis()
        if (peakLoggedAt == 0L) peakLoggedAt = now
        if (now - peakLoggedAt >= PEAK_WINDOW_MS) {
            log.d(TAG, "peak score ${"%.3f".format(peakScore)} over the last minute, threshold $threshold")
            peakScore = 0f
            peakLoggedAt = now
        }
    }

    /**
     * Android 14 and later hand back silence rather than an error when a service
     * records without a microphone foreground service type. Without this check
     * that failure looks identical to a wake word that simply never fires, which
     * is the worst kind of bug to chase.
     */
    private fun isDeadSilent(frame: ShortArray): Boolean {
        val silent = frame.all { it.toInt() == 0 }
        // A real microphone in a real room never returns exact zeros, so one
        // non-zero frame is proof the mic is genuinely live.
        if (!silent) micProven = true
        silentFrames = if (silent) silentFrames + 1 else 0
        if (silentFrames < SILENCE_FRAMES) return false
        log.e(
            TAG,
            "microphone returned pure silence for ${SILENCE_FRAMES * 80 / 1000}s, giving up. " +
                "The microphone service type was refused, so the mic yields zeros. " +
                "Open Happy from the launcher and it will retry."
        )
        _micBlocked.value = true
        return true
    }

    /**
     * Upgrades the running service from specialUse to microphone.
     *
     * This is what actually licenses recording in the background on Android 14 and
     * later. It is attempted at the moment the mic opens rather than at start,
     * because a service launched from BOOT_COMPLETED is not allowed to claim the
     * microphone type at start time. It can still be refused here, in which case
     * the silence check above is what makes that visible.
     */
    private fun promoteToMicrophoneType() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(HappyState.LISTENING_WAKE, uptimeText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            log.i(TAG, "foreground service type promoted to microphone")
        } catch (t: Throwable) {
            log.e(TAG, "microphone service type refused, recording may return silence", t)
        }
    }

    private fun buildNotification(state: HappyState, uptime: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HappyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (uptime == null) state.label else "${state.label} - up $uptime"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(body)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Spec Phase 2: the last thing heard goes in the notification, so a failed
        // command can be diagnosed from the shade without opening anything.
        lastTranscript?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText("Heard: $it")) }
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * The user swiped Happy out of recents. With stopWithTask=false the service is
     * unaffected on stock Android, but several OEM skins tear the process down
     * anyway, so re-assert the start while it is still legal to do so: a service
     * that is currently in the foreground is allowed to start a foreground service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        log.w(TAG, "task removed from recents, re-asserting the service")
        try {
            startForegroundService(Intent(this, HappyService::class.java))
        } catch (t: Throwable) {
            log.e(TAG, "could not re-assert after task removal", t)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        log.i(TAG, "service destroyed after ${uptimeText()}")
        heartbeatStarted = false
        // Cancel first so the listening loop runs its finally and hands the mic
        // back before the interpreters close underneath it.
        listenJob?.cancel()
        listenJob = null
        scope.cancel()
        detector.close()
        earcon.release()
        stt.release()
        speaker.release()
        _state.value = HappyState.OFF
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val TAG = "Service"
        private const val CHANNEL_ID = "happy_service"
        private const val NOTIF_ID = 1001
        private const val HEARTBEAT_MINUTES = 5L

        /** Spec section 4: ignore a detection within 2s of the previous one. */
        private const val DEBOUNCE_MS = 2_000L

        /** How often the loudest recent score is logged, for threshold tuning. */
        private const val PEAK_WINDOW_MS = 60_000L

        /** 80 ms frames, so this is roughly five seconds of pure digital silence. */
        private const val SILENCE_FRAMES = 62
        private const val MAX_MIC_RETRIES = 4
        private const val MIC_RETRY_MS = 6_000L

        /** Sustained RMS that counts as the user talking over Happy. */
        private const val BARGE_IN_RMS = 3_000
        private const val BARGE_IN_FRAMES = 5

        /** How many times Happy may ask a follow-up before giving up. */
        private const val MAX_FOLLOW_UPS = 2

        const val ACTION_RESTART_LISTENING = "com.happy.assistant.action.RESTART_LISTENING"

        /** True while the wake word loop holds the mic. */
        val isListening: Boolean get() = _state.value == HappyState.LISTENING_WAKE

        /** True once the open mic has delivered audio that is not pure zeros. */
        @Volatile
        var micProven = false
            private set

        private val _micBlocked = MutableStateFlow(false)

        /**
         * True when the mic handed back nothing but zeros, which on Android 14 and
         * later means the microphone service type was refused.
         *
         * Observable rather than a plain flag because the service cannot fix this
         * itself. The while-in-use capability that licenses the microphone type is
         * decided from the process state at the moment the service was *started*,
         * so a service started from a boot broadcast can never promote itself no
         * matter how long it retries. Only a fresh start command issued while an
         * activity is in the foreground re-evaluates it, so the UI watches this and
         * kicks the service when it goes true.
         */
        val micBlockedFlow: StateFlow<Boolean> = _micBlocked.asStateFlow()

        val micBlocked: Boolean get() = _micBlocked.value

        fun restartListening(context: Context) {
            context.startService(
                Intent(context, HappyService::class.java).setAction(ACTION_RESTART_LISTENING)
            )
        }

        const val ACTION_STOP = "com.happy.assistant.action.STOP"
        const val EXTRA_FROM_BOOT = "from_boot"

        private val _state = MutableStateFlow(HappyState.OFF)

        /** Observable pipeline state, so the UI never has to bind to the service. */
        val state: StateFlow<HappyState> = _state.asStateFlow()

        val isRunning: Boolean get() = _state.value != HappyState.OFF

        fun start(context: Context, fromBoot: Boolean = false) {
            context.startForegroundService(
                Intent(context, HappyService::class.java).putExtra(EXTRA_FROM_BOOT, fromBoot)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HappyService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
