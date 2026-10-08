package dev.adkit.internal

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import dev.adkit.AdEventType
import dev.adkit.AdFormat
import dev.adkit.Placement
import dev.adkit.SkipReason

internal class LoadedAd(val ad: Ad, val format: AdFormat, val loadedAt: Long, val generation: Int)

/** One callback object usable for every full-screen ad type. Hops to main before anything else. */
internal class FullScreenEvents(
    private val onShowed: () -> Unit,
    private val onDismissed: () -> Unit,
    private val onFailedToShow: (String) -> Unit,
    private val onImpression: () -> Unit,
    private val onClicked: () -> Unit,
    private val onPaid: (AdValue) -> Unit,
) : InterstitialAdEventCallback, RewardedAdEventCallback, RewardedInterstitialAdEventCallback, AppOpenAdEventCallback {
    override fun onAdShowedFullScreenContent() = Main.run(onShowed)
    override fun onAdDismissedFullScreenContent() = Main.run(onDismissed)
    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
        Main.run { onFailedToShow("${fullScreenContentError.code} ${fullScreenContentError.message}") }
    override fun onAdImpression() = Main.run(onImpression)
    override fun onAdClicked() = Main.run(onClicked)
    override fun onAdPaid(value: AdValue) = onPaid(value)
}

/**
 * Per-placement cache for interstitial, rewarded, rewarded interstitial and app open.
 * Waterfall over unit ids, expiry, premium generation check, preload retry with backoff.
 */
internal class FullScreenLoader(private val engine: Engine) {
    private class Slot {
        var ad: LoadedAd? = null
        var loading = false
        var failures = 0
        var retry: Runnable? = null
        val waiters = mutableListOf<(Boolean) -> Unit>()
    }

    private val slots = HashMap<String, Slot>()

    private fun slot(name: String) = slots.getOrPut(name) { Slot() }

    /** Cached, fresh ad for [p], or null. Stale ads are destroyed. */
    fun peek(p: Placement): LoadedAd? {
        val s = slots[p.name] ?: return null
        val a = s.ad ?: return null
        val maxAge = if (p.format == AdFormat.APP_OPEN) p.expiryHours * 3_600_000L else 55 * 60_000L
        if (a.generation != engine.generation || now() - a.loadedAt > maxAge) {
            AdLog.d("[${p.name}] cached ad expired")
            destroy(a)
            s.ad = null
            return null
        }
        return a
    }

    fun take(p: Placement): LoadedAd? = peek(p)?.also { slots[p.name]?.ad = null }

    fun putBack(p: Placement, ad: LoadedAd) {
        val s = slot(p.name)
        if (s.ad == null) s.ad = ad else destroy(ad)
    }

    fun isLoading(name: String) = slots[name]?.loading == true

    /** Starts a load unless one is cached or running. [waiter] gets true when an ad is in the slot. */
    fun load(p: Placement, waiter: ((Boolean) -> Unit)? = null) {
        Main.run {
            val s = slot(p.name)
            if (peek(p) != null) { waiter?.invoke(true); return@run }
            waiter?.let { s.waiters += it }
            if (s.loading) return@run
            if (engine.gate(p.name, p, null) != null) {
                finish(p, s, false); return@run
            }
            s.loading = true
            s.retry?.let { Main.cancel(it) }
            s.retry = null
            engine.whenReady { loadUnits(p, s, engine.unitsFor(p), 0, engine.generation) }
        }
    }

    fun preload(p: Placement) {
        if (p.preload) load(p)
    }

    fun clearAll() {
        Main.run {
            slots.values.forEach { s ->
                s.ad?.let(::destroy)
                s.ad = null
                s.retry?.let { Main.cancel(it) }
                s.retry = null
            }
        }
    }

