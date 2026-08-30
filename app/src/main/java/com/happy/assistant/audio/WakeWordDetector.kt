package com.happy.assistant.audio

import android.content.Context
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * openWakeWord, ported to Android. Three models in a rolling pipeline, run once
 * per 80 ms frame:
 *
 *     1280 new samples (+ 480 of lookback)
 *       -> melspectrogram.tflite   -> mel frames, 32 bins each
 *       -> [rolling mel buffer]
 *       -> embedding_model.tflite  -> one 96-dim embedding per frame
 *       -> [rolling embedding buffer]
 *       -> hey_jarvis_v0.1.tflite  -> score 0..1
 *
 * Three details are load-bearing, all taken from the reference implementation
 * rather than guessed, because each fails silently rather than loudly:
 *
 *  - The mel model is fed 1760 samples, not 1280: the extra 480 are lookback so
 *    successive windows overlap the way the model was trained. That is why the
 *    input tensor gets resized at load.
 *  - Mel output is transformed by x/10 + 2. Skip it and the scores are garbage
 *    that never crosses any threshold.
 *  - Audio goes in as float32 of the raw int16 values, NOT normalised to -1..1.
 *
 * No tensor shape is hardcoded. Everything is read from the models at load and
 * logged on first run, as the spec requires.
 */
