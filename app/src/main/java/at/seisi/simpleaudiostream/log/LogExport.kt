package at.seisi.simpleaudiostream.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import at.seisi.simpleaudiostream.CastState
import at.seisi.simpleaudiostream.Prefs
import at.seisi.simpleaudiostream.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bundles device info, the app log and the app's own logcat into a text file and opens the share sheet. */
object LogExport {
    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) { write(context) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_subject))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_log)))
    }

    private fun write(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "simpleaudiostream-log-$stamp.txt")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val renderer = Prefs(context).renderer
        file.bufferedWriter().use { out ->
            out.appendLine("SimpleAudioStream ${info.versionName}")
            out.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            out.appendLine("Speaker: ${renderer?.let { "${it.name} (${it.model}) ${it.location}" } ?: "none"}")
            out.appendLine("Status: ${CastState.status.value}")
            out.appendLine()
            out.appendLine("===== app log =====")
            AppLog.files.filter { it.exists() }.forEach { out.write(it.readText()) }
            out.appendLine()
            out.appendLine("===== logcat (this app only) =====")
            // Apps can read their own log lines without any permission.
            runCatching {
                val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000")
                    .redirectErrorStream(true).start()
                process.inputStream.bufferedReader().use { it.copyTo(out) }
                process.waitFor()
            }.onFailure { out.appendLine("logcat unavailable: $it") }
        }
        return file
    }
}
