package dev.adkit

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdInspectorError
import com.google.android.libraries.ads.mobile.sdk.common.OnAdInspectorClosedListener
import dev.adkit.internal.ActivityTracker
import dev.adkit.internal.AdLog
import dev.adkit.internal.AppOpenManager
import dev.adkit.internal.Engine
import dev.adkit.internal.FullScreenFlow
import dev.adkit.internal.FullScreenLoader
import dev.adkit.internal.FullScreenPresenter
import dev.adkit.internal.LoadingDialog
import dev.adkit.internal.Main
import dev.adkit.internal.NativePool
import dev.adkit.internal.Network
import dev.adkit.internal.Once
import dev.adkit.internal.SplashFlow
import dev.adkit.internal.TestIds
import dev.adkit.internal.isAlive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Entry point. Typical integration:
 * ```
 * AdKit.init(this) { testMode = BuildConfig.DEBUG; interstitial("home", "ca-app-pub-...") }
 * AdKit.splash(this) { goHome() }
 * AdKit.showInterstitial(this, "home") { openTool() }
 * <dev.adkit.AdBannerView app:adPlacement="home_banner" />
 * ```
 * Every callback runs on the main thread, exactly once.
 */
object AdKit {
    @Volatile private var engine: Engine? = null
    private lateinit var loader: FullScreenLoader
    private lateinit var flow: FullScreenFlow
    private lateinit var appOpenManager: AppOpenManager
    private lateinit var splashFlow: SplashFlow
    internal lateinit var nativePool: NativePool
        private set

    internal val engineOrNull: Engine? get() = engine
    private val e: Engine get() = engine ?: error("Call AdKit.init(application) { ... } first")

    val isInitialized: Boolean get() = engine != null

    /** Configure once in `Application.onCreate`. Later calls are ignored. */
    @JvmStatic
    fun init(app: Application, block: AdKitBuilder.() -> Unit = {}) {
        if (engine != null) return
        val builder = AdKitBuilder().apply(block)
        val eng = Engine(app, builder)
        engine = eng
        Network.init(app)
        app.registerActivityLifecycleCallbacks(ActivityTracker)
        ActivityTracker.onAnyPause = eng::onActivityPaused
        ActivityTracker.onAnyResume = eng::onActivityResumed
        eng.config.load()
        warnTestIdsInRelease(app, eng)

        loader = FullScreenLoader(eng)
        val presenter = FullScreenPresenter(eng, loader)
        flow = FullScreenFlow(eng, loader, presenter)
        nativePool = NativePool(eng)
        appOpenManager = AppOpenManager(eng, loader, presenter).also { m ->
            m.nativeReady = { name -> nativePool.available(name) > 0 }
            m.nativePreload = { name -> e.placement(name)?.let { nativePool.preload(it, 1) } }
            m.nativeShow = { activity, name -> showFullScreenNative(activity, name, force = true) }
            m.register()
        }
        splashFlow = SplashFlow(eng, loader, presenter, appOpenManager, ::preload)
        splashFlow.onFinished = { preloadAll() }

        eng.addPremiumListener { premium ->
            if (premium) {
                loader.clearAll()
                nativePool.clearAll()
                SharedBanners.clear()
                RetainStore.clear()
            } else preloadAll()
        }
        eng.mirrorPremiumFlow()
        eng.onReady { preloadAll() }
        eng.config.addListener { if (eng.sdkReady) Main.post { preloadAll() } }
        eng.fetchRemote()

        // Consent from a previous session lets the SDK start before any screen shows.
        eng.startSdk()
        if (eng.consentAutoGather) {
            ActivityTracker.onFirstResume = { activity ->
                if (!appOpenManager.splashActive && !eng.consent.gathered) eng.consent.gather(activity)
            }
        }
        AdLog.d("AdKit initialized (testMode=${eng.testMode}, launch #${eng.launchCount})")
    }

    /** A non-debuggable build with Google demo units earns nothing; say so loudly. */
    private fun warnTestIdsInRelease(app: Application, eng: Engine) {
        val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (eng.testMode || debuggable) return
        eng.config.placements.values
            .filter { p -> p.units.any(TestIds::isTestId) }
            .forEach { AdLog.e("Release build uses a Google TEST unit in placement '${it.name}' - it will never earn") }
    }

