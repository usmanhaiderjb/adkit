package dev.adkit.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.adkit.AdFormat
import dev.adkit.Globals
import dev.adkit.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FrequencyTest {
    private lateinit var context: Context
    private lateinit var f: Frequency
    private val g = Globals(
        enabled = true, globalGapSec = 0, afterAppOpenGapSec = 0, sessionCap = 0,
        splashFirst = null, splashReturning = null, splashTimeoutMs = 0, appOpenResume = null,
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("adkit", Context.MODE_PRIVATE).edit().clear().commit()
        f = Frequency(context)
    }

    private fun inter(name: String = "p", everyN: Int = 1, firstShows: Boolean = true, cooldownSec: Int = 0, sessionCap: Int = 0) =
        Placement(name, AdFormat.INTERSTITIAL, listOf("u"), everyN = everyN, firstShows = firstShows,
            cooldownSec = cooldownSec, sessionCap = sessionCap)

    /** Calls check and records a show when allowed, like the real flow does. */
    private fun tap(p: Placement, globals: Globals = g, force: Boolean = false): Boolean =
        f.check(p, globals, force).also { if (it) f.recordShown(p) }

    @Test fun everyNWithFirstShows() {
        val p = inter(everyN = 3)
        assertEquals(listOf(true, false, false, true, false, false, true), List(7) { tap(p) })
    }

    @Test fun everyNWithoutFirstShows() {
        val p = inter(everyN = 3, firstShows = false)
        assertEquals(listOf(false, false, true, false, false, true), List(6) { tap(p) })
    }

    @Test fun counterPersistsAcrossInstances() {
        val p = inter(everyN = 3, firstShows = false)
        tap(p); tap(p)
        f = Frequency(context)
        assertTrue(tap(p))
    }

    @Test fun counterStaysArmedWhileTimeRuleBlocks() {
        val p = inter(everyN = 2, firstShows = false)
        val gapped = g.copy(globalGapSec = 10)
        f.recordShown(inter("other")) // something full-screen just showed
        assertFalse(tap(p, gapped))   // count 1
        assertFalse(tap(p, gapped))   // count 2 = armed, but inside the gap
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        assertTrue(tap(p, gapped))    // armed counter fires right after the gap
    }

    @Test fun cooldownUsesLastShowOfPlacement() {
        val p = inter(cooldownSec = 30)
        assertTrue(tap(p))
        assertFalse(tap(p))
        Prefs.setLastShown(context, p.name, System.currentTimeMillis() - 31_000)
        assertTrue(tap(p))
    }

    @Test fun sessionCaps() {
        val p = inter(sessionCap = 2)
        assertEquals(listOf(true, true, false), List(3) { tap(p) })
        val q = inter("q")
        assertEquals(listOf(true, true, false), List(3) { tap(q, g.copy(sessionCap = 4)) })
    }

    @Test fun afterAppOpenGapOnlyBlocksNonAppOpen() {
        val gapped = g.copy(afterAppOpenGapSec = 10)
        f.recordShown(Placement("resume", AdFormat.APP_OPEN, listOf("u")))
        assertFalse(tap(inter(), gapped))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        assertTrue(tap(inter(), gapped))
    }

    @Test fun forceBypassesEverything() {
        val p = inter(everyN = 5, firstShows = false, sessionCap = 1)
        f.recordShown(p)
        assertTrue(f.check(p, g.copy(globalGapSec = 100), force = true))
    }

    @Test fun rewardedIgnoresGlobalCapButKeepsOwnRules() {
        val rewarded = Placement("r", AdFormat.REWARDED, listOf("u"), sessionCap = 1)
        val capped = g.copy(sessionCap = 1)
        f.recordShown(inter("x"))
        assertTrue(f.underCaps(rewarded, capped))
        f.recordShown(rewarded)
        assertFalse(f.underCaps(rewarded, capped))
    }

    @Test fun appOpenRespectsGlobalGap() {
        val open = Placement("resume", AdFormat.APP_OPEN, listOf("u"))
        val gapped = g.copy(globalGapSec = 15)
        f.recordShown(inter())
        assertFalse(f.appOpenAllowed(open, gapped))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        assertTrue(f.appOpenAllowed(open, gapped))
    }
}
