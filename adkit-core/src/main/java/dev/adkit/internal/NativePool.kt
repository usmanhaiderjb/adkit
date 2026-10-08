package dev.adkit.internal

import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import dev.adkit.AdEventType
import dev.adkit.AdFormat
import dev.adkit.NativeStyle
import dev.adkit.Placement

/**
 * Native ads keyed by placement. Consume-once, 1 h TTL, waiters served in order so an ad
 * preloaded on splash is handed to the language screen the moment it asks.
 */
internal class NativePool(private val engine: Engine) {
    private class Entry(val ad: NativeAd, val at: Long, val gen: Int)

    private class Slot {
        val ready = ArrayDeque<Entry>()
        val waiters = ArrayDeque<Waiter>()
        var inFlight = 0
    }

    class Waiter internal constructor(val deliver: (NativeAd?) -> Unit) {
        @Volatile var cancelled = false
        fun cancel() { cancelled = true }
    }

    private val slots = HashMap<String, Slot>()

    private fun slot(name: String) = slots.getOrPut(name) { Slot() }

    /** Gets an ad now or as soon as one loads. Null delivery = load failed. */
    fun request(p: Placement, deliver: (NativeAd?) -> Unit): Waiter {
        val w = Waiter(deliver)
        Main.run {
            val s = slot(p.name)
            pollFresh(s)?.let { w.deliver(it); return@run }
            s.waiters += w
            refill(p, s, want = 1)
        }
        return w
    }

    /** Keep [count] ads ready for [p]. */
    fun preload(p: Placement, count: Int = p.preloadCount.coerceAtLeast(1)) {
        Main.run {
            val s = slot(p.name)
            dropStale(s)
            refill(p, s, want = count - s.ready.size)
        }
    }

    /** Returns an unused ad (its view left before it arrived) to the pool. */
    fun offer(p: Placement, ad: NativeAd) {
        Main.run { deliver(slot(p.name), ad, engine.generation) }
    }

    fun available(name: String): Int = slots[name]?.let { dropStale(it); it.ready.size } ?: 0

    fun clearAll() {
        Main.run {
            slots.values.forEach { s ->
                s.ready.forEach { runCatching { it.ad.destroy() } }
                s.ready.clear()
                s.waiters.forEach { it.cancel() }
                s.waiters.clear()
            }
        }
    }

    private fun pollFresh(s: Slot): NativeAd? {
        dropStale(s)
        return s.ready.removeFirstOrNull()?.ad
    }

    private fun dropStale(s: Slot) {
        val t = now()
        s.ready.removeAll { e ->
            val stale = e.gen != engine.generation || t - e.at > TTL_MS
            if (stale) runCatching { e.ad.destroy() }
            stale
        }
    }

    private fun refill(p: Placement, s: Slot, want: Int) {
        val pending = s.waiters.count { !it.cancelled }
        val need = maxOf(want, pending) - s.inFlight
        if (need <= 0) return
        if (engine.gate(p.name, p, null, needSdk = false) != null) {
            flushFailed(s); return
        }
        repeat(need) {
            s.inFlight++
            engine.whenReady { loadUnits(p, s, engine.unitsFor(p), 0, engine.generation) }
        }
    }

    private fun loadUnits(p: Placement, s: Slot, units: List<String>, i: Int, gen: Int) {
        if (i >= units.size) {
            s.inFlight--
            deliverFailure(s)
            return
        }
        val unit = units[i]
        val started = now()
        engine.event(AdEventType.REQUESTED, p.name, AdFormat.NATIVE, unit)
        val request = NativeAdRequest.Builder(unit, listOf(NativeAd.NativeAdType.NATIVE))
            .setMediaAspectRatio(
                if (p.nativeStyle == NativeStyle.FULLSCREEN) NativeAd.NativeMediaAspectRatio.PORTRAIT
                else NativeAd.NativeMediaAspectRatio.ANY,
            )
            .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
            .build()
        runCatching {
            NativeAdLoader.load(request, object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) = Main.run {
                    engine.event(AdEventType.LOADED, p.name, AdFormat.NATIVE, unit, latencyMs = now() - started)
                    s.inFlight--
                    if (gen != engine.generation) { runCatching { nativeAd.destroy() }; deliverFailure(s); return@run }
                    attachEvents(p, nativeAd)
                    deliver(s, nativeAd, gen)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = Main.run {
                    engine.event(
                        AdEventType.LOAD_FAILED, p.name, AdFormat.NATIVE, unit,
                        message = "${adError.code} ${adError.message}", latencyMs = now() - started,
                    )
                    loadUnits(p, s, units, i + 1, gen)
                }
            })
        }.onFailure {
            AdLog.e("Native load threw for $unit", it)
            Main.run { s.inFlight--; deliverFailure(s) }
        }
    }

    private fun attachEvents(p: Placement, ad: NativeAd) {
        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() = engine.event(AdEventType.IMPRESSION, p.name, AdFormat.NATIVE)
            override fun onAdClicked() {
                engine.event(AdEventType.CLICKED, p.name, AdFormat.NATIVE)
                engine.appOpenSuppressOnce()
            }
            override fun onAdPaid(value: AdValue) = engine.paid(p.name, AdFormat.NATIVE, ad, value)
        }
    }

    private fun deliver(s: Slot, ad: NativeAd, gen: Int) {
        while (s.waiters.isNotEmpty()) {
            val w = s.waiters.removeFirst()
            if (!w.cancelled) { w.deliver(ad); return }
        }
        s.ready += Entry(ad, now(), gen)
    }

    private fun deliverFailure(s: Slot) {
        // One failure answers one waiter; others keep waiting on loads still in flight.
        if (s.inFlight > 0) {
            while (s.waiters.isNotEmpty()) {
                val w = s.waiters.removeFirst()
                if (!w.cancelled) { w.deliver(null); return }
            }
            return
        }
        flushFailed(s)
    }

    private fun flushFailed(s: Slot) {
        val all = s.waiters.toList()
        s.waiters.clear()
        all.filter { !it.cancelled }.forEach { it.deliver(null) }
    }

    companion object {
        private const val TTL_MS = 60 * 60_000L
    }
}
