package dev.adkit.internal

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.adkit.NativeStyle
import dev.adkit.R

/** Pulsing placeholder that reserves the ad's space so the layout never jumps. */
internal object Skeleton {
    fun banner(context: Context, heightPx: Int): View =
        View(context).apply {
            setBackgroundResource(R.drawable.adkit_skeleton_block)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
            pulse(this)
        }

    fun native(context: Context, style: NativeStyle): View {
        val layout = when (style) {
            NativeStyle.BANNER, NativeStyle.SMALL -> R.layout.adkit_skeleton_row
            NativeStyle.FULLSCREEN -> R.layout.adkit_skeleton_fullscreen
            else -> R.layout.adkit_skeleton_media
        }
        return LayoutInflater.from(context).inflate(layout, null, false).apply {
            if (style == NativeStyle.LARGE) {
                findViewById<View>(R.id.adkit_skeleton_media)?.layoutParams?.height =
                    (220 * resources.displayMetrics.density).toInt()
            }
            pulse(this)
        }
    }

    fun stop(view: View) {
        (view.getTag(R.id.adkit_tag_anim) as? ValueAnimator)?.cancel()
    }

    private fun pulse(view: View) {
        val anim = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        view.setTag(R.id.adkit_tag_anim, anim)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = anim.start()
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
    }
}
