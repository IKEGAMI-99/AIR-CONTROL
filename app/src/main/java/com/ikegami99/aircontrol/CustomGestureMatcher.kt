package com.ikegami99.aircontrol

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

class CustomGestureMatcher(context: Context) {

    data class Match(
        val command: GestureEngine.Command,
        val similarity: Float,
        val secondBest: Float
    )

    private data class PreparedFrame(
        val local: FloatArray,
        val moveX: Float,
        val moveY: Float
    )

    private val store = GestureTemplateStore(context)
    private val history = ArrayDeque<GestureTemplateStore.Frame>()
    private var lastEvalMs = 0L
    private var cooldownUntil = 0L

    fun hasTemplates(command: GestureEngine.Command): Boolean = store.has(command)

    fun process(result: GestureRecognizerResult): Match? {
        val now = SystemClock.uptimeMillis()
        val frame = GestureTemplateStore.extractFrame(result, now)
        if (frame == null) {
            trim(now)
            return null
        }

        history.addLast(frame)
        trim(now)

        val templates = store.all()
        if (templates.isEmpty()) return null
        if (now < cooldownUntil) return null
        if (now - lastEvalMs < EVAL_INTERVAL_MS) return null
        lastEvalMs = now

        val commandScores = mutableMapOf<GestureEngine.Command, MutableList<Float>>()
        templates.forEach { template ->
            val bestForTemplate = candidateDurations(template.durationMs)
                .mapNotNull { duration ->
                    val candidate = history.filter { now - it.timeMs <= duration }
                    if (candidate.size < MIN_CANDIDATE_FRAMES) null
                    else similarity(template.frames, candidate)
                }
                .maxOrNull() ?: return@forEach
            commandScores.getOrPut(template.command) { mutableListOf() }.add(bestForTemplate)
        }

        if (commandScores.isEmpty()) return null

        val ranked = commandScores.mapValues { (_, values) ->
            val sorted = values.sortedDescending()
            if (sorted.size >= 2) (sorted[0] * 0.68f + sorted[1] * 0.32f) else sorted[0]
        }.entries.sortedByDescending { it.value }

        val best = ranked.first()
        val second = ranked.getOrNull(1)?.value ?: 0f
        val count = commandScores[best.key]?.size ?: 1
        val threshold = when {
            count >= 3 -> 0.55f
            count == 2 -> 0.58f
            else -> 0.62f
        }

        if (best.value < threshold) return null
        if (second > 0f && best.value - second < MIN_WIN_MARGIN) return null

        cooldownUntil = now + MATCH_COOLDOWN_MS
        AppLogger.i(
            "Custom gesture matched command=${best.key} score=${best.value} second=$second templates=$count"
        )
        return Match(best.key, best.value, second)
    }

    private fun trim(now: Long) {
        while (history.isNotEmpty() && now - history.first().timeMs > HISTORY_MS) {
            history.removeFirst()
        }
    }

    private fun candidateDurations(templateDurationMs: Long): LongArray {
        return longArrayOf(
            (templateDurationMs * 0.68f).toLong(),
            (templateDurationMs * 0.86f).toLong(),
            templateDurationMs,
            (templateDurationMs * 1.18f).toLong(),
            (templateDurationMs * 1.38f).toLong()
        ).map { it.coerceIn(300L, HISTORY_MS) }.distinct().toLongArray()
    }

    private fun similarity(
        template: List<GestureTemplateStore.Frame>,
        candidate: List<GestureTemplateStore.Frame>
    ): Float {
        if (template.size < 2 || candidate.size < 2) return 0f

        val a = prepare(template)
        val b = prepare(candidate)

        val templateMotion = overallMotion(a)
        val motionWeight = if (templateMotion >= MOTION_GESTURE_THRESHOLD) 0.70f else 0.26f
        val shapeWeight = 1f - motionWeight

        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dp[0][0] = 0f

        for (i in 1..n) {
            for (j in 1..m) {
                val d = frameDistance(a[i - 1], b[j - 1], shapeWeight, motionWeight)
                dp[i][j] = d + min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
            }
        }

        var distance = dp[n][m] / (n + m).toFloat().coerceAtLeast(1f)

        if (templateMotion >= MOTION_GESTURE_THRESHOLD) {
            val endA = a.last()
            val endB = b.last()
            val endpoint = hypot(
                (endA.moveX - endB.moveX).toDouble(),
                (endA.moveY - endB.moveY).toDouble()
            ).toFloat()
            distance += endpoint * ENDPOINT_WEIGHT
        }

        return exp((-DISTANCE_TO_SCORE * distance).toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun prepare(frames: List<GestureTemplateStore.Frame>): List<PreparedFrame> {
        val first = frames.first()
        val baseScale = frames.map { it.palmScale }.average().toFloat().coerceAtLeast(0.03f)
        return frames.map { frame ->
            PreparedFrame(
                local = frame.local,
                moveX = (frame.palmX - first.palmX) / baseScale,
                moveY = (frame.palmY - first.palmY) / baseScale
            )
        }
    }

    private fun overallMotion(frames: List<PreparedFrame>): Float {
        if (frames.isEmpty()) return 0f
        val end = frames.last()
        return hypot(end.moveX.toDouble(), end.moveY.toDouble()).toFloat()
    }

    private fun frameDistance(
        a: PreparedFrame,
        b: PreparedFrame,
        shapeWeight: Float,
        motionWeight: Float
    ): Float {
        var shape = 0f
        var points = 0
        SELECTED_LANDMARKS.forEach { index ->
            val k = index * 2
            val dx = a.local[k] - b.local[k]
            val dy = a.local[k + 1] - b.local[k + 1]
            shape += hypot(dx.toDouble(), dy.toDouble()).toFloat()
            points++
        }
        shape /= points.coerceAtLeast(1)

        val motion = hypot(
            (a.moveX - b.moveX).toDouble(),
            (a.moveY - b.moveY).toDouble()
        ).toFloat()

        return shape * shapeWeight + motion * motionWeight
    }

    companion object {
        private const val HISTORY_MS = 1_700L
        private const val EVAL_INTERVAL_MS = 82L
        private const val MATCH_COOLDOWN_MS = 720L
        private const val MIN_CANDIDATE_FRAMES = 6
        private const val MIN_WIN_MARGIN = 0.045f
        private const val MOTION_GESTURE_THRESHOLD = 0.34f
        private const val ENDPOINT_WEIGHT = 0.30f
        private const val DISTANCE_TO_SCORE = 1.42f

        private val SELECTED_LANDMARKS = intArrayOf(
            0, 2, 4,
            5, 6, 8,
            9, 10, 12,
            13, 14, 16,
            17, 18, 20
        )
    }
}
