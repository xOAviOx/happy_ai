package com.happy.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.happy.assistant.core.HappyLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short synthesised tones. Spec section 3 wants the earcon to be immediate, so
 * the PCM is generated once up front and every tone keeps a static AudioTrack
 * ready to replay - no allocation, no decoding, no file IO on the hot path.
 *
 * Synthesised rather than played from ToneGenerator because a rising tone needs
 * a sweep, and because the fade at each end is what stops the click that makes a
 * short tone sound broken.
 */
@Singleton
class Earcon @Inject constructor(private val log: HappyLog) {

    enum class Tone(val fromHz: Float, val toHz: Float, val millis: Int) {
        /** Wake word heard, Happy is listening to you. */
        LISTENING(620f, 980f, 130),

        /** Finished, back to waiting. */
        DONE(880f, 620f, 120),

        /** Happy asked a question and is waiting for the answer. */
        FOLLOW_UP(520f, 700f, 110),

        /** Something failed. */
        ERROR(440f, 330f, 200),
    }

    private val tracks = HashMap<Tone, AudioTrack>()

    fun play(tone: Tone) {
        try {
            val track = tracks.getOrPut(tone) { build(tone) }
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (t: Throwable) {
            // An earcon failing must never take the pipeline down with it.
            log.e(TAG, "could not play $tone", t)
        }
    }

    private fun build(tone: Tone): AudioTrack {
        val pcm = sweep(tone)
        val bytes = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    /** A linear frequency sweep with a short fade at both ends to kill the click. */
    private fun sweep(tone: Tone): ShortArray {
        val samples = SAMPLE_RATE * tone.millis / 1000
        val out = ShortArray(samples)
        val fade = (samples * FADE_FRACTION).toInt().coerceAtLeast(1)
        var phase = 0.0
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val hz = tone.fromHz + (tone.toHz - tone.fromHz) * progress
            phase += 2.0 * PI * hz / SAMPLE_RATE
            val envelope = when {
                i < fade -> i.toFloat() / fade
                i > samples - fade -> (samples - i).toFloat() / fade
                else -> 1f
            }
            out[i] = (sin(phase) * envelope * AMPLITUDE).toInt().toShort()
        }
        return out
    }

    fun release() {
        tracks.values.forEach {
            try {
                it.stop()
                it.release()
            } catch (t: Throwable) {
                log.e(TAG, "could not release an earcon track", t)
            }
        }
        tracks.clear()
    }

    companion object {
        private const val TAG = "Earcon"
        private const val SAMPLE_RATE = 16_000
        private const val AMPLITUDE = 9_000
        private const val FADE_FRACTION = 0.15f
    }
}
