package com.ikegami99.aircontrol

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        AppLogger.i("MainActivity created, version=${BuildConfig.VERSION_NAME}")
        setContentView(buildUi())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 17, 10))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        root.addView(TextView(this).apply {
            text = "AIR CONTROL"
            textSize = 32f
            setTextColor(Color.rgb(96, 255, 138))
            gravity = Gravity.CENTER
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "HAND TRACKING INPUT LAYER"
            textSize = 12f
            setTextColor(Color.rgb(139, 196, 153))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }, fullWidth())

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(12, 32, 18))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(statusText, fullWidth(bottom = 18))

        root.addView(actionButton("1. カメラ権限") { requestCameraPermission() }, fullWidth())

        root.addView(actionButton("2. ユーザー補助設定") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, fullWidth())

        root.addView(actionButton("アクセス拒否された場合 / 制限付き設定を解除") {
            showRestrictedSettingsHelp()
        }, fullWidth())

        root.addView(actionButton("手追跡をテスト（カメラ＋ボーン表示）") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermission()
                Toast.makeText(this, "先にカメラ権限を許可してください", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, HandTestActivity::class.java))
            }
        }, fullWidth(top = 10))

        startButton = actionButton("START AIR CONTROL") { startAirControl() }.apply {
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(96, 255, 138))
        }
        root.addView(startButton, fullWidth(top = 12))

        root.addView(actionButton("STOP") {
            stopService(Intent(this, AirControlService::class.java))
            AppLogger.i("Stop requested from UI")
            refreshStatus()
        }, fullWidth())

        root.addView(actionButton("TikTokを開く") { openTikTok() }, fullWidth(top = 10))
        root.addView(actionButton("アップデートを確認") { checkUpdate() }, fullWidth(top = 10))
        root.addView(actionButton("ログを書き出す") { exportLog() }, fullWidth())

        root.addView(TextView(this).apply {
            text = "操作\n✋ 上/下スワイプ → 次/前\n🤏 ピンチ → 再生/一時停止\n👍 0.5秒 → いいね\n✊ 0.9秒 → ロック/解除"
            textSize = 14f
            setTextColor(Color.rgb(190, 220, 196))
            setPadding(dp(6), dp(20), dp(6), 0)
        }, fullWidth())

        scroll.addView(root)
        return scroll
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.rgb(225, 255, 232))
        setBackgroundColor(Color.rgb(22, 54, 30))
        setOnClickListener { action() }
    }

    private fun showRestrictedSettingsHelp() {
        AlertDialog.Builder(this)
            .setTitle("『アクセスを拒否されました』の解除")
            .setMessage(
                "GitHubなどPlayストア以外から入れたAPKは、Android 13以降でユーザー補助が『制限付き設定』としてブロックされることがあります。\n\n" +
                    "1. 下の『アプリ情報を開く』を押す\n" +
                    "2. AIR CONTROLのアプリ情報で右上の︙を押す\n" +
                    "3. 『制限付き設定を許可』を選ぶ\n" +
                    "4. PIN/指紋などで承認\n" +
                    "5. AIR CONTROLへ戻り『ユーザー補助設定』をもう一度開く\n\n" +
                    "これはOS側の保護機能なので、アプリ自身から自動解除することはできません。"
            )
            .setPositiveButton("アプリ情報を開く") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun startAirControl() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            Toast.makeText(this, "先にカメラ権限を許可してください", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, AirControlService::class.java)
        ContextCompat.startForegroundService(this, intent)
        AppLogger.i("AirControlService start requested")
        Toast.makeText(this, "AIR CONTROLを開始しました", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    private fun refreshStatus() {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val accessibility = AirAccessibilityService.isConnected
        val service = AirControlService.isRunning
        statusText.text = buildString {
            append("CAMERA       : ${if (camera) "READY" else "NEEDS PERMISSION"}\n")
            append("ACCESSIBILITY: ${if (accessibility) "READY" else "OFF"}\n")
            append("TRACKING     : ${if (service) "RUNNING" else "STOPPED"}\n")
            append("GESTURES     : ${if (AirControlService.controlsLocked) "LOCKED" else "ACTIVE"}\n")
            append("VERSION      : ${BuildConfig.VERSION_NAME}")
            if (!accessibility && Build.VERSION.SDK_INT >= 33) {
                append("\n\n※『アクセスを拒否されました』と出る場合は、下の\n『アクセス拒否された場合 / 制限付き設定を解除』を使ってください。")
            }
        }
    }

    private fun openTikTok() {
        val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val launch = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            startActivity(launch)
            AppLogger.i("TikTok launched")
        } else {
            Toast.makeText(this, "TikTokアプリが見つかりません", Toast.LENGTH_SHORT).show()
            AppLogger.w("TikTok package not found")
        }
    }

    private fun checkUpdate() {
        statusText.text = "GitHub Releasesを確認中…"
        UpdateManager.checkAndInstall(this) { message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
    }

    private fun exportLog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "aircontrol-${System.currentTimeMillis()}.log.txt")
        }
        startActivityForResult(intent, REQ_EXPORT_LOG)
    }

    @Deprecated("Deprecated in Android API, retained for minSdk-compatible SAF result handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT_LOG && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            Thread {
                val result = AppLogger.exportToUri(this, uri)
                runOnUiThread {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            AppLogger.i("Camera permission result=${grantResults.firstOrNull()}")
            refreshStatus()
        }
    }

    private fun fullWidth(top: Int = 6, bottom: Int = 6): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAMERA = 10
        private const val REQ_NOTIFICATION = 11
        private const val REQ_EXPORT_LOG = 12
    }
}
