package at.seisi.simpleaudiostream.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logs to logcat and to a small rotating file in the app's storage, so users can export
 * a log after the fact (logcat alone is shared device-wide and overwritten quickly).
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "app-log").apply { isDaemon = true } }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var dir: File? = null

    val files: List<File> get() = dir?.let { listOf(File(it, "log.1.txt"), File(it, "log.txt")) }.orEmpty()

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written synchronously: the process is about to die.
            append(line("E", "Crash", "Uncaught exception in ${thread.name}", error))
            previous?.uncaughtException(thread, error)
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        enqueue(line("I", tag, message, null))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        enqueue(line("W", tag, message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        enqueue(line("E", tag, message, error))
    }

    private fun line(level: String, tag: String, message: String, error: Throwable?): String {
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val trace = error?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }
        return "$time $level/$tag: $message\n" + (trace ?: "")
    }

    private fun enqueue(text: String) {
        writer.execute { append(text) }
    }

    @Synchronized
    private fun append(text: String) {
        val dir = dir ?: return
        runCatching {
            val current = File(dir, "log.txt")
            if (current.length() > MAX_BYTES) current.renameTo(File(dir, "log.1.txt"))
            current.appendText(text)
        }
    }
}
