package com.heartlink.app

import android.app.Application
import android.content.Context
import java.io.File

/**
 * Saves the last crash so the app can show it next time it opens. Watches are hard to connect
 * to a computer, so this is the easiest way to see why the app closed.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file(context).writeText(
                    "${java.util.Date()} on ${thread.name}\n${error.stackTraceToString()}".take(8_000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}

class HeartLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
