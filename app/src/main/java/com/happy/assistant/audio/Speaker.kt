package com.happy.assistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.happy.assistant.core.HappyLog
import com.happy.assistant.data.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything Happy says out loud.
 *
 * Two rules, both from the spec:
 *  - The engine is built once at service start and reused, never constructed per
 *    request (section 3). Building one costs hundreds of milliseconds.
 *  - Nothing reaches TextToSpeech.speak without passing through
 *    [TtsSanitizer.clean] first (section 8). That is enforced here, in one place,
 *    so no caller can forget.
 */
@Singleton
class Speaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: Prefs,
    private val log: HappyLog,
) {

    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ids = AtomicLong(0)

    @Volatile
    var isSpeaking = false
        private set

    /** Builds the engine and picks a voice. Safe to call more than once. */
    suspend fun prepare(): Boolean {
        if (tts != null) return true
        val ready = CompletableDeferred<Boolean>()
        val engine = TextToSpeech(context) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!ok) {
            log.e(TAG, "text to speech engine failed to initialise")
            engine.shutdown()
            return false
        }

        engine.setOnUtteranceProgressListener(Progress())
        chooseVoice(engine)
        engine.setSpeechRate(prefs.ttsRate.first())
        engine.setPitch(prefs.ttsPitch.first())
        tts = engine
        log.i(TAG, "text to speech ready")
        return true
    }

    /**
     * Indian English first, because that is the accent this phone is spoken to in
     * and the one whose place names it will have to pronounce. British English is
     * a closer second than American for the same reason.
     */
    private fun chooseVoice(engine: TextToSpeech) {
        val wanted = listOf(Locale("en", "IN"), Locale.UK, Locale.US)
        for (locale in wanted) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                log.i(TAG, "voice set to $locale")
                return
            }
        }
        log.w(TAG, "none of the preferred English voices are installed, using the default")
    }

    /**
     * Speaks one piece of text and suspends until it finishes.
     *
     * [flush] true interrupts whatever is being said; false queues behind it,
     * which is what sentence-by-sentence streaming needs in Phase 7.
     */
    suspend fun say(text: String, flush: Boolean = true) {
        val engine = tts ?: run {
            log.w(TAG, "asked to speak before the engine was ready")
            return
        }
        val clean = TtsSanitizer.clean(text)
        if (clean.isEmpty()) {
            log.d(TAG, "nothing speakable left after sanitising: $text")
            return
        }

        val id = "happy-${ids.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        isSpeaking = true

        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val queued = engine.speak(clean, mode, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            log.e(TAG, "speak() refused the utterance")
            pending.remove(id)
            isSpeaking = false
            return
        }

        // Bounded: a lost utterance callback must not strand the pipeline in
        // SPEAKING forever.
        withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() }
        pending.remove(id)
        isSpeaking = false
    }

    /** Barge-in, and the stop button. Silences the engine immediately. */
    fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            log.e(TAG, "stop threw", t)
        }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        isSpeaking = false
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
            log.e(TAG, "shutdown threw", t)
        }
        tts = null
    }

    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            pending.remove(utteranceId)?.complete(Unit)
        }

        @Deprecated("Required by the base class", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            log.w(TAG, "utterance failed: $utteranceId")
            pending.remove(utteranceId)?.complete(Unit)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            log.w(TAG, "utterance failed: $utteranceId, code $errorCode")
            pending.remove(utteranceId)?.complete(Unit)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            pending.remove(utteranceId)?.complete(Unit)
        }
    }

    companion object {
        private const val TAG = "Tts"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