    // ---- Splash ------------------------------------------------------------------------------

    /** Consent, remote config, SDK init and the splash ad, then [onDone]. Never hangs. */
    @JvmStatic
    fun splash(activity: Activity, onDone: (AdResult) -> Unit) = splashFlow.run(activity, onDone)

    /** "Get started" button mode, step 1: prepare everything; [onReady] says if an ad is waiting. */
    @JvmStatic
    fun splashPrepare(activity: Activity, onReady: (adReady: Boolean) -> Unit) = splashFlow.prepare(activity, onReady)

    /** Step 2, on button tap: show the prepared ad (if any), then [onDone]. */
    @JvmStatic
    fun splashShow(activity: Activity, onDone: (AdResult) -> Unit) = splashFlow.show(activity, onDone)

    // ---- Full-screen -------------------------------------------------------------------------

    /**
     * Shows an interstitial (or rewarded interstitial) if rules allow, then [onDone].
     * [force] bypasses counters/cooldowns (splash-like "must show" moments), not premium/consent.
     */
    @JvmStatic
    @JvmOverloads
    fun showInterstitial(activity: Activity, placement: String, force: Boolean = false, onDone: (AdResult) -> Unit = {}) {
        flow.show(
            activity, placement, setOf(AdFormat.INTERSTITIAL, AdFormat.REWARDED_INTERSTITIAL, AdFormat.APP_OPEN),
            force = force, countFrequency = true,
        ) { o ->
            onDone(if (o.shown) AdResult.shown(placement) else AdResult.skipped(placement, o.reason ?: SkipReason.NOT_LOADED, o.message))
        }
    }

    suspend fun awaitInterstitial(activity: Activity, placement: String, force: Boolean = false): AdResult =
        suspendCancellableCoroutine { c -> showInterstitial(activity, placement, force) { if (c.isActive) c.resume(it) } }

    /**
     * Rewarded flow. Grant only when [RewardResult.earned]. Falls back to the placement's `fallback`
     * (rewarded interstitial or interstitial) when no rewarded ad is available.
     */
    @JvmStatic
    fun showRewarded(activity: Activity, placement: String, onResult: (RewardResult) -> Unit) {
        val done = Once(onResult)
        showRewardedInternal(activity, placement, placement, mutableSetOf(), done)
    }

    suspend fun awaitRewarded(activity: Activity, placement: String): RewardResult =
        suspendCancellableCoroutine { c -> showRewarded(activity, placement) { if (c.isActive) c.resume(it) } }

    private fun showRewardedInternal(
        activity: Activity, requested: String, name: String, visited: MutableSet<String>, done: Once<RewardResult>,
    ) {
        visited += name
        val p = e.placement(name)
        val isInterstitial = p?.format == AdFormat.INTERSTITIAL
        flow.show(
            activity, name, setOf(AdFormat.REWARDED, AdFormat.REWARDED_INTERSTITIAL, AdFormat.INTERSTITIAL),
            force = false, countFrequency = false,
        ) { o ->
            val noAd = !o.shown && o.reason in FALLBACK_REASONS
            val next = p?.fallback
            if (noAd && next != null && next !in visited && activity.isAlive) {
                showRewardedInternal(activity, requested, next, visited, done)
                return@show
            }
            val base = e.placement(requested)
            val earned = when {
                o.reward != null -> true
                // AdMob policy: a plain interstitial is not a rewarded format. Opt-in only.
                isInterstitial && o.shown -> base?.rewardOnFallback ?: false
                else -> false
            }
            done(
                RewardResult(
                    placement = requested, earned = earned, shown = o.shown,
                    rewardType = o.reward?.type, rewardAmount = o.reward?.amount ?: 0,
                    skipReason = o.reason, servedBy = name,
                ),
            )
        }
    }

