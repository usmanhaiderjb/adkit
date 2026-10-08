package dev.adkit.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import dev.adkit.AdFormat
import dev.adkit.AdRevenueEvent
import dev.adkit.AdRevenueListener
import dev.adkit.AdsConfigSource
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Reads the AdKit JSON from one Remote Config string parameter (default `adkit_config`).
 *
 * Apps with legacy flat keys can translate them with [legacyMapper]; its JSON is applied first and
 * the `adkit_config` JSON (if present) wins on conflicts.
 */
class FirebaseAdsConfigSource(
    private val key: String = "adkit_config",
    /** Use 0 only while debugging; Remote Config throttles frequent fetches in production. */
    private val minimumFetchIntervalSec: Long = 3_600,
    private val legacyMapper: ((FirebaseRemoteConfig) -> JSONObject?)? = null,
) : AdsConfigSource {

    override suspend fun fetch(): String? {
        val rc = FirebaseRemoteConfig.getInstance()
        rc.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(minimumFetchIntervalSec).build(),
        ).await()
        runCatching { rc.fetchAndActivate().await() }
        val main = rc.getString(key).trim().takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val legacy = legacyMapper?.invoke(rc)
        return when {
            legacy == null -> main?.toString()
            main == null -> legacy.toString()
            else -> deepMerge(legacy, main).toString()
        }
    }

    private fun deepMerge(base: JSONObject, top: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        for (k in top.keys()) {
            val a = out.optJSONObject(k)
            val b = top.optJSONObject(k)
            out.put(k, if (a != null && b != null) deepMerge(a, b) else top.get(k))
        }
        return out
    }
}

/** Logs every paid impression as Firebase's standard `ad_impression` event (feeds GA4 / Ads tROAS). */
class FirebaseRevenueSink(context: Context) : AdRevenueListener {
    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun onAdRevenue(event: AdRevenueEvent) {
        analytics.logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, Bundle().apply {
            putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob")
            putString(FirebaseAnalytics.Param.AD_SOURCE, event.adSourceName ?: "AdMob")
            putString(FirebaseAnalytics.Param.AD_FORMAT, event.format.analyticsName)
            putString(FirebaseAnalytics.Param.AD_UNIT_NAME, event.placement)
            putString(FirebaseAnalytics.Param.CURRENCY, event.currencyCode)
            putDouble(FirebaseAnalytics.Param.VALUE, event.value)
        })
    }
}

/**
 * Accumulates revenue per currency and logs [eventName] each time the total crosses [threshold]
 * (the "tROAS bucket" event several apps used). Threshold can come from remote config.
 */
class TroasSink(
    context: Context,
    private val threshold: () -> Double = { 0.01 },
    private val eventName: String = "custom_ads_revenue",
) : AdRevenueListener {
    private val app = context.applicationContext
    private val analytics = FirebaseAnalytics.getInstance(app)
    private val prefs = app.getSharedPreferences("adkit_troas", Context.MODE_PRIVATE)

    @Synchronized
    override fun onAdRevenue(event: AdRevenueEvent) {
        if (event.valueMicros <= 0) return
        val key = "total_${event.currencyCode}"
        val total = prefs.getFloat(key, 0f).toDouble() + event.value
        val limit = threshold().coerceAtLeast(0.0)
        if (total >= limit) {
            analytics.logEvent(eventName, Bundle().apply {
                putDouble(FirebaseAnalytics.Param.VALUE, total)
                putString(FirebaseAnalytics.Param.CURRENCY, event.currencyCode)
            })
            prefs.edit().putFloat(key, 0f).apply()
        } else {
            prefs.edit().putFloat(key, total.toFloat()).apply()
        }
    }
}

private val AdFormat.analyticsName: String
    get() = when (this) {
        AdFormat.INTERSTITIAL -> "Interstitial"
        AdFormat.REWARDED -> "Rewarded"
        AdFormat.REWARDED_INTERSTITIAL -> "Rewarded Interstitial"
        AdFormat.APP_OPEN -> "App Open"
        AdFormat.BANNER -> "Banner"
        AdFormat.NATIVE -> "Native"
    }
