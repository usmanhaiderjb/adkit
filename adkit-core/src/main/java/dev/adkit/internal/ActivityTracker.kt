package dev.adkit.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Knows the resumed activity without holding a strong reference. */
internal object ActivityTracker : Application.ActivityLifecycleCallbacks {
    private var resumedRef: WeakReference<Activity>? = null
    private var lastStartedRef: WeakReference<Activity>? = null
    private val resumeWaiters = mutableListOf<Pair<WeakReference<Activity>?, (Activity) -> Unit>>()
    private val destroyListeners = mutableMapOf<Int, MutableList<() -> Unit>>()
    var onFirstResume: ((Activity) -> Unit)? = null
    var onAnyResume: (() -> Unit)? = null
    var onAnyPause: (() -> Unit)? = null
    private var firstResumeDone = false

    val resumed: Activity? get() = resumedRef?.get()?.takeIf { it.isAlive }
    val current: Activity? get() = resumed ?: lastStartedRef?.get()?.takeIf { it.isAlive }

    fun isResumed(activity: Activity): Boolean = resumedRef?.get() === activity

    /** Runs [block] now if [activity] is resumed, otherwise on its next resume. Null = any activity. */
    fun whenResumed(activity: Activity?, block: (Activity) -> Unit) {
        val r = resumed
        if (r != null && (activity == null || r === activity)) {
            block(r); return
        }
        resumeWaiters += (activity?.let { WeakReference(it) }) to block
    }

    /** Called once when [activity] is destroyed. */
    fun onDestroyed(activity: Activity, block: () -> Unit) {
        destroyListeners.getOrPut(System.identityHashCode(activity)) { mutableListOf() } += block
    }

    override fun onActivityResumed(activity: Activity) {
        resumedRef = WeakReference(activity)
        if (!firstResumeDone) {
            firstResumeDone = true
            onFirstResume?.invoke(activity)
        }
        val ready = resumeWaiters.filter { (ref, _) -> ref == null || ref.get() === activity }
        resumeWaiters.removeAll(ready)
        resumeWaiters.removeAll { (ref, _) -> ref != null && ref.get() == null }
        ready.forEach { (_, block) -> block(activity) }
        onAnyResume?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
        onAnyPause?.invoke()
    }

    override fun onActivityStarted(activity: Activity) {
        lastStartedRef = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        destroyListeners.remove(System.identityHashCode(activity))?.forEach { it() }
        resumeWaiters.removeAll { (ref, _) -> ref?.get() === activity }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
