package com.example.pococam

import android.content.Context
import java.io.File

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                file(app).writeText("Thread: ${t.name}\n" + e.stackTraceToString().take(3500))
            }
            old?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
