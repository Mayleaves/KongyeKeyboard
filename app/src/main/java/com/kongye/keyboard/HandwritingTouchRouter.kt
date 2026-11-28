package com.kongye.keyboard

import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/** Transfers a toolbar drag to the visible handwriting pad, within the IME window. */
@androidx.annotation.Keep
class HandwritingTouchRouter(
    private val root:ViewGroup,
    private val pad:()->HandwriteView?,
    private val source:()->View?,
) {
    private var pending:HandwriteView?=null
    private var captured:HandwriteView?=null
    private var pointer=MotionEvent.INVALID_POINTER_ID
    private var suppressRemainder=false

    /** Apply the same parent offsets and inverse child matrices as normal ViewGroup dispatch. */
    private fun local(event:MotionEvent,target:View):MotionEvent? {
        val chain=generateSequence(target) {it.parent as? View}.takeWhile {it!==root}.toList()
        if(chain.lastOrNull()?.parent!==root)return null
        val copy=MotionEvent.obtain(event)
        for(child in chain.asReversed()) {
            val parent=child.parent as View
            copy.offsetLocation((parent.scrollX-child.left).toFloat(),(parent.scrollY-child.top).toFloat())
            if(!child.matrix.isIdentity) {
                val inverse=Matrix()
                if(!child.matrix.invert(inverse)) {copy.recycle();return null}
                copy.transform(inverse)
            }
        }
        return copy
    }

    fun cancel() {
        captured?.let {target->
            val now=SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0f,0f,0)
            try {target.dispatchTouchEvent(event)}finally {event.recycle()}
        }
        pending=null;captured=null;pointer=MotionEvent.INVALID_POINTER_ID;suppressRemainder=false
    }

    fun dispatch(event:MotionEvent,children:(MotionEvent)->Boolean):Boolean {
        val action=event.actionMasked
        val end=action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL
        if(action==MotionEvent.ACTION_DOWN) {
            cancel()
            val target=pad()?.takeIf {it.isShown}
            val toolbar=if(target==null)null else source()?.takeIf {it.isShown}
            val at=toolbar?.let {local(event,it)}
            if(target!=null && toolbar!=null && at!=null && at.x>=0 && at.y>=0 && at.x<toolbar.width && at.y<toolbar.height) {
                pending=target;pointer=event.getPointerId(0)
            }
            at?.recycle()
        }
        captured?.let {target->
            val copy=if(target===pad() && target.isShown)local(event,target) else null
            if(copy==null) {cancel();suppressRemainder=!end}
            else try {target.dispatchTouchEvent(copy)}finally {copy.recycle()}
            if(end)cancel()
            return true
        }
        if(suppressRemainder) {if(end)cancel();return true}
        val target=pending
        if(target!=null && target===pad() && target.isShown &&
            action in listOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP) && event.pointerCount==1 &&
            event.getPointerId(0)==pointer) {
            val copy=local(event,target)
            if(copy!=null)try {
                if(target.writingBounds().contains(copy.x,copy.y)) {
                    // Cancel the old toolbar gesture before it can select a candidate or tool.
                    val cancelled=MotionEvent.obtain(event).apply {setAction(MotionEvent.ACTION_CANCEL)}
                    try {children(cancelled)}finally {cancelled.recycle()}
                    pending=null;captured=target
                    val down=MotionEvent.obtainNoHistory(copy).apply {setAction(MotionEvent.ACTION_DOWN)}
                    try {target.dispatchTouchEvent(down)}finally {down.recycle()}
                    val current=MotionEvent.obtainNoHistory(copy)
                    try {target.dispatchTouchEvent(current)}finally {current.recycle()}
                    if(end)cancel()
                    return true
                }
            } finally {copy.recycle()}
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN)pending=null
        val keep=pending!=null
        val handled=children(event)
        if(end)cancel()
        return handled || keep
    }
}
