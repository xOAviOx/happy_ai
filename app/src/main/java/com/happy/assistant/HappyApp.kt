package com.happy.assistant

import android.app.Application
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HappyApp : Application() {

    @Inject lateinit var log: HappyLog

    override fun onCreate() {
        super.onCreate()
        // Anything that escapes a thread's handler would otherwise vanish with the
        // process. Record it first, then let the platform handler kill us as usual.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log.crash("Crash", "uncaught on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        log.i("App", "Happy process started")
    }
}
