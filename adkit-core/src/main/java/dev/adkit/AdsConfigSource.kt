package dev.adkit

/**
 * Supplies the remote ads config as a JSON string (schema in README / docs/PLAN.md).
 * `adkit-firebase` ships `FirebaseAdsConfigSource`; implement this for any other backend.
 */
fun interface AdsConfigSource {
    /** Return the latest JSON, or null when unavailable. Called off the main thread. */
    suspend fun fetch(): String?
}
