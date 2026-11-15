package com.kongye.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Decode only visible thumbnails, off the input thread, with bounded dimensions/cache. */
@android.annotation.SuppressLint("AppCompatCustomView")
class ClipThumbnailView(context:Context,private val file:File,private val active:MutableSet<ClipThumbnailView>):ImageView(context) {
    private var requested=false
    private var generation=0
    private val visible=Rect()
    init {scaleType=ScaleType.CENTER_CROP}
    fun releaseThumbnail() {
        generation++;requested=false;setImageDrawable(null);active.remove(this)
    }
    fun releaseIfOffscreen() {if(!getLocalVisibleRect(visible)) releaseThumbnail()}
    override fun onDetachedFromWindow() {releaseThumbnail();super.onDetachedFromWindow()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(requested || !isAttachedToWindow || !getLocalVisibleRect(visible))return
        requested=true
        val id=++generation
        val key="${file.path}:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let {setImageBitmap(it);active.add(this);return}
        decoder.execute {
            val bmp=runCatching {
                val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,options)
                if(options.outWidth<=0 || options.outHeight<=0) return@runCatching null
                var sample=1
                while(options.outWidth/sample>512 || options.outHeight/sample>512)sample*=2
                BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})
            }.getOrNull()
            if(bmp!=null) cache.put(key,bmp)
            main.post {
                if(id==generation && isAttachedToWindow && getLocalVisibleRect(visible)) {
                    if(bmp!=null) {setImageBitmap(bmp);active.add(this)}
                } else if(id==generation) {requested=false}
            }
        }
    }
    companion object {
        private val main=Handler(Looper.getMainLooper())
        private val decoder=Executors.newSingleThreadExecutor {task->Thread(task,"clipboard-thumbnails").apply {isDaemon=true}}
        private val cache=object:LruCache<String,Bitmap>(4*1024*1024) {
            override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
        }
    }
}
