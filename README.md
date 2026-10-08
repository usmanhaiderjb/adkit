# AdKit

AdKit is an Android ads SDK built on the GMA **Next-Gen** SDK (`ads-mobile-sdk` 1.5.0). It covers every ad scenario used across our apps. Most screens need one call or none.

- **Planning notes:** `docs/PLAN.md` holds the scenario matrix, the API design and the bugs it fixes.

| Module | What it contains |
|---|---|
| `adkit-core` | Engine, consent (UMP), all ad formats, XML views |
| `adkit-compose` | `AdBanner`, `AdNative`, `SuppressAppOpen` |
| `adkit-firebase` | Remote Config source, `ad_impression` revenue sink, tROAS sink |
| `sample` | Demo app covering every scenario, using test ids |

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }

// app/build.gradle.kts
implementation("com.github.usmanhaiderjb.adkit:adkit-core:v1.0.0")
implementation("com.github.usmanhaiderjb.adkit:adkit-compose:v1.0.0")   // optional
implementation("com.github.usmanhaiderjb.adkit:adkit-firebase:v1.0.0")  // optional

// Required when you add mediation adapters (they still pull the legacy SDK):
configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
```

## Integrate

### 1. Application: the only required setup

```kotlin
AdKit.init(this) {
    testMode = BuildConfig.DEBUG              // debug builds use Google test units automatically
    premium(prefs.isPro)                      // or premium(billing.isProFlow)
    remoteConfig = FirebaseAdsConfigSource()  // optional
    revenue(FirebaseRevenueSink(this@App), TroasSink(this@App))

    splash { firstLaunch = "splash_inter"; returning = "splash_open"; preload += "language" }

    interstitial("splash_inter", "ca-app-pub-x/1") { preload = false }
    appOpen("splash_open", "ca-app-pub-x/2") { resume = false }
    appOpen("resume", "ca-app-pub-x/3") { exclude(SplashActivity::class, PaywallActivity::class) }
    interstitial("tool_click", "ca-app-pub-x/4") { everyN = 3; cooldownSec = 30 }
    rewarded("unlock", "ca-app-pub-x/5") { fallback = "unlock_ri" }
    rewardedInterstitial("unlock_ri", "ca-app-pub-x/6")
    banner("home_banner", "ca-app-pub-x/7") { type = BannerType.COLLAPSIBLE_BOTTOM }
    native("language", "ca-app-pub-x/8") { style = NativeStyle.MEDIUM; preloadCount = 1 }
    fullScreenNative("onboard_full", "ca-app-pub-x/9")
}
```

The app id comes from `appId` or from the manifest meta-data `com.google.android.gms.ads.APPLICATION_ID`.

### 2. Screens

```kotlin
AdKit.splash(this) { goHome() }                                  // consent → config → init → ad → continue
AdKit.showInterstitial(this, "tool_click") { openTool() }        // counters, cooldowns and dialog handled
AdKit.showRewarded(this, "unlock") { if (it.earned) unlock() }
AdKit.showFullScreenNative(this, "onboard_full") { next() }
AdKit.showExitDialog(this, "exit") { exit -> if (exit) finish() }
```

```xml
<dev.adkit.AdBannerView app:adPlacement="home_banner" />
<dev.adkit.AdNativeView app:adPlacement="language" app:adStyle="medium" />
<dev.adkit.AdNativeView app:adPlacement="list" app:adStyle="small" app:adRetainKey="row7" />
```

```kotlin
AdBanner("home_banner")
AdNative("language", style = NativeStyle.LARGE)
SuppressAppOpen()   // no resume ad while this composable is shown
```

### 3. Everything else

| Need | Call |
|---|---|
| Premium bought or expired | `AdKit.setPremium(true / false)`. Live views collapse; caches are cleared. |
| Privacy settings entry (EEA/UK) | Show the entry when `AdKit.consent.isPrivacyOptionsRequired` is true, then call `AdKit.consent.showPrivacyOptions(activity)` |
| External intent (share, settings, picker) | `startActivityNoAppOpen(intent)`, `launcher.launchNoAppOpen(input)`, `AdKit.suppressNextAppOpen()` |
| Long flow without app open (camera, editor) | `AdKit.holdAppOpen(owner)` / `releaseAppOpen(owner)` |
| "Get started" button splash | `AdKit.splashPrepare(this) { showButton() }`, then on tap call `AdKit.splashShow(this) { goHome() }` |
| Must-show moment | `AdKit.showInterstitial(this, "x", force = true) { }` |
| Coroutines | `AdKit.awaitInterstitial(activity, "x")`, `AdKit.awaitRewarded(activity, "x")` |
| Analytics funnel | `AdKit.addEventListener { e -> }`: requested, loaded, failed, shown, clicked and skipped (with reason) |
| Revenue to Adjust / AppsFlyer | `AdKit.addRevenueListener { r -> /* r.value, r.currencyCode, r.adSourceName */ }` |
| Remote values for app code | `AdKit.placement("x")?.extras`, `AdKit.remoteExtras` |
| Debug | `AdKit.openAdInspector()`, Logcat tag `AdKit` |

## Placement options

| Option | Formats | Default | Meaning |
|---|---|---|---|
| `units` | all | — | Waterfall; tried in order |
| `enabled` | all | `true` | Per-placement kill switch |
| `preload` | full-screen | `true` | Keeps one ad ready so show is instant |
| `loadOnDemand` | full-screen | `true` (`false` for app open) | If nothing is cached, loads behind the loading dialog |
| `timeoutMs` | full-screen | 4500 (8000 for rewarded) | Maximum wait for an on-demand load |
| `everyN`, `firstShows` | interstitial, full-screen native | 1, `true` | Show every Nth call; the first call shows |
| `cooldownSec`, `sessionCap` | full-screen | 0 | Time between shows; maximum per session. App open also respects `globalGapSec`; rewarded skips the global cap and gap |
| `firstSession` | full-screen | — | Override `everyN` / `cooldownSec` / `enabled` on the first launch |
| `fallback`, `rewardOnFallback` | rewarded | —, `false` | Next placement to try when there is no fill. A rewarded interstitial fallback rewards through its own callback. `rewardOnFallback` also rewards a plain interstitial fallback; it is off by default because AdMob policy forbids it |
| `expiryHours` | app open | 4 | Age after which a cached ad is dropped |
| `nativeFallback` | app open | — | Native placement shown full-screen on resume when no app open ad is ready |
| `resume`, `exclude(...)` | app open | `true` | Resume placement and the activities it skips |
| `type`, `inlineMaxHeightDp`, `shared` | banner | `ADAPTIVE` | Size, collapsible, inline, or kept across tabs |
| `style`, `layout`, `ctaColor`, `preloadCount` | native | `MEDIUM` | Template or custom layout, CTA colour, pool size |
| `skipDelaySec` | full-screen native | 3 | Delay before the close button appears |
| `collapseOnFail` | banner, native | `true` | Hide the slot when there is no ad |

Global options: `adsEnabled`, `globalGapSec`, `afterAppOpenGapSec`, `sessionCap`, `requireNetwork`, `appOpenDelayMs`, `appOpenCover`, `loadingDialogFactory` and `testDeviceIds`.

A custom native layout uses these ids: `ad_headline`, `ad_body`, `ad_call_to_action`, `ad_app_icon`, `ad_media` (a `MediaView`), `ad_advertiser`, `ad_stars`, `ad_price`, `ad_store` and `ad_badge`. Theme the built-in templates by overriding the `adkit_*` colours.

## Remote config

The config lives in one JSON parameter, `adkit_config`. Layers are applied in this order:

1. Code
2. `assets/adkit_config.json`
3. Last cached remote
4. Fresh remote

```json
{
  "enabled": true,
  "global_gap_s": 15,
  "after_app_open_gap_s": 10,
  "session_cap": 0,
  "app_open_resume": "resume",
  "splash": { "first": "splash_inter", "returning": "splash_open", "timeout_ms": 12000 },
  "extras": { "paywall_variant": 2 },
  "placements": {
    "tool_click": { "every_n": 3, "first_shows": true, "cooldown_s": 30, "first_session": { "every_n": 5 } },
    "home_banner": { "format": "native", "style": "small" },
    "language": { "style": "large", "cta_color": "#FF5722" },
    "new_slot": { "format": "interstitial", "units": ["ca-app-pub-x/10"] }
  }
}
```

Behaviour of the remote layer:

- **New placements:** a placement defined only remotely is created at runtime, as long as it has a `format`.
- **Format swaps:** remote config may change a placement's format within its family, either banner ↔ native or interstitial ↔ rewarded interstitial. `AdBannerView` and `AdNativeView` host the other format automatically.
- **Unit ids in test mode:** remote unit ids are ignored when `testMode` is on.
- **Empty values:** an empty value never overwrites a default.

For apps with legacy flat keys, pass `FirebaseAdsConfigSource(legacyMapper = { rc -> JSONObject(...) })`.

## Guarantees

- **Callbacks:** every callback runs exactly once, on the main thread. That includes no fill, timeouts, offline, premium and consent refused.
- **Splash:** the splash never hangs. Its timer stops while the app is in the background or the consent form is on screen. If the user leaves the splash during preparation, nothing navigates.
- **Requests:** nothing is requested before consent allows it (`canRequestAds`). Readiness is gated on core init, not on mediation adapters.
- **Rewards:** a reward is granted only on the earned-reward callback, never on dismiss.
- **App open:** it skips cold start, the time right after another full-screen ad, ad-click returns, excluded activities, suppressed flows and ad SDK activities.
- **Keyboards:** no ad renders inside an `InputMethodService`.
- **Expiry:** cached ads expire (full-screen after 55 minutes, app open after the configured hours, natives after 1 hour). Natives are always destroyed.

## Release check (Gradle plugin)

`dev.adkit.check` makes every `assemble*Release` / `bundle*Release` scan the APK or AAB it produces. The build fails if a Google test ad id is still inside.

```kotlin
// settings.gradle.kts
pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral(); maven("https://jitpack.io") } }

// app/build.gradle.kts
buildscript { dependencies { classpath("com.github.usmanhaiderjb.adkit:adkit-gradle-plugin:v1.0.0") } }
apply(plugin = "dev.adkit.check")
adkitCheck { failOnTestIds.set(true) }   // false = warn only
```

AdKit also logs an error at startup when a non-debuggable build uses a test unit.

## Tests

```bash
./gradlew :adkit-core:testDebugUnitTest :adkit-gradle-plugin:test
```

The tests use Robolectric and JUnit. They cover:

- the remote config merge
- the frequency rules: every Nth call, cooldowns, gaps, caps and force
- test ids
- the exactly-once callback
- the release archive scanner

## Build

```bash
./gradlew :adkit-core:assembleRelease :adkit-compose:assembleRelease :adkit-firebase:assembleRelease :sample:assembleDebug
```
