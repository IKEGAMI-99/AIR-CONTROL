package com.ikegami99.aircontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

object UpdateManager {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/IKEGAMI-99/AIR-CONTROL/releases/latest"

    fun checkAndInstall(context: Context, callback: (String) -> Unit) {
        Thread {
            try {
                val release = fetchLatestRelease()
                val latest = release.getString("tag_name").removePrefix("v")
                val current = BuildConfig.VERSION_NAME.removePrefix("v")
                AppLogger.i("Update check current=$current latest=$latest")

                if (compareVersions(latest, current) <= 0) {
                    callback("最新版です ($current)")
                    return@Thread
                }

                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                var apkName = "AIR-CONTROL-$latest.apk"
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.getString("browser_download_url")
                        apkName = name
                        break
                    }
                }
                if (apkUrl == null) {
                    callback("v$latest はありますがAPKが添付されていません")
                    return@Thread
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    callback("AIR CONTROLからのアプリインストールを許可して、もう一度更新を押してください")
                    return@Thread
                }

                callback("v$latest をダウンロード中…")
                val apk = downloadApk(context, apkUrl, apkName)
                launchInstaller(context, apk)
                callback("v$latest のインストーラを開きました")
            } catch (t: Throwable) {
                AppLogger.e("Update failed", t)
                callback("アップデート確認に失敗: ${t.message}")
            }
        }.start()
    }

    private fun fetchLatestRelease(): JSONObject {
        val connection = URI(LATEST_RELEASE_API).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "AIR-CONTROL/${BuildConfig.VERSION_NAME}")
        return try {
            val code = connection.responseCode
            if (code == 404) error("GitHub Releaseがまだありません")
            if (code !in 200..299) error("GitHub API HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadApk(context: Context, url: String, name: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val temp = File(dir, "${target.name}.part")
        temp.delete()

        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "AIR-CONTROL/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode !in 200..299) error("APK download HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (temp.length() < 1_000_000L) {
            temp.delete()
            error("APKファイルが小さすぎます")
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        AppLogger.i("Update APK downloaded bytes=${target.length()} file=${target.name}")
        return target
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
