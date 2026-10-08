package dev.adkit

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import dev.adkit.internal.NativeBinder
import dev.adkit.internal.now
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native ad as its own screen (between onboarding and home, after a save, ...).
 * Close appears after the placement's `skipDelaySec`. Counts as a full-screen ad for app open.
 */
class FullScreenNativeActivity : Activity() {
    private var token = -1
    private var timer: CountDownTimer? = null
    private var ad: NativeAd? = null
    private var closable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getIntExtra(EXTRA_TOKEN, -1)
        val handoff = pending[token]
        if (handoff == null || savedInstanceState != null) {
            // Process death or a stale intent: nothing to show.
            finishWith(false); return
        }
        setContentView(R.layout.adkit_fullscreen_native_activity)
        val container = findViewById<FrameLayout>(R.id.adkit_fullscreen_container)
        val close = findViewById<TextView>(R.id.adkit_fullscreen_close)

        val p = handoff.placement
        val view = NativeBinder.create(this, NativeStyle.FULLSCREEN, p.layoutRes)
        NativeBinder.bind(view, handoff.ad, p.ctaColor)
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        ad = handoff.ad
        AdKit.engineOrNull?.frequency?.recordShown(p)

        val delaySec = p.skipDelaySec.coerceAtLeast(0)
        if (delaySec == 0) showClose(close) else {
            close.visibility = View.VISIBLE
            close.isEnabled = false
            timer = object : CountDownTimer(delaySec * 1000L, 250) {
                override fun onTick(left: Long) {
                    close.text = getString(R.string.adkit_skip_in, ((left + 999) / 1000).toInt())
                }
                override fun onFinish() = showClose(close)
            }.start()
        }
        close.setOnClickListener { if (closable) finishWith(true) }
    }

    private fun showClose(close: TextView) {
        closable = true
        close.isEnabled = true
        close.text = getString(R.string.adkit_close)
        close.visibility = View.VISIBLE
    }

    @Deprecated("Back is allowed once the close button is available")
    override fun onBackPressed() {
        if (closable) finishWith(true)
    }

    private fun finishWith(shown: Boolean) {
        val h = pending.remove(token)
        AdKit.engineOrNull?.frequency?.let {
            it.showing = false
            it.lastDismissAt = now()
        }
        finish()
        h?.done?.invoke(shown)
    }

    override fun onDestroy() {
        timer?.cancel()
        runCatching { ad?.destroy() }
        ad = null
        // Destroyed without the close button (task removed): still release the caller.
        if (!isChangingConfigurations) {
            pending.remove(token)?.let { h ->
                AdKit.engineOrNull?.frequency?.let { it.showing = false; it.lastDismissAt = now() }
                h.done(true)
            }
        }
        super.onDestroy()
    }

    internal class Handoff(val placement: Placement, val ad: NativeAd, val done: (Boolean) -> Unit)

    internal companion object {
        const val EXTRA_TOKEN = "adkit_token"
        private val ids = AtomicInteger()
        val pending = ConcurrentHashMap<Int, Handoff>()

        fun register(h: Handoff): Int = ids.incrementAndGet().also { pending[it] = h }
    }
}
