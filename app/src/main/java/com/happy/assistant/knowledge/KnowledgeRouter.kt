package com.happy.assistant.knowledge

import com.happy.assistant.core.HappyLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides who answers a question, and in what order.
 *
 * Spec section 7, the anti-hallucination rules. Deterministic sources are tried
 * first and a language model is the last resort, never the first. Time, date,
 * battery and storage never arrive here at all - the intent router matches those
 * as commands before anything reaches this class.
 *
 * Wikipedia is preferred over the model for named entities because it is
 * grounded: it either has an article or it does not. A model asked who someone is
 * will answer either way, with the same confidence.
 */
@Singleton
class KnowledgeRouter @Inject constructor(
    private val math: MathEvaluator,
    private val weather: WeatherClient,
    private val wikipedia: WikipediaClient,
    private val gemini: GeminiClient,
    private val log: HappyLog,
) {

    sealed interface Answer {
        /** Complete and ready to speak. */
        data class Spoken(val text: String) : Answer

        /** Arrives in pieces and should be spoken sentence by sentence. */
        data class Streaming(val events: Flow<GeminiClient.Event>) : Answer
    }

    suspend fun answer(question: String, elaborate: Boolean = false): Answer {
        val text = question.trim()
        if (text.isEmpty()) return Answer.Spoken("I did not catch that.")
        val lower = text.lowercase()

        // 1. Arithmetic and unit conversion, entirely on device.
        math.evaluate(lower)?.let {
            log.d(TAG, "answered by maths")
            return Answer.Spoken(it)
        }

        // 2. Weather, from Open-Meteo. Never from a model.
        if (!elaborate && WEATHER.containsMatchIn(lower)) {
            log.d(TAG, "answered by weather")
            val tomorrow = TOMORROW.containsMatchIn(lower)
            return Answer.Spoken(weather.describe(placeIn(lower), tomorrow))
        }

        // 3. Named entities, from Wikipedia - but only when the question is asking
        //    what something IS. "Why" and "explain" want prose, not a definition.
        if (!elaborate && !WANTS_PROSE.containsMatchIn(lower)) {
            subjectOf(lower)?.let { subject ->
                wikipedia.summarise(subject)?.let {
                    log.d(TAG, "answered by wikipedia: $subject")
                    return Answer.Spoken(it)
                }
                log.d(TAG, "wikipedia had nothing for \"$subject\", falling through")
            }
        }

        // 4. Everything else.
        log.d(TAG, "asking gemini (elaborate=$elaborate)")
        return Answer.Streaming(gemini.ask(text, elaborate))
    }

    /** "weather in Delhi" -> "Delhi". Null means here. */
    private fun placeIn(lower: String): String? =
        PLACE.find(lower)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The thing being asked about, for a "who is" or "what is" question.
     *
     * Deliberately narrow. Anything with a verb of explanation, or a question that
     * is really about the user's own phone, is not an encyclopedia lookup.
     */
    private fun subjectOf(lower: String): String? {
        val match = ENTITY.find(lower) ?: return null
        val subject = match.groupValues[1]
            .trim()
            .removeSuffix("?")
            .trim()
        if (subject.isEmpty() || subject.length > MAX_SUBJECT) return null
        if (subject.split(" ").size > MAX_SUBJECT_WORDS) return null
        if (PERSONAL.containsMatchIn(subject)) return null
        return subject
    }

    companion object {
        private const val TAG = "Knowledge"
        private const val MAX_SUBJECT = 60
        private const val MAX_SUBJECT_WORDS = 6

        private val WEATHER = Regex(
            """\b(weather|temperature outside|forecast|raining|going to rain|mausam|barish)\b"""
        )
        private val TOMORROW = Regex("""\b(tomorrow|kal)\b""")
        private val PLACE = Regex("""(?:weather|forecast|temperature) (?:in|at|for) ([a-z ]+?)(?: tomorrow)?$""")

        /** Questions that want an explanation rather than a definition. */
        private val WANTS_PROSE = Regex("""^\s*(why|how|explain|tell me about|compare|should i|can i|do i)\b""")

        private val ENTITY = Regex("""^\s*(?:who|what)(?:'s| is| was| are| were)\s+(?:a |an |the )?(.+)$""")

        /** "what is my battery" is about this phone, not about a concept. */
        private val PERSONAL = Regex("""\b(my|your|this phone|the phone)\b""")
    }
}
