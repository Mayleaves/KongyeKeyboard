package com.kongye.keyboard

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

object StreamingVoiceRegression {
    fun run(i: Instrumentation) {
        val file = File(i.targetContext.getExternalFilesDir("offline-voice"), "test/streaming.wav")
        val wave = WaveReader.readWave(file.absolutePath)
        val partials = ArrayList<String>()
        val start = SystemClock.elapsedRealtime()
        var firstAt = -1L
        val text = OfflineVoice.recognizeStreamingSamples(i.targetContext, wave.samples, wave.sampleRate) {
            if (firstAt < 0) firstAt = SystemClock.elapsedRealtime()-start
            partials.add(it)
        }
        val elapsed = SystemClock.elapsedRealtime()-start
        check("介绍" in text && "研究" in text) { "Streaming recognition: $text" }
        check(partials.size >= 3 && firstAt >= 0) { "Not an incremental recognition path" }
        check(elapsed < (wave.samples.size*1000L/wave.sampleRate)) { "Streaming decoder slower than audio" }
        i.sendStatus(0, Bundle().apply { putString("stream", "Streaming: $text; ${partials.size} partials, first $firstAt ms; total $elapsed ms\n") })
        val second = OfflineVoice.recognizeStreamingSamples(i.targetContext, wave.samples, wave.sampleRate) {}
        check(second == text) { "Streaming session state leaked across recordings" }
    }
}
