package dev.adkit.internal

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import dev.adkit.R

/** "Loading ad..." dialog shown during on-demand loads. Replace via `loadingDialogFactory`. */
internal object LoadingDialog {
    fun show(activity: Activity, factory: ((Activity) -> Dialog)?): Dialog? {
        if (!activity.isAlive) return null
        return runCatching {
            val dialog = factory?.invoke(activity) ?: Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(LayoutInflater.from(activity).inflate(R.layout.adkit_loading_dialog, null))
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.setCancelable(false)
            dialog.show()
            dialog
        }.getOrNull()
    }

    fun dismiss(dialog: Dialog?) {
        if (dialog == null) return
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }
}
