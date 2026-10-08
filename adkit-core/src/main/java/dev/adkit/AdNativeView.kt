package dev.adkit

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import dev.adkit.internal.ActivityTracker
import dev.adkit.internal.AdLog
import dev.adkit.internal.NativeBinder
import dev.adkit.internal.NativePool
import dev.adkit.internal.Network
import dev.adkit.internal.Skeleton
import dev.adkit.internal.findActivity

/**
 * Self-loading native ad. XML:
 * `<dev.adkit.AdNativeView app:adPlacement="language" app:adStyle="medium" />`
 *
 * - Takes a preloaded ad from the pool when one is ready (splash -> language hand-over).
 * - [retainKey] keeps the ad across detach/attach (RecyclerView rows, ViewPager pages) until the
 *   activity is destroyed.
 * - If remote config switches the placement to banner, it hosts an [AdBannerView] instead.
 */
class AdNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var placement: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (isAttachedToWindow) reload()
        }

    /** Overrides the placement's style for this view. */
    var style: NativeStyle? = null
    @LayoutRes var customLayout: Int = 0
    var showSkeleton = true
    var autoLoad = true
    var ctaColor: Int? = null
    var retainKey: String? = null
    var onStateChanged: ((AdState) -> Unit)? = null

    var state: AdState = AdState.IDLE
        private set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    private var ad: NativeAd? = null
    private var adView: NativeAdView? = null
    private var skeleton: View? = null
    private var waiter: NativePool.Waiter? = null
    private var bannerChild: AdBannerView? = null

    private val premiumListener: (Boolean) -> Unit = { premium ->
        if (premium) hide() else if (state == AdState.HIDDEN && isAttachedToWindow) reload()
    }
    private val networkListener: () -> Unit = { if (state == AdState.FAILED && isAttachedToWindow) reload() }
    private val consentListener: () -> Unit = networkListener

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.AdNativeView)
            placement = a.getString(R.styleable.AdNativeView_adPlacement)
            a.getString(R.styleable.AdNativeView_adStyle)?.let { s -> style = NativeStyle.from(s) }
            customLayout = a.getResourceId(R.styleable.AdNativeView_adLayout, 0)
            showSkeleton = a.getBoolean(R.styleable.AdNativeView_adShowSkeleton, true)
            autoLoad = a.getBoolean(R.styleable.AdNativeView_adAutoLoad, true)
            retainKey = a.getString(R.styleable.AdNativeView_adRetainKey)
            if (a.hasValue(R.styleable.AdNativeView_adCtaColor)) ctaColor = a.getColor(R.styleable.AdNativeView_adCtaColor, 0)
            a.recycle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val engine = AdKit.engineOrNull ?: return
        engine.addPremiumListener(premiumListener)
        Network.onAvailable(networkListener)
        engine.addConsentGrantedListener(consentListener)
        if (autoLoad && state == AdState.IDLE) load()
    }

    override fun onDetachedFromWindow() {
        AdKit.engineOrNull?.removePremiumListener(premiumListener)
        AdKit.engineOrNull?.removeConsentGrantedListener(consentListener)
        Network.remove(networkListener)
        release(keep = true)
        state = AdState.IDLE
        super.onDetachedFromWindow()
    }

    fun load() {
        val engine = AdKit.engineOrNull ?: return AdLog.w("AdNativeView: AdKit.init not called")
        val name = placement ?: return AdLog.w("AdNativeView: no placement set")
        val p = engine.placement(name)
        if (p?.format == AdFormat.BANNER) return hostBanner(name)
        val reason = engine.gate(name, p, context, needSdk = false)
        if (reason != null) {
            engine.event(AdEventType.SKIPPED, name, AdFormat.NATIVE, reason = reason)
            if (reason == SkipReason.OFFLINE || reason == SkipReason.NO_CONSENT) {
                state = AdState.FAILED
                if (p?.collapseOnFail != false) visibility = GONE
            } else hide()
            return
        }
        p!!
        visibility = VISIBLE
        val resolved = NativeBinder.resolve(context, style ?: p.nativeStyle)

        retained(name)?.let { bind(p, resolved, it); return }

        if (showSkeleton && skeleton == null) {
            skeleton = Skeleton.native(context, resolved).also {
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, if (resolved == NativeStyle.FULLSCREEN) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT))
            }
        }
        state = AdState.LOADING
        waiter?.cancel()
        waiter = AdKit.nativePool.request(p) { loaded ->
            waiter = null
            if (!isAttachedToWindow) {
                // Arrived after we left: give it back for the next view rather than wasting it.
                loaded?.let { AdKit.nativePool.offer(p, it) }
                return@request
            }
            if (loaded == null) {
                removeSkeleton()
                state = AdState.FAILED
                if (p.collapseOnFail) visibility = GONE
            } else bind(p, resolved, loaded)
        }
    }

    private fun bind(p: Placement, style: NativeStyle, nativeAd: NativeAd) {
        removeSkeleton()
        adView?.let { removeView(it) }
        val v = NativeBinder.create(context, style, if (customLayout != 0) customLayout else p.layoutRes)
        NativeBinder.bind(v, nativeAd, ctaColor ?: p.ctaColor)
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, if (style == NativeStyle.FULLSCREEN) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT))
        ad = nativeAd
        adView = v
        state = AdState.LOADED
    }

    private fun hostBanner(name: String) {
        if (bannerChild != null) return
        bannerChild = AdBannerView(context).also {
            it.placement = name
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun hide() {
        release(keep = false)
        state = AdState.HIDDEN
        visibility = GONE
    }

    private fun reload() {
        release(keep = false)
        state = AdState.IDLE
        load()
    }

    private fun release(keep: Boolean) {
        waiter?.cancel()
        waiter = null
        removeSkeleton()
        bannerChild?.let { removeView(it) }
        bannerChild = null
        adView?.let { removeView(it) }
        adView = null
        val current = ad ?: return
        ad = null
        val key = retainKey
        val activity = context.findActivity()
        if (keep && key != null && activity != null && AdKit.engineOrNull?.premium?.value != true) {
            RetainStore.put(activity, placement + "#" + key, current)
        } else {
            runCatching { current.destroy() }
        }
    }

    private fun retained(name: String): NativeAd? {
        val key = retainKey ?: return null
        val activity = context.findActivity() ?: return null
        return RetainStore.take(activity, "$name#$key")
    }

    private fun removeSkeleton() {
        skeleton?.let { Skeleton.stop(it); removeView(it) }
        skeleton = null
    }
}

/** Activity-scoped storage for natives that must survive view recycling. */
internal object RetainStore {
    private val byActivity = HashMap<Int, HashMap<String, NativeAd>>()

    fun put(activity: Activity, key: String, ad: NativeAd) {
        val id = System.identityHashCode(activity)
        val map = byActivity.getOrPut(id) {
            ActivityTracker.onDestroyed(activity) {
                byActivity.remove(id)?.values?.forEach { runCatching { it.destroy() } }
            }
            HashMap()
        }
        map.put(key, ad)?.let { if (it !== ad) runCatching { it.destroy() } }
    }

    fun take(activity: Activity, key: String): NativeAd? = byActivity[System.identityHashCode(activity)]?.remove(key)

    fun clear() {
        byActivity.values.forEach { m -> m.values.forEach { runCatching { it.destroy() } } }
        byActivity.clear()
    }
}
