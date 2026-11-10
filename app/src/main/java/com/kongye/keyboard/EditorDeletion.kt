package com.kongye.keyboard

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/** UU 的本地代理文本不等于远端全文；按其支持的退格事件删除。 */
@androidx.annotation.Keep
object EditorDeletion {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    fun cancel() { pending?.let(main::removeCallbacks); pending = null }
    private fun remote(packageName: String?) = packageName == "com.netease.uuremote"

    private fun key(c: InputConnection, code: Int, action: Int, meta: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return c.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    private fun tap(c: InputConnection, code: Int, meta: Int = 0) {
        key(c, code, KeyEvent.ACTION_DOWN, meta)
        key(c, code, KeyEvent.ACTION_UP, meta)
    }

    fun delete(c: InputConnection, packageName: String?) {
        cancel()
        if (remote(packageName)) {
            tap(c, KeyEvent.KEYCODE_DEL)
        } else {
            val selected = c.getSelectedText(0)
            if (!selected.isNullOrEmpty()) c.commitText("", 1)
            else if (!c.deleteSurroundingTextInCodePoints(1, 0)) tap(c, KeyEvent.KEYCODE_DEL)
        }
    }

    fun clear(c: InputConnection, packageName: String?): Boolean {
        cancel()
        if (remote(packageName)) {
            // UU 仅转发 ENTER/DEL 的 ACTION_UP，忽略 Ctrl+A 和批量删除长度。
            // 本地代理输入框提供的是本次输入的文字，不把它当作远端全文。
            val before = c.getTextBeforeCursor(10001, 0)?.toString().orEmpty()
            val selected = c.getSelectedText(0)?.toString().orEmpty()
            val text = before + selected
            val count = text.codePointCount(0, text.length)
            if (count == 0 || count > 10000 || !c.getTextAfterCursor(1, 0).isNullOrEmpty()) return false
            var remaining = count
            val task = object : Runnable {
                override fun run() {
                    if (pending !== this) return
                    repeat(minOf(16, remaining)) { tap(c, KeyEvent.KEYCODE_DEL); remaining-- }
                    if (remaining > 0) main.postDelayed(this, 16) else pending = null
                }
            }
            pending = task
            task.run()
        } else {
            c.commitText("", 1)
            c.deleteSurroundingText(100000, 100000)
        }
        return true
    }
}
