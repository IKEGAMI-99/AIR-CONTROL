package com.ikegami99.aircontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class GestureRecognizerHelper(
    context: Context,
    private val listener: (GestureRecognizerResult) -> Unit,
    private val errorListener: (String) -> Unit
) : AutoCloseable {

    private val recognizer: GestureRecognizer

    init {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath("gesture_recognizer.task")
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onResult)
            .setErrorListener { error ->
                val message = error.message ?: "Unknown MediaPipe error"
                AppLogger.e("MediaPipe error: $message", error)
                errorListener(message)
            }
            .build()

        recognizer = GestureRecognizer.createFromOptions(context.applicationContext, options)
        AppLogger.i("MediaPipe GestureRecognizer initialized")
    }

    fun recognize(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        try {
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            bitmapBuffer.copyPixelsFromBuffer(buffer)
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                postScale(
                    -1f,
                    1f,
                    bitmapBuffer.width.toFloat(),
                    bitmapBuffer.height.toFloat()
                )
            }
            val rotated = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )
            val mpImage = BitmapImageBuilder(rotated).build()
            recognizer.recognizeAsync(mpImage, frameTime)
        } catch (t: Throwable) {
            runCatching { imageProxy.close() }
            AppLogger.e("Frame conversion/recognition failed", t)
            errorListener(t.message ?: "Frame processing failed")
        }
    }

    private fun onResult(result: GestureRecognizerResult, input: MPImage) {
        listener(result)
    }

    override fun close() {
        recognizer.close()
        AppLogger.i("MediaPipe GestureRecognizer closed")
    }
}
