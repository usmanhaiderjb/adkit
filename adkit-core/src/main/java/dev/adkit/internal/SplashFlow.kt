package dev.adkit.internal

import android.app.Activity
import dev.adkit.AdEventType
import dev.adkit.AdFormat
import dev.adkit.AdResult
import dev.adkit.Placement
import dev.adkit.SkipReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * consent -> remote config -> SDK init -> splash ad -> continue.
 * The budget only counts while the splash is resumed and the consent form is not up,
 * so backgrounding or reading the form never burns the timeout.
 */
internal class SplashFlow(
    private val engine: Engine,
    private val loader: FullScreenLoader,
    private val presenter: FullScreenPresenter,
    private val appOpen: AppOpenManager,
    private val preload: (String) -> Unit,
) {
    private var job: Job? = null
    private var ticker: Runnable? = null
    private var ready: Placement? = null
    private var readyReason: SkipReason? = null
    private var prepared = false
    private var onReady: ((Boolean) -> Unit)? = null

    /** Fired when the splash ends (shown, skipped or abandoned); deferred preloads start here. */
    var onFinished: (() -> Unit)? = null

    private val allowed = setOf(AdFormat.INTERSTITIAL, AdFormat.APP_OPEN, AdFormat.REWARDED_INTERSTITIAL)

    /** Phase 1: get everything ready. [onReady] gets true when a splash ad is loaded. Fires once. */
    fun prepare(activity: Activity, onReady: (Boolean) -> Unit) {
        Main.run {
            cancel()
            appOpen.splashActive = true
            prepared = false
            ready = null
            readyReason = null
            val once = Once(onReady)
            this.onReady = { once(it) }
            val startedAt = now()
            val minEnd = startedAt + engine.splashOptions.minDurationMs
            ActivityTracker.onDestroyed(activity) { cancel() }

            var elapsed = 0L
            val budget = engine.config.globals.splashTimeoutMs
            val tick = object : Runnable {
                override fun run() {
                    if (ActivityTracker.isResumed(activity) && !engine.consent.formShowing) elapsed += TICK
                    if (elapsed >= budget) {
                        AdLog.d("Splash budget used up ($budget ms)")
                        finishPrepare(null, SkipReason.TIMEOUT, minEnd)
                    } else {
                        Main.delay(TICK, this)
                    }
                }
            }
            ticker = tick
            Main.delay(TICK, tick)

            job = engine.scope.launch {
                if (engine.premium.value) return@launch finishPrepare(null, SkipReason.PREMIUM, minEnd)
                if (!engine.consent.gathered) awaitConsent(activity)
                if (!engine.consent.canRequestAds) return@launch finishPrepare(null, SkipReason.NO_CONSENT, minEnd)
                engine.startSdk()
                withTimeoutOrNull(engine.remoteFetchTimeoutMs) { awaitRemote() }
                awaitSdk()
                engine.splashOptions.preload.forEach(preload)

                val name = if (engine.isFirstLaunch) engine.config.globals.splashFirst else engine.config.globals.splashReturning
                if (name.isNullOrBlank() || name == "none") return@launch finishPrepare(null, SkipReason.PLACEMENT_DISABLED, minEnd)
                val p = engine.placement(name)
                engine.gate(name, p, activity)?.let { return@launch finishPrepare(null, it, minEnd) }
                p!!
                if (p.format !in allowed) return@launch finishPrepare(null, SkipReason.WRONG_FORMAT, minEnd)
                val ok = awaitLoad(p)
                finishPrepare(if (ok) p else null, if (ok) null else SkipReason.LOAD_FAILED, minEnd)
            }
        }
    }

    /** Phase 2: show the prepared ad (if any), then [onDone]. Waits for the activity to be resumed. */
    fun show(activity: Activity, onDone: (AdResult) -> Unit) {
        val done = Once<AdResult> { appOpen.splashActive = false; onFinished?.invoke(); onDone(it) }
        Main.run {
            val p = ready
            ready = null
            if (p == null) {
                val name = engine.config.globals.splashFirst ?: "splash"
                done(AdResult.skipped(name, readyReason ?: SkipReason.NOT_LOADED))
                return@run
            }
            if (!activity.isAlive) {
                done(AdResult.skipped(p.name, SkipReason.ACTIVITY_GONE))
                return@run
            }
            ActivityTracker.whenResumed(activity) { resumed ->
                if (engine.frequency.showing) {
                    done(AdResult.skipped(p.name, SkipReason.ALREADY_SHOWING)); return@whenResumed
                }
                val ad = loader.take(p) ?: run {
                    done(AdResult.skipped(p.name, SkipReason.NOT_LOADED)); return@whenResumed
                }
                presenter.present(resumed, p, ad, null) { shown, msg ->
                    done(if (shown) AdResult.shown(p.name) else AdResult.skipped(p.name, SkipReason.SHOW_FAILED, msg))
                }
            }
            ActivityTracker.onDestroyed(activity) { appOpen.splashActive = false }
        }
    }

    fun run(activity: Activity, onDone: (AdResult) -> Unit) {
        prepare(activity) { show(activity, onDone) }
    }

    private fun finishPrepare(p: Placement?, reason: SkipReason?, minEnd: Long) {
        Main.run {
            if (prepared) return@run
            prepared = true
            ticker?.let { Main.cancel(it) }
            ticker = null
            job?.cancel()
            ready = p
            readyReason = reason
            if (p == null && reason != null) {
                engine.event(AdEventType.SKIPPED, "splash", null, reason = reason)
            }
            val wait = (minEnd - now()).coerceAtLeast(0)
            val cb = onReady
            onReady = null
            Main.delay(wait, Runnable { cb?.invoke(p != null) })
        }
    }

    private fun cancel() {
        if (appOpen.splashActive) {
            appOpen.splashActive = false
            onFinished?.invoke()
        }
        job?.cancel()
        job = null
        ticker?.let { Main.cancel(it) }
        ticker = null
        onReady = null
    }

    private suspend fun awaitConsent(activity: Activity) = suspendCancellableCoroutine { c ->
        engine.consent.gather(activity) { if (c.isActive) c.resume(Unit) }
    }

    private suspend fun awaitRemote() = suspendCancellableCoroutine { c ->
        engine.whenRemoteDone { if (c.isActive) c.resume(Unit) }
    }

    private suspend fun awaitSdk() = suspendCancellableCoroutine { c ->
        engine.whenReady { if (c.isActive) c.resume(Unit) }
    }

    private suspend fun awaitLoad(p: Placement): Boolean = suspendCancellableCoroutine { c ->
        loader.load(p) { ok -> if (c.isActive) c.resume(ok) }
    }

    companion object {
        private const val TICK = 100L
    }
}
