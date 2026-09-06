package com.ikegami99.aircontrol

import android.os.SystemClock
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlin.math.abs
import kotlin.math.hypot

class GestureEngine(
    private val commandSink: (Command) -> Unit,
    private val lockedProvider: () -> Boolean
) {
    enum class Command { SWIPE_UP, SWIPE_DOWN, CENTER_TAP, LIKE, TOGGLE_LOCK }

    private var palmStartX = 0f
    private var palmStartY = 0f
    private var palmStartMs = 0L
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
        val indexTip = landmarks[8]
        val middleMcp = landmarks[9]

        val palmScale = hypot(
            (middleMcp.x() - wrist.x()).toDouble(),
            (middleMcp.y() - wrist.y()).toDouble()
        ).toFloat().coerceAtLeast(0.04f)
        val pinchRatio = hypot(
            (thumbTip.x() - indexTip.x()).toDouble(),
            (thumbTip.y() - indexTip.y()).toDouble()
        ).toFloat() / palmScale
        val isPinching = pinchRatio < 0.48f

        handleFist(now, gesture, confidence)

        if (!lockedProvider()) {
            handlePinch(isPinching)
            handleThumbUp(now, gesture, confidence)
            handlePalmSwipe(now, wrist.x(), wrist.y(), gesture, confidence)
        } else {
            pinchDown = isPinching
            resetPalm()
            resetThumbUp()
        }
    }

    private fun handlePalmSwipe(now: Long, x: Float, y: Float, gesture: String, confidence: Float) {
        if (gesture != "Open_Palm" || confidence < 0.55f) {
            resetPalm()
            return
        }
        if (now < gestureCooldownUntil) return

        if (palmStartMs == 0L || now - palmStartMs > 550L) {
            palmStartMs = now
            palmStartX = x
            palmStartY = y
            return
        }

        val dt = (now - palmStartMs).coerceAtLeast(1L) / 1000f
        val dx = x - palmStartX
        val dy = y - palmStartY
        val verticalSpeed = abs(dy) / dt

        if (abs(dy) >= 0.16f && abs(dy) > abs(dx) * 1.35f && verticalSpeed >= 0.34f) {
            if (dy < 0f) {
                emit(Command.SWIPE_UP, "open_palm dy=$dy speed=$verticalSpeed")
            } else {
                emit(Command.SWIPE_DOWN, "open_palm dy=$dy speed=$verticalSpeed")
            }
            gestureCooldownUntil = now + 650L
            resetPalm()
        }
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
        resetPalm()
        resetThumbUp()
        fistSince = 0L
        fistFired = false
        pinchDown = false
    }

    private fun resetPalm() {
        palmStartMs = 0L
    }

    private fun resetThumbUp() {
        thumbUpSince = 0L
        thumbUpFired = false
    }
}
