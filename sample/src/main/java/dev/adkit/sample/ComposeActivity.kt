package dev.adkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.adkit.AdKit
import dev.adkit.NativeStyle
import dev.adkit.compose.AdBanner
import dev.adkit.compose.AdNative
import dev.adkit.compose.SuppressAppOpen
import dev.adkit.compose.currentActivity

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    // This screen never gets a resume app open ad.
    SuppressAppOpen()
    val activity = currentActivity()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Compose slots", style = MaterialTheme.typography.titleLarge)
        AdNative("language", style = NativeStyle.MEDIUM)
        Button(onClick = { activity?.let { AdKit.showInterstitial(it, "tool_click") } }) { Text("Interstitial") }
        Column(Modifier.weight(1f)) {}
        AdBanner("tabs_banner")
    }
}
