package com.kongye.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Exercises the actual Compose intro, including taps while it is fading out
 * and Activity recreation before/after it has finished. */
object SettingsMotionRegression {
    fun run(i: Instrumentation) {
        val app=i.targetContext.applicationContext as android.app.Application
        val current=java.util.concurrent.atomic.AtomicReference<Activity>()
        val lifecycle=object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:Activity) {if(a is SettingsActivity) current.set(a)}
            override fun onActivityCreated(a:Activity,b:Bundle?) {}
            override fun onActivityStarted(a:Activity) {}
            override fun onActivityPaused(a:Activity) {}
            override fun onActivityStopped(a:Activity) {}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle) {}
            override fun onActivityDestroyed(a:Activity) {}
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        fun visible(): Boolean {
            fun find(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.contentDescription?.toString() == "开屏动画") return true
                for (index in 0 until node.childCount) if (find(node.getChild(index))) return true
                return false
            }
            return find(i.uiAutomation.rootInActiveWindow)
        }
        fun noBlackFrame() {
            val screenshot = i.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            try {
                var black = 0; var total = 0
                for (y in 40 until screenshot.height - 40 step 20) for (x in 20 until screenshot.width - 20 step 20) {
                    val pixel = screenshot.getPixel(x, y)
                    if ((pixel and 0xFFFFFF) == 0) black++
                    total++
                }
                check(black < total * .5) { "Black frame during repeated splash taps" }
            } finally { screenshot.recycle() }
        }
        fun start(): Activity {
            current.set(null)
            i.targetContext.startActivity(Intent(i.targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val resumed=SystemClock.uptimeMillis()+5000
            while(current.get()==null && SystemClock.uptimeMillis()<resumed) SystemClock.sleep(40)
            val activity = current.get() ?: error("Settings did not open")
            val deadline = SystemClock.uptimeMillis() + 1800
            while (!visible() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40)
            check(visible()) { "Fresh launch intro did not appear" }
            return activity
        }
        try {
            var activity = start()
            // Configuration change during the intro must not replay it.
            i.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            SystemClock.sleep(1000)
            activity = current.get() ?: error("Missing rotated settings")
            check(!visible()) { "Intro replayed on landscape rotation" }
            i.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            SystemClock.sleep(650)
            check(!visible()) { "Intro replayed on portrait rotation" }
            activity = start()
            repeat(1) {
                i.runOnMainSync {
                    val now = SystemClock.uptimeMillis()
                    val x = activity.window.decorView.width / 2f
                    val y = activity.window.decorView.height / 2f
                    for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, x, y, 0)
                        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                noBlackFrame()
                SystemClock.sleep(70)
            }
            val dismissed=SystemClock.uptimeMillis()+450
            while(visible() && SystemClock.uptimeMillis()<dismissed) SystemClock.sleep(20)
            check(!visible()) { "Tapped intro did not finish" }
            activity = current.get() ?: activity
            i.runOnMainSync { activity.recreate() }
            SystemClock.sleep(650)
            check(!visible()) { "Intro replayed after recreation" }
            noBlackFrame()
            i.sendStatus(0, Bundle().apply { putString("stream", "PASS actual settings intro: rotation during intro, repeated taps without black frames, recreation after dismissal\n") })
        } finally { app.unregisterActivityLifecycleCallbacks(lifecycle) }
    }
}
