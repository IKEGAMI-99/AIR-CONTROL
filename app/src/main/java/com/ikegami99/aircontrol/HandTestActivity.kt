package com.ikegami99.aircontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HandTestActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: HandOverlayView
    private lateinit var statusText: TextView
    private lateinit var analyzerExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var recognizer: GestureRecognizerHelper? = null
    private var lastSubmittedFrame = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        AppLogger.i("Hand test screen opened")

        if (AirControlService.isRunning) {
            stopService(Intent(this, AirControlService::class.java))
            AppLogger.i("AirControlService stopped temporarily for hand test")
            Toast.makeText(this, "動作テストのためAIR CONTROLを一時停止しました", Toast.LENGTH_SHORT).show()
        }

        analyzerExecutor = Executors.newSingleThreadExecutor()
        setContentView(buildUi())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTrackingTest()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(5, 12, 8))
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }

        root.addView(TextView(this).apply {
            text = "HAND TRACKING TEST"
            textSize = 23f
            setTextColor(Color.rgb(96, 255, 138))
            gravity = Gravity.CENTER
        }, fullWidth(bottom = 4))

        root.addView(TextView(this).apply {
            text = "手の上に21点のランドマークと骨格線が出ればMediaPipeは正常です"
            textSize = 12f
            setTextColor(Color.rgb(180, 215, 188))
            gravity = Gravity.CENTER
        }, fullWidth(bottom = 10))

        val cameraFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlayView = HandOverlayView(this)
        cameraFrame.addView(
            previewView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        cameraFrame.addView(
            overlayView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val cameraHeight = (resources.displayMetrics.widthPixels * 4f / 3f).toInt()
        root.addView(
            cameraFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cameraHeight).apply {
                setMargins(0, 0, 0, dp(10))
            }
        )

        statusText = TextView(this).apply {
            text = "MediaPipeを初期化中…"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(12, 32, 18))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(statusText, fullWidth(bottom = 8))

        root.addView(Button(this).apply {
            text = "閉じる"
            isAllCaps = false
            setOnClickListener { finish() }
        }, fullWidth())

        return root
    }

    private fun startTrackingTest() {
        try {
            recognizer = GestureRecognizerHelper(
                context = this,
                listener = ::onGestureResult,
                errorListener = { message ->
                    runOnUiThread {
                        statusText.text = "ERROR: $message"
                        overlayView.clear()
                    }
                }
            )
        } catch (t: Throwable) {
            AppLogger.e("Hand test recognizer init failed", t)
            statusText.text = "MediaPipe初期化失敗: ${t.message}"
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

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
                    val helper = recognizer
                    if (helper == null) {
                        imageProxy.close()
                    } else {
                        helper.recognize(imageProxy)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                statusText.text = "CAMERA: READY  /  手をカメラに向けてください"
                AppLogger.i("Hand test front camera bound")
            } catch (t: Throwable) {
                AppLogger.e("Hand test CameraX bind failed", t)
                statusText.text = "カメラ開始失敗: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onGestureResult(result: GestureRecognizerResult) {
        val hands = result.landmarks().size
        val category = result.gestures().firstOrNull()?.firstOrNull()
        val gesture = category?.categoryName() ?: "None"
        val confidence = category?.score() ?: 0f

        runOnUiThread {
            overlayView.update(result)
            statusText.text = if (hands > 0) {
                "HAND: DETECTED ($hands)  /  GESTURE: $gesture  /  CONF: ${String.format(Locale.US, "%.2f", confidence)}"
            } else {
                "HAND: NOT FOUND  /  手全体が入るように映してください"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startTrackingTest()
            } else {
                statusText.text = "カメラ権限が必要です"
            }
        }
    }

    override fun onDestroy() {
        runCatching { cameraProvider?.unbindAll() }
        runCatching { recognizer?.close() }
        runCatching { analyzerExecutor.shutdownNow() }
        AppLogger.i("Hand test screen closed")
        super.onDestroy()
    }

    private fun fullWidth(top: Int = 4, bottom: Int = 4): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAMERA = 20
        private const val FRAME_INTERVAL_MS = 66L
    }
}