    private fun loadUnits(p: Placement, s: Slot, units: List<String>, index: Int, gen: Int) {
        if (index >= units.size) {
            s.failures++
            scheduleRetry(p, s)
            finish(p, s, false)
            return
        }
        val unit = units[index]
        val started = now()
        engine.event(AdEventType.REQUESTED, p.name, p.format, unit)
        requestOne(p.format, unit) { ad, error ->
            Main.run {
                if (gen != engine.generation) {
                    ad?.let { runCatching { it.destroy() } }
                    finish(p, s, false)
                    return@run
                }
                if (ad != null) {
                    engine.event(AdEventType.LOADED, p.name, p.format, unit, latencyMs = now() - started)
                    s.ad = LoadedAd(ad, p.format, now(), gen)
                    s.failures = 0
                    finish(p, s, true)
                } else {
                    engine.event(
                        AdEventType.LOAD_FAILED, p.name, p.format, unit,
                        message = error?.let { "${it.code} ${it.message}" }, latencyMs = now() - started,
                    )
                    loadUnits(p, s, units, index + 1, gen)
                }
            }
        }
    }

    private fun finish(p: Placement, s: Slot, ok: Boolean) {
        s.loading = false
        val w = s.waiters.toList()
        s.waiters.clear()
        w.forEach { it(ok) }
    }

    private fun scheduleRetry(p: Placement, s: Slot) {
        if (!p.preload || s.failures > 3) return
        val delayMs = longArrayOf(10_000, 30_000, 60_000).getOrElse(s.failures - 1) { 60_000 }
        val r = Runnable { s.retry = null; load(p) }
        s.retry = r
        Main.delay(delayMs, r)
    }

    private fun requestOne(format: AdFormat, unit: String, cb: (Ad?, LoadAdError?) -> Unit) {
        val request = AdRequest.Builder(unit).build()
        runCatching {
            when (format) {
                AdFormat.INTERSTITIAL -> InterstitialAd.load(request, callback(cb))
                AdFormat.REWARDED -> RewardedAd.load(request, callback(cb))
                AdFormat.REWARDED_INTERSTITIAL -> RewardedInterstitialAd.load(request, callback(cb))
                AdFormat.APP_OPEN -> AppOpenAd.load(request, callback(cb))
                else -> error("Not a full-screen format: $format")
            }
        }.onFailure {
            AdLog.e("Load threw for $unit", it)
            cb(null, null)
        }
    }

    private fun <T : Ad> callback(cb: (Ad?, LoadAdError?) -> Unit) = object : AdLoadCallback<T> {
        override fun onAdLoaded(ad: T) = cb(ad, null)
        override fun onAdFailedToLoad(adError: LoadAdError) = cb(null, adError)
    }

    private fun destroy(a: LoadedAd) = runCatching { a.ad.destroy() }
}

/** Shows a loaded full-screen ad and reports exactly one close. */
internal class FullScreenPresenter(private val engine: Engine, private val loader: FullScreenLoader) {

    /**
     * [onClosed] gets (shown, failureMessage). [onReward] fires before [onClosed] when earned.
     */
    fun present(
        activity: Activity,
        p: Placement,
        loaded: LoadedAd,
        onReward: ((RewardItem) -> Unit)?,
        onClosed: (Boolean, String?) -> Unit,
    ) {
        val done = Once<Pair<Boolean, String?>> { (shown, msg) -> onClosed(shown, msg) }
        val f = engine.frequency
        var displayed = false
        f.showing = true

        val events = FullScreenEvents(
            onShowed = {
                displayed = true
                f.recordShown(p)
                engine.event(AdEventType.SHOWN, p.name, p.format)
            },
            onDismissed = {
                f.showing = false
                f.lastDismissAt = now()
                engine.event(AdEventType.DISMISSED, p.name, p.format)
                runCatching { loaded.ad.destroy() }
                done(true to null)
                loader.preload(p)
            },
            onFailedToShow = { msg ->
                f.showing = false
                engine.event(AdEventType.SHOW_FAILED, p.name, p.format, message = msg)
                runCatching { loaded.ad.destroy() }
                done((displayed) to msg)
                loader.preload(p)
            },
            onImpression = { engine.event(AdEventType.IMPRESSION, p.name, p.format) },
            onClicked = {
                engine.event(AdEventType.CLICKED, p.name, p.format)
                // The click opens a browser/store; do not greet the user with an app open on return.
                engine.appOpenSuppressOnce()
            },
            onPaid = { engine.paid(p.name, p.format, loaded.ad, it) },
        )

        val reward = object : OnUserEarnedRewardListener {
            override fun onUserEarnedReward(reward: RewardItem) = Main.run { onReward?.invoke(reward) }
        }
        runCatching {
            when (val ad = loaded.ad) {
                is InterstitialAd -> { ad.adEventCallback = events; ad.show(activity) }
                is RewardedAd -> { ad.adEventCallback = events; ad.show(activity, reward) }
                is RewardedInterstitialAd -> { ad.adEventCallback = events; ad.show(activity, reward) }
                is AppOpenAd -> { ad.adEventCallback = events; ad.show(activity) }
                else -> error("Unsupported ad ${ad.javaClass.name}")
            }
        }.onFailure {
            f.showing = false
            runCatching { loaded.ad.destroy() }
            engine.event(AdEventType.SHOW_FAILED, p.name, p.format, message = it.message)
            done(false to it.message)
            return
        }

        // Watchdog: if the SDK never reports showed/failed (paused activity, mediation bug),
        // release the caller and the "showing" flag instead of blocking app open forever.
        Main.delay(SHOW_WATCHDOG_MS) {
            if (!displayed && !done.isDone) {
                f.showing = false
                engine.event(AdEventType.SHOW_FAILED, p.name, p.format, message = "no show callback")
                done(false to "no show callback")
            }
        }
    }

