package dev.adkit.internal

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import dev.adkit.NativeStyle
import dev.adkit.R

/**
 * Inflates a template (or the app's own layout) inside a [NativeAdView] and binds assets by id:
 * ad_headline, ad_body, ad_call_to_action, ad_app_icon, ad_media, ad_advertiser, ad_stars,
 * ad_price, ad_store. Missing views and missing assets are skipped.
 */
internal object NativeBinder {
    fun resolve(context: Context, style: NativeStyle): NativeStyle {
        if (style != NativeStyle.LARGE_ADJUSTED) return style
        val dm = context.resources.displayMetrics
        val heightDp = dm.heightPixels / dm.density
        return if (heightDp >= 700) NativeStyle.LARGE else NativeStyle.MEDIUM
    }

    fun layoutFor(style: NativeStyle): Int = when (style) {
        NativeStyle.BANNER -> R.layout.adkit_native_banner
        NativeStyle.SMALL -> R.layout.adkit_native_small
        NativeStyle.MEDIUM, NativeStyle.LARGE_ADJUSTED -> R.layout.adkit_native_medium
        NativeStyle.LARGE -> R.layout.adkit_native_large
        NativeStyle.FULLSCREEN -> R.layout.adkit_native_fullscreen
    }

    fun create(context: Context, style: NativeStyle, customLayout: Int): NativeAdView {
        val adView = NativeAdView(context)
        val layout = if (customLayout != 0) customLayout else layoutFor(style)
        LayoutInflater.from(context).inflate(layout, adView, true)
        return adView
    }

    fun bind(adView: NativeAdView, ad: NativeAd, ctaColor: Int?) {
        adView.find<TextView>(R.id.ad_headline)?.let { v ->
            v.text = ad.headline
            adView.headlineView = v
        }
        adView.find<TextView>(R.id.ad_body)?.let { v ->
            v.setTextOrGone(ad.body)
            adView.bodyView = v
        }
        adView.find<TextView>(R.id.ad_call_to_action)?.let { v ->
            v.setTextOrGone(ad.callToAction)
            ctaColor?.let { v.backgroundTintList = ColorStateList.valueOf(it) }
            adView.callToActionView = v
        }
        adView.find<ImageView>(R.id.ad_app_icon)?.let { v ->
            val icon = ad.icon?.drawable
            if (icon != null) { v.setImageDrawable(icon); v.visibility = View.VISIBLE } else v.visibility = View.GONE
            adView.iconView = v
        }
        adView.find<TextView>(R.id.ad_advertiser)?.let { v ->
            v.setTextOrGone(ad.advertiser)
            adView.advertiserView = v
        }
        adView.find<RatingBar>(R.id.ad_stars)?.let { v ->
            val stars = ad.starRating
            if (stars != null && stars > 0) { v.rating = stars.toFloat(); v.visibility = View.VISIBLE } else v.visibility = View.GONE
            adView.starRatingView = v
        }
        adView.find<TextView>(R.id.ad_price)?.let { v ->
            v.setTextOrGone(ad.price)
            adView.priceView = v
        }
        adView.find<TextView>(R.id.ad_store)?.let { v ->
            v.setTextOrGone(ad.store)
            adView.storeView = v
        }
        val media = adView.find<MediaView>(R.id.ad_media)
        adView.registerNativeAd(ad, media)
    }

    private fun <T : View> View.find(id: Int): T? = findViewById(id)

    private fun TextView.setTextOrGone(text: String?) {
        if (text.isNullOrBlank()) visibility = View.GONE else { this.text = text; visibility = View.VISIBLE }
    }
}
