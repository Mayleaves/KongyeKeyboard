package com.kongye.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

/** Native CommonMark rendering; HTML/images never load executable or remote content. */
class MarkdownTextView(ctx:Context):androidx.appcompat.widget.AppCompatTextView(android.view.ContextThemeWrapper(ctx,androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)) {
    private val markdown=Markwon.builder(ctx)
        .usePlugin(TablePlugin.create(ctx))
        .usePlugin(TaskListPlugin.create(ctx))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(object:io.noties.markwon.AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder:io.noties.markwon.MarkwonConfiguration.Builder) {
                builder.linkResolver {_,link ->
                    val uri=Uri.parse(link)
                    if(uri.scheme in listOf("https","http","mailto")) runCatching {ctx.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                }
            }
        }).build()
    private var source:String?=null
    init {textSize=14f;setLineSpacing(5*resources.displayMetrics.density,1.08f);setTextIsSelectable(true);movementMethod=LinkMovementMethod.getInstance()}
    fun render(value:String,fg:Int,link:Int) {
        setTextColor(fg);setLinkTextColor(link)
        if(source==value)return
        source=value;markdown.setMarkdown(this,value)
    }
}
