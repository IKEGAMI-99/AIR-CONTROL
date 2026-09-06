package com.ikegami99.aircontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var hands: List<List<PointF>> = emptyList()

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 255, 138)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 236, 96)
        style = Paint.Style.FILL
    }

    private val wristPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 96, 96)
        style = Paint.Style.FILL
    }

    fun update(result: GestureRecognizerResult) {
        hands = result.landmarks().map { hand ->
            hand.map { landmark -> PointF(landmark.x(), landmark.y()) }
        }
        invalidate()
    }

    fun clear() {
        hands = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        hands.forEach { hand ->
            if (hand.size < 21) return@forEach

            CONNECTIONS.forEach { (from, to) ->
                val a = hand[from]
                val b = hand[to]
                canvas.drawLine(
                    a.x * width,
                    a.y * height,
                    b.x * width,
                    b.y * height,
                    bonePaint
                )
            }

            hand.forEachIndexed { index, point ->
                canvas.drawCircle(
                    point.x * width,
                    point.y * height,
                    if (index == 0) dp(7f) else dp(5f),
                    if (index == 0) wristPaint else jointPaint
                )
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private val CONNECTIONS = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
    }
}
