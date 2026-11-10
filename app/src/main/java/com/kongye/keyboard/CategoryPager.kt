package com.kongye.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Cached neighbor pages are measured before sliding; only translations change per frame. */
class CategoryPager(context:Context):FrameLayout(context) {
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val density=resources.displayMetrics.density
    private var count=0
    private var selected=0
    private var factory:(Int)->View={View(context)}
    private var onSelected:(Int)->Unit={}
    private var current:View?=null
    private var neighbor:View?=null
    private var next=-1
    private var downX=0f;private var downY=0f
    private var dragging=false;private var offset=0f
    private var animator:ValueAnimator?=null
    private var queued:Int?=null
    private var velocity:VelocityTracker?=null
    var onProgress: ((Int,Int,Float)->Unit)? = null
    private val cache=LinkedHashMap<Int,View>()
    fun pages(count:Int,selected:Int,factory:(Int)->View,onSelected:(Int)->Unit) {
        animator?.cancel();animator=null;queued=null
        this.count=count;this.selected=selected.coerceIn(0,(count-1).coerceAtLeast(0))
        this.factory=factory;this.onSelected=onSelected
        removeAllViews();cache.clear();neighbor=null;next=-1;offset=0f
        current=factory(this.selected).also {addView(it,LayoutParams(-1,-1))}
        post {prefetch()}
    }
    private fun measured(index:Int):View {
        val view=cache.remove(index) ?: factory(index)
        if(width>0 && height>0) {
            view.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
            view.layout(0,0,width,height)
        }
        return view
    }
    private fun remember(index:Int,view:View) {
        view.translationX=0f;view.setLayerType(View.LAYER_TYPE_NONE,null)
        cache[index]=view
        while(cache.size>3) cache.remove(cache.keys.first())
    }
    private fun prefetch() {
        if(animator!=null || !isAttachedToWindow || width<=0) return
        for(i in listOf(selected-1,selected+1)) if(i in 0 until count && !cache.containsKey(i)) remember(i,measured(i))
    }
    fun select(index:Int) {
        if(index !in 0 until count || index==selected && animator==null) return
        if(animator!=null) {queued=index;return}
        if(width<=0 || height<=0) {post {select(index)};return}
        prepare(index);settle(true,0f)
    }
    private fun prepare(index:Int) {
        if(next==index) return
        neighbor?.let {removeView(it);remember(next,it)}
        next=index
        neighbor=measured(index).also {addView(it,LayoutParams(-1,-1))}
        current?.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        neighbor?.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        position(offset)
    }
    private fun position(x:Float) {
        offset=x;current?.translationX=x
        neighbor?.translationX=x+(if(next>selected) width else -width)
        onProgress?.invoke(selected,if(next>=0) next else selected,if(next>=0) (abs(x)/width.coerceAtLeast(1)).coerceIn(0f,1f) else 0f)
    }
    private fun settle(commit:Boolean,speed:Float) {
        val end=if(commit) (if(next>selected) -width else width).toFloat() else 0f
        val start=offset
        if(!ValueAnimator.areAnimatorsEnabled()) {finish(commit);return}
        animator=ValueAnimator.ofFloat(start,end).apply {
            val distance=abs(end-start)
            duration=if(abs(speed)>500*density) (distance/abs(speed)*1000).toLong().coerceIn(130,230)
                else (230*(distance/width.coerceAtLeast(1)).coerceIn(.55f,1f)).toLong()
            interpolator=DecelerateInterpolator(1.25f)
            addUpdateListener {position(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation:android.animation.Animator) {cancelled=true}
                override fun onAnimationEnd(animation:android.animation.Animator) {if(!cancelled) finish(commit)}
            });start()
        }
    }
    private fun finish(commit:Boolean) {
        if(commit && neighbor!=null) {
            current?.let {removeView(it);remember(selected,it)}
            current=neighbor;selected=next
        } else neighbor?.let {removeView(it);remember(next,it)}
        current?.setLayerType(View.LAYER_TYPE_NONE,null)
        neighbor=null;next=-1;position(0f);animator=null
        if(commit) onSelected(selected)
        val pending=queued;queued=null
        if(pending!=null && pending!=selected) post {select(pending)} else post {prefetch()}
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;dragging=false;velocity?.recycle();velocity=VelocityTracker.obtain().apply {addMovement(e)}}
            MotionEvent.ACTION_MOVE->if(animator==null && abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)*1.25f) {
                dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true
            }
        };return false
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(animator!=null) return true
        velocity?.addMovement(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;dragging=true}
            MotionEvent.ACTION_MOVE->if(dragging) {
                val dx=(e.x-downX).coerceIn(-width.toFloat(),width.toFloat())
                val target=selected+if(dx<0) 1 else -1
                if(target in 0 until count) {prepare(target);position(dx)}
                else {neighbor?.let {removeView(it);remember(next,it)};neighbor=null;next=-1;position(dx*.16f)}
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                dragging=false;velocity?.computeCurrentVelocity(1000)
                val speed=velocity?.xVelocity ?: 0f
                velocity?.recycle();velocity=null
                val commit=e.actionMasked==MotionEvent.ACTION_UP && next>=0 && (abs(offset)>width*.18f || abs(speed)>500*density && speed*offset>0)
                parent?.requestDisallowInterceptTouchEvent(false);settle(commit,speed)
            }
        };return true
    }
    override fun onDetachedFromWindow() {
        animator?.cancel();animator=null;queued=null
        velocity?.recycle();velocity=null
        current?.setLayerType(View.LAYER_TYPE_NONE,null);neighbor?.setLayerType(View.LAYER_TYPE_NONE,null)
        super.onDetachedFromWindow()
    }
}
