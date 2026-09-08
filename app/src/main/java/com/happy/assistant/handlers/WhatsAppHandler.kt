package com.happy.assistant.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.ContactResolver
import com.happy.assistant.service.HappyAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sending a WhatsApp message to someone not already in a chat.
 *
 * WhatsApp has no send API, so this opens a wa.me deep link with the message
 * pre-filled and then presses send through the accessibility service. That
 * second half is inherently fragile: view ids change between WhatsApp releases,
 * and the chat takes an unpredictable moment to appear.
 *
 * So it fails in the least annoying direction. If the send button cannot be
 * found, the chat is still open with the message typed and Happy says so - the
 * user taps send. Nothing is lost, and nothing is sent by accident.
 */
@Singleton
class WhatsAppHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    suspend fun send(to: ContactResolver.Contact, message: String): String {
        val number = to.number.filter { it.isDigit() }
        if (number.isEmpty()) return "I do not have a usable number for ${to.name}."

        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(message))
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(WHATSAPP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            log.e(TAG, "could not open WhatsApp", t)
            return "I could not open WhatsApp."
        }

        if (!HappyAccessibilityService.isConnected) {
            return "I have opened the chat with ${to.name}. Tap send, because I do not have accessibility access."
        }

        // The chat takes an unpredictable moment to draw, so poll rather than
        // guess a single delay.
        repeat(SEND_ATTEMPTS) {
            delay(POLL_MS)
            if (HappyAccessibilityService.click(SEND_VIEW_IDS, SEND_DESCRIPTIONS)) {
                log.i(TAG, "WhatsApp message sent to ${to.name}")
                return "Sent to ${to.name} on WhatsApp."
            }
        }

        log.w(TAG, "send button never appeared for ${to.name}")
        return "The chat with ${to.name} is open with your message ready. Tap send."
    }

    companion object {
        private const val TAG = "WhatsApp"
        private const val WHATSAPP = "com.whatsapp"
        private const val SEND_ATTEMPTS = 12
        private const val POLL_MS = 400L

        private val SEND_VIEW_IDS = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
        )
        private val SEND_DESCRIPTIONS = listOf("send")
    }
}
