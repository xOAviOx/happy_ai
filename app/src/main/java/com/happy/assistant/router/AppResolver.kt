package com.happy.assistant.router

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spoken app name to a launchable package.
 *
 * Reading the launcher list needs a `queries` element in the manifest on Android
 * 11 and later; without it this silently returns an empty list, which looks
 * exactly like "no app matched".
 */
@Singleton
class AppResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    data class App(val label: String, val packageName: String)

    private var cache: List<App> = emptyList()
    private var cachedAt = 0L

    fun resolve(query: String): App? {
        val apps = apps()
        if (apps.isEmpty()) {
            log.w(TAG, "no launchable apps visible, check the manifest queries element")
            return null
        }
        val best = apps.mapNotNull { app -> Fuzzy.score(query, app.label)?.let { it to app } }
            .minByOrNull { it.first }
        if (best == null) {
            log.d(TAG, "no app matched \"$query\"")
            return null
        }
        log.d(TAG, "\"$query\" resolved to ${best.second.label} (score ${best.first})")
        return best.second
    }

    fun launchIntent(app: App): Intent? =
        context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun apps(): List<App> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cachedAt < CACHE_MS) return cache

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (t: Throwable) {
            log.e(TAG, "could not query launcher activities", t)
            emptyList()
        }
        cache = resolved.mapNotNull { info ->
            val label = info.loadLabel(context.packageManager)?.toString()?.trim()
            val pkg = info.activityInfo?.packageName
            if (label.isNullOrEmpty() || pkg.isNullOrEmpty()) null else App(label, pkg)
        }.distinctBy { it.packageName }
        cachedAt = now
        log.d(TAG, "${cache.size} launchable apps")
        return cache
    }

    companion object {
        private const val TAG = "Apps"
        private const val CACHE_MS = 5 * 60 * 1000L
    }
}
