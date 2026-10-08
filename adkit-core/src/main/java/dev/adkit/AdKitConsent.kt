package dev.adkit

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dev.adkit.internal.AdLog
import dev.adkit.internal.Main

/**
 * UMP wrapper. One gather per session, serialized, callback always fires once.
 * Every ad load checks [canRequestAds].
 */
class AdKitConsent internal constructor(
    private val appContext: Context,
    private val options: ConsentOptions,
    private val appId: String?,
) {
    private val info: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(appContext) }
    private val pending = mutableListOf<(Boolean) -> Unit>()
    private var gathering = false

    /** True once this session finished the UMP flow (success or error). */
    var gathered: Boolean = !options.enabled
        private set

    /** True while the consent form is on screen (splash pauses its clock). */
    var formShowing: Boolean = false
        internal set

    internal var onCanRequestAds: (() -> Unit)? = null

    /** Ads may be requested: consent obtained or not required. Valid from the previous session too. */
    val canRequestAds: Boolean
        get() = !options.enabled || runCatching { info.canRequestAds() }.getOrDefault(false)

    /** Show a "Privacy settings" entry in your settings screen when true. */
    val isPrivacyOptionsRequired: Boolean
        get() = options.enabled && runCatching {
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        }.getOrDefault(false)

    /**
     * Requests a consent update and shows the form if needed.
     * [onDone] gets [canRequestAds]. Safe to call repeatedly; concurrent calls share one flow.
     */
    fun gather(activity: Activity, onDone: (Boolean) -> Unit = {}) {
        Main.run {
            if (!options.enabled) { onDone(true); return@run }
            pending += onDone
            if (gathering) return@run
            gathering = true

            val params = ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(options.tagForUnderAgeOfConsent)
                .apply { appId?.let { setAdMobAppId(it) } }
                .apply { debugSettings(activity)?.let { setConsentDebugSettings(it) } }
                .build()

            info.requestConsentInfoUpdate(
                activity,
                params,
                {
                    Main.run {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            // Pause the splash clock only when a form will actually appear.
                            formShowing = info.consentStatus == ConsentInformation.ConsentStatus.REQUIRED
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                                Main.run {
                                    formShowing = false
                                    if (error != null) AdLog.w("Consent form: ${error.errorCode} ${error.message}")
                                    finish()
                                }
                            }
                        } else {
                            finish()
                        }
                    }
                },
                { error ->
                    // Offline or misconfigured: fall back to the stored decision from a previous session.
                    AdLog.w("Consent update failed: ${error.errorCode} ${error.message}")
                    Main.run { finish() }
                },
            )
        }
    }

    /** Opens the UMP privacy options form. [onDone] gets [canRequestAds] afterwards. */
    fun showPrivacyOptions(activity: Activity, onDone: (Boolean) -> Unit = {}) {
        Main.run {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                if (error != null) AdLog.w("Privacy options: ${error.errorCode} ${error.message}")
                Main.run {
                    val can = canRequestAds
                    if (can) onCanRequestAds?.invoke()
                    onDone(can)
                }
            }
        }
    }

    /** Debug only: forget consent so the form shows again. */
    fun reset() {
        info.reset()
        gathered = false
    }

    private fun finish() {
        gathering = false
        gathered = true
        val can = canRequestAds
        AdLog.d("Consent done, canRequestAds=$can")
        if (can) onCanRequestAds?.invoke()
        val callbacks = pending.toList()
        pending.clear()
        callbacks.forEach { it(can) }
    }

    private fun debugSettings(activity: Activity): ConsentDebugSettings? {
        if (options.debugGeography == DebugGeography.DISABLED && options.testDeviceHashedIds.isEmpty()) return null
        val geo = when (options.debugGeography) {
            DebugGeography.DISABLED -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED
            DebugGeography.EEA -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
            DebugGeography.NOT_EEA -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
            DebugGeography.REGULATED_US_STATE -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
            DebugGeography.OTHER -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
        }
        return ConsentDebugSettings.Builder(activity)
            .setDebugGeography(geo)
            .apply { options.testDeviceHashedIds.forEach { addTestDeviceHashedId(it) } }
            .build()
    }
}
