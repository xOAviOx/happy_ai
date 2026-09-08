package com.happy.assistant.handlers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.ContactResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placing, answering and ending calls, and reading the call log back.
 *
 * ACTION_CALL dials immediately with no confirmation screen, which is the point
 * of a voice assistant, and is also why the caller must be certain which person
 * was meant before this is reached. Ambiguity is resolved before dialling, never
 * after.
 */
@Singleton
class CallHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apps: AppHandler,
    private val contacts: ContactResolver,
    private val log: HappyLog,
) {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun dial(contact: ContactResolver.Contact): String {
        if (!granted(Manifest.permission.CALL_PHONE)) return "I need permission to place calls."
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(contact.number)))
        return apps.start(intent, "place the call", "Calling ${contact.name}.")
    }

    /** Redials the most recent call in either direction. */
    fun callBack(): Reply {
        if (!granted(Manifest.permission.READ_CALL_LOG)) {
            return Reply("I need permission to read your call log.")
        }
        val last = recentCalls(limit = 1).firstOrNull()
            ?: return Reply("I could not find a recent call.")
        return Reply(dial(ContactResolver.Contact(last.name, last.number)))
    }

    fun answer(): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "I need permission to answer calls."
        }
        return try {
            context.getSystemService(TelecomManager::class.java).acceptRingingCall()
            "Answered."
        } catch (t: Throwable) {
            log.e(TAG, "could not answer", t)
            "I could not answer the call."
        }
    }

    fun reject(): String {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "I need permission to end calls."
        }
        return try {
            val ended = context.getSystemService(TelecomManager::class.java).endCall()
            if (ended) "Call ended." else "There was no call to end."
        } catch (t: Throwable) {
            log.e(TAG, "could not end the call", t)
            "I could not end the call."
        }
    }

    /**
     * Speakerphone. setCommunicationDevice replaced setSpeakerphoneOn in API 31,
     * and minSdk here is 31, so the deprecated path is not needed.
     */
    fun speakerphone(on: Boolean): String {
        val audio = context.getSystemService(AudioManager::class.java)
        return try {
            if (on) {
                val speaker = audio.availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: return "This phone has no speakerphone to switch to."
                if (audio.setCommunicationDevice(speaker)) "Speaker on." else "I could not switch to the speaker."
            } else {
                audio.clearCommunicationDevice()
                "Speaker off."
            }
        } catch (t: Throwable) {
            log.e(TAG, "speakerphone toggle failed", t)
            "I could not change the speaker."
        }
    }

    data class RecentCall(
        val name: String,
        val number: String,
        val type: Int,
        val at: Long,
    )

    /**
     * Who rang, most recent first.
     *
     * Missed calls lead, because that is what the question usually means. A
     * number with no cached name is looked up in contacts before falling back to
     * reading the digits.
     */
    fun whoCalled(): String {
        if (!granted(Manifest.permission.READ_CALL_LOG)) {
            return "I need permission to read your call log."
        }
        val calls = recentCalls(limit = CALL_LOG_LOOKBACK)
            .filter { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.INCOMING_TYPE }
        if (calls.isEmpty()) return "No one has called recently."

        val missed = calls.filter { it.type == CallLog.Calls.MISSED_TYPE }
        val toRead = (missed.ifEmpty { calls }).take(MAX_SPOKEN_CALLS)
        val who = toRead.joinToString(", ") { it.name }
        return if (missed.isNotEmpty()) {
            if (missed.size == 1) "You missed a call from $who." else "You missed calls from $who."
        } else {
            "The last call was from $who."
        }
    }

    private fun recentCalls(limit: Int): List<RecentCall> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        val out = mutableListOf<RecentCall>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT " + limit,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(0)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    val cached = cursor.getString(1)?.trim().orEmpty()
                    val name = cached.ifEmpty { nameFor(number) }
                    out += RecentCall(name, number, cursor.getInt(2), cursor.getLong(3))
                }
            }
        } catch (t: Throwable) {
            log.e(TAG, "could not read the call log", t)
        }
        return out
    }

    /** Falls back to spoken digits, which is more use than "unknown number". */
    private fun nameFor(number: String): String {
        val tail = number.filter(Char::isDigit).takeLast(TAIL)
        val match = contacts.all().firstOrNull {
            it.number.filter(Char::isDigit).endsWith(tail)
        }
        return match?.name ?: contacts.spoken(number)
    }

    companion object {
        private const val TAG = "Call"
        private const val CALL_LOG_LOOKBACK = 20
        private const val MAX_SPOKEN_CALLS = 3
        private const val TAIL = 8
    }
}
