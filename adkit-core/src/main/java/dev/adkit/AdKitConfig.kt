package dev.adkit

import android.app.Activity
import android.app.Dialog
import androidx.annotation.LayoutRes
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

@DslMarker
annotation class AdKitDsl

/** UMP debug geography. */
enum class DebugGeography { DISABLED, EEA, NOT_EEA, REGULATED_US_STATE, OTHER }

@AdKitDsl
class ConsentOptions internal constructor() {
    /** Run the UMP flow. Turn off only if you handle consent elsewhere. */
    var enabled: Boolean = true
    /** Gather consent automatically on the first resumed activity when [AdKit.splash] is not used. */
    var autoGather: Boolean = true
    var tagForUnderAgeOfConsent: Boolean = false
    var debugGeography: DebugGeography = DebugGeography.DISABLED
    val testDeviceHashedIds: MutableList<String> = mutableListOf()
}

@AdKitDsl
class SplashOptions internal constructor() {
    /** Placement shown on the first ever launch (interstitial, app open or rewarded interstitial). */
    var firstLaunch: String? = null
    /** Placement shown on later launches. Defaults to [firstLaunch]. */
    var returning: String? = null
    /** Total foreground budget for consent + config + init + ad load. Paused while consent form is up. */
    var timeoutMs: Long = 12_000
    /** Keep the splash on screen at least this long, even for premium/offline users. */
    var minDurationMs: Long = 0
    /** Placements to start loading during splash (language native, onboarding natives, ...). */
    val preload: MutableList<String> = mutableListOf()
}

/** Mutable builder for one placement. Fields not relevant to the format are ignored. */
@AdKitDsl
class PlacementBuilder internal constructor(
    private val name: String,
    private val format: AdFormat,
    private val units: List<String>,
) {
    var enabled = true
    var preload = true
    var loadOnDemand = true
    var timeoutMs = 4_500L
    var loadingDialog = true
    var everyN = 1
    var firstShows = true
    var cooldownSec = 0
    var sessionCap = 0
    var firstSession: FirstSessionRules? = null
    var fallback: String? = null
    var rewardOnFallback = false
    var expiryHours = 4
    var nativeFallback: String? = null
    var skipDelaySec = 3
    var type = BannerType.ADAPTIVE
    var inlineMaxHeightDp = 0
    var shared = false
    var style = NativeStyle.MEDIUM
    var preloadCount = 0
    @LayoutRes var layout = 0
    var ctaColor: Int? = null
    var collapseOnFail = true

    init {
        // App open waits for a cached ad; rewarded is user-initiated so it may wait longer.
        if (format == AdFormat.APP_OPEN) loadOnDemand = false
        if (format == AdFormat.REWARDED || format == AdFormat.REWARDED_INTERSTITIAL) timeoutMs = 8_000
    }

    /** App open only: use this placement for resume (foreground) ads. */
    var resume = true
    internal val excluded = mutableListOf<KClass<out Activity>>()

    /** App open only: never show over these activities. */
    fun exclude(vararg activities: KClass<out Activity>) {
        excluded += activities
    }

    internal fun build() = Placement(
        name = name, format = format, units = units, enabled = enabled,
        preload = preload, loadOnDemand = loadOnDemand, timeoutMs = timeoutMs,
        loadingDialog = loadingDialog, everyN = everyN.coerceAtLeast(1), firstShows = firstShows,
        cooldownSec = cooldownSec, sessionCap = sessionCap, firstSession = firstSession,
        fallback = fallback, rewardOnFallback = rewardOnFallback, expiryHours = expiryHours,
        nativeFallback = nativeFallback,
        skipDelaySec = skipDelaySec, bannerType = type, inlineMaxHeightDp = inlineMaxHeightDp,
        shared = shared, nativeStyle = style, preloadCount = preloadCount, layoutRes = layout,
        ctaColor = ctaColor, collapseOnFail = collapseOnFail,
    )
}

