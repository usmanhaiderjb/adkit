package dev.adkit.internal

import android.content.Context
import android.graphics.Color
import dev.adkit.AdFormat
import dev.adkit.BannerType
import dev.adkit.FirstSessionRules
import dev.adkit.Globals
import dev.adkit.NativeStyle
import dev.adkit.Placement
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the merged config. Layers, lowest first:
 * code DSL < bundled asset < cached last-good remote < fresh remote.
 */
internal class ConfigStore(
    private val context: Context,
    private val basePlacements: Map<String, Placement>,
    private val baseGlobals: Globals,
    private val testMode: Boolean,
    private val assetName: String?,
) {
    @Volatile var globals: Globals = baseGlobals
        private set

    @Volatile var placements: Map<String, Placement> = basePlacements
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private var assetJson: JSONObject? = null

    fun load() {
        assetJson = assetName?.let { readAsset(it) }
        val cached = Prefs.remoteJson(context)?.let { parse(it) }
        rebuild(cached)
    }

    /** Applies a freshly fetched remote JSON. Invalid JSON keeps the previous config. */
    fun applyRemote(json: String?) {
        if (json.isNullOrBlank()) return
        val parsed = parse(json) ?: run {
            AdLog.w("Remote config is not valid JSON, keeping previous config")
            return
        }
        Prefs.setRemoteJson(context, json)
        rebuild(parsed)
        AdLog.d("Remote config applied (${placements.size} placements)")
    }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    operator fun get(name: String): Placement? = placements[name]

    private fun rebuild(remote: JSONObject?) {
        var g = baseGlobals
        val p = LinkedHashMap(basePlacements)
        assetJson?.let { g = merge(g, p, it) }
        remote?.let { g = merge(g, p, it) }
        globals = g
        placements = p
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    private fun merge(g: Globals, out: MutableMap<String, Placement>, json: JSONObject): Globals {
        var result = g
        runCatching {
            val splash = json.optJSONObject("splash")
            result = g.copy(
                enabled = json.bool("enabled") ?: g.enabled,
                globalGapSec = json.int("global_gap_s") ?: g.globalGapSec,
                afterAppOpenGapSec = json.int("after_app_open_gap_s") ?: g.afterAppOpenGapSec,
                sessionCap = json.int("session_cap") ?: g.sessionCap,
                splashFirst = splash?.str("first") ?: g.splashFirst,
                splashReturning = splash?.str("returning") ?: g.splashReturning,
                splashTimeoutMs = splash?.long("timeout_ms") ?: g.splashTimeoutMs,
                appOpenResume = json.str("app_open_resume") ?: g.appOpenResume,
                extras = json.optJSONObject("extras")?.toMap() ?: g.extras,
            )
        }.onFailure { AdLog.w("Bad globals in config: ${it.message}") }

        val placementsJson = json.optJSONObject("placements") ?: return result
        for (name in placementsJson.keys()) {
            val obj = placementsJson.optJSONObject(name) ?: continue
            runCatching {
                val existing = out[name]
                val merged = when {
                    existing != null -> override(existing, obj)
                    else -> create(name, obj)
                }
                if (merged != null) out[name] = merged
            }.onFailure { AdLog.w("Bad placement '$name' in config: ${it.message}") }
        }
        return result
    }

    /** A placement only defined remotely. Needs at least a format. */
    private fun create(name: String, obj: JSONObject): Placement? {
        val format = AdFormat.from(obj.str("format")) ?: run {
            AdLog.w("Remote placement '$name' has no valid format, ignored")
            return null
        }
        return override(Placement(name = name, format = format, units = emptyList(), loadOnDemand = format != AdFormat.APP_OPEN), obj)
    }

    private fun override(p: Placement, o: JSONObject): Placement {
        val remoteUnits = o.strings("units") ?: o.str("unit")?.let { listOf(it) }
        // Remote unit ids are ignored in test mode so a debug build never hits production units.
        val units = if (remoteUnits != null && !testMode) remoteUnits else p.units
        val format = AdFormat.from(o.str("format"))?.takeIf { compatible(p.format, it) } ?: p.format
        val fs = o.optJSONObject("first_session")?.let {
            FirstSessionRules(it.int("every_n"), it.int("cooldown_s"), it.bool("enabled"))
        }
        return p.copy(
            format = format,
            units = units,
            enabled = o.bool("enabled") ?: p.enabled,
            preload = o.bool("preload") ?: p.preload,
            loadOnDemand = o.bool("load_on_demand") ?: p.loadOnDemand,
            timeoutMs = o.long("timeout_ms") ?: p.timeoutMs,
            loadingDialog = o.bool("loading_dialog") ?: p.loadingDialog,
            everyN = (o.int("every_n") ?: p.everyN).coerceAtLeast(1),
            firstShows = o.bool("first_shows") ?: p.firstShows,
            cooldownSec = o.int("cooldown_s") ?: p.cooldownSec,
            sessionCap = o.int("session_cap") ?: p.sessionCap,
            firstSession = fs ?: p.firstSession,
            fallback = o.str("fallback") ?: p.fallback,
            rewardOnFallback = o.bool("reward_on_fallback") ?: p.rewardOnFallback,
            expiryHours = o.int("expiry_h") ?: p.expiryHours,
            nativeFallback = o.str("native_fallback") ?: p.nativeFallback,
            skipDelaySec = o.int("skip_delay_s") ?: p.skipDelaySec,
            bannerType = BannerType.from(o.str("banner")) ?: p.bannerType,
            inlineMaxHeightDp = o.int("inline_max_height_dp") ?: p.inlineMaxHeightDp,
            shared = o.bool("shared") ?: p.shared,
            nativeStyle = NativeStyle.from(o.str("style")) ?: p.nativeStyle,
            preloadCount = o.int("preload_count") ?: p.preloadCount,
            ctaColor = o.str("cta_color")?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: p.ctaColor,
            collapseOnFail = o.bool("collapse_on_fail") ?: p.collapseOnFail,
            extras = o.optJSONObject("extras")?.toMap() ?: p.extras,
        )
    }

    /** Remote may swap banner <-> native, or interstitial <-> rewarded interstitial, never across families. */
    private fun compatible(base: AdFormat, wanted: AdFormat): Boolean {
        if (base == wanted) return true
        val inline = setOf(AdFormat.BANNER, AdFormat.NATIVE)
        val full = setOf(AdFormat.INTERSTITIAL, AdFormat.REWARDED_INTERSTITIAL, AdFormat.APP_OPEN)
        return (base in inline && wanted in inline) || (base in full && wanted in full)
    }

    private fun readAsset(name: String): JSONObject? = runCatching {
        context.assets.open(name).bufferedReader().use { parse(it.readText()) }
    }.getOrNull()

    private fun parse(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
}

// Lenient readers: missing key or empty string means "not set", never "set to zero/empty".
private fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

private fun JSONObject.bool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val v: Any? = opt(key)) {
        null -> null
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }
}

private fun JSONObject.long(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v: Any? = opt(key)) {
        null -> null
        is Number -> v.toLong()
        is String -> v.trim().toDoubleOrNull()?.toLong()
        else -> null
    }
}

private fun JSONObject.int(key: String): Int? = long(key)?.toInt()

private fun JSONObject.strings(key: String): List<String>? {
    val arr = optJSONArray(key) ?: return null
    return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
        .takeIf { it.isNotEmpty() }
}

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k ->
    when (val v: Any? = opt(k)) {
        null -> null
        is JSONObject -> v.toMap()
        is JSONArray -> (0 until v.length()).map { v.opt(it) }
        JSONObject.NULL -> null
        else -> v
    }
}
