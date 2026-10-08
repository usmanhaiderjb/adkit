package dev.adkit.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import dev.adkit.AdEvent
import dev.adkit.AdEventListener
import dev.adkit.AdEventType
import dev.adkit.AdFormat
import dev.adkit.AdKitBuilder
import dev.adkit.AdKitConsent
import dev.adkit.AdRevenueEvent
import dev.adkit.AdRevenueListener
import dev.adkit.Globals
import dev.adkit.Placement
import dev.adkit.SkipReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide state behind the [dev.adkit.AdKit] facade. */
internal class Engine(val app: Application, b: AdKitBuilder) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val testMode = b.testMode
    val requireNetwork = b.requireNetwork
    val loadingDialogFactory = b.loadingDialogFactory
    val appOpenDelayMs = b.appOpenDelayMs
    val appOpenCover = b.appOpenCover
    val remoteSource = b.remoteConfig
    val remoteFetchTimeoutMs = b.remoteFetchTimeoutMs
    val splashOptions = b.splash
    val appOpenExcluded = b.placements.values.flatMap { it.excluded }.toSet()

    private val appId: String = b.appId?.takeIf { it.isNotBlank() } ?: manifestAppId(app)
        ?: if (testMode) TestIds.APP_ID else error("AdKit: set appId or add com.google.android.gms.ads.APPLICATION_ID meta-data")
    private val testDeviceIds = b.testDeviceIds.toList()
    private val nativeValidator = b.nativeValidator

    val revenueListeners = CopyOnWriteArrayList<AdRevenueListener>(b.revenueListeners)
    val eventListeners = CopyOnWriteArrayList<AdEventListener>(b.eventListeners)

    val launchCount: Int = Prefs.bumpLaunchCount(app)
    val isFirstLaunch: Boolean get() = launchCount <= 1

    val config = ConfigStore(
        context = app,
        basePlacements = b.placements.mapValues { it.value.build() },
        baseGlobals = Globals(
            enabled = b.adsEnabled,
            globalGapSec = b.globalGapSec,
            afterAppOpenGapSec = b.afterAppOpenGapSec,
            sessionCap = b.sessionCap,
            splashFirst = b.splash.firstLaunch,
            splashReturning = b.splash.returning ?: b.splash.firstLaunch,
            splashTimeoutMs = b.splash.timeoutMs,
            appOpenResume = b.placements.values.firstOrNull { it.resume && it.isAppOpen }?.let { name(it) },
        ),
        testMode = testMode,
        assetName = b.configAsset,
    )

    val consent = AdKitConsent(app, b.consent, appId)
    val consentAutoGather = b.consent.autoGather

    private val _premium = MutableStateFlow(b.premiumInitial)
    val premium: StateFlow<Boolean> = _premium

    /** Bumped on every premium change so in-flight loads can be discarded. */
    @Volatile var generation = 0
        private set

    val frequency = Frequency(app)

    // ---- SDK init --------------------------------------------------------------------------

    @Volatile var sdkReady = false
        private set
    private var initStarted = false
    private val readyWaiters = mutableListOf<() -> Unit>()
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    private val premiumFlow = b.premiumFlow

    init {
        AdLog.enabled = b.logging ?: testMode
        consent.onCanRequestAds = { onConsentGranted() }
    }

    /** Called by AdKit.init once every manager exists; the first emission may arrive synchronously. */
    fun mirrorPremiumFlow() {
        premiumFlow?.let { flow -> scope.launch { flow.collect { setPremium(it) } } }
    }

    /** Initializes MobileAds once consent allows it. Ready = core init done (not adapters). */
    fun startSdk() {
        Main.run {
            if (initStarted || !consent.canRequestAds) return@run
            initStarted = true
            val started = now()
            scope.launch {
                withContext(Dispatchers.IO) {
                    val request = RequestConfiguration.Builder()
                        .apply { if (testDeviceIds.isNotEmpty()) setTestDeviceIds(testDeviceIds) }
                        .build()
                    val cfg = InitializationConfig.Builder(appId).setRequestConfiguration(request)
                        .apply { if (!nativeValidator) setNativeValidatorDisabled() }
                        .build()
                    runCatching {
                        MobileAds.initialize(app, cfg) { status ->
                            AdLog.d("Adapters initialized in ${status.totalLatency} ms")
                        }
                    }.onFailure { AdLog.e("MobileAds.initialize failed", it) }
                }
                // Mediation adapter init can take ~10 s; ads only need the core SDK.
                withTimeoutOrNull(15_000) {
                    while (!runCatching { MobileAds.isInitialized }.getOrDefault(false)) kotlinx.coroutines.delay(50)
                }
                AdLog.d("SDK ready in ${now() - started} ms")
                markReady()
            }
        }
    }

    private fun markReady() {
        if (sdkReady) return
        sdkReady = true
        val waiters = readyWaiters.toList()
        readyWaiters.clear()
        waiters.forEach { it() }
        readyListeners.forEach { it() }
    }

    /** Runs [block] on main once the SDK is ready. */
    fun whenReady(block: () -> Unit) {
        Main.run { if (sdkReady) block() else readyWaiters += block }
    }

    fun onReady(listener: () -> Unit) {
        readyListeners += listener
        if (sdkReady) Main.post(listener)
    }

    // ---- Premium ---------------------------------------------------------------------------

    private val premiumListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun setPremium(value: Boolean) {
        Main.run {
            if (_premium.value == value) return@run
            _premium.value = value
            generation++
            AdLog.d("Premium=$value")
            premiumListeners.forEach { it(value) }
        }
    }

    fun addPremiumListener(l: (Boolean) -> Unit) { premiumListeners += l }
    fun removePremiumListener(l: (Boolean) -> Unit) { premiumListeners -= l }

    // ---- Gate ------------------------------------------------------------------------------

    fun placement(name: String): Placement? =
        config[name]?.effective(isFirstLaunch)

    /**
     * Single ordered gate shared by every format. Null = allowed.
     * Frequency and "already showing" are checked by the full-screen managers.
     */
    fun gate(name: String, p: Placement?, context: Context?, needSdk: Boolean = true): SkipReason? {
        val reason = when {
            _premium.value -> SkipReason.PREMIUM
            !config.globals.enabled -> SkipReason.DISABLED
            p == null -> SkipReason.UNKNOWN_PLACEMENT
            !p.enabled -> SkipReason.PLACEMENT_DISABLED
            context != null && context.isImeContext() -> SkipReason.IME_CONTEXT
            !consent.canRequestAds -> SkipReason.NO_CONSENT
            needSdk && !sdkReady && !initStarted -> SkipReason.NOT_INITIALIZED
            requireNetwork && !Network.isOnline -> SkipReason.OFFLINE
            context is Activity && !context.isAlive -> SkipReason.ACTIVITY_GONE
            unitsFor(p).isEmpty() -> SkipReason.NO_UNIT
            else -> null
        }
        return reason
    }

    fun unitsFor(p: Placement): List<String> =
        if (testMode) listOf(TestIds.forPlacement(p)) else p.units.filter { it.isNotBlank() }

    // ---- Reporting -------------------------------------------------------------------------

    fun event(
        type: AdEventType, placement: String, format: AdFormat?, unit: String? = null,
        reason: SkipReason? = null, message: String? = null, latencyMs: Long? = null,
    ) {
        val e = AdEvent(type, placement, format, unit, reason, message, latencyMs)
        AdLog.d("[$placement] $type${reason?.let { " $it" } ?: ""}${message?.let { " $it" } ?: ""}${latencyMs?.let { " ${it}ms" } ?: ""}")
        if (eventListeners.isEmpty()) return
        Main.run { eventListeners.forEach { runCatching { it.onAdEvent(e) } } }
    }

    fun paid(placement: String, format: AdFormat, ad: Ad, value: AdValue) {
        val info = runCatching { ad.getResponseInfo() }.getOrNull()
        val e = AdRevenueEvent(
            placement = placement,
            format = format,
            adUnitId = runCatching { ad.adUnitId }.getOrDefault(""),
            valueMicros = value.valueMicros,
            currencyCode = value.currencyCode,
            precision = value.precisionType.name,
            adSourceName = info?.loadedAdSourceResponseInfo?.name,
            responseId = info?.responseId,
        )
        AdLog.d("[$placement] paid ${e.value} ${e.currencyCode} (${e.precision}, ${e.adSourceName})")
        Main.run { revenueListeners.forEach { runCatching { it.onAdRevenue(e) } } }
    }

    // ---- App open suppression --------------------------------------------------------------

    /** Next foreground return shows no app open (external intent, ad click, permission screen...). */
    @Volatile var appOpenSuppressedOnce = false
    private var pausedSinceSuppress = false
    /** Named holds; app open is off while any is present (Compose scope, long flows). */
    val appOpenHolds: MutableSet<Any> = java.util.Collections.synchronizedSet(mutableSetOf())

    fun appOpenSuppressOnce() {
        appOpenSuppressedOnce = true
        pausedSinceSuppress = false
    }

    /**
     * Translucent flows (runtime permission dialog) only pause the activity; the process never
     * leaves the foreground, so the flag would eat the next real resume. Clear it once the user
     * is back without a process restart (a restart consumes it first via ON_START).
     */
    fun onActivityPaused() { if (appOpenSuppressedOnce) pausedSinceSuppress = true }

    fun onActivityResumed() {
        if (appOpenSuppressedOnce && pausedSinceSuppress) {
            appOpenSuppressedOnce = false
            pausedSinceSuppress = false
        }
    }

    // ---- Consent granted -------------------------------------------------------------------

    private val consentGrantedListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addConsentGrantedListener(l: () -> Unit) { consentGrantedListeners += l }
    fun removeConsentGrantedListener(l: () -> Unit) { consentGrantedListeners -= l }

    private fun onConsentGranted() {
        startSdk()
        Main.run { consentGrantedListeners.forEach { it() } }
    }

    // ---- Remote config ---------------------------------------------------------------------

    @Volatile var remoteDone = remoteSource == null
        private set
    private val remoteWaiters = mutableListOf<() -> Unit>()

    fun fetchRemote() {
        val source = remoteSource ?: return
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { source.fetch() }.onFailure { AdLog.w("Remote fetch failed: ${it.message}") }.getOrNull()
            }
            config.applyRemote(json)
            remoteDone = true
            remoteWaiters.toList().also { remoteWaiters.clear() }.forEach { it() }
        }
    }

    fun whenRemoteDone(block: () -> Unit) {
        Main.run { if (remoteDone) block() else remoteWaiters += block }
    }

    private fun name(b: dev.adkit.PlacementBuilder): String =
        b.build().name

    private val dev.adkit.PlacementBuilder.isAppOpen: Boolean get() = build().format == AdFormat.APP_OPEN

    private fun manifestAppId(c: Context): String? = runCatching {
        val info = c.packageManager.getApplicationInfo(c.packageName, PackageManager.GET_META_DATA)
        info.metaData?.getString("com.google.android.gms.ads.APPLICATION_ID")
    }.getOrNull()
}
