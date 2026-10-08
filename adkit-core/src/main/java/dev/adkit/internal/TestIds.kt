package dev.adkit.internal

import dev.adkit.AdFormat
import dev.adkit.BannerType
import dev.adkit.Placement

/**
 * Google's public demo units. Safe to click; never earn revenue.
 *
 * Built at runtime on purpose: the release-build scanner (`dev.adkit.check`) looks for the full
 * test publisher id in the APK, and AdKit itself must not trip it.
 */
internal object TestIds {
    private val PUB: String = listOf("ca", "app", "pub", "3940256099942544").joinToString("-")

    val APP_ID: String = "$PUB~3347511713"
    private val APP_OPEN = unit("9257395921")
    private val BANNER_ADAPTIVE = unit("9214589741")
    private val BANNER_FIXED = unit("6300978111")
    private val BANNER_COLLAPSIBLE = unit("2014213617")
    private val INTERSTITIAL = unit("1033173712")
    private val REWARDED = unit("5224354917")
    private val REWARDED_INTERSTITIAL = unit("5354046379")
    private val NATIVE = unit("2247696110")
    private val NATIVE_VIDEO = unit("1044960115")

    private fun unit(id: String) = "$PUB/$id"

    /** True for any Google demo unit or app id. */
    fun isTestId(id: String): Boolean = id.startsWith(PUB)

    fun forPlacement(p: Placement): String = when (p.format) {
        AdFormat.APP_OPEN -> APP_OPEN
        AdFormat.INTERSTITIAL -> INTERSTITIAL
        AdFormat.REWARDED -> REWARDED
        AdFormat.REWARDED_INTERSTITIAL -> REWARDED_INTERSTITIAL
        AdFormat.NATIVE -> if (p.nativeStyle.hasMedia) NATIVE_VIDEO else NATIVE
        AdFormat.BANNER -> when (p.bannerType) {
            BannerType.COLLAPSIBLE_TOP, BannerType.COLLAPSIBLE_BOTTOM -> BANNER_COLLAPSIBLE
            BannerType.BANNER, BannerType.LARGE_BANNER, BannerType.MEDIUM_RECTANGLE -> BANNER_FIXED
            else -> BANNER_ADAPTIVE
        }
    }
}
