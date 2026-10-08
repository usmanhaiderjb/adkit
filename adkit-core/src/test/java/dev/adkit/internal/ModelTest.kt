package dev.adkit.internal

import android.os.Looper
import dev.adkit.AdFormat
import dev.adkit.BannerType
import dev.adkit.NativeStyle
import dev.adkit.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelTest {
    @Test fun testIdsPerFormat() {
        fun id(format: AdFormat, style: NativeStyle = NativeStyle.MEDIUM, banner: BannerType = BannerType.ADAPTIVE) =
            TestIds.forPlacement(Placement("p", format, emptyList(), nativeStyle = style, bannerType = banner))
        assertTrue(id(AdFormat.INTERSTITIAL).endsWith("/1033173712"))
        assertTrue(id(AdFormat.APP_OPEN).endsWith("/9257395921"))
        assertTrue(id(AdFormat.REWARDED).endsWith("/5224354917"))
        assertTrue(id(AdFormat.REWARDED_INTERSTITIAL).endsWith("/5354046379"))
        assertTrue(id(AdFormat.NATIVE, NativeStyle.SMALL).endsWith("/2247696110"))
        assertTrue(id(AdFormat.NATIVE, NativeStyle.LARGE).endsWith("/1044960115"))
        assertTrue(id(AdFormat.BANNER, banner = BannerType.COLLAPSIBLE_BOTTOM).endsWith("/2014213617"))
        assertTrue(id(AdFormat.BANNER, banner = BannerType.MEDIUM_RECTANGLE).endsWith("/6300978111"))
        assertTrue(TestIds.isTestId(id(AdFormat.INTERSTITIAL)))
        assertTrue(TestIds.isTestId(TestIds.APP_ID))
        assertFalse(TestIds.isTestId("ca-app-pub-1234567890123456/1"))
    }

    @Test fun enumKeysAreLenient() {
        assertEquals(AdFormat.APP_OPEN, AdFormat.from("APP_OPEN"))
        assertEquals(BannerType.MEDIUM_RECTANGLE, BannerType.from("mrec"))
        assertEquals(NativeStyle.LARGE_ADJUSTED, NativeStyle.from("large_adjusted"))
        assertEquals(null, NativeStyle.from("huge"))
    }

    @Test fun firstSessionOverridesOnlyOnFirstLaunch() {
        val p = Placement("p", AdFormat.INTERSTITIAL, emptyList(), everyN = 3, cooldownSec = 10,
            firstSession = dev.adkit.FirstSessionRules(everyN = 6, enabled = false))
        assertEquals(6, p.effective(true).everyN)
        assertFalse(p.effective(true).enabled)
        assertEquals(10, p.effective(true).cooldownSec)
        assertEquals(p, p.effective(false))
    }

    @Test fun onceFiresExactlyOnceOnMain() {
        val seen = mutableListOf<Int>()
        val once = Once<Int> { seen += it }
        val t = Thread { once(1) }
        t.start(); t.join()
        once(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), seen)
        assertTrue(once.isDone)
    }
}
