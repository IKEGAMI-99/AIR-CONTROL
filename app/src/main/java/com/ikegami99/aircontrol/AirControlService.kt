package com.ikegami99.aircontrol

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AirControlService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var analyzerExecutor: ExecutorService
    private var recognizer: GestureRecognizerHelper? = null
    private lateinit var gestureEngine: GestureEngine
    private var lastSubmittedFrame = 0L

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        isRunning = true
        controlsLocked = false
        analyzerExecutor = Executors.newSingleThreadExecutor()
        createNotificationChannel()
        promoteToForeground()

        gestureEngine = GestureEngine(
            commandSink = ::handleCommand,
            lockedProvider = { controlsLocked }
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AppLogger.e("Camera permission missing when service started")
            stopSelf()
            return
        }

        try {
            recognizer = GestureRecognizerHelper(
                context = this,
                listener = gestureEngine::process,
                errorListener = { AppLogger.w("Recognizer callback: $it") }
            )
            startCamera()
        } catch (t: Throwable) {
            AppLogger.e("Failed to initialize hand tracking", t)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        AppLogger.i("AirControlService onStartCommand")
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        )
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                AppLogger.i("Front camera bound for gesture analysis")
            } catch (t: Throwable) {
                AppLogger.e("CameraX bind failed", t)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCommand(command: GestureEngine.Command) {
        when (command) {
            GestureEngine.Command.TOGGLE_LOCK -> {
                controlsLocked = !controlsLocked
                AppLogger.i("Controls locked=$controlsLocked")
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
            GestureEngine.Command.SWIPE_UP -> if (!controlsLocked) {
                AirAccessibilityService.withService { it.swipeUp() }
            }
            GestureEngine.Command.SWIPE_DOWN -> if (!controlsLocked) {
                AirAccessibilityService.withService { it.swipeDown() }
            }
            GestureEngine.Command.CENTER_TAP -> if (!controlsLocked) {
                AirAccessibilityService.withService { it.tapCenter() }
            }
            GestureEngine.Command.LIKE -> if (!controlsLocked) {
                AirAccessibilityService.withService { it.doubleTapCenter() }
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("AIR CONTROL")
        .setContentText(if (controlsLocked) "Hand tracking running • LOCKED" else "Hand tracking running • ACTIVE")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AIR CONTROL hand tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "前面カメラでハンドトラッキング中に表示されます"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        runCatching { cameraProvider?.unbindAll() }
        runCatching { recognizer?.close() }
        runCatching { analyzerExecutor.shutdownNow() }
        isRunning = false
        controlsLocked = false
        AppLogger.i("AirControlService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "air_control_tracking"
        private const val NOTIFICATION_ID = 7419
        private const val FRAME_INTERVAL_MS = 66L

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var controlsLocked: Boolean = false
            private set
    }
}
