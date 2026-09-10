package com.happy.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw microphone access: 16 kHz, mono, 16-bit PCM, delivered in 1280-sample
 * frames because that is the 80 ms chunk openWakeWord is built around.
 *
 * Android will not let AudioRecord and SpeechRecognizer hold the mic at the same
 * time, so ownership here is explicit and single-threaded: [start] hands back a
 * session, and the caller must [Session.release] it on every exit path including
 * exceptions. Nothing else in Happy may open an AudioRecord.//
 */
@Singleton
class AudioCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Opens the mic. Returns null if the permission is missing or the device
     * refuses to give us a recorder, which happens when another app holds it.
     */
    @SuppressLint("MissingPermission")
    fun start(): Session? {
        if (!hasPermission()) {
            log.w(TAG, "RECORD_AUDIO not granted, refusing to open the mic")
            return null
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            log.e(TAG, "getMinBufferSize returned $minBuffer, cannot record at ${SAMPLE_RATE}Hz")
            return null
        }
        // Several frames of slack so a scheduling hiccup does not drop audio.
        val bufferSize = maxOf(minBuffer, FRAME_SAMPLES * BYTES_PER_SAMPLE * BUFFER_FRAMES)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        } catch (t: Throwable) {
            log.e(TAG, "could not construct AudioRecord", t)
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            log.e(TAG, "AudioRecord failed to initialise (state=${recorder.state})")
            recorder.release()
            return null
        }

        enableEffects(recorder)

        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                log.e(TAG, "AudioRecord would not start (state=${recorder.recordingState})")
                recorder.release()
                null
            } else {
                val open = openSessions.incrementAndGet()
                if (open > 1) {
                    // The one invariant this class exists to keep. If it ever trips,
                    // a phase handed the mic on without releasing it.
                    log.e(TAG, "MIC LEAK: $open sessions open at once")
                }
                log.i(TAG, "mic open at ${SAMPLE_RATE}Hz, buffer $bufferSize bytes")
                Session(recorder, log, openSessions)
            }
        } catch (t: Throwable) {
            log.e(TAG, "startRecording threw", t)
            recorder.release()
            null
        }
    }

    /**
     * Turns on the platform DSP where the device offers it.
     *
     * Echo cancellation is what makes barge-in possible at all: without it the
     * microphone hears Happy through its own speaker and cuts itself off. Noise
     * suppression helps the wake word in a noisy room. Both are best-effort - many
     * phones do not ship either.
     */
    private fun enableEffects(recorder: AudioRecord) {
        val id = recorder.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(id)?.enabled = true
                log.d(TAG, "acoustic echo canceler enabled")
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(id)?.enabled = true
                log.d(TAG, "noise suppressor enabled")
            }
        } catch (t: Throwable) {
            log.w(TAG, "could not enable audio effects: ${t.javaClass.simpleName}")
        }
    }

    /** An open microphone. Exactly one owner, and it must be released. */
    class Session(
        private val recorder: AudioRecord,
        private val log: HappyLog,
        private val openSessions: AtomicInteger,
    ) {

        private var released = false

        /**
         * Fills [frame] with exactly [FRAME_SAMPLES] samples, blocking until they
         * arrive. Returns false when the mic died or was released, which is the
         * signal for the caller to stop and clean up.
         */
        fun readFrame(frame: ShortArray): Boolean {
            var offset = 0
            while (offset < FRAME_SAMPLES) {
                if (released) return false
                val n = recorder.read(frame, offset, FRAME_SAMPLES - offset)
                if (n <= 0) {
                    if (!released) log.e(TAG, "AudioRecord.read returned $n")
                    return false
                }
                offset += n
            }
            return true
        }

        fun release() {
            if (released) return
            released = true
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (t: Throwable) {
                log.e(TAG, "AudioRecord.stop threw", t)
            }
            try {
                recorder.release()
            } catch (t: Throwable) {
                log.e(TAG, "AudioRecord.release threw", t)
            }
            log.i(TAG, "mic released, ${openSessions.decrementAndGet()} still open")
        }
    }

    private val openSessions = AtomicInteger(0)

    companion object {
        private const val TAG = "Audio"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1280
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAMES = 8
    }
}
