package dev.adkit.sample

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import dev.adkit.AdKit
import dev.adkit.startActivityNoAppOpen

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        click(R.id.btn_interstitial) {
            AdKit.showInterstitial(this, "tool_click") { show("interstitial: $it") }
        }
        click(R.id.btn_rewarded) {
            AdKit.showRewarded(this, "unlock") { r ->
                show(if (r.earned) "reward earned (${r.servedBy})" else "no reward: ${r.skipReason}")
            }
        }
        click(R.id.btn_fullscreen_native) {
            AdKit.showFullScreenNative(this, "onboard_full") { show("full-screen native: $it") }
        }
        click(R.id.btn_list) { startActivity(Intent(this, ListActivity::class.java)) }
        click(R.id.btn_compose) {
            AdKit.showInterstitial(this, "tool_click") { startActivity(Intent(this, ComposeActivity::class.java)) }
        }
        click(R.id.btn_share) {
            // External flow: no app open ad when the user comes back.
            startActivityNoAppOpen(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "AdKit"), null))
        }
        click(R.id.btn_premium) {
            AdKit.setPremium(!AdKit.isPremium)
            show("premium = ${AdKit.isPremium}")
        }
        click(R.id.btn_inspector) { AdKit.openAdInspector() }

        val privacy = findViewById<Button>(R.id.btn_privacy)
        privacy.visibility = if (AdKit.consent.isPrivacyOptionsRequired) View.VISIBLE else View.GONE
        privacy.setOnClickListener { AdKit.consent.showPrivacyOptions(this) { show("canRequestAds=$it") } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AdKit.showExitDialog(this@MainActivity, "exit") { exit -> if (exit) finish() }
            }
        })
    }

    private fun click(id: Int, block: () -> Unit) = findViewById<View>(id).setOnClickListener { block() }

    private fun show(text: String) {
        status.text = text
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
