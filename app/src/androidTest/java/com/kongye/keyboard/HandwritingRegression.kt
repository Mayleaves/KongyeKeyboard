package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object HandwritingRegression {
    private fun line(vararg xy: Float): List<FloatArray> = (xy.indices step 2).map { floatArrayOf(xy[it], xy[it+1], it.toFloat()) }
    fun run(i: Instrumentation) {
        val loaded = CountDownLatch(1)
        OpenHandwriting.prepare(i.targetContext) { loaded.countDown() }
        check(loaded.await(15, TimeUnit.SECONDS)) { "Handwriting initialization timeout" }
        check(OpenHandwriting.ready) { "Neural handwriting unavailable: ${OpenHandwriting.failure}" }
        HandwritingPinyin.load(i.targetContext)
        val poly = HandwritingPinyin.label("行")
        check("xíng" in poly && "háng" in poly && " / " in poly) { "Polyphonic tones: $poly" }
        check(HandwritingPinyin.label("大").contains("dà")) { "Tone mark missing" }
        check(HandwritingPinyin.label("银行") == "yín háng") { "Phrase pronunciation lost" }
        val inputs = arrayOf(
            listOf(line(55f,100f,210f,100f),line(140f,35f,130f,125f,85f,200f,35f,230f),line(130f,120f,180f,190f,230f,225f)),
            listOf(line(140f,35f,115f,130f,40f,225f),line(120f,100f,170f,180f,225f,230f)),
            listOf(line(75f,80f,75f,175f),line(75f,80f,200f,80f,200f,175f),line(75f,175f,200f,175f),line(140f,30f,140f,230f))
        )
        val wanted = arrayOf("大", "人", "中")
        for (index in inputs.indices) {
            val done = CountDownLatch(1)
            var candidates: List<String> = emptyList()
            val start = SystemClock.elapsedRealtime()
            i.runOnMainSync {
                check(OpenHandwriting.recognize(inputs[index]) { list -> candidates = list; done.countDown() })
            }
            check(done.await(5, TimeUnit.SECONDS)) { "HanziLookup result timeout" }
            i.sendStatus(0, Bundle().apply { putString("stream", "${wanted[index]} => ${candidates.joinToString()} [${SystemClock.elapsedRealtime()-start} ms]; 行: $poly\n") })
            check(wanted[index] in candidates.take(5)) { "Handwriting ${wanted[index]} missing: $candidates" }
            check(candidates.size >= 5) { "Only one OCR result was returned" }
        }
        val fixtures = org.json.JSONArray(i.context.assets.open("handwriting-variants.json").bufferedReader().use { it.readText() })
        var first = 0
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val array = fixture.getJSONArray("strokes")
            val ink = (0 until array.length()).map { s ->
                val line = array.getJSONArray(s)
                (0 until line.length()).map { p -> val point = line.getJSONArray(p); floatArrayOf(point.getDouble(0).toFloat(), point.getDouble(1).toFloat()) }
            }
            val done = CountDownLatch(1)
            var output: List<String> = emptyList()
            i.runOnMainSync { check(OpenHandwriting.recognize(ink) { output = it; done.countDown() }) }
            check(done.await(5, TimeUnit.SECONDS)) { "Neural result timeout" }
            val expected = fixture.getString("character")
            check(expected in output.take(5)) { "$expected / ${fixture.getString("style")}: $output" }
            if (output.firstOrNull() == expected) first++
        }
        i.sendStatus(0, Bundle().apply { putString("stream", "Neural ink: $first/${fixtures.length()} first choices on regular/slanted/wobbly public stroke fixtures\n") })
        i.runOnMainSync { OpenHandwriting.release() }
        val again = CountDownLatch(1)
        OpenHandwriting.prepare(i.targetContext) { again.countDown() }
        check(again.await(15, TimeUnit.SECONDS) && OpenHandwriting.ready) { "Handwriting did not reload after memory release" }
        // Both native runtimes must coexist in the same process, in both orders.
        StreamingVoiceRegression.run(i)
        val afterVoice = CountDownLatch(1)
        var after: List<String> = emptyList()
        i.runOnMainSync { check(OpenHandwriting.recognize(inputs[0]) { after = it; afterVoice.countDown() }) }
        check(afterVoice.await(5, TimeUnit.SECONDS) && "大" in after.take(5)) { "Speech runtime interfered with handwriting" }
    }
}
