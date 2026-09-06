package com.ikegami99.aircontrol

import android.os.SystemClock
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot

class GestureEngine(
    private val commandSink: (Command) -> Unit,
    private val lockedProvider: () -> Boolean
) {
    enum class Command { SWIPE_UP, SWIPE_DOWN, CENTER_TAP, LIKE, TOGGLE_LOCK }

    private data class PalmSample(
        val x: Float,
        val y: Float,
        val scale: Float,
        val timeMs: Long
    )

    private val palmHistory = ArrayDeque<PalmSample>()
    private var palmGraceUntil = 0L
    private var smoothedPalmX = Float.NaN
    private var smoothedPalmY = Float.NaN
    private var smoothedPalmScale = Float.NaN
    private var gestureCooldownUntil = 0L

    private var thumbUpSince = 0L
    private var thumbUpFired = false
    private var fistSince = 0L
    private var fistFired = false
    private var pinchDown = false

    fun process(result: GestureRecognizerResult) {
        val now = SystemClock.uptimeMillis()
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null || landmarks.size < 21) {
            onNoHand()
            return
        }

        val category = result.gestures().firstOrNull()?.firstOrNull()
        val gesture = category?.categoryName() ?: "None"
        val confidence = category?.score() ?: 0f

        val wrist = landmarks[0]
        val thumbTip = landmarks[4]
        val indexMcp = landmarks[5]
        val indexTip = landmarks[8]
        val middleMcp = landmarks[9]
        val ringMcp = landmarks[13]
        val pinkyMcp = landmarks[17]

        val rawPalmScale = hypot(
            (middleMcp.x() - wrist.x()).toDouble(),
            (middleMcp.y() - wrist.y()).toDouble()
        ).toFloat().coerceAtLeast(0.035f)

        val palmCenterX = (
            wrist.x() + indexMcp.x() + middleMcp.x() + ringMcp.x() + pinkyMcp.x()
        ) / 5f
        val palmCenterY = (
            wrist.y() + indexMcp.y() + middleMcp.y() + ringMcp.y() + pinkyMcp.y()
        ) / 5f

        updateSmoothedPalm(palmCenterX, palmCenterY, rawPalmScale)

        val pinchRatio = hypot(
            (thumbTip.x() - indexTip.x()).toDouble(),
            (thumbTip.y() - indexTip.y()).toDouble()
        ).toFloat() / smoothedPalmScale.coerceAtLeast(0.035f)
        val isPinching = pinchRatio < 0.48f

        handleFist(now, gesture, confidence)

        if (!lockedProvider()) {
            handlePinch(isPinching)
            handleThumbUp(now, gesture, confidence)
            handlePalmSwipe(now, gesture, confidence)
        } else {
            pinchDown = isPinching
            resetPalmTracking()
            resetThumbUp()
        }
    }

    /**
     * Vertical swipe detection deliberately does not require Open_Palm on every frame.
     * MediaPipe's gesture category can flicker while the whole hand is moving quickly,
     * even though the 21 landmarks remain stable. Seeing Open_Palm arms a short grace
     * window and motion is then measured from the palm centre rather than the wrist.
     *
     * Distance is normalized by palm size, so the same physical gesture works whether
     * the hand is close to or farther from the front camera.
     */
    private fun handlePalmSwipe(now: Long, gesture: String, confidence: Float) {
        val isOpenPalm = gesture == "Open_Palm" && confidence >= OPEN_PALM_CONFIDENCE
        if (isOpenPalm) {
            palmGraceUntil = now + OPEN_PALM_GRACE_MS
        }

        if (now > palmGraceUntil) {
            resetPalmTracking()
            return
        }
        if (now < gestureCooldownUntil) {
            palmHistory.clear()
            return
        }
        if (smoothedPalmX.isNaN() || smoothedPalmY.isNaN() || smoothedPalmScale.isNaN()) return

        palmHistory.addLast(
            PalmSample(smoothedPalmX, smoothedPalmY, smoothedPalmScale, now)
        )
        while (palmHistory.isNotEmpty() && now - palmHistory.first().timeMs > SWIPE_WINDOW_MS) {
            palmHistory.removeFirst()
        }

        if (palmHistory.size < 3) return

        // Prefer a point far enough in the past to reject one-frame landmark jitter.
        val start = palmHistory.firstOrNull { now - it.timeMs >= MIN_SWIPE_TIME_MS } ?: return
        val dtMs = now - start.timeMs
        if (dtMs <= 0L) return

        val avgScale = ((start.scale + smoothedPalmScale) * 0.5f).coerceAtLeast(0.035f)
        val dxRaw = smoothedPalmX - start.x
        val dyRaw = smoothedPalmY - start.y
        val dxPalm = dxRaw / avgScale
        val dyPalm = dyRaw / avgScale
        val verticalSpeedPalmPerSec = abs(dyPalm) / (dtMs / 1000f)

        val enoughDistance = abs(dyPalm) >= MIN_VERTICAL_PALM_DISTANCE && abs(dyRaw) >= MIN_VERTICAL_RAW_DISTANCE
        val mostlyVertical = abs(dyPalm) > abs(dxPalm) * VERTICAL_DOMINANCE
        val enoughSpeed = verticalSpeedPalmPerSec >= MIN_VERTICAL_SPEED_PALMS_PER_SEC

        if (enoughDistance && mostlyVertical && enoughSpeed) {
            if (dyPalm < 0f) {
                emit(
                    Command.SWIPE_UP,
                    "palm_swipe dyPalm=$dyPalm dyRaw=$dyRaw speed=$verticalSpeedPalmPerSec conf=$confidence"
                )
            } else {
                emit(
                    Command.SWIPE_DOWN,
                    "palm_swipe dyPalm=$dyPalm dyRaw=$dyRaw speed=$verticalSpeedPalmPerSec conf=$confidence"
                )
            }
            gestureCooldownUntil = now + SWIPE_COOLDOWN_MS
            resetPalmTracking(keepSmoothing = true)
        }
    }

    private fun updateSmoothedPalm(x: Float, y: Float, scale: Float) {
        if (smoothedPalmX.isNaN()) {
            smoothedPalmX = x
            smoothedPalmY = y
            smoothedPalmScale = scale
            return
        }
        smoothedPalmX += (x - smoothedPalmX) * PALM_SMOOTHING_ALPHA
        smoothedPalmY += (y - smoothedPalmY) * PALM_SMOOTHING_ALPHA
        smoothedPalmScale += (scale - smoothedPalmScale) * PALM_SMOOTHING_ALPHA
    }

    private fun handlePinch(isPinching: Boolean) {
        if (isPinching && !pinchDown) {
            emit(Command.CENTER_TAP, "pinch edge")
        }
        pinchDown = isPinching
    }

    private fun handleThumbUp(now: Long, gesture: String, confidence: Float) {
        if (gesture == "Thumb_Up" && confidence >= 0.55f) {
            if (thumbUpSince == 0L) thumbUpSince = now
            if (!thumbUpFired && now - thumbUpSince >= 500L && now >= gestureCooldownUntil) {
                thumbUpFired = true
                gestureCooldownUntil = now + 700L
                emit(Command.LIKE, "thumb_up hold=${now - thumbUpSince}ms")
            }
        } else {
            resetThumbUp()
        }
    }

    private fun handleFist(now: Long, gesture: String, confidence: Float) {
        if (gesture == "Closed_Fist" && confidence >= 0.55f) {
            if (fistSince == 0L) fistSince = now
            if (!fistFired && now - fistSince >= 900L && now >= gestureCooldownUntil) {
                fistFired = true
                gestureCooldownUntil = now + 1_000L
                emit(Command.TOGGLE_LOCK, "closed_fist hold=${now - fistSince}ms")
            }
        } else {
            fistSince = 0L
            fistFired = false
        }
    }

    private fun emit(command: Command, reason: String) {
        AppLogger.i("Gesture command=$command reason=$reason")
        commandSink(command)
    }

    private fun onNoHand() {
        resetPalmTracking()
        resetThumbUp()
        fistSince = 0L
        fistFired = false
        pinchDown = false
    }

    private fun resetPalmTracking(keepSmoothing: Boolean = false) {
        palmHistory.clear()
        palmGraceUntil = 0L
        if (!keepSmoothing) {
            smoothedPalmX = Float.NaN
            smoothedPalmY = Float.NaN
            smoothedPalmScale = Float.NaN
        }
    }

    private fun resetThumbUp() {
        thumbUpSince = 0L
        thumbUpFired = false
    }

    companion object {
        // Gesture label may flicker during motion, so Open_Palm only needs to arm tracking.
        private const val OPEN_PALM_CONFIDENCE = 0.42f
        private const val OPEN_PALM_GRACE_MS = 320L

        // Roughly half a palm-height of vertical motion is enough to count as a swipe.
        private const val MIN_VERTICAL_PALM_DISTANCE = 0.52f
        private const val MIN_VERTICAL_RAW_DISTANCE = 0.055f
        private const val MIN_VERTICAL_SPEED_PALMS_PER_SEC = 1.05f
        private const val VERTICAL_DOMINANCE = 1.10f
        private const val MIN_SWIPE_TIME_MS = 90L
        private const val SWIPE_WINDOW_MS = 520L
        private const val SWIPE_COOLDOWN_MS = 560L

        private const val PALM_SMOOTHING_ALPHA = 0.42f
    }
}