    /** Native ad as its own screen; [onDone] fires when the user closes it (or no ad). */
    @JvmStatic
    @JvmOverloads
    fun showFullScreenNative(activity: Activity, placement: String, force: Boolean = false, onDone: (AdResult) -> Unit = {}) {
        val done = Once(onDone)
        Main.run {
            val p = e.placement(placement)
            val skip = { r: SkipReason ->
                e.event(AdEventType.SKIPPED, placement, AdFormat.NATIVE, reason = r)
                done(AdResult.skipped(placement, r))
            }
            e.gate(placement, p, activity)?.let { skip(it); return@run }
            p!!
            if (p.format != AdFormat.NATIVE) { skip(SkipReason.WRONG_FORMAT); return@run }
            if (e.frequency.showing) { skip(SkipReason.ALREADY_SHOWING); return@run }
            if (!e.frequency.check(p, e.config.globals, force)) { skip(SkipReason.FREQUENCY); return@run }

            val dialog = if (nativePool.available(placement) == 0 && p.loadingDialog) LoadingDialog.show(activity, e.loadingDialogFactory) else null
            var finished = false
            val timeout = Runnable {
                if (!finished) { finished = true; LoadingDialog.dismiss(dialog); skip(SkipReason.TIMEOUT) }
            }
            Main.delay(p.timeoutMs, timeout)
            val waiter = nativePool.request(p) { ad ->
                if (finished) { ad?.let { nativePool.offer(p, it) }; return@request }
                finished = true
                Main.cancel(timeout)
                LoadingDialog.dismiss(dialog)
                when {
                    ad == null -> skip(SkipReason.LOAD_FAILED)
                    !ActivityTracker.isResumed(activity) -> { nativePool.offer(p, ad); skip(SkipReason.ACTIVITY_GONE) }
                    e.frequency.showing -> { nativePool.offer(p, ad); skip(SkipReason.ALREADY_SHOWING) }
                    else -> {
                        e.frequency.showing = true
                        val token = FullScreenNativeActivity.register(
                            FullScreenNativeActivity.Handoff(p, ad) { shown ->
                                done(if (shown) AdResult.shown(placement) else AdResult.skipped(placement, SkipReason.SHOW_FAILED))
                            },
                        )
                        activity.startActivity(
                            Intent(activity, FullScreenNativeActivity::class.java).putExtra(FullScreenNativeActivity.EXTRA_TOKEN, token),
                        )
                    }
                }
            }
            if (finished) waiter.cancel()
        }
    }

