package dev.adkit.sample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.adkit.AdKit

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // One call: consent -> remote config -> SDK init -> splash ad -> continue.
        AdKit.splash(this) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