@Singleton
class WakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    private var melspec: Interpreter? = null
    private var embed: Interpreter? = null
    private var wake: Interpreter? = null

    // Discovered at load, never assumed.
    private var melBins = 0
    private var melFramesPerChunk = 0
    private var embedWindow = 0
    private var embeddingSize = 0
    private var wakeFrames = 0

    private val melBuffer = ArrayDeque<FloatArray>()
    private val featureBuffer = ArrayDeque<FloatArray>()

    private val carry = ShortArray(LOOKBACK_SAMPLES)
    private var carryValid = false

    private var melIn: ByteBuffer? = null
    private var melOut: ByteBuffer? = null
    private var embedIn: ByteBuffer? = null
    private var embedOut: ByteBuffer? = null
    private var wakeIn: ByteBuffer? = null
    private var wakeOut: ByteBuffer? = null

    val isLoaded: Boolean get() = wake != null

    /** Loads all three interpreters and warms the rolling buffers. Slow, so never on the main thread. */
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            val options = Interpreter.Options().setNumThreads(INFERENCE_THREADS)

            val mel = Interpreter(mapAsset(MEL_MODEL), options)
            // 1280 samples plus 480 of lookback. The model ships expecting 1280,
            // so it has to be resized before the first invocation.
            mel.resizeInput(0, intArrayOf(1, MEL_INPUT_SAMPLES))
            mel.allocateTensors()

            val emb = Interpreter(mapAsset(EMBEDDING_MODEL), options)
            val wak = Interpreter(mapAsset(WAKE_MODEL), options)

            val melOutShape = mel.getOutputTensor(0).shape()
            val embInShape = emb.getInputTensor(0).shape()
            val embOutShape = emb.getOutputTensor(0).shape()
            val wakeInShape = wak.getInputTensor(0).shape()
            val wakeOutShape = wak.getOutputTensor(0).shape()

            melBins = melOutShape.last()
            melFramesPerChunk = melOutShape.elements() / melBins
            embedWindow = embInShape[1]
            embeddingSize = embOutShape.elements()
            wakeFrames = wakeInShape[1]

            log.i(
                TAG,
                "shapes: mel in [1, $MEL_INPUT_SAMPLES] out ${melOutShape.pretty()}, " +
                    "embed in ${embInShape.pretty()} out ${embOutShape.pretty()}, " +
                    "wake in ${wakeInShape.pretty()} out ${wakeOutShape.pretty()}"
            )

            require(melBins == embInShape[2]) {
                "mel produces $melBins bins but the embedding model wants ${embInShape[2]}"
            }
            require(wakeInShape[2] == embeddingSize) {
                "embeddings are $embeddingSize wide but the wake model wants ${wakeInShape[2]}"
            }
            require(wakeOutShape.elements() == 1) {
                "expected a single score, got ${wakeOutShape.pretty()}"
            }

            melIn = directBuffer(MEL_INPUT_SAMPLES)
            melOut = directBuffer(melOutShape.elements())
            embedIn = directBuffer(embInShape.elements())
            embedOut = directBuffer(embeddingSize)
            wakeIn = directBuffer(wakeInShape.elements())
            wakeOut = directBuffer(1)

            melspec = mel
            embed = emb
            wake = wak

            warmUp()
            log.i(TAG, "wake word pipeline ready")
            true
        } catch (t: Throwable) {
            log.e(TAG, "failed to load the wake word models", t)
            close()
            false
        }
    }

    /**
     * Feeds one 80 ms frame through the pipeline.
     *
     * Returns the wake score once enough history has accumulated, or null while
     * the buffers are still filling. Call this from a single thread; the buffers
     * are deliberately not synchronised.
     */
    fun accept(frame: ShortArray): Float? {
        val mel = melspec ?: return null
        val emb = embed ?: return null
        val wak = wake ?: return null

        // Lookback plus the new chunk, as float32 of the raw int16 values.
        val audio = FloatArray(MEL_INPUT_SAMPLES)
        for (i in 0 until LOOKBACK_SAMPLES) {
            audio[i] = if (carryValid) carry[i].toFloat() else 0f
        }
        for (i in frame.indices) {
            audio[LOOKBACK_SAMPLES + i] = frame[i].toFloat()
        }
        System.arraycopy(frame, frame.size - LOOKBACK_SAMPLES, carry, 0, LOOKBACK_SAMPLES)
        carryValid = true

        val melFrames = invoke(mel, audio, melIn!!, melOut!!, melFramesPerChunk * melBins)
        for (f in 0 until melFramesPerChunk) {
            val row = FloatArray(melBins)
            for (b in 0 until melBins) {
                // The transform that makes these models behave like the original
                // TensorFlow implementation. Not optional.
                row[b] = melFrames[f * melBins + b] / 10f + 2f
            }
            melBuffer.addLast(row)
        }
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()
        if (melBuffer.size < embedWindow) return null

        val window = FloatArray(embedWindow * melBins)
        val start = melBuffer.size - embedWindow
        for (f in 0 until embedWindow) {
            System.arraycopy(melBuffer[start + f], 0, window, f * melBins, melBins)
        }
        featureBuffer.addLast(invoke(emb, window, embedIn!!, embedOut!!, embeddingSize))
        while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeFirst()
        if (featureBuffer.size < wakeFrames) return null

        val features = FloatArray(wakeFrames * embeddingSize)
        val from = featureBuffer.size - wakeFrames
        for (f in 0 until wakeFrames) {
            System.arraycopy(featureBuffer[from + f], 0, features, f * embeddingSize, embeddingSize)
        }
        return invoke(wak, features, wakeIn!!, wakeOut!!, 1)[0]
    }

    /**
     * Pushes four seconds of quiet noise through the pipeline so the rolling
     * buffers hold real history rather than zeros. The reference seeds its
     * buffers the same way; without it the first second of live audio scores
     * against a half-empty window.
     */
    private fun warmUp() {
        val random = Random(0)
        val frame = ShortArray(AudioCapture.FRAME_SAMPLES)
        val frames = (AudioCapture.SAMPLE_RATE * WARMUP_SECONDS) / AudioCapture.FRAME_SAMPLES
        repeat(frames) {
            for (i in frame.indices) frame[i] = random.nextInt(-1000, 1000).toShort()
            accept(frame)
        }
        // Deliberately not reset afterwards: the point is to KEEP this history so
        // the first live frame scores against a full window.
        log.d(TAG, "warmed up over $frames frames, mel=${melBuffer.size} features=${featureBuffer.size}")
    }

    /** Clears rolling history. Used after a detection so the next one starts clean. */
    fun reset() {
        melBuffer.clear()
        featureBuffer.clear()
        carryValid = false
    }

    fun close() {
        melspec?.close()
        embed?.close()
        wake?.close()
        melspec = null
        embed = null
        wake = null
        melBuffer.clear()
        featureBuffer.clear()
        carryValid = false
    }

    private fun invoke(
        interpreter: Interpreter,
        input: FloatArray,
        inBuffer: ByteBuffer,
        outBuffer: ByteBuffer,
        outSize: Int,
    ): FloatArray {
        inBuffer.rewind()
        for (v in input) inBuffer.putFloat(v)
        inBuffer.rewind()
        outBuffer.rewind()
        interpreter.run(inBuffer, outBuffer)
        outBuffer.rewind()
        return FloatArray(outSize) { outBuffer.float }
    }

    private fun directBuffer(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    /**
     * Memory-maps a model straight out of assets. Requires noCompress "tflite" in
     * the build script, otherwise the asset is deflated and has no file offset to
     * map.
     */
    private fun mapAsset(name: String) = context.assets.openFd(name).use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun IntArray.elements(): Int = fold(1) { acc, v -> acc * v }

    private fun IntArray.pretty(): String = joinToString(prefix = "[", postfix = "]")

    companion object {
        private const val TAG = "Wake"

        private const val MEL_MODEL = "melspectrogram.tflite"
        private const val EMBEDDING_MODEL = "embedding_model.tflite"
        private const val WAKE_MODEL = "hey_jarvis_v0.1.tflite"

        /** 160 * 3, the overlap the reference implementation feeds the mel model. */
        private const val LOOKBACK_SAMPLES = 480
        private const val MEL_INPUT_SAMPLES = AudioCapture.FRAME_SAMPLES + LOOKBACK_SAMPLES

        /** 10 seconds of mel history and roughly 10 seconds of embeddings. */
        private const val MEL_BUFFER_MAX = 970
        private const val FEATURE_BUFFER_MAX = 120

        private const val WARMUP_SECONDS = 4
        private const val INFERENCE_THREADS = 1
    }
}
