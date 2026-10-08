package dev.adkit.internal

import android.content.Context
import dev.adkit.AdFormat
import dev.adkit.Globals
import dev.adkit.Placement

/**
 * Frequency rules for full-screen ads, plus the shared "something full-screen is showing" state
 * that app open and every other format respect.
 */
internal class Frequency(private val context: Context) {
    /** A full-screen ad (or AdKit full-screen screen) is on top right now. */
    @Volatile var showing = false
    /** elapsedRealtime of the last full-screen dismiss; app open skips the foreground right after. */
    @Volatile var lastDismissAt = 0L
    @Volatile private var lastFullScreenAt = 0L
    @Volatile private var lastAppOpenAt = 0L
    private var sessionTotal = 0
    private val sessionPerPlacement = mutableMapOf<String, Int>()

    /**
     * Counts the call and decides if the ad may show. Counter persists across launches.
     * When the counter is satisfied but a time rule blocks, the counter stays "armed".
     */
    fun check(p: Placement, g: Globals, force: Boolean): Boolean {
        if (force) return true
        if (p.sessionCap > 0 && (sessionPerPlacement[p.name] ?: 0) >= p.sessionCap) return false
        if (g.sessionCap > 0 && sessionTotal >= g.sessionCap) return false

        val armed = if (p.everyN <= 1) true else {
            val initial = if (p.firstShows) p.everyN - 1 else 0
            val count = (Prefs.counter(context, p.name, initial) + 1).coerceAtMost(p.everyN)
            Prefs.setCounter(context, p.name, count)
            count >= p.everyN
        }
        if (!armed) return false

        val t = now()
        if (p.cooldownSec > 0) {
            val last = Prefs.lastShown(context, p.name)
            if (last > 0 && wallNow() - last < p.cooldownSec * 1000L) return false
        }
        if (g.globalGapSec > 0 && lastFullScreenAt > 0 && t - lastFullScreenAt < g.globalGapSec * 1000L) return false
        if (p.format != AdFormat.APP_OPEN && g.afterAppOpenGapSec > 0 && lastAppOpenAt > 0 &&
            t - lastAppOpenAt < g.afterAppOpenGapSec * 1000L
        ) return false
        return true
    }

    /**
     * Rules for user-initiated rewarded flows: session caps and the placement's own cooldown.
     * Rewarded is exempt from the global cap/gap because the user asked for it.
     */
    fun underCaps(p: Placement, g: Globals): Boolean {
        if (p.sessionCap > 0 && (sessionPerPlacement[p.name] ?: 0) >= p.sessionCap) return false
        val userInitiated = p.format == AdFormat.REWARDED || p.format == AdFormat.REWARDED_INTERSTITIAL
        if (!userInitiated && g.sessionCap > 0 && sessionTotal >= g.sessionCap) return false
        if (p.cooldownSec > 0) {
            val last = Prefs.lastShown(context, p.name)
            if (last > 0 && wallNow() - last < p.cooldownSec * 1000L) return false
        }
        return true
    }

    /** App open: caps, cooldown and the global gap after any other full-screen ad. No counter. */
    fun appOpenAllowed(p: Placement, g: Globals): Boolean {
        if (!underCaps(p, g)) return false
        if (g.globalGapSec > 0 && lastFullScreenAt > 0 && now() - lastFullScreenAt < g.globalGapSec * 1000L) return false
        return true
    }

    fun recordShown(p: Placement) {
        val t = now()
        lastFullScreenAt = t
        if (p.format == AdFormat.APP_OPEN) lastAppOpenAt = t
        sessionTotal++
        sessionPerPlacement[p.name] = (sessionPerPlacement[p.name] ?: 0) + 1
        Prefs.setCounter(context, p.name, 0)
        Prefs.setLastShown(context, p.name, wallNow())
    }
}
