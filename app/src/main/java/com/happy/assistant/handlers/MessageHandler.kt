package com.happy.assistant.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.ContactResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sending texts, fully programmatically - no messaging app, no send button.
 *
 * Which is exactly why nothing here sends without a spoken confirmation first.
 * Recognition mishears names and mangles sentences, and a text to the wrong
 * person cannot be recalled. [compose] only prepares; [send] is reached after
 * the user says yes.
 */
@Singleton
class MessageHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Reads the message back and asks. Never sends. */
    fun compose(to: ContactResolver.Contact, message: String): Reply {
        if (!hasPermission()) return Reply("I need permission to send texts.")
        return Reply(
            "Send ${to.name}, $message. Is that right?",
            Pending.ConfirmSms(to, message),
        )
    }

    fun send(to: ContactResolver.Contact, message: String): String {
        if (!hasPermission()) return "I need permission to send texts."
        return try {
            val manager = context.getSystemService(SmsManager::class.java)
                ?: return "This phone cannot send texts."
            // Long messages must be split, or they are silently truncated.
            val parts = manager.divideMessage(message)
            if (parts.size == 1) {
                manager.sendTextMessage(to.number, null, message, null, null)
            } else {
                manager.sendMultipartTextMessage(to.number, null, parts, null, null)
            }
            log.i(TAG, "text sent to ${to.name}, ${parts.size} part(s)")
            "Sent."
        } catch (t: Throwable) {
            log.e(TAG, "could not send the text", t)
            "I could not send that text."
        }
    }

    /** Recognises a spoken yes. Anything else is treated as no, deliberately. */
    fun isYes(answer: String): Boolean {
        val a = answer.lowercase().trim()
        return AFFIRMATIVE.any { a == it || a.startsWith("$it ") }
    }

    companion object {
        private const val TAG = "Sms"
        private val AFFIRMATIVE = setOf(
            "yes", "yeah", "yep", "yup", "correct", "right", "send it", "send",
            "go ahead", "confirm", "ok", "okay", "haan", "haa", "ha", "theek hai", "bhej do",
        )
    }
}
