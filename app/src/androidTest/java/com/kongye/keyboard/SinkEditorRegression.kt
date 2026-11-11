package com.kongye.keyboard

import android.app.Instrumentation
import android.view.View

object SinkEditorRegression {
    @JvmStatic fun run(inst: Instrumentation) {
        var failure: Throwable? = null
        inst.runOnMainSync {
            try {
                val strip = SinkStrip(inst.targetContext, {}, {})
                var saved = ""
                strip.onEdited = { saved = it }
                strip.localEditing = true
                strip.multiline = true
                strip.value = ""
                strip.showAnimated(true, false)
                strip.composing = "ni'hao"
                check(strip.value.isEmpty() && strip.editor.text.toString() == "ni'hao")
                strip.insertAtCursor("你好")
                check(saved == "你好" && strip.editor.selectionStart == 2)
                strip.editor.setSelection(1)
                strip.insertAtCursor("们")
                check(saved == "你们好") { "Input ignored the local cursor" }
                strip.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
                strip.editAction("left")
                check(strip.editor.selectionStart == 1) { "Cursor command left the local editor" }
                strip.editAction("right",true)
                check(strip.editor.selectionStart != strip.editor.selectionEnd) { "Local selection did not extend" }
                strip.editor.setSelection(1, 2)
                strip.insertAtCursor("真棒")
                check(saved == "你真棒好") { "Input did not replace the local selection" }
                strip.editor.setSelection(1, 3)
                strip.deleteAtCursor()
                check(saved == "你好")
                strip.editor.setSelection(2)
                strip.insertAtCursor("😀")
                strip.deleteAtCursor()
                check(saved == "你好") { "Deletion split an emoji" }
                // Android's native paste/selection menu edits this same Editable.
                strip.editor.text.replace(1, 1, "长按粘贴\n第二行")
                check(saved == "你长按粘贴\n第二行好" && strip.value == saved)
                strip.editor.setSelection(1)
                strip.composing = "zhong"
                strip.composing = "zhong'wen"
                strip.insertAtCursor("中文")
                check(saved == "你中文长按粘贴\n第二行好")
                strip.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
                check(strip.editor.width > 0 && strip.editor.height > 0 && !strip.editor.showSoftInputOnFocus)
                strip.value="甲"
                strip.insertAtCursor("乙")
                strip.editAction("undo")
                check(strip.value=="甲") {"Local undo did not restore the previous text"}
                strip.editAction("redo")
                check(strip.value=="甲乙") {"Local redo did not restore the edited text"}
                strip.localEditing = false
                check(strip.editor.visibility == View.GONE)
                // Translation uses the same real editor inside its language /
                // input / done layout, with drag-to-place caret.
                strip.chip="自动";strip.multiline=false;strip.localEditing=true
                strip.value="甲乙丙丁戊己庚辛壬癸";strip.refresh();strip.showAnimated(true,false)
                strip.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                strip.layout(0,0,strip.measuredWidth,strip.measuredHeight)
                check(strip.editor.left>44*inst.targetContext.resources.displayMetrics.density && strip.editor.isCursorVisible)
                val layout=strip.editor.layout ?: error("Translation editor layout missing")
                val y=strip.editor.height/2f
                val from=layout.getPrimaryHorizontal(1)-strip.editor.scrollX+strip.editor.totalPaddingLeft
                val to=layout.getPrimaryHorizontal(6)-strip.editor.scrollX+strip.editor.totalPaddingLeft
                val now=android.os.SystemClock.uptimeMillis()
                for ((action,x) in listOf(android.view.MotionEvent.ACTION_DOWN to from,android.view.MotionEvent.ACTION_MOVE to to,android.view.MotionEvent.ACTION_UP to to)) {
                    val event=android.view.MotionEvent.obtain(now,now,action,x,y,0)
                    try {strip.editor.dispatchTouchEvent(event)} finally {event.recycle()}
                }
                check(strip.editor.selectionStart==6 && strip.editor.selectionEnd==6) {"Translation drag did not place caret: ${strip.editor.selectionStart}"}
                strip.insertAtCursor("新")
                check(saved=="甲乙丙丁戊己新庚辛壬癸") {"Translation input ignored dragged caret"}
            } catch (t: Throwable) { failure = t }
        }
        failure?.let { throw it }
    }
}
