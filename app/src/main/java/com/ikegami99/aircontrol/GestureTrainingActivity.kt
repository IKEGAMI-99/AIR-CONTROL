package com.ikegami99.aircontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GestureTrainingActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: HandOverlayView
    private lateinit var statusText: TextView
    private lateinit var store: GestureTemplateStore
    private lateinit var analyzerExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val countTexts = mutableMapOf<GestureEngine.Command, TextView>()

    private var cameraProvider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizerHelper? = null
    private var lastSubmittedFrame = 0L

    @Volatile private var recording = false
    private var pendingCommand: GestureEngine.Command? = null
    private val recordedFrames = mutableListOf<GestureTemplateStore.Frame>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        store = GestureTemplateStore(this)

        if (AirControlService.isRunning) {
            stopService(Intent(this, AirControlService::class.java))
            Toast.makeText(this, "登録中はAIR CONTROLを一時停止します", Toast.LENGTH_SHORT).show()
        }

        analyzerExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildUi())
        refreshCounts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(5, 12, 8)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(18), dp(14), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "MY GESTURES"
            textSize = 25f
            setTextColor(Color.rgb(96, 255, 138))
            gravity = Gravity.CENTER
        }, fullWidth(bottom = 4))

        root.addView(TextView(this).apply {
            text = "自分の動きを3回くらい登録すると、速度や軌道が少し違っても似た動きとして認識します。登録中は1秒ほど自然に動かしてください。"
            textSize = 13f
            setTextColor(Color.rgb(185, 216, 192))
            gravity = Gravity.CENTER
        }, fullWidth(bottom = 10))

        val cameraFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlayView = HandOverlayView(this)
        cameraFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(overlayView, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            cameraFrame,
            LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 4f / 3f).toInt()).apply {
                setMargins(0, 0, 0, dp(8))
            }
        )

        statusText = TextView(this).apply {
            text = "カメラ準備中…"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(12, 32, 18))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(statusText, fullWidth(bottom = 10))

        addGestureRow(root, "↑ 次の動画", GestureEngine.Command.SWIPE_UP)
        addGestureRow(root, "↓ 前の動画", GestureEngine.Command.SWIPE_DOWN)
        addGestureRow(root, "⏯ 再生 / 一時停止", GestureEngine.Command.CENTER_TAP)
        addGestureRow(root, "♡ いいね", GestureEngine.Command.LIKE)
        addGestureRow(root, "🔒 ロック / 解除", GestureEngine.Command.TOGGLE_LOCK)

        root.addView(Button(this).apply {
            text = "すべての登録を消す"
            isAllCaps = false
            setTextColor(Color.rgb(255, 210, 210))
            setOnClickListener {
                AlertDialog.Builder(this@GestureTrainingActivity)
                    .setTitle("登録したジェスチャーを全部消しますか？")
                    .setMessage("消した操作は従来の標準ジェスチャー判定に戻ります。")
                    .setPositiveButton("全部消す") { _, _ ->
                        store.clearAll()
                        refreshCounts()
                        statusText.text = "すべてのカスタムジェスチャーを削除しました"
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }, fullWidth(top = 12))

        root.addView(Button(this).apply {
            text = "閉じる"
            isAllCaps = false
            setOnClickListener { finish() }
        }, fullWidth())

        scroll.addView(root)
        return scroll
    }

    private fun addGestureRow(root: LinearLayout, label: String, command: GestureEngine.Command) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(10, 27, 15))
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
        }, fullWidth(bottom = 2))

        val count = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(150, 200, 160))
        }
        countTexts[command] = count
        box.addView(count, fullWidth(bottom = 4))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(Button(this).apply {
            text = "この動きを登録"
            isAllCaps = false
            setOnClickListener { beginRecording(command, label) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "リセット"
            isAllCaps = false
            setOnClickListener {
                store.clear(command)
                refreshCounts()
                statusText.text = "$label の登録を削除しました。標準判定を使います。"
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f))
        box.addView(buttons, fullWidth())
        root.addView(box, fullWidth(top = 6, bottom = 6))
    }

    private fun beginRecording(command: GestureEngine.Command, label: String) {
        if (recording || pendingCommand != null) return
        pendingCommand = command
        recordedFrames.clear()
        statusText.setTextColor(Color.WHITE)
        statusText.text = "$label を登録します。手を構えてください… 3"

        handler.postDelayed({ if (pendingCommand != null) statusText.text = "$label を登録します… 2" }, 650L)
        handler.postDelayed({ if (pendingCommand != null) statusText.text = "$label を登録します… 1" }, 1_300L)
        handler.postDelayed({
            if (pendingCommand == null) return@postDelayed
            recordedFrames.clear()
            recording = true
            statusText.setTextColor(Color.rgb(255, 120, 120))
            statusText.text = "● REC  今の動きを自然に1回やってください"
        }, 1_950L)
        handler.postDelayed({ finishRecording(label) }, 3_050L)
    }

    private fun finishRecording(label: String) {
        val command = pendingCommand ?: return
        recording = false
        pendingCommand = null
        statusText.setTextColor(Color.WHITE)

        val frames = synchronized(recordedFrames) { recordedFrames.toList() }
        if (store.add(command, frames)) {
            val count = store.count(command)
            statusText.text = "$label を保存しました ($count/3 推奨)"
            refreshCounts()
        } else {
            statusText.text = "登録できませんでした。手全体を映したまま、もう一度試してください。"
        }
    }

    private fun refreshCounts() {
        countTexts.forEach { (command, view) ->
            val count = store.count(command)
            view.text = when {
                count == 0 -> "未登録 / 標準ジェスチャーを使用"
                count < 3 -> "登録 $count 件 / 3件以上がおすすめ"
                else -> "登録 $count 件 / パーソナル判定 ACTIVE"
            }
        }
    }

    private fun startCamera() {
        try {
            recognizer = GestureRecognizerHelper(
                context = this,
                listener = ::onGestureResult,
                errorListener = { message -> runOnUiThread { statusText.text = "ERROR: $message" } }
            )
        } catch (t: Throwable) {
            statusText.text = "MediaPipe初期化失敗: ${t.message}"
            AppLogger.e("Gesture training recognizer init failed", t)
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSubmittedFrame < FRAME_INTERVAL_MS) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastSubmittedFrame = now
                    recognizer?.recognize(imageProxy) ?: imageProxy.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                statusText.text = "READY / 登録したい操作を選んでください"
            } catch (t: Throwable) {
                statusText.text = "カメラ開始失敗: ${t.message}"
                AppLogger.e("Gesture training camera failed", t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onGestureResult(result: GestureRecognizerResult) {
        val now = SystemClock.uptimeMillis()
        if (recording) {
            val frame = GestureTemplateStore.extractFrame(result, now)
            if (frame != null) synchronized(recordedFrames) { recordedFrames.add(frame) }
        }
        runOnUiThread { overlayView.update(result) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { cameraProvider?.unbindAll() }
        runCatching { recognizer?.close() }
        runCatching { analyzerExecutor.shutdownNow() }
        super.onDestroy()
    }

    private fun fullWidth(top: Int = 4, bottom: Int = 4): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAMERA = 31
        private const val FRAME_INTERVAL_MS = 66L
    }
}
