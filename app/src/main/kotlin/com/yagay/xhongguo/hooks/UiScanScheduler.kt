package com.yagay.xhongguo.hooks

import android.os.Handler
import android.view.ViewTreeObserver
import android.app.Activity
import java.lang.ref.WeakReference
import com.yagay.xhongguo.LogUtil

/** Event-coalesced UI scanning, active only on the current visible activity. */
internal class UiScanScheduler(private val handler: Handler,
    private val enabled: () -> Boolean, private val scan: () -> Unit) {
    private var activity = WeakReference<Activity>(null)
    private var observer: ViewTreeObserver? = null
    private var pending = false
    private var running = false
    private val task = Runnable {
        pending = false
        val a = activity.get()
        if (!running || a == null || a.isDestroyed || a.isFinishing ||
            !a.hasWindowFocus() || !enabled()) return@Runnable
        scan()
    }
    private val fallback = object : Runnable {
        override fun run() {
            if (!running) return
            request()
            handler.postDelayed(this, 2000L)
        }
    }
    private val listener = ViewTreeObserver.OnGlobalLayoutListener { request() }
    fun start(a: Activity?) {
        stop()
        if (a == null || a.isDestroyed || a.isFinishing) return
        running = true
        activity = WeakReference(a)
        observer = a.window.decorView.viewTreeObserver
        observer?.takeIf { it.isAlive }?.addOnGlobalLayoutListener(listener)
        request()
        handler.postDelayed(fallback, 2000L)
    }
    fun request() {
        if (!running || pending || !enabled()) return
        pending = true
        handler.postDelayed(task, 250L)
    }
    fun stop() {
        running = false
        pending = false
        handler.removeCallbacks(task)
        handler.removeCallbacks(fallback)
        runCatching { observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener) }
        observer = null
        activity.clear()
    }
}
