package com.happy.assistant.handlers

import android.content.Context
import android.content.pm.PackageManager
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.Fuzzy
import com.happy.assistant.service.HappyNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reading notifications aloud, and replying to them inline.
 *
 * Replying goes through the notification's own RemoteInput action, so it works in
 * any app that supports inline reply without simulating taps.
 */
@Singleton
class NotificationHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    private val notGranted =
        "I need notification access for that. It is on the setup checklist."

    fun readRecent(): String {
        if (!HappyNotificationListener.isEnabled(context)) return notGranted
        if (!HappyNotificationListener.connected) {
            return "Notification access is on but not connected yet. Try again in a moment."
        }
        val items = HappyNotificationListener.snapshot()
        if (items.isEmpty()) return "Nothing new."

        val spoken = items.take(MAX_SPOKEN).joinToString(". ") { item ->
            val who = item.title.ifEmpty { appLabel(item.packageName) }
            if (item.text.isEmpty()) who else "$who says ${item.text}"
        }
        val count = items.size
        val preamble = if (count == 1) "One notification. " else "$count notifications. "
        return preamble + spoken
    }

    /**
     * Replies to the most recent notification that plausibly came from [name] and
     * actually offers an inline reply.
     *
     * Matching is fuzzy against the sender's name and the app name, since a
     * spoken name rarely matches a notification title exactly.
     */
    fun reply(name: String, message: String): String {
        if (!HappyNotificationListener.isEnabled(context)) return notGranted

        val candidates = HappyNotificationListener.snapshot()
            .filter { HappyNotificationListener.canReplyTo(it.key) }
        if (candidates.isEmpty()) return "I could not find a message I can reply to."

        val best = candidates.mapNotNull { item ->
            val byTitle = Fuzzy.score(name, item.title)
            val byApp = Fuzzy.score(name, appLabel(item.packageName))
            listOfNotNull(byTitle, byApp).minOrNull()?.let { it to item }
        }.minByOrNull { it.first }?.second
            ?: return "I could not find a message from $name to reply to."

        return if (HappyNotificationListener.reply(best.key, message)) {
            "Replied to ${best.title.ifEmpty { appLabel(best.packageName) }}."
        } else {
            "That message would not accept a reply."
        }
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.')
    }

    companion object {
        private const val MAX_SPOKEN = 5
    }
}
