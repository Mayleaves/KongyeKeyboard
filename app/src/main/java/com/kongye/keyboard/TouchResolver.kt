package com.kongye.keyboard

import android.graphics.RectF
import android.view.MotionEvent

/** Geometry ranks candidates only at uncertain edges; visible key cores remain authoritative. */
@androidx.annotation.Keep
object TouchResolver {
    @androidx.annotation.Keep
    data class Sample(val x:Float,val y:Float,val major:Float,val minor:Float,val tool:Int,val time:Long)
    @androidx.annotation.Keep
    data class Choice(val key:Key,val score:Float,val distance:Float)
    @androidx.annotation.Keep
    data class Decision(val key:Key?,val choices:List<Choice>,val reason:String)
    fun sample(e:MotionEvent,index:Int,history:Int = -1):Sample = if(history<0)
        Sample(e.getX(index),e.getY(index),e.getTouchMajor(index),e.getTouchMinor(index),e.getToolType(index),e.eventTime)
        else Sample(e.getHistoricalX(index,history),e.getHistoricalY(index,history),e.getHistoricalTouchMajor(index,history),e.getHistoricalTouchMinor(index,history),e.getToolType(index),e.getHistoricalEventTime(history))
    fun palm(s:Sample,density:Float):Boolean = s.tool==MotionEvent.TOOL_TYPE_FINGER &&
        s.major.isFinite() && s.minor.isFinite() && s.major>65*density && s.minor>35*density
    fun resolve(keys:List<Key>,width:Float,height:Float,density:Float,s:Sample,
        bias:(Key)->TouchCalibration.Bias={TouchCalibration.Bias(0f,0f,0f)},
        context:(Key)->Float={0f},separator:(Key)->Boolean={it.kind==Kind.SEP}):Decision {
        if(!s.x.isFinite() || !s.y.isFinite() || s.x !in 0f..width || s.y !in 0f..height)return Decision(null,emptyList(),"outside")
        if(palm(s,density))return Decision(null,emptyList(),"large-contact")
        val stylus=s.tool==MotionEvent.TOOL_TYPE_STYLUS || s.tool==MotionEvent.TOOL_TYPE_ERASER
        val ranked=keys.mapNotNull {k->
            if(k.rect.width()<=0 || k.rect.height()<=0)return@mapNotNull null
            // Bounded extensions fill narrow key gaps without making indented row
            // margins or the split-keyboard gap behave as oversized letter keys.
            val expand=if(separator(k))0f else when(k.kind){Kind.SPACE,Kind.DEL,Kind.ENTER->6f;else->3f}*density
            val allowed=RectF(k.rect).apply {inset(-expand,-expand)}
            if(separator(k))allowed.inset(2*density,2*density)
            if(separator(k) && !allowed.contains(s.x,s.y))return@mapNotNull null
            if(!allowed.contains(s.x,s.y) && !k.rect.contains(s.x,s.y))return@mapNotNull null
            val b=if(stylus)TouchCalibration.Bias(0f,0f,0f)else bias(k)
            val inward=if(k.rect.left<8*density)1.5f*density else if(k.rect.right>width-8*density)-1.5f*density else 0f
            val cx=k.rect.centerX()+b.x*k.rect.width()+inward;val cy=k.rect.centerY()+b.y*k.rect.height()
            val dx=(s.x-cx)/k.rect.width();val dy=(s.y-cy)/k.rect.height()
            val distance=dx*dx+dy*dy
            if(distance>1.1f)return@mapNotNull null
            val prior=if(k.kind==Kind.LETTER && k.letters in listOf("s","d","k")) .003f else 0f
            Choice(k,distance-prior,distance)
        }.sortedBy {it.score}.take(3)
        if(ranked.isEmpty())return Decision(null,ranked,"gap-or-distance")
        val core=ranked.firstOrNull {val r=RectF(it.key.rect);r.inset(r.width()*.12f,r.height()*.1f);r.contains(s.x,s.y)}
        if(core!=null)return Decision(core.key,ranked,"visual-core")
        val winner=ranked.first()
        if(stylus)return Decision(winner.key.takeIf {it.rect.contains(s.x,s.y)},ranked,"stylus")
        // Symbols/separators require an intentional direct hit, never nearest-letter substitution.
        if(separator(winner.key) && !winner.key.rect.contains(s.x,s.y))return Decision(null,ranked,"separator-gap")
        val uncertain=ranked.filter {it.key.kind==Kind.LETTER && it.key.letters.length==1 && it.score-winner.score<=.035f}
        if(winner.key.kind==Kind.LETTER && uncertain.size>1) {
            val contextual=uncertain.minBy {it.score-context(it.key).coerceIn(0f,.02f)}
            return Decision(contextual.key,ranked,"ambiguous-letter-edge")
        }
        return Decision(winner.key,ranked,"nearest")
    }
}
