package dev.adkit

/** One paid impression. Forward to Firebase, Adjust, AppsFlyer, your backend... */
data class AdRevenueEvent(
    val placement: String,
    val format: AdFormat,
    val adUnitId: String,
    val valueMicros: Long,
    val currencyCode: String,
    /** UNKNOWN, ESTIMATED, PUBLISHER_PROVIDED or PRECISE. */
    val precision: String,
    /** Network that served the ad, e.g. "AdMob Network", "AppLovin". */
    val adSourceName: String?,
    val responseId: String?,
) {
    val value: Double get() = valueMicros / 1_000_000.0
}

fun interface AdRevenueListener {
    fun onAdRevenue(event: AdRevenueEvent)
}

enum class AdEventType { REQUESTED, LOADED, LOAD_FAILED, SHOWN, IMPRESSION, CLICKED, DISMISSED, SHOW_FAILED, SKIPPED }

/** Funnel event for analytics/debugging. [latencyMs] is set on LOADED / LOAD_FAILED. */
data class AdEvent(
    val type: AdEventType,
    val placement: String,
    val format: AdFormat?,
    val adUnitId: String? = null,
    val skipReason: SkipReason? = null,
    val message: String? = null,
    val latencyMs: Long? = null,
)

fun interface AdEventListener {
    fun onAdEvent(event: AdEvent)
}
