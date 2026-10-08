package dev.adkit

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import dev.adkit.internal.AdLog
import dev.adkit.internal.Engine
import dev.adkit.internal.Main
import dev.adkit.internal.Network
import dev.adkit.internal.Skeleton
import dev.adkit.internal.findActivity
import dev.adkit.internal.now

enum class AdState { IDLE, LOADING, LOADED, FAILED, HIDDEN }

/**
 * Self-loading banner. XML: `<dev.adkit.AdBannerView app:adPlacement="home" />`.
 * Reserves its height, shows a skeleton, collapses on failure or premium, destroys itself on detach.
 * If remote config switches the placement to native, it hosts an [AdNativeView] instead.
 */
class AdBannerView @JvmOverloads constructor(
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

    /** Overrides the placement's banner type for this view only. */
    var bannerType: BannerType? = null
    var showSkeleton: Boolean = true
    var autoLoad: Boolean = true
    var onStateChanged: ((AdState) -> Unit)? = null

    var state: AdState = AdState.IDLE
        private set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    private var adView: AdView? = null
    private var skeleton: View? = null
    private var child: AdNativeView? = null
    private var loadToken = 0
    private var shared = false

    private val premiumListener: (Boolean) -> Unit = { premium ->
        if (premium) hide() else if (state == AdState.HIDDEN && isAttachedToWindow) reload()
    }
    private val networkListener: () -> Unit = { if (state == AdState.FAILED && isAttachedToWindow) reload() }
    private val consentListener: () -> Unit = networkListener

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.AdBannerView)
            placement = a.getString(R.styleable.AdBannerView_adPlacement)
            a.getString(R.styleable.AdBannerView_adBannerType)?.let { t -> bannerType = BannerType.from(t) }
            showSkeleton = a.getBoolean(R.styleable.AdBannerView_adShowSkeleton, true)
            autoLoad = a.getBoolean(R.styleable.AdBannerView_adAutoLoad, true)
            a.recycle()
        }
        if (isInEditMode) minimumHeight = (50 * resources.displayMetrics.density).toInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val engine = AdKit.engineOrNull ?: return
        engine.addPremiumListener(premiumListener)
        Network.onAvailable(networkListener)
        engine.addConsentGrantedListener(consentListener)
        if (autoLoad && state == AdState.IDLE) post { if (isAttachedToWindow && state == AdState.IDLE) load() }
    }

    override fun onDetachedFromWindow() {
        AdKit.engineOrNull?.removePremiumListener(premiumListener)
        AdKit.engineOrNull?.removeConsentGrantedListener(consentListener)
        Network.remove(networkListener)
        release()
        state = AdState.IDLE
        super.onDetachedFromWindow()
    }

    /** Loads (or reloads) the banner. Called automatically on attach. */
    fun load() {
        val engine = AdKit.engineOrNull ?: return AdLog.w("AdBannerView: AdKit.init not called")
        val name = placement ?: return AdLog.w("AdBannerView: no placement set")
        val p = engine.placement(name)
        if (p?.format == AdFormat.NATIVE) return hostNative(name)
        val reason = engine.gate(name, p, context, needSdk = false)
        if (reason != null) {
            engine.event(AdEventType.SKIPPED, name, AdFormat.BANNER, reason = reason)
            if (reason == SkipReason.OFFLINE || reason == SkipReason.NO_CONSENT) {
                state = AdState.FAILED
                if (p?.collapseOnFail != false) visibility = GONE
            } else hide()
            return
        }
        p!!
        visibility = VISIBLE
        val type = bannerType ?: p.bannerType
        val size = sizeFor(type, p)
        minimumHeight = size.getHeightInPixels(context)
        if (showSkeleton && skeleton == null) {
            skeleton = Skeleton.banner(context, size.getHeightInPixels(context)).also { addView(it) }
        }
        state = AdState.LOADING
        shared = p.shared
        val token = ++loadToken

        if (shared) {
            SharedBanners.take(name)?.let { attach(engine, p, it, token); return }
        }
        engine.whenReady { request(engine, p, type, size, engine.unitsFor(p), 0, token) }
    }

    private fun reload() {
        release()
        state = AdState.IDLE
        load()
    }

    private fun request(engine: Engine, p: Placement, type: BannerType, size: AdSize, units: List<String>, i: Int, token: Int) {
        if (token != loadToken || !isAttachedToWindow) return
        if (i >= units.size) {
            state = AdState.FAILED
            removeSkeleton()
            if (p.collapseOnFail) visibility = GONE
            return
        }
        val unit = units[i]
        val started = now()
        val gen = engine.generation
        engine.event(AdEventType.REQUESTED, p.name, AdFormat.BANNER, unit)
        val request = BannerAdRequest.Builder(unit, size).apply {
            when (type) {
                BannerType.COLLAPSIBLE_TOP -> setGoogleExtrasBundle(Bundle().apply { putString("collapsible", "top") })
                BannerType.COLLAPSIBLE_BOTTOM -> setGoogleExtrasBundle(Bundle().apply { putString("collapsible", "bottom") })
                else -> Unit
            }
        }.build()
        // The view loads off-screen behind the skeleton and is revealed once filled.
        val view = AdView(context)
        view.visibility = INVISIBLE
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        adView = view
        view.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) = Main.run {
                engine.event(AdEventType.LOADED, p.name, AdFormat.BANNER, unit, latencyMs = now() - started)
                if (token != loadToken || !isAttachedToWindow || gen != engine.generation) return@run
                listen(engine, p, ad)
                reveal(view)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = Main.run {
                engine.event(
                    AdEventType.LOAD_FAILED, p.name, AdFormat.BANNER, unit,
                    message = "${adError.code} ${adError.message}", latencyMs = now() - started,
                )
                if (token != loadToken) return@run
                removeView(view)
                runCatching { view.destroy() }
                if (adView === view) adView = null
                request(engine, p, type, size, units, i + 1, token)
            }
        })
    }

    /** Shared mode: move a banner that already lives in memory into this view. */
    private fun attach(engine: Engine, p: Placement, ad: BannerAd, token: Int) {
        if (token != loadToken) return
        val activity = context.findActivity() ?: run { runCatching { ad.destroy() }; return }
        val view = AdView(context)
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        view.registerBannerAd(ad, activity)
        adView = view
        listen(engine, p, ad)
        reveal(view)
    }

    private fun listen(engine: Engine, p: Placement, ad: BannerAd) {
        ad.adEventCallback = object : BannerAdEventCallback {
            override fun onAdImpression() = engine.event(AdEventType.IMPRESSION, p.name, AdFormat.BANNER)
            override fun onAdClicked() {
                engine.event(AdEventType.CLICKED, p.name, AdFormat.BANNER)
                engine.appOpenSuppressOnce()
            }
            override fun onAdPaid(value: AdValue) = engine.paid(p.name, AdFormat.BANNER, ad, value)
        }
    }

    private fun reveal(view: AdView) {
        removeSkeleton()
        view.visibility = VISIBLE
        state = AdState.LOADED
    }

    private fun hostNative(name: String) {
        if (child != null) return
        child = AdNativeView(context).also {
            it.placement = name
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun hide() {
        release()
        state = AdState.HIDDEN
        visibility = GONE
    }

    private fun release() {
        loadToken++
        removeSkeleton()
        child?.let { removeView(it) }
        child = null
        val v = adView ?: return
        adView = null
        val name = placement
        if (shared && name != null && AdKit.engineOrNull?.premium?.value != true) {
            // Still loading: nothing to keep, so stop the load.
            val ad = v.unregisterBannerAd()
            if (ad != null) SharedBanners.put(name, ad) else runCatching { v.destroy() }
        } else {
            runCatching { v.destroy() }
        }
        removeView(v)
    }

    private fun removeSkeleton() {
        skeleton?.let { Skeleton.stop(it); removeView(it) }
        skeleton = null
    }

    private fun sizeFor(type: BannerType, p: Placement): AdSize {
        val dm = resources.displayMetrics
        val widthPx = if (width > 0) width else dm.widthPixels
        val widthDp = (widthPx / dm.density).toInt()
        return when (type) {
            BannerType.ADAPTIVE, BannerType.COLLAPSIBLE_TOP, BannerType.COLLAPSIBLE_BOTTOM ->
                @Suppress("DEPRECATION") AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
            BannerType.LARGE_ADAPTIVE -> AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)
            BannerType.INLINE_ADAPTIVE ->
                if (p.inlineMaxHeightDp > 0) AdSize.getInlineAdaptiveBannerAdSize(widthDp, p.inlineMaxHeightDp)
                else AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, widthDp)
            BannerType.BANNER -> AdSize.BANNER
            BannerType.LARGE_BANNER -> AdSize.LARGE_BANNER
            BannerType.MEDIUM_RECTANGLE -> AdSize.MEDIUM_RECTANGLE
        }
    }
}

/** Banners kept alive between screens for placements with `shared = true`. */
internal object SharedBanners {
    private const val MAX_AGE_MS = 30 * 60_000L
    private val cache = HashMap<String, Pair<BannerAd, Long>>()

    fun put(name: String, ad: BannerAd) {
        cache.put(name, ad to now())?.first?.let { if (it !== ad) runCatching { it.destroy() } }
    }

    fun take(name: String): BannerAd? {
        val (ad, at) = cache.remove(name) ?: return null
        if (now() - at > MAX_AGE_MS) { runCatching { ad.destroy() }; return null }
        return ad
    }

    fun clear() {
        cache.values.forEach { runCatching { it.first.destroy() } }
        cache.clear()
    }
}
