package dev.adkit

import androidx.annotation.LayoutRes

enum class AdFormat(val key: String) {
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    REWARDED_INTERSTITIAL("rewarded_interstitial"),
    APP_OPEN("app_open"),
    BANNER("banner"),
    NATIVE("native");

    val isFullScreen: Boolean
        get() = this == INTERSTITIAL || this == REWARDED || this == REWARDED_INTERSTITIAL || this == APP_OPEN

    companion object {
        fun from(key: String?): AdFormat? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

enum class BannerType(val key: String) {
    /** Anchored adaptive, current orientation. The default. */
    ADAPTIVE("adaptive"),
    /** Large anchored adaptive (taller creative). */
    LARGE_ADAPTIVE("large_adaptive"),
    /** Inline adaptive; height capped by [Placement.inlineMaxHeightDp] when set. */
    INLINE_ADAPTIVE("inline_adaptive"),
    /** Anchored adaptive that expands from the top. */
    COLLAPSIBLE_TOP("collapsible_top"),
    /** Anchored adaptive that expands from the bottom. */
    COLLAPSIBLE_BOTTOM("collapsible_bottom"),
    /** Fixed 320x50. */
    BANNER("banner"),
    /** Fixed 320x100. */
    LARGE_BANNER("large_banner"),
    /** Fixed 300x250 medium rectangle. */
    MEDIUM_RECTANGLE("mrec");

    companion object {
        fun from(key: String?): BannerType? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

enum class NativeStyle(val key: String) {
    /** One-line strip, icon + headline + CTA. Fits banner slots and list rows. */
    BANNER("banner"),
    /** Icon, headline, body, CTA. No media. */
    SMALL("small"),
    /** Small header plus a media view. */
    MEDIUM("medium"),
    /** Big media, rating, full body. */
    LARGE("large"),
    /** [LARGE] on tall screens, [MEDIUM] on short ones. */
    LARGE_ADJUSTED("large_adjusted"),
    /** Whole screen: onboarding page, full-screen native step. */
    FULLSCREEN("fullscreen");

    internal val hasMedia: Boolean
        get() = this == MEDIUM || this == LARGE || this == LARGE_ADJUSTED || this == FULLSCREEN

    companion object {
        fun from(key: String?): NativeStyle? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/**
 * Overrides applied while the user is in their first app session (launch count == 1).
 * Null fields keep the base value.
 */
data class FirstSessionRules(
    val everyN: Int? = null,
    val cooldownSec: Int? = null,
    val enabled: Boolean? = null,
)

/**
 * Everything AdKit knows about one ad slot. Built from the init DSL, then overridden by the
 * bundled asset and remote config. Immutable: overrides produce copies.
 */
data class Placement(
    val name: String,
    val format: AdFormat,
    /** Ad unit ids tried in order (waterfall). */
    val units: List<String>,
    val enabled: Boolean = true,

    // Full-screen (interstitial, rewarded, rewarded interstitial, app open, full-screen native).
    /** Keep one ad loaded in the background so show is instant. */
    val preload: Boolean = true,
    /** When nothing is cached at show time, load now behind the loading dialog. */
    val loadOnDemand: Boolean = true,
    /** Max wait for an on-demand load before continuing without an ad. */
    val timeoutMs: Long = 4_500,
    val loadingDialog: Boolean = true,
    /** Show on every Nth eligible call. 1 = every call. */
    val everyN: Int = 1,
    /** The very first call (fresh install) shows, then every Nth. */
    val firstShows: Boolean = true,
    /** Min seconds between two shows of this placement. */
    val cooldownSec: Int = 0,
    /** Max shows of this placement per app session. 0 = unlimited. */
    val sessionCap: Int = 0,
    val firstSession: FirstSessionRules? = null,
    /** Rewarded: placement tried when this one has no ad (rewarded interstitial or interstitial). */
    val fallback: String? = null,
    /**
     * Rewarded: treat a completed *plain interstitial* fallback as earning the reward.
     * Off by default: AdMob policy forbids rewarding non-rewarded formats. Rewarded interstitial
     * fallbacks always reward through their own earned callback.
     */
    val rewardOnFallback: Boolean = false,
    /** App open: drop cached ad after this many hours. */
    val expiryHours: Int = 4,
    /**
     * App open: native placement shown full-screen when no app open ad is ready on resume
     * (the "native as app open" pattern). Its pool is filled whenever the app goes to background.
     */
    val nativeFallback: String? = null,
    /** Full-screen native: seconds before the close button appears. */
    val skipDelaySec: Int = 3,

    // Banner.
    val bannerType: BannerType = BannerType.ADAPTIVE,
    val inlineMaxHeightDp: Int = 0,
    /** Keep one banner alive per placement and move it between screens (tab apps). */
    val shared: Boolean = false,

    // Native.
    val nativeStyle: NativeStyle = NativeStyle.MEDIUM,
    /** Natives kept ready in the pool for this placement. */
    val preloadCount: Int = 0,
    @param:LayoutRes val layoutRes: Int = 0,
    /** CTA background colour (ARGB). Null = theme colour. */
    val ctaColor: Int? = null,

    /** Hide the container instead of leaving the placeholder when no ad is available. */
    val collapseOnFail: Boolean = true,
    /** Free-form values from remote config for app code (`AdKit.placement(name)?.extras`). */
    val extras: Map<String, Any?> = emptyMap(),
) {
    internal fun effective(firstSession: Boolean): Placement {
        val fs = this.firstSession ?: return this
        if (!firstSession) return this
        return copy(
            everyN = fs.everyN ?: everyN,
            cooldownSec = fs.cooldownSec ?: cooldownSec,
            enabled = fs.enabled ?: enabled,
        )
    }
}
