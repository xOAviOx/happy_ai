package com.happy.assistant.knowledge

import com.happy.assistant.BuildConfig
import com.happy.assistant.core.HappyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini 2.5 Flash on the free tier, streamed.
 *
 * Two things here were measured against the live API rather than assumed:
 *
 *  - Thinking is switched off for normal answers. With it on, the response
 *    arrives as a single chunk however long it is, which makes sentence-by-
 *    sentence speech pointless. With thinkingBudget at zero the same answer
 *    arrives in several chunks and can be spoken as it lands.
 *  - streamGenerateContent works despite being absent from the model's
 *    advertised supportedGenerationMethods. That field cannot be trusted.
 */
@Singleton
class GeminiClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val log: HappyLog,
) {

    sealed interface Event {
        /** A piece of the answer, in order. */
        data class Chunk(val text: String) : Event

        /** Terminal. The text is safe to read aloud as it is. */
        data class Failed(val spoken: String) : Event
    }

    val isConfigured: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotBlank()

    private enum class Outcome { Done, RateLimited, Offline, Failed }

    /**
     * Streams an answer. Never throws: failures arrive as [Event.Failed] carrying
     * something sayable, because a voice assistant cannot show a stack trace.
     */
    fun ask(question: String, elaborate: Boolean = false): Flow<Event> = flow {
        if (!isConfigured) {
            emit(Event.Failed("I do not have an API key set up for that."))
            return@flow
        }

        var attempt = 0
        var wait = FIRST_BACKOFF_MS
        while (true) {
            when (stream(question, elaborate) { emit(Event.Chunk(it)) }) {
                Outcome.Done -> return@flow

                Outcome.RateLimited -> {
                    if (attempt++ >= MAX_RETRIES) {
                        // Google revises free-tier quotas without notice, so never
                        // quote a number back at the user.
                        emit(Event.Failed("I have hit my daily limit for questions."))
                        return@flow
                    }
                    log.w(TAG, "rate limited, waiting ${wait}ms (attempt $attempt)")
                    delay(wait)
                    wait *= 2
                }

                Outcome.Offline -> {
                    emit(Event.Failed("I need a connection for that."))
                    return@flow
                }

                Outcome.Failed -> {
                    emit(Event.Failed("I could not get an answer just now."))
                    return@flow
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun stream(
        question: String,
        elaborate: Boolean,
        onChunk: suspend (String) -> Unit,
    ): Outcome {
        val request = Request.Builder()
            .url(BASE + MODEL + ":streamGenerateContent?alt=sse&key=" + BuildConfig.GEMINI_API_KEY)
            .post(body(question, elaborate).toRequestBody(JSON_TYPE))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == TOO_MANY_REQUESTS) return Outcome.RateLimited
                if (!response.isSuccessful) {
                    log.w(TAG, "gemini returned ${response.code}")
                    return Outcome.Failed
                }
                val source = response.body?.source() ?: return Outcome.Failed
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val payload = line.removePrefix(DATA_PREFIX).trim()
                    if (payload.isEmpty() || payload == DONE) continue
                    textOf(payload)?.let { if (it.isNotEmpty()) onChunk(it) }
                }
                Outcome.Done
            }
        } catch (t: IOException) {
            // No connection, DNS failure, timeout. Distinct from a bad response,
            // because offline has its own answer.
            log.w(TAG, "gemini call failed: ${t.javaClass.simpleName}")
            Outcome.Offline
        } catch (t: Throwable) {
            log.e(TAG, "gemini call failed", t)
            Outcome.Failed
        }
    }

    /** Pulls the text out of one SSE payload, tolerating anything unexpected. */
    private fun textOf(payload: String): String? = try {
        json.parseToJsonElement(payload)
            .jsonObject["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.joinToString("") { part ->
                part.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
            }
    } catch (t: Throwable) {
        // A malformed chunk must not kill the whole answer.
        log.d(TAG, "unreadable stream chunk, skipping")
        null
    }

    private fun body(question: String, elaborate: Boolean): String {
        val config = buildString {
            append("{\"temperature\":0.3,\"maxOutputTokens\":")
            append(if (elaborate) ELABORATE_TOKENS else NORMAL_TOKENS)
            // Thinking stays off for normal answers: it buffers the whole response
            // into a single chunk and defeats streaming. An explicit request to
            // elaborate is worth the wait.
            if (!elaborate) append(",\"thinkingConfig\":{\"thinkingBudget\":0}")
            append("}")
        }
        return "{\"systemInstruction\":{\"parts\":[{\"text\":" + quote(SYSTEM_PROMPT) + "}]}," +
            "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":" + quote(question) + "}]}]," +
            "\"generationConfig\":" + config + "}"
    }

    /** Proper JSON string escaping, rather than hoping the text has no quotes. */
    private fun quote(text: String): String =
        json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    companion object {
        private const val TAG = "Gemini"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MODEL = "gemini-2.5-flash"
        private val JSON_TYPE = "application/json".toMediaType()
        private const val DATA_PREFIX = "data:"
        private const val DONE = "[DONE]"
        private const val TOO_MANY_REQUESTS = 429
        private const val NORMAL_TOKENS = 200
        private const val ELABORATE_TOKENS = 600
        private const val MAX_RETRIES = 4
        private const val FIRST_BACKOFF_MS = 1_000L

        /** Section 7 of the build spec, verbatim. */
        private val SYSTEM_PROMPT = """
            You are a voice assistant. Everything you output is read aloud by a
            text-to-speech engine.

            Rules:
            - Plain spoken prose only. Never use markdown, asterisks, hashes, backticks,
              underscores, bullet points, numbered lists, tables, emoji, or any symbol that
              is not ordinary sentence punctuation.
            - Answer in three sentences or fewer unless the user explicitly asks you to
              elaborate.
            - Write numbers, units and symbols as spoken words where it reads naturally.
            - If you are not confident about a fact, say you are not sure. Never invent
              names, dates, statistics, quotations or sources.
            - Do not greet, do not add filler, do not offer follow-up questions. Answer and
              stop.
        """.trimIndent()
    }
}
