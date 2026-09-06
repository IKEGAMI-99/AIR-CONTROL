package com.ikegami99.aircontrol

import android.content.Context
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class GestureTemplateStore(context: Context) {

    data class Frame(
        val timeMs: Long,
        val palmX: Float,
        val palmY: Float,
        val palmScale: Float,
        val local: FloatArray
    )

    data class Template(
        val id: Long,
        val command: GestureEngine.Command,
        val durationMs: Long,
        val frames: List<Frame>
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Template> = decode(prefs.getString(KEY_TEMPLATES, null))

    fun forCommand(command: GestureEngine.Command): List<Template> = all().filter { it.command == command }

    fun count(command: GestureEngine.Command): Int = forCommand(command).size

    fun has(command: GestureEngine.Command): Boolean = count(command) > 0

    fun add(command: GestureEngine.Command, recorded: List<Frame>): Boolean {
        if (recorded.size < MIN_FRAMES) return false
        val start = recorded.first().timeMs
        val normalized = recorded.map {
            it.copy(timeMs = it.timeMs - start)
        }
        val duration = normalized.last().timeMs
        if (duration < MIN_DURATION_MS) return false

        val list = all().toMutableList()
        val same = list.filter { it.command == command }.sortedBy { it.id }
        if (same.size >= MAX_TEMPLATES_PER_COMMAND) {
            val removeIds = same.take(same.size - MAX_TEMPLATES_PER_COMMAND + 1).map { it.id }.toSet()
            list.removeAll { it.id in removeIds }
        }
        list.add(
            Template(
                id = System.currentTimeMillis(),
                command = command,
                durationMs = duration,
                frames = normalized
            )
        )
        save(list)
        AppLogger.i("Custom gesture template saved command=$command frames=${normalized.size} duration=${duration}ms")
        return true
    }

    fun clear(command: GestureEngine.Command) {
        val list = all().filterNot { it.command == command }
        save(list)
        AppLogger.i("Custom gesture templates cleared command=$command")
    }

    fun clearAll() {
        prefs.edit().remove(KEY_TEMPLATES).apply()
        AppLogger.i("All custom gesture templates cleared")
    }

    private fun save(list: List<Template>) {
        val root = JSONArray()
        list.forEach { template ->
            val obj = JSONObject()
                .put("id", template.id)
                .put("command", template.command.name)
                .put("duration", template.durationMs)
            val frames = JSONArray()
            template.frames.forEach { frame ->
                val local = JSONArray()
                frame.local.forEach { local.put(it.toDouble()) }
                frames.put(
                    JSONObject()
                        .put("t", frame.timeMs)
                        .put("px", frame.palmX.toDouble())
                        .put("py", frame.palmY.toDouble())
                        .put("s", frame.palmScale.toDouble())
                        .put("l", local)
                )
            }
            obj.put("frames", frames)
            root.put(obj)
        }
        prefs.edit().putString(KEY_TEMPLATES, root.toString()).apply()
    }

    private fun decode(raw: String?): List<Template> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONArray(raw)
            buildList {
                for (i in 0 until root.length()) {
                    val obj = root.getJSONObject(i)
                    val command = runCatching {
                        GestureEngine.Command.valueOf(obj.getString("command"))
                    }.getOrNull() ?: continue
                    val framesJson = obj.getJSONArray("frames")
                    val frames = buildList {
                        for (j in 0 until framesJson.length()) {
                            val f = framesJson.getJSONObject(j)
                            val l = f.getJSONArray("l")
                            val local = FloatArray(l.length()) { k -> l.getDouble(k).toFloat() }
                            if (local.size != LOCAL_FEATURE_SIZE) continue
                            add(
                                Frame(
                                    timeMs = f.getLong("t"),
                                    palmX = f.getDouble("px").toFloat(),
                                    palmY = f.getDouble("py").toFloat(),
                                    palmScale = f.getDouble("s").toFloat(),
                                    local = local
                                )
                            )
                        }
                    }
                    if (frames.size >= MIN_FRAMES) {
                        add(
                            Template(
                                id = obj.getLong("id"),
                                command = command,
                                durationMs = obj.optLong("duration", frames.last().timeMs),
                                frames = frames
                            )
                        )
                    }
                }
            }
        }.onFailure { AppLogger.e("Failed to decode custom gesture templates", it) }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "custom_gestures"
        private const val KEY_TEMPLATES = "templates_v1"
        private const val MAX_TEMPLATES_PER_COMMAND = 5
        private const val MIN_FRAMES = 6
        private const val MIN_DURATION_MS = 260L
        private const val LOCAL_FEATURE_SIZE = 42

        fun extractFrame(result: GestureRecognizerResult, timeMs: Long): Frame? {
            val landmarks = result.landmarks().firstOrNull() ?: return null
            if (landmarks.size < 21) return null
            return extractFrame(landmarks, timeMs)
        }

        private fun extractFrame(landmarks: List<NormalizedLandmark>, timeMs: Long): Frame {
            val wrist = landmarks[0]
            val middleMcp = landmarks[9]
            val scale = hypot(
                (middleMcp.x() - wrist.x()).toDouble(),
                (middleMcp.y() - wrist.y()).toDouble()
            ).toFloat().coerceAtLeast(0.03f)

            val palmIndices = intArrayOf(0, 5, 9, 13, 17)
            var palmX = 0f
            var palmY = 0f
            palmIndices.forEach {
                palmX += landmarks[it].x()
                palmY += landmarks[it].y()
            }
            palmX /= palmIndices.size
            palmY /= palmIndices.size

            // Canonicalize hand rotation. Whole-hand translation is intentionally kept
            // separately in palmX/palmY so swipe-like gestures still have direction.
            val axisAngle = atan2(
                (middleMcp.y() - wrist.y()).toDouble(),
                (middleMcp.x() - wrist.x()).toDouble()
            )
            val rotation = -axisAngle - Math.PI / 2.0
            val c = cos(rotation).toFloat()
            val s = sin(rotation).toFloat()

            val local = FloatArray(42)
            for (i in 0 until 21) {
                val x = (landmarks[i].x() - wrist.x()) / scale
                val y = (landmarks[i].y() - wrist.y()) / scale
                local[i * 2] = x * c - y * s
                local[i * 2 + 1] = x * s + y * c
            }

            return Frame(
                timeMs = timeMs,
                palmX = palmX,
                palmY = palmY,
                palmScale = scale,
                local = local
            )
        }
    }
}
