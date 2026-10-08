package dev.adkit.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.adkit.AdBannerView
import dev.adkit.AdKit
import dev.adkit.AdNativeView
import dev.adkit.AdState
import dev.adkit.BannerType
import dev.adkit.NativeStyle

/**
 * Banner slot. Collapses itself on failure/premium; reserves height while loading.
 * `AdBanner("home_banner", Modifier.navigationBarsPadding())`
 */
@Composable
fun AdBanner(
    placement: String,
    modifier: Modifier = Modifier,
    type: BannerType? = null,
    onStateChanged: (AdState) -> Unit = {},
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdBannerView(ctx).apply {
                bannerType = type
                this.onStateChanged = onStateChanged
                this.placement = placement
            }
        },
        update = { it.placement = placement },
    )
}

/**
 * Native slot. [retainKey] keeps the ad while the item scrolls out of a LazyColumn.
 * Returns nothing; observe [onStateChanged] to drop an onboarding page on FAILED.
 */
@Composable
fun AdNative(
    placement: String,
    modifier: Modifier = Modifier,
    style: NativeStyle? = null,
    retainKey: String? = null,
    onStateChanged: (AdState) -> Unit = {},
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdNativeView(ctx).apply {
                this.style = style
                this.retainKey = retainKey
                this.onStateChanged = onStateChanged
                this.placement = placement
            }
        },
        update = { it.placement = placement },
    )
}

/** Observable native state, e.g. to hide an onboarding page when no ad filled. */
@Composable
fun rememberAdState(): AdStateHolder = remember { AdStateHolder() }

class AdStateHolder internal constructor() {
    var state by mutableStateOf(AdState.IDLE)
        internal set
    val onChange: (AdState) -> Unit = { state = it }
}

/** No resume app open ad while this composable is on screen (camera, editor, checkout...). */
@Composable
fun SuppressAppOpen() {
    DisposableEffect(Unit) {
        val owner = Any()
        AdKit.holdAppOpen(owner)
        onDispose { AdKit.releaseAppOpen(owner) }
    }
}

/** Current Activity from a composable, for `AdKit.showInterstitial(activity, ...)`. */
@Composable
fun currentActivity(): Activity? = LocalContext.current.findActivity()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