    private companion object {
        const val SHOW_WATCHDOG_MS = 6_000L
    }
}

/** Show orchestration shared by interstitial / rewarded / rewarded interstitial / app open. */
internal class FullScreenFlow(
    private val engine: Engine,
    private val loader: FullScreenLoader,
    private val presenter: FullScreenPresenter,
) {
    class Outcome(val shown: Boolean, val reason: SkipReason?, val message: String?, val reward: RewardItem?)

    fun show(
        activity: Activity,
        name: String,
        allowed: Set<AdFormat>,
        force: Boolean,
        countFrequency: Boolean,
        onDone: (Outcome) -> Unit,
    ) {
        val done = Once(onDone)
        Main.run {
            val p = engine.placement(name)
            val skip = { r: SkipReason, msg: String? ->
                engine.event(AdEventType.SKIPPED, name, p?.format, reason = r, message = msg)
                done(Outcome(false, r, msg, null))
            }
            engine.gate(name, p, activity)?.let { skip(it, null); return@run }
            p!!
            if (p.format !in allowed) { skip(SkipReason.WRONG_FORMAT, p.format.key); return@run }
            if (engine.frequency.showing) { skip(SkipReason.ALREADY_SHOWING, null); return@run }
            val freqOk = if (countFrequency) engine.frequency.check(p, engine.config.globals, force)
            else force || engine.frequency.underCaps(p, engine.config.globals)
            if (!freqOk) {
                loader.preload(p)
                skip(SkipReason.FREQUENCY, null); return@run
            }

            var reward: RewardItem? = null
            val present = { ad: LoadedAd ->
                presenter.present(activity, p, ad, onReward = { reward = it }) { shown, msg ->
                    done(Outcome(shown, if (shown) null else SkipReason.SHOW_FAILED, msg, reward))
                }
            }

            loader.take(p)?.let { present(it); return@run }

            if (!p.loadOnDemand) {
                loader.preload(p)
                skip(SkipReason.NOT_LOADED, null); return@run
            }

            // On-demand: loading dialog + hard timeout. A late ad stays cached for next time.
            val dialog = if (p.loadingDialog) LoadingDialog.show(activity, engine.loadingDialogFactory) else null
            var finished = false
            val timeout = Runnable {
                if (finished) return@Runnable
                finished = true
                LoadingDialog.dismiss(dialog)
                skip(SkipReason.TIMEOUT, "${p.timeoutMs} ms")
            }
            Main.delay(p.timeoutMs, timeout)
            loader.load(p) { ok ->
                if (finished) return@load
                finished = true
                Main.cancel(timeout)
                LoadingDialog.dismiss(dialog)
                val ad = if (ok) loader.take(p) else null
                when {
                    ad == null -> skip(SkipReason.LOAD_FAILED, null)
                    // User left (Home) or another full-screen ad took over while we loaded: keep the ad.
                    !ActivityTracker.isResumed(activity) -> { loader.putBack(p, ad); skip(SkipReason.ACTIVITY_GONE, null) }
                    engine.frequency.showing -> { loader.putBack(p, ad); skip(SkipReason.ALREADY_SHOWING, null) }
                    else -> present(ad)
                }
            }
        }
    }
}
