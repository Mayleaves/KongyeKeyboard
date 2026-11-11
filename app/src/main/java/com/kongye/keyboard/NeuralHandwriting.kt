package com.kongye.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import org.json.JSONObject
import kotlin.math.roundToInt

/** ochwpro's published stroke-sequence model. All inference and lifetime changes
 * are serialized on one worker; handwriting never runs in the IME draw loop.
 * No strokes leave the device. The model's own normalization preserves bent and
 * slanted trajectories rather than comparing them to regular-font outlines.
 */
object OpenHandwriting {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Kongye neural ink") }
    @Volatile var ready = false; private set
    @Volatile var failure: String? = null; private set
    private var loading = false
    private var generation = 0
    private var request = 0
    private var session: OrtSession? = null
    private var fallback = false
    private var chars: List<String> = emptyList()
    private val waiters = ArrayList<() -> Unit>()

    fun prepare(ctx: Context, callback: () -> Unit = {}) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { prepare(ctx, callback) }; return }
        if (ready) { callback(); return }
        waiters.add(callback)
        if (loading) return
        loading = true
        val token = generation
        worker.execute {
            val result = runCatching {
                HandwritingPinyin.load(ctx)
                val json = JSONObject(ctx.assets.open("handwriting-neural/char_index.json").bufferedReader().use { it.readText() }).getJSONArray("chars")
                val labels = (0 until json.length()).map { json.getString(it) }
                val options = OrtSession.SessionOptions()
                try {
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    val model = ctx.assets.open("handwriting-neural/ochwpro.onnx").use { it.readBytes() }
                    val engine = OrtEnvironment.getEnvironment().createSession(model, options)
                    // Warm the execution graph outside the first recognition callback.
                    try { predict(engine, labels, listOf(listOf(floatArrayOf(0f, 0f), floatArrayOf(50f, 50f)))) }
                    catch (e: Exception) { engine.close(); throw e }
                    engine to labels
                } finally { options.close() }
            }
            main.post {
                if (token != generation) {
                    result.getOrNull()?.first?.let { old -> worker.execute { old.close() } }
                    return@post
                }
                loading = false
                result.onSuccess { (engine, labels) -> session = engine; chars = labels; failure = null; ready = true }
                    .onFailure { failure = it.javaClass.simpleName + ": " + it.message; ready = false }
                if (!ready) {
                    TemplateHandwriting.prepare(ctx) {
                        if (token == generation) {
                            fallback = TemplateHandwriting.ready; ready = fallback
                            notifyReady()
                        }
                    }
                } else notifyReady()
            }
        }
    }

    private fun notifyReady() {
        val callbacks = waiters.toList(); waiters.clear(); callbacks.forEach { it() }
    }

    fun recognize(strokes: List<List<FloatArray>>, callback: (List<String>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (fallback) return TemplateHandwriting.recognize(strokes, callback)
        val engine = session ?: return false
        if (!ready) return false
        val input = strokes.map { line -> line.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }.map { it.copyOf(2) } }.filter { it.isNotEmpty() }
        val token = generation; val id = ++request; val labels = chars
        worker.execute {
            val result = runCatching { predict(engine, labels, input) }
            main.post {
                if (token == generation && id == request) {
                    result.onFailure { failure = it.javaClass.simpleName + ": " + it.message }
                    callback(result.getOrDefault(emptyList()))
                }
            }
        }
        return true
    }

    /** Same 8-point sampling and five normalized features as upstream demo.py.
     * Keep more points for long joined strokes; cap the whole input by sampling
     * each stroke proportionally, so the tail of a character is never truncated.
     */
    private fun predict(engine: OrtSession, labels: List<String>, strokes: List<List<FloatArray>>): List<String> {
        if (strokes.isEmpty()) return emptyList()
        val clean = strokes.take(100)
        val budget = clean.map { minOf(it.size, if (it.size > 40) 16 else 8) }.toMutableList()
        while (budget.sum() > 200) {
            val at = budget.indices.maxBy { budget[it] }
            budget[at]--
        }
        val points = ArrayList<FloatArray>()
        val flags = ArrayList<Float>()
        clean.forEachIndexed { index, stroke ->
            val count = budget[index]
            for (j in 0 until count) {
                val at = if (count <= 1) 0 else (j * (stroke.size - 1f) / (count - 1)).roundToInt()
                points.add(stroke[at]); flags.add(if (j == count - 1) 0f else 1f)
            }
        }
        val left = points.minOf { it[0] }; val top = points.minOf { it[1] }
        val width = maxOf(points.maxOf { it[0] } - left, 1f)
        val height = maxOf(points.maxOf { it[1] } - top, 1f)
        val data = FloatArray(200 * 5)
        points.forEachIndexed { index, p ->
            val previous = points[maxOf(index - 1, 0)]
            val at = index * 5
            data[at] = (p[0] - left) / width; data[at + 1] = (p[1] - top) / height
            data[at + 2] = (p[0] - previous[0]) / width; data[at + 3] = (p[1] - previous[1]) / height
            data[at + 4] = flags[index]
        }
        val mask = ByteBuffer.allocateDirect(200)
        repeat(200) { mask.put(if (it < points.size) 1.toByte() else 0.toByte()) }; mask.flip()
        val env = OrtEnvironment.getEnvironment()
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 200, 5))
        val attention = OnnxTensor.createTensor(env, mask, longArrayOf(1, 200), OnnxJavaType.BOOL)
        try {
            val output = engine.run(mapOf("input" to input, "mask" to attention))
            try {
                val values = (output[0] as OnnxTensor).floatBuffer
                val scores = FloatArray(values.remaining()); values.get(scores)
                return scores.indices.asSequence().filter { index ->
                    index < labels.size && labels[index].codePointCount(0, labels[index].length) == 1 &&
                        Character.UnicodeScript.of(labels[index].codePointAt(0)) == Character.UnicodeScript.HAN
                }.sortedByDescending { scores[it] }.take(12).map { labels[it] }.toList()
            } finally { output.close() }
        } finally { attention.close(); input.close() }
    }

    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { release() }; return }
        generation++; request++; ready = false; loading = false; fallback = false; waiters.clear(); chars = emptyList()
        val old = session; session = null
        if (old != null) worker.execute { old.close() }
        TemplateHandwriting.release()
    }
}
