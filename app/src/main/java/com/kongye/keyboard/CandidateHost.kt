package com.kongye.keyboard

import android.content.Context
import android.widget.FrameLayout

/** Overlays use the candidate bar's height, even when their children fill the host. */
class CandidateHost(context:Context):FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val bar=getChildAt(0)
        if(bar==null) {
            super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            return
        }
        bar.measure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(bar.measuredHeight,MeasureSpec.EXACTLY))
    }
}
