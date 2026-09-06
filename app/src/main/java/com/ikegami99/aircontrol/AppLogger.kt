package com.ikegami99.aircontrol

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AirControl"
    private val lock = Any()
    @Volatile private var logFile: File? = null
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.JAPAN)

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(context.filesDir, "aircontrol.log")
            }
            val file = logFile!!
            if (!file.exists() || file.length() == 0L) {
                appendRaw("=== AIR CONTROL LOG ===\n")
                appendRaw("created=${formatter.format(Date())}\n")
                appendRaw("device=${Build.MANUFACTURER} ${Build.MODEL}\n")
                appendRaw("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}\n")
                appendRaw("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n")
            }
        }
    }

    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, throwable: Throwable? = null) {
        write("E", message + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        throwable?.let { Log.e(TAG, message, it) }
    }

    private fun write(level: String, message: String) {
        Log.println(when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO }, TAG, message)
        synchronized(lock) {
            val safe = message.replace("\r", " ").replace("\n", " ")
            appendRaw("${formatter.format(Date())} [$level] $safe\n")
        }
    }

    private fun appendRaw(text: String) {
        val file = logFile ?: return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { fos ->
            OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                writer.write(text)
                writer.flush()
                fos.fd.sync()
            }
        }
    }

    fun exportToUri(context: Context, uri: Uri): String {
        return try {
            init(context.applicationContext)
            val source = logFile
            val payload = if (source != null && source.exists() && source.length() > 0L) {
                source.readBytes()
            } else {
                buildString {
                    append("=== AIR CONTROL LOG EXPORT ===\n")
                    append("No internal log payload was available.\n")
                    append("time=${formatter.format(Date())}\n")
                    append("app=${BuildConfig.VERSION_NAME}\n")
                }.toByteArray(Charsets.UTF_8)
            }

            require(payload.isNotEmpty()) { "Export payload unexpectedly empty" }
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("保存先を開けませんでした")
            output.use { stream ->
                stream.write(payload)
                stream.flush()
            }

            val verifyCount = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64)
                input.read(buffer)
            } ?: -1

            if (verifyCount == 0) {
                error("書き出し後のファイルが空です")
            }
            i("Log exported bytes=${payload.size}, verifyRead=$verifyCount")
            "ログを書き出しました (${payload.size} bytes)"
        } catch (t: Throwable) {
            e("Log export failed", t)
            "ログの書き出しに失敗: ${t.message}"
        }
    }
}
