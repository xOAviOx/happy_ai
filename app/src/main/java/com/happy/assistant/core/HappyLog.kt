package com.happy.assistant.core

import android.util.Log
import com.happy.assistant.data.LogDao
import com.happy.assistant.data.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * The single place anything in Happy reports what it did.
 *
 * Writes to logcat (for a cabled session) and to Room (for reading a failure back
 * on the phone hours later, which is the case that actually matters for an
 * always-on background service).
 *
 * Every method is fire-and-forget and never throws: a logger that can crash the
 * service defeats its own purpose. Spec section 12 - no silent swallowing.
 */
@Singleton
class HappyLog @Inject constructor(private val dao: LogDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun d(tag: String, message: String, durationMs: Long? = null) =
        write(LogLevel.DEBUG, tag, message, durationMs)

    fun i(tag: String, message: String, durationMs: Long? = null) =
        write(LogLevel.INFO, tag, message, durationMs)

    fun w(tag: String, message: String) = write(LogLevel.WARN, tag, message, null)

    fun e(tag: String, message: String, t: Throwable? = null) {
        val detail = if (t == null) message else "$message\n${stackTraceOf(t)}"
        write(LogLevel.ERROR, tag, detail, null)
    }

    /**
     * Persists synchronously, for the crash handler: the process is about to die
     * and a fire-and-forget insert would die with it. Bounded, so it can never be
     * the reason the app hangs on the way out.
     */
    fun crash(tag: String, message: String, t: Throwable) {
        Log.e("$TAG_PREFIX$tag", message, t)
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = LogLevel.ERROR.name,
            tag = tag,
            message = "$message\n${stackTraceOf(t)}",
        )
        try {
            runBlocking { withTimeout(BLOCKING_WRITE_TIMEOUT_MS) { dao.insert(entry) } }
        } catch (t2: Throwable) {
            Log.e("${TAG_PREFIX}HappyLog", "failed to persist crash entry", t2)
        }
    }

    /** Times [block] and logs how long it took. Feeds the section 3 latency budget. */
    inline fun <T> timed(tag: String, label: String, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            d(tag, "$label took ${ms}ms", ms)
        }
    }

    fun write(level: LogLevel, tag: String, message: String, durationMs: Long?) {
        val prefixed = "$TAG_PREFIX$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(prefixed, message)
            LogLevel.INFO -> Log.i(prefixed, message)
            LogLevel.WARN -> Log.w(prefixed, message)
            LogLevel.ERROR -> Log.e(prefixed, message)
        }
        scope.launch {
            try {
                dao.insert(
                    LogEntry(
                        timestamp = System.currentTimeMillis(),
                        level = level.name,
                        tag = tag,
                        message = message,
                        durationMs = durationMs,
                    )
                )
                if (dao.count() > MAX_ROWS + TRIM_SLACK) dao.trimTo(MAX_ROWS)
            } catch (t: Throwable) {
                Log.e("${TAG_PREFIX}HappyLog", "failed to persist log entry", t)
            }
        }
    }

    fun stackTraceOf(t: Throwable): String = StringWriter().also { sw ->
        PrintWriter(sw).use { t.printStackTrace(it) }
    }.toString().lineSequence().take(STACK_TRACE_LINES).joinToString("\n")

    companion object {
        const val TAG_PREFIX = "Happy/"
        private const val MAX_ROWS = 500
        private const val TRIM_SLACK = 50
        private const val STACK_TRACE_LINES = 12
        private const val BLOCKING_WRITE_TIMEOUT_MS = 750L
    }
}
