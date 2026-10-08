package dev.adkit.sample

import android.app.Application
import dev.adkit.AdKit
import dev.adkit.BannerType
import dev.adkit.DebugGeography
import dev.adkit.FirstSessionRules
import dev.adkit.NativeStyle

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdKit.init(this) {
            // Debug builds swap every unit for Google's test units; the ids below never load here.
            testMode = BuildConfig.DEBUG
            consent {
                debugGeography = if (BuildConfig.DEBUG) DebugGeography.EEA else DebugGeography.DISABLED
                // UMP prints this id in logcat ("addTestDeviceHashedId"); only listed devices get the debug form.
                if (BuildConfig.DEBUG) testDeviceHashedIds += "C493DE39C32144BEE366ECC526F2082C"
            }
            globalGapSec = 15
            afterAppOpenGapSec = 10

            splash {
                firstLaunch = "splash_inter"
                returning = "splash_open"
                timeoutMs = 12_000
                preload += "language"
            }

            interstitial("splash_inter", "ca-app-pub-0000000000000000/1000000001") { preload = false }
            appOpen("splash_open", "ca-app-pub-0000000000000000/1000000002") { resume = false; preload = false }
            appOpen("resume", "ca-app-pub-0000000000000000/1000000003") {
                exclude(SplashActivity::class)
                nativeFallback = "onboard_full" // no app open ready on resume? show this native full-screen
            }

            interstitial("tool_click", "ca-app-pub-0000000000000000/1000000004") {
                everyN = 3
                cooldownSec = 30
                firstSession = FirstSessionRules(everyN = 5)
            }
            interstitial("back_press", "ca-app-pub-0000000000000000/1000000005") { everyN = 2 }

            rewarded("unlock", "ca-app-pub-0000000000000000/1000000006") { fallback = "unlock_ri" }
            rewardedInterstitial("unlock_ri", "ca-app-pub-0000000000000000/1000000007")

            banner("home_banner", "ca-app-pub-0000000000000000/1000000008") { type = BannerType.COLLAPSIBLE_BOTTOM }
            banner("tabs_banner", "ca-app-pub-0000000000000000/1000000009") { shared = true }
            native("language", "ca-app-pub-0000000000000000/1000000010") { style = NativeStyle.MEDIUM; preloadCount = 1 }
            native("list", "ca-app-pub-0000000000000000/1000000011") { style = NativeStyle.SMALL }
            native("exit", "ca-app-pub-0000000000000000/1000000012") { style = NativeStyle.MEDIUM; preloadCount = 1 }
            fullScreenNative("onboard_full", "ca-app-pub-0000000000000000/1000000013") { skipDelaySec = 3 }

            events({ e -> android.util.Log.v("AdKitSample", e.toString()) })
            revenue({ r -> android.util.Log.i("AdKitSample", "paid ${r.value} ${r.currencyCode} ${r.placement}") })
        }
    }
}
