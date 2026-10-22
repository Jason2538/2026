package com.bookscan.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class ScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            CrashLog.write(this, "스레드: ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        try {
            System.loadLibrary("opencv_java4")
            CrashLog.openCvReady = true
        } catch (e: Throwable) {
            CrashLog.write(this, "OpenCV 불러오기 실패", e)
        }
    }
}

/** Saves the last crash so the app can show it (and the user can copy it) on the next start. */
object CrashLog {
    @Volatile var openCvReady = false

    fun file(app: Application) = File(app.filesDir, "crash.txt")

    fun write(app: Application, where: String, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val info = "북스캔 1.0 (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})\n$where\n\n$sw"
            file(app).writeText(info.take(12000))
        } catch (_: Throwable) {
        }
    }
}