    /**
     * Exit confirmation with a native ad. [onResult] gets true when the user chose to exit.
     * Shows without the ad for premium users.
     */
    @JvmStatic
    fun showExitDialog(activity: Activity, placement: String, onResult: (exit: Boolean) -> Unit) {
        val done = Once(onResult)
        if (!activity.isAlive) { done(false); return }
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(LayoutInflater.from(activity).inflate(R.layout.adkit_exit_dialog, null))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Covers back, outside tap and the activity being destroyed under the dialog.
            setOnDismissListener { done(false) }
        }
        dialog.findViewById<AdNativeView>(R.id.adkit_exit_native).placement = placement
        dialog.findViewById<TextView>(R.id.adkit_exit_yes).setOnClickListener { done(true); dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.adkit_exit_no).setOnClickListener { done(false); dialog.dismiss() }
        ActivityTracker.onDestroyed(activity) { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    // ---- Preload / state ---------------------------------------------------------------------

    /** Start loading these placements now (any format). */
    @JvmStatic
    fun preload(vararg placements: String) = placements.forEach(::preload)

    private fun preload(name: String) {
        val p = e.placement(name) ?: return AdLog.w("preload: unknown placement '$name'")
        when (p.format) {
            AdFormat.NATIVE -> nativePool.preload(p)
            AdFormat.BANNER -> Unit
            else -> loader.load(p)
        }
    }

    private fun preloadAll() {
        val eng = engine ?: return
        if (eng.premium.value) return
        // The splash ad gets the network to itself; everything else loads once it is done.
        if (appOpenManager.splashActive) return
        eng.config.placements.values.forEach { base ->
            val p = eng.placement(base.name) ?: return@forEach
            when {
                p.format == AdFormat.NATIVE && p.preloadCount > 0 -> nativePool.preload(p)
                p.format.isFullScreen && p.preload && p.format != AdFormat.APP_OPEN -> loader.preload(p)
                p.format == AdFormat.APP_OPEN && p.name == eng.config.globals.appOpenResume -> {
                    if (p.preload) loader.preload(p)
                    p.nativeFallback?.let { fb -> eng.placement(fb)?.let { nativePool.preload(it, 1) } }
                }
            }
        }
    }

    /** True when a full-screen ad for [placement] can show instantly. */
    @JvmStatic
    fun isReady(placement: String): Boolean {
        val p = e.placement(placement) ?: return false
        return if (p.format == AdFormat.NATIVE) nativePool.available(placement) > 0 else loader.peek(p) != null
    }

    /** Merged config for [name] (code + asset + remote). Use `extras` for app-specific remote values. */
    @JvmStatic
    fun placement(name: String): Placement? = e.placement(name)

    /** Top-level `extras` object of the remote config. */
    val remoteExtras: Map<String, Any?> get() = e.config.globals.extras

    val isFirstLaunch: Boolean get() = e.isFirstLaunch
    val launchCount: Int get() = e.launchCount

    // ---- Inline helpers ---------------------------------------------------------------------

    /** Adds a banner to [container] (replacing its children) and returns it. */
    @JvmStatic
    fun banner(container: ViewGroup, placement: String): AdBannerView =
        AdBannerView(container.context).also {
            it.placement = placement
            container.removeAllViews()
            container.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** Adds a native ad to [container] (replacing its children) and returns it. */
    @JvmStatic
    @JvmOverloads
    fun native(container: ViewGroup, placement: String, style: NativeStyle? = null): AdNativeView =
        AdNativeView(container.context).also {
            it.placement = placement
            it.style = style
            container.removeAllViews()
            container.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    // ---- Premium -----------------------------------------------------------------------------

    /** Turns every ad off (and back on). Live banners/natives collapse immediately. */
    @JvmStatic
    fun setPremium(premium: Boolean) = e.setPremium(premium)

    val isPremium: Boolean get() = e.premium.value
    val premium: StateFlow<Boolean> get() = e.premium

    // ---- Consent -----------------------------------------------------------------------------

    val consent: AdKitConsent get() = e.consent

    // ---- App open ----------------------------------------------------------------------------

    /** The next return to foreground shows no app open ad. Call before launching external flows. */
    @JvmStatic
    fun suppressNextAppOpen() = e.appOpenSuppressOnce()

    /** Turn resume app open off while [owner] holds it (e.g. a long camera/picker flow). */
    @JvmStatic
    fun holdAppOpen(owner: Any) { e.appOpenHolds += owner }

    @JvmStatic
    fun releaseAppOpen(owner: Any) { e.appOpenHolds -= owner }

    // ---- Listeners / debug -------------------------------------------------------------------

    @JvmStatic
    fun addRevenueListener(listener: AdRevenueListener) { e.revenueListeners += listener }

    @JvmStatic
    fun addEventListener(listener: AdEventListener) { e.eventListeners += listener }

    /** Opens Google's Ad Inspector (test devices / test mode only). */
    @JvmStatic
    fun openAdInspector() {
        MobileAds.openAdInspector(object : OnAdInspectorClosedListener {
            override fun onAdInspectorClosed(error: AdInspectorError?) {
                error?.let { AdLog.w("Ad Inspector: ${it.message}") }
            }
        })
    }

    private val FALLBACK_REASONS = setOf(
        SkipReason.NOT_LOADED, SkipReason.TIMEOUT, SkipReason.LOAD_FAILED, SkipReason.SHOW_FAILED,
        SkipReason.NO_UNIT, SkipReason.FREQUENCY, SkipReason.WRONG_FORMAT,
    )
}

/** `startActivity` that will not trigger an app open ad on return (share, settings, picker...). */
fun Activity.startActivityNoAppOpen(intent: Intent) {
    AdKit.suppressNextAppOpen()
    startActivity(intent)
}

/** `launch` that will not trigger an app open ad on return (permission, camera, picker...). */
fun <I> ActivityResultLauncher<I>.launchNoAppOpen(input: I) {
    AdKit.suppressNextAppOpen()
    launch(input)
}
