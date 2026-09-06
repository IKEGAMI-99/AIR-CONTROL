package com.ikegami99.aircontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AirAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.init(applicationContext)
        AppLogger.i("AccessibilityService connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AppLogger.i("AccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun swipeUp() = swipe(0.76f, 0.26f, "SWIPE_UP")
    fun swipeDown() = swipe(0.26f, 0.76f, "SWIPE_DOWN")

    private fun swipe(fromY: Float, toY: Float, name: String) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * fromY)
            lineTo(x, dm.heightPixels * toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()
        dispatchGesture(gesture, gestureCallback(name), null)
    }

    fun tapCenter() {
        val dm = resources.displayMetrics
        tap(dm.widthPixels * 0.5f, dm.heightPixels * 0.46f, "CENTER_TAP")
    }

    fun doubleTapCenter() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val y = dm.heightPixels * 0.46f
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 45))
            .addStroke(GestureDescription.StrokeDescription(p2, 135, 45))
            .build()
        dispatchGesture(gesture, gestureCallback("CENTER_DOUBLE_TAP"), null)
    }

    fun tap(x: Float, y: Float, name: String = "AIR_TAP") {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
            .build()
        dispatchGesture(gesture, gestureCallback(name), null)
    }

    private fun gestureCallback(name: String) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            AppLogger.i("Accessibility gesture completed: $name")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            AppLogger.w("Accessibility gesture cancelled: $name")
        }
    }

    companion object {
        @Volatile private var instance: AirAccessibilityService? = null
        val isConnected: Boolean get() = instance != null

        fun withService(block: (AirAccessibilityService) -> Unit): Boolean {
            val service = instance ?: run {
                AppLogger.w("Gesture ignored: AccessibilityService is not connected")
                return false
            }
            block(service)
            return true
        }
    }
}