/** Receiver of `AdKit.init(app) { ... }`. */
@AdKitDsl
class AdKitBuilder internal constructor() {
    /** AdMob app id. Falls back to manifest meta-data `com.google.android.gms.ads.APPLICATION_ID`. */
    var appId: String? = null
    /** Replace every unit id with Google's test ids. Pass `BuildConfig.DEBUG`. */
    var testMode = false
    /** Devices that receive test ads with real unit ids (mediation testing). */
    val testDeviceIds: MutableList<String> = mutableListOf()
    /** Logcat output under tag "AdKit". Defaults to [testMode]. */
    var logging: Boolean? = null
    /** Global kill switch (remote: "enabled"). */
    var adsEnabled = true
    /** Min seconds between any two full-screen ads (remote: "global_gap_s"). */
    var globalGapSec = 0
    /** Min seconds between an app open ad and the next interstitial (remote: "after_app_open_gap_s"). */
    var afterAppOpenGapSec = 0
    /** Max full-screen ads per session across placements. 0 = unlimited (remote: "session_cap"). */
    var sessionCap = 0
    /** Skip ads when the device is offline instead of attempting a load. */
    var requireNetwork = true
    /** Bundled default config in `assets/`. Missing file is fine. */
    var configAsset: String? = "adkit_config.json"
    var remoteConfig: AdsConfigSource? = null
    /** Max wait for remote config inside the splash flow. */
    var remoteFetchTimeoutMs = 3_000L
    /** Replace the built-in "Loading ad" dialog. */
    var loadingDialogFactory: ((Activity) -> Dialog)? = null
    /** App open: delay between the app returning to foreground and the ad appearing. */
    var appOpenDelayMs = 350L
    /** Google's on-device native validator popup (debug aid). Set false to hide it in test builds. */
    var nativeValidator = true
    /** App open: draw a cover over the screen during [appOpenDelayMs]. */
    var appOpenCover = true

    internal val consent = ConsentOptions()
    internal val splash = SplashOptions()
    internal val placements = linkedMapOf<String, PlacementBuilder>()
    internal val revenueListeners = mutableListOf<AdRevenueListener>()
    internal val eventListeners = mutableListOf<AdEventListener>()
    internal var premiumInitial = false
    internal var premiumFlow: Flow<Boolean>? = null

    fun consent(block: ConsentOptions.() -> Unit) = consent.block()
    fun splash(block: SplashOptions.() -> Unit) = splash.block()

    /** Initial premium state (e.g. cached purchase flag). Update later with [AdKit.setPremium]. */
    fun premium(isPremium: Boolean) { premiumInitial = isPremium }

    /** Mirror a billing flow; every emission updates the premium gate. */
    fun premium(flow: Flow<Boolean>) { premiumFlow = flow }

    fun revenue(vararg listeners: AdRevenueListener) { revenueListeners += listeners }
    fun events(vararg listeners: AdEventListener) { eventListeners += listeners }

    fun interstitial(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.INTERSTITIAL, units, block)

    fun rewarded(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.REWARDED, units, block)

    fun rewardedInterstitial(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.REWARDED_INTERSTITIAL, units, block)

    fun appOpen(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.APP_OPEN, units, block)

    fun banner(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.BANNER, units, block)

    fun native(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.NATIVE, units, block)

    /** Native shown as its own screen ([AdKit.showFullScreenNative]) or onboarding page. */
    fun fullScreenNative(name: String, vararg units: String, block: PlacementBuilder.() -> Unit = {}) =
        add(name, AdFormat.NATIVE, units) { style = NativeStyle.FULLSCREEN; block() }

    private fun add(name: String, format: AdFormat, units: Array<out String>, block: PlacementBuilder.() -> Unit) {
        require(name.isNotBlank()) { "Placement name must not be blank" }
        placements[name] = PlacementBuilder(name, format, units.toList()).apply(block)
    }
}

/** Remote/global knobs, overridable from JSON. */
internal data class Globals(
    val enabled: Boolean,
    val globalGapSec: Int,
    val afterAppOpenGapSec: Int,
    val sessionCap: Int,
    val splashFirst: String?,
    val splashReturning: String?,
    val splashTimeoutMs: Long,
    val appOpenResume: String?,
    val extras: Map<String, Any?> = emptyMap(),
)
