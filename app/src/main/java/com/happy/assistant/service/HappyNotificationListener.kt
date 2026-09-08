package com.happy.assistant.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Reads notifications so Happy can say what arrived, and replies to them through
 * RemoteInput.
 *
 * RemoteInput is the reason this exists rather than an accessibility script: it
 * is the same channel the notification shade uses, so replying works in any app
 * that supports inline reply without simulating taps in a UI that may move.
 *
 * The system binds this service; nothing constructs it. It keeps only the last
 * few notifications, in memory, and none of it is persisted or sent anywhere.
 */
@AndroidEntryPoint
class HappyNotificationListener : NotificationListenerService() {

    @Inject lateinit var log: HappyLog

    data class Item(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        instance = this
        log.i(TAG, "notification listener connected")
        // Seed from what is already on screen, so "what did I miss" works
        // immediately rather than only for things that arrive from now on.
        try {
            activeNotifications?.forEach { remember(it) }
        } catch (t: Throwable) {
            log.e(TAG, "could not read active notifications", t)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        log.w(TAG, "notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        remember(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(recent) { recent.removeAll { it.key == sbn.key } }
    }

    private fun remember(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        // Ongoing notifications are status, not news. Happy's own is one of them.
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val item = Item(sbn.key, sbn.packageName, title, text, sbn.postTime)
        synchronized(recent) {
            recent.removeAll { it.key == item.key }
            recent.add(item)
            while (recent.size > MAX_REMEMBERED) recent.removeAt(0)
        }
    }

    /**
     * Replies to a notification through its own RemoteInput action.
     *
     * The live notification is looked up again rather than cached: its
     * PendingIntent may have been cancelled since it was seen, and firing a stale
     * one fails in ways that are hard to read.
     */
    private fun sendReply(key: String, message: String): Boolean {
        val sbn = try {
            activeNotifications?.firstOrNull { it.key == key }
        } catch (t: Throwable) {
            log.e(TAG, "could not re-read notifications", t)
            null
        } ?: return false

        val action = sbn.notification?.actions?.firstOrNull { candidate ->
            candidate.remoteInputs?.isNotEmpty() == true
        } ?: return false

        return try {
            val intent = Intent()
            val bundle = Bundle()
            action.remoteInputs.forEach { input -> bundle.putCharSequence(input.resultKey, message) }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
            // Some apps only accept the reply when told it came from a free-form
            // input rather than a canned choice.
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            action.actionIntent.send(this, 0, intent)
            log.i(TAG, "replied to ${sbn.packageName}")
            true
        } catch (t: Throwable) {
            log.e(TAG, "reply failed for ${sbn.packageName}", t)
            false
        }
    }

    companion object {
        private const val TAG = "Notifications"
        private const val MAX_REMEMBERED = 30

        private val recent = mutableListOf<Item>()

        @Volatile
        var connected = false
            private set

        @Volatile
        private var instance: HappyNotificationListener? = null

        /** True when the notification carries an inline reply action. */
        fun canReplyTo(key: String): Boolean =
            instance?.activeNotifications?.firstOrNull { it.key == key }
                ?.notification?.actions
                ?.any { it.remoteInputs?.isNotEmpty() == true } == true

        fun reply(key: String, message: String): Boolean =
            instance?.sendReply(key, message) ?: false

        /** Most recent first. */
        fun snapshot(): List<Item> = synchronized(recent) { recent.reversed() }

        /** Whether the user has granted notification access in Settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(":").any { it.startsWith("${context.packageName}/") }
        }
    }
}
