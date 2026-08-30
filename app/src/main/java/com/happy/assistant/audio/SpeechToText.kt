package com.happy.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Turns the seconds after the wake word into text, on device and offline.
 *
 * Two platform rules shape all of this:
 *  - SpeechRecognizer must be created and driven from the main thread, and it
 *    calls back on the main thread. The pipeline lives on a background thread, so
 *    every call hops through a main-thread Handler.
 *  - It takes the microphone for itself. AudioCapture must already have released
 *    it before [listen] is called, or recognition returns nothing at all.
 */
@Singleton
class SpeechToText @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active: CancellableContinuation<Result>? = null

    sealed interface Result {
        data class Heard(val text: String, val millis: Long) : Result
        data object Silence : Result
        data class Failed(val reason: String) : Result
    }

    fun isOnDeviceAvailable(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Builds the recogniser ahead of time.
     *
     * Constructing an on-device recogniser is slow enough that doing it lazily
     * made the first command after a restart time out. Spec section 3 asks for one
     * long-lived instance anyway; this is where it gets born.
     */
    fun prepare() {
        main.post {
            if (recognizer != null) return@post
            try {
                recognizer = create()
            } catch (t: Throwable) {
                log.e(TAG, "could not pre-warm the recognizer", t)
            }
        }
    }

    /**
     * Listens for one utterance. Never throws, always completes: an overall
     * timeout guards against a recogniser that goes quiet, because a pipeline
     * stuck in CAPTURING is a pipeline that has stopped answering to its name.
     */
    suspend fun listen(timeoutMs: Long = OVERALL_TIMEOUT_MS): Result {
        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) { awaitResult() }
        if (result == null) {
            log.w(TAG, "recognition timed out after ${timeoutMs}ms")
            cancel()
            return Result.Failed("timeout")
        }
        if (result is Result.Heard) {
            log.i(TAG, "heard: ${result.text}", System.currentTimeMillis() - started)
        }
        return result
    }

    private suspend fun awaitResult(): Result = suspendCancellableCoroutine { cont ->
        active = cont
        cont.invokeOnCancellation { cancel() }
        main.post {
            try {
                val engine = recognizer ?: create().also { recognizer = it }
                engine.startListening(buildIntent())
            } catch (t: Throwable) {
                log.e(TAG, "could not start recognition", t)
                finish(Result.Failed(t.javaClass.simpleName))
            }
        }
    }

    private fun create(): SpeechRecognizer {
        val onDevice = isOnDeviceAvailable()
        log.i(TAG, "creating recognizer, onDevice=$onDevice")
        // Spec section 1: device commands must work with no internet, so on-device
        // recognition is the primary. The networked recogniser is only a stand-in
        // where the platform has no offline engine at all.
        val engine = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            log.w(TAG, "no on-device recogniser on this phone, falling back to the default")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        engine.setRecognitionListener(Listener())
        return engine
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
    }

    private fun finish(result: Result) {
        val cont = active ?: return
        active = null
        if (cont.isActive) cont.resume(result)
    }

    /** Stops the recogniser and hands the mic back. Safe to call from any thread. */
    fun cancel() {
        main.post {
            try {
                recognizer?.cancel()
            } catch (t: Throwable) {
                log.e(TAG, "cancel threw", t)
            }
        }
    }

    fun release() {
        main.post {
            try {
                recognizer?.destroy()
            } catch (t: Throwable) {
                log.e(TAG, "destroy threw", t)
            }
            recognizer = null
        }
    }

    private inner class Listener : RecognitionListener {

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            finish(if (text.isEmpty()) Result.Silence else Result.Heard(text, 0))
        }

        override fun onError(error: Int) {
            // Silence and no-match are ordinary outcomes, not failures: the user
            // said the wake word and then thought better of it.
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(Result.Silence)

                else -> {
                    val name = errorName(error)
                    log.w(TAG, "recognition error: $name")
                    finish(Result.Failed(name))
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permissions"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "networkTimeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "noMatch"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speechTimeout"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "languageNotSupported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "languageUnavailable"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannotCheckSupport"
        else -> "unknown($error)"
    }

    companion object {
        private const val TAG = "Stt"

        /** Hard ceiling on one utterance, so the pipeline always returns to idle. */
        private const val OVERALL_TIMEOUT_MS = 9_000L

        /** How much trailing silence ends the utterance. */
        private const val END_SILENCE_MS = 900L
    }
}
