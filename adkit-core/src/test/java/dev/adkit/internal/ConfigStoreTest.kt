package dev.adkit.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.adkit.AdFormat
import dev.adkit.BannerType
import dev.adkit.Globals
import dev.adkit.NativeStyle
import dev.adkit.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigStoreTest {
    private lateinit var context: Context

    private val base = mapOf(
        "tool" to Placement("tool", AdFormat.INTERSTITIAL, listOf("code/1"), everyN = 2),
        "home" to Placement("home", AdFormat.BANNER, listOf("code/2")),
    )
    private val globals = Globals(
        enabled = true, globalGapSec = 0, afterAppOpenGapSec = 0, sessionCap = 0,
        splashFirst = "tool", splashReturning = "tool", splashTimeoutMs = 12_000, appOpenResume = null,
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("adkit", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store(testMode: Boolean = false) =
        ConfigStore(context, base, globals, testMode, assetName = null).also { it.load() }

    @Test fun codeDefaultsWithoutRemote() {
        val s = store()
        assertEquals(2, s["tool"]!!.everyN)
        assertTrue(s.globals.enabled)
    }

    @Test fun remoteOverridesPlacementAndGlobals() {
        val s = store()
        s.applyRemote(
            """{"enabled":false,"global_gap_s":15,"splash":{"first":"x","timeout_ms":9000},
               "placements":{"tool":{"every_n":5,"cooldown_s":"30","first_shows":"off","units":["remote/1"]}}}""",
        )
        val p = s["tool"]!!
        assertEquals(5, p.everyN)
        assertEquals(30, p.cooldownSec)
        assertFalse(p.firstShows)
        assertEquals(listOf("remote/1"), p.units)
        assertFalse(s.globals.enabled)
        assertEquals(15, s.globals.globalGapSec)
        assertEquals("x", s.globals.splashFirst)
        assertEquals("tool", s.globals.splashReturning)
        assertEquals(9000L, s.globals.splashTimeoutMs)
    }

    @Test fun emptyValuesNeverOverwrite() {
        val s = store()
        s.applyRemote("""{"placements":{"tool":{"unit":"","every_n":null,"units":[]}}}""")
        assertEquals(listOf("code/1"), s["tool"]!!.units)
        assertEquals(2, s["tool"]!!.everyN)
    }

    @Test fun testModeIgnoresRemoteUnits() {
        val s = store(testMode = true)
        s.applyRemote("""{"placements":{"tool":{"units":["prod/9"],"every_n":4}}}""")
        assertEquals(listOf("code/1"), s["tool"]!!.units)
        assertEquals(4, s["tool"]!!.everyN)
    }

    @Test fun remoteOnlyPlacementNeedsFormat() {
        val s = store()
        s.applyRemote(
            """{"placements":{"settings":{"format":"native","style":"small","units":["r/1"]},
               "broken":{"units":["r/2"]}}}""",
        )
        val n = s["settings"]!!
        assertEquals(AdFormat.NATIVE, n.format)
        assertEquals(NativeStyle.SMALL, n.nativeStyle)
        assertNull(s["broken"])
    }

    @Test fun remoteAppOpenPlacementDefaultsToCachedOnly() {
        val s = store()
        s.applyRemote("""{"placements":{"resume2":{"format":"app_open","units":["r/3"]}}}""")
        assertFalse(s["resume2"]!!.loadOnDemand)
    }

    @Test fun formatSwapOnlyWithinFamily() {
        val s = store()
        s.applyRemote("""{"placements":{"home":{"format":"native"},"tool":{"format":"banner"}}}""")
        assertEquals(AdFormat.NATIVE, s["home"]!!.format)
        assertEquals(AdFormat.INTERSTITIAL, s["tool"]!!.format)
    }

    @Test fun bannerTypeColourAndFirstSession() {
        val s = store()
        s.applyRemote(
            """{"placements":{"home":{"banner":"collapsible_top","cta_color":"#FF0000"},
               "tool":{"first_session":{"every_n":7},"native_fallback":"nf"}}}""",
        )
        assertEquals(BannerType.COLLAPSIBLE_TOP, s["home"]!!.bannerType)
        assertEquals(0xFFFF0000.toInt(), s["home"]!!.ctaColor)
        assertEquals(7, s["tool"]!!.effective(firstSession = true).everyN)
        assertEquals(2, s["tool"]!!.effective(firstSession = false).everyN)
        assertEquals("nf", s["tool"]!!.nativeFallback)
    }

    @Test fun invalidJsonKeepsPreviousConfig() {
        val s = store()
        s.applyRemote("""{"placements":{"tool":{"every_n":3}}}""")
        s.applyRemote("not json {")
        assertEquals(3, s["tool"]!!.everyN)
    }

    @Test fun lastGoodRemoteSurvivesRestart() {
        store().applyRemote("""{"placements":{"tool":{"every_n":6}}}""")
        assertEquals(6, store()["tool"]!!.everyN)
    }

    @Test fun badFieldDoesNotDropOtherPlacements() {
        val s = store()
        s.applyRemote("""{"placements":{"tool":{"every_n":"abc"},"home":{"banner":"mrec"}}}""")
        assertEquals(2, s["tool"]!!.everyN)
        assertEquals(BannerType.MEDIUM_RECTANGLE, s["home"]!!.bannerType)
    }

    @Test fun extrasAreExposed() {
        val s = store()
        s.applyRemote("""{"extras":{"paywall":2},"placements":{"home":{"extras":{"k":"v"}}}}""")
        assertEquals(2, s.globals.extras["paywall"])
        assertEquals("v", s["home"]!!.extras["k"])
    }

    @Test fun listenerFiresOnApply() {
        val s = store()
        var calls = 0
        s.addListener { calls++ }
        s.applyRemote("""{"enabled":true}""")
        assertEquals(1, calls)
    }
}
