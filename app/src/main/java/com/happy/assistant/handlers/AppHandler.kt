package com.happy.assistant.handlers

import android.content.Context
import android.content.Intent
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.AppResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launching apps by name.
 *
 * Starting an activity from a background service is restricted on Android 10 and
 * later. A foreground service alone does not earn the right - the reliable grant
 * for a sideloaded assistant is "display over other apps", which is why that sits
 * on the setup checklist.
 */
@Singleton
class AppHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: AppResolver,
    private val log: HappyLog,
) {

    fun open(query: String): String {
        val app = resolver.resolve(query) ?: return "I could not find an app called $query."
        val intent = resolver.launchIntent(app)
            ?: return "${app.label} cannot be opened from here."
        return try {
            context.startActivity(intent)
            "Opening ${app.label}."
        } catch (t: Throwable) {
            log.e(TAG, "could not launch ${app.packageName}", t)
            "I could not open ${app.label}. Happy may need permission to display over other apps."
        }
    }

    /** Used by handlers that fire a system intent rather than a named app. */
    fun start(intent: Intent, describe: String, success: String): String = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        success
    } catch (t: Throwable) {
        log.e(TAG, "could not start $describe", t)
        "I could not $describe. Happy may need permission to display over other apps."
    }

    companion object {
        private const val TAG = "AppHandler"
    }
}
