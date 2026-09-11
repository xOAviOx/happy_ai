package com.happy.assistant.knowledge

import com.happy.assistant.core.HappyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wikipedia's REST summary endpoint. No key, no quota, no account.
 *
 * Spec section 7: this is tried before Gemini for anything that looks like a
 * named entity, because it is faster and, more importantly, it is grounded. A
 * model asked who someone is will answer confidently either way; Wikipedia
 * either has an article or says it does not.
 */
@Singleton
class WikipediaClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val log: HappyLog,
) {

    @Serializable
    private data class Summary(
        val title: String = "",
        val extract: String = "",
        val type: String = "",
        @SerialName("description") val description: String = "",
    )

    /**
     * A short spoken summary, or null when there is no clean article - which is
     * the signal to fall through to Gemini rather than to apologise.
     */
    suspend fun summarise(subject: String): String? = withContext(Dispatchers.IO) {
        val topic = subject.trim()
        if (topic.isEmpty()) return@withContext null

        val title = URLEncoder.encode(topic.replace(' ', '_'), "UTF-8")
        val request = Request.Builder()
            .url("$BASE$title")
            .header("Accept", "application/json")
            // Wikipedia asks that clients identify themselves.
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    log.d(TAG, "no article for \"$topic\"")
                    return@withContext null
                }
                if (!response.isSuccessful) {
                    log.w(TAG, "wikipedia returned ${response.code} for \"$topic\"")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) return@withContext null

                val summary = json.decodeFromString<Summary>(body)
                // A disambiguation page lists other pages; reading it aloud is
                // noise. Better to let the model answer in prose.
                if (summary.type == DISAMBIGUATION) {
                    log.d(TAG, "\"$topic\" is a disambiguation page")
                    return@withContext null
                }
                val extract = summary.extract.trim()
                if (extract.isEmpty()) return@withContext null

                log.d(TAG, "wikipedia answered for \"$topic\"")
                firstSentences(extract, MAX_SENTENCES)
            }
        } catch (t: Throwable) {
            log.w(TAG, "wikipedia lookup failed for \"$topic\": ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Trims to a couple of sentences. Wikipedia's opening paragraph is written to
     * be read, not heard, and the whole thing is far too long spoken aloud.
     */
    private fun firstSentences(text: String, count: Int): String {
        val out = StringBuilder()
        var taken = 0
        var i = 0
        while (i < text.length && taken < count) {
            val c = text[i]
            out.append(c)
            // An initial like "J. R. R." is not the end of a sentence.
            val endsSentence = (c == '.' || c == '!' || c == '?') &&
                (i + 1 >= text.length || text[i + 1] == ' ') &&
                !(i >= 1 && text[i - 1].isUpperCase() && (i < 2 || !text[i - 2].isLetter()))
            if (endsSentence) taken++
            i++
        }
        return out.toString().trim()
    }

    companion object {
        private const val TAG = "Wikipedia"
        private const val BASE = "https://en.wikipedia.org/api/rest_v1/page/summary/"
        private const val DISAMBIGUATION = "disambiguation"
        private const val MAX_SENTENCES = 2
        private const val USER_AGENT = "Happy/0.1 (personal voice assistant; sideloaded)"
    }
}
