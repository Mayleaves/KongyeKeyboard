package com.kongye.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** PCM rendered from the second supplied HTML's Web Audio graph, with no network or WebView. */
class ReceiptSounds(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val loaded = mutableSetOf<Int>()
    private val streams = mutableSetOf<Int>()
    private var released = false
    var active = true
        set(value) { field = value; if (!value) stop() }
    var printEnabled = true
        set(value) { field = value; if (!value) stop() }
    var tearEnabled = true
    private val key: Int
    private val slow: Int
    private val fast: Int
    private val stamp: Int
    private val tear: Int
    init {
        pool.setOnLoadCompleteListener { _, id, result -> if (result == 0) loaded.add(id) }
        key = pool.load(context, R.raw.receipt_key, 1)
        slow = pool.load(context, R.raw.receipt_step_slow, 1)
        fast = pool.load(context, R.raw.receipt_step_fast, 1)
        stamp = pool.load(context, R.raw.receipt_stamp, 1)
        tear = pool.load(context, R.raw.receipt_tear, 1)
    }
    private fun play(id: Int, allowed: Boolean) {
        if (!allowed || !active || released || id !in loaded) return
        val stream = pool.play(id, 1f, 1f, 1, 0, 1f)
        if (stream != 0) {
            streams.add(stream)
            // Sounds finish within 260 ms; bound the bookkeeping during repeated printing.
            if (streams.size > 24) streams.remove(streams.first())
        }
    }
    fun key() = play(key, printEnabled)
    fun step(dense: Boolean) = play(if (dense) fast else slow, printEnabled)
    fun stamp() = play(stamp, printEnabled)
    fun previewPrint() { stop(); play(fast, printEnabled) }
    fun previewTear() = tear()
    fun tear() { stop(); play(tear, tearEnabled) }
    fun stop() { if (!released) streams.forEach { pool.stop(it) }; streams.clear() }
    fun release() { if (!released) { stop(); released = true; pool.release() } }
}
