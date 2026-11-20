package com.kongye.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle

/** Suppress only our own write notification, never a later external copy. */
@androidx.annotation.Keep
object ClipboardTransfer {
    private const val ORIGIN="com.kongye.keyboard.clip.origin"
    private val origin=java.util.UUID.randomUUID().toString()

    fun own(clip:ClipData)=clip.description.extras?.getString(ORIGIN)==origin

    fun copy(manager:ClipboardManager?,clip:ClipData):Boolean {
        if(manager==null) return false
        clip.description.extras=PersistableBundle(clip.description.extras ?: PersistableBundle()).apply {putString(ORIGIN,origin)}
        return runCatching {manager.setPrimaryClip(clip);true}.getOrDefault(false)
    }
}
