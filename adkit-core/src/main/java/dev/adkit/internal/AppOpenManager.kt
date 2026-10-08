package dev.adkit.internal

import android.app.Activity
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.adkit.AdEventType
import dev.adkit.FullScreenNativeActivity
import dev.adkit.R
import dev.adkit.SkipReason

/**
 * Resume app open ads. Cold start belongs to the splash flow; every later foreground return
 * may show the ad unless something suppresses it.
 */
internal class AppOpenManager(
    private val engine: Engine,
    private val loader: FullScreenLoader,
    private val presenter: FullScreenPresenter,
) : DefaultLifecycleObserver {
    /** Native fallback hooks, wired by AdKit: (placement) -> ready?, preload, show. */
    var nativeReady: (String) -> Boolean = { false }
    var nativePreload: (String) -> Unit = {}
    var nativeShow: (Activity, String) -> Unit = { _, _ -> }

    private var coldStartSeen = false
    @Volatile var splashActive = false

    fun register() {
        Main.run { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!coldStartSeen) { coldStartSeen = true; return }
        tryShow()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Request on the way out, show on the way in.
        val p = resumePlacement() ?: return
        if (engine.gate(p.name, p, null) == null) {
            loader.preload(p)
            p.nativeFallback?.let(nativePreload)
        }
    }

    private fun resumePlacement() = engine.config.globals.appOpenResume?.let { engine.placement(it) }

    private fun skip(name: String, reason: SkipReason, msg: String) {
        engine.event(AdEventType.SKIPPED, name, dev.adkit.AdFormat.APP_OPEN, reason = reason, message = msg)
    }

    private fun tryShow() {
        val name = engine.config.globals.appOpenResume ?: return
        val f = engine.frequency
        when {
            engine.appOpenSuppressedOnce -> { engine.appOpenSuppressedOnce = false; skip(name, SkipReason.DISABLED, "suppressed once"); return }
            engine.appOpenHolds.isNotEmpty() -> { skip(name, SkipReason.DISABLED, "held"); return }
            splashActive -> return
            f.showing -> return
            now() - f.lastDismissAt < RETURN_WINDOW_MS -> return
        }
        val p = engine.placement(name)
        engine.gate(name, p, null)?.let { skip(name, it, "resume"); return }
        p!!
        if (!f.appOpenAllowed(p, engine.config.globals)) { skip(name, SkipReason.FREQUENCY, "cap/gap"); return }

        val requestedAt = now()
        ActivityTracker.whenResumed(null) { activity ->
            if (now() - requestedAt > 2_000) return@whenResumed
            if (isExcluded(activity)) { skip(name, SkipReason.DISABLED, "excluded ${activity.javaClass.simpleName}"); return@whenResumed }
            val cached = loader.peek(p)
            val fallback = p.nativeFallback
            if (cached == null && fallback != null && nativeReady(fallback)) {
                // No app open ad but a native is waiting: show it full-screen instead.
                engine.event(AdEventType.SKIPPED, name, dev.adkit.AdFormat.APP_OPEN, reason = SkipReason.NOT_LOADED, message = "native fallback $fallback")
                loader.preload(p)
                Main.delay(engine.appOpenDelayMs) { if (ActivityTracker.isResumed(activity) && !f.showing) nativeShow(activity, fallback) }
                return@whenResumed
            }
            if (cached == null && !p.loadOnDemand) {
                skip(name, SkipReason.NOT_LOADED, "resume")
                loader.preload(p)
                return@whenResumed
            }
            val cover = if (engine.appOpenCover) Cover.show(activity) else null
            val present = present@{
                if (!ActivityTracker.isResumed(activity) || f.showing) { Cover.hide(cover); return@present }
                val ad = loader.take(p) ?: run { Cover.hide(cover); return@present }
                presenter.present(activity, p, ad, null) { _, _ -> Cover.hide(cover) }
                // The ad activity covers the screen; drop our cover shortly after.
                Main.delay(600) { Cover.hide(cover) }
            }
            if (cached != null) {
                Main.delay(engine.appOpenDelayMs, Runnable { present() })
            } else {
                var done = false
                val timeout = Runnable { if (!done) { done = true; Cover.hide(cover); skip(name, SkipReason.TIMEOUT, "resume") } }
                Main.delay(p.timeoutMs, timeout)
                loader.load(p) { ok ->
                    if (done) return@load
                    done = true
                    Main.cancel(timeout)
                    if (ok) present() else Cover.hide(cover)
                }
            }
        }
    }

    private fun isExcluded(activity: Activity): Boolean {
        if (activity::class in engine.appOpenExcluded) return true
        if (activity is FullScreenNativeActivity) return true
        val n = activity.javaClass.name
        return AD_ACTIVITY_PREFIXES.any { n.startsWith(it) }
    }

    /** Plain cover drawn over the content while the ad gets ready. */
    private object Cover {
        fun show(activity: Activity): View? = runCatching {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
            val v = LayoutInflater.from(activity).inflate(R.layout.adkit_app_open_cover, root, false)
            v.isClickable = true
            root.addView(v, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            v
        }.getOrNull()

        fun hide(v: View?) {
            v ?: return
            runCatching { (v.parent as? ViewGroup)?.removeView(v) }
        }
    }

    companion object {
        private const val RETURN_WINDOW_MS = 2_000L
        private val AD_ACTIVITY_PREFIXES = listOf(
            "com.google.android.libraries.ads.mobile.sdk",
            "com.google.android.gms.ads",
            "com.applovin", "com.facebook.ads", "com.bytedance", "com.mbridge", "com.ironsource",
            "com.unity3d", "com.vungle", "com.liftoff", "com.inmobi", "com.chartboost", "sg.bigo",
            "com.google.android.ump",
        )
    }
}
