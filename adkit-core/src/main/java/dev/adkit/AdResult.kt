package dev.adkit

/** Why an ad was not shown. Logged and reported to [AdEventListener]. */
enum class SkipReason {
    PREMIUM,
    DISABLED,
    PLACEMENT_DISABLED,
    UNKNOWN_PLACEMENT,
    WRONG_FORMAT,
    IME_CONTEXT,
    NO_CONSENT,
    NOT_INITIALIZED,
    OFFLINE,
    ACTIVITY_GONE,
    NO_UNIT,
    FREQUENCY,
    ALREADY_SHOWING,
    NOT_LOADED,
    TIMEOUT,
    LOAD_FAILED,
    SHOW_FAILED,
}

/** Outcome of a full-screen show call. Delivered exactly once, on the main thread. */
data class AdResult(
    val placement: String,
    val shown: Boolean,
    val skipReason: SkipReason? = null,
    val message: String? = null,
) {
    companion object {
        internal fun shown(placement: String) = AdResult(placement, true)
        internal fun skipped(placement: String, reason: SkipReason, message: String? = null) =
            AdResult(placement, false, reason, message)
    }
}

/** Outcome of [AdKit.showRewarded]. Grant the reward only when [earned] is true. */
data class RewardResult(
    val placement: String,
    /** The user watched long enough to earn the reward. */
    val earned: Boolean,
    /** An ad was actually displayed (false = no fill, offline, premium, ...). */
    val shown: Boolean,
    val rewardType: String? = null,
    val rewardAmount: Int = 0,
    val skipReason: SkipReason? = null,
    /** Placement that actually served (differs from [placement] when a fallback was used). */
    val servedBy: String = placement,
)
