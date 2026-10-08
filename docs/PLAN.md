# AdKit — Android Ads SDK plan

Goal: one library that covers every ad scenario found across 32 ad-enabled Android apps,
with the fewest possible integration calls. Engine: **GMA Next-Gen SDK**
(`com.google.android.libraries.ads.mobile.sdk`, 1.5.0). Views + Compose. Published via JitPack.

---

## 1. Inventory (what the apps do today)

| Apps | SDK |
|---|---|
| 11 | GMA Next-Gen SDK 1.1–1.5 |
| 20 | Legacy `play-services-ads` 23–25 |
| 1 | Third-party mediation stack, no direct AdMob calls |

The set includes utility, camera, document, media, casting and keyboard (IME) apps, with and
without mediation adapters, Remote Config and Play Billing.

## 2. Scenario matrix (union — SDK must cover all)

### 2.1 Startup
- Consent (UMP) → Remote Config fetch (timeout) → `MobileAds.initialize` on background thread → splash ad → navigate.
- Splash ad = interstitial | app open | none, chosen by config, **different for first launch vs returning**.
- Splash budget timer: pauses while consent form is up and while app is backgrounded; ad loaded while away is shown on resume.
- Optional "Get started" button mode: prepare first, show ad on user tap.
- Splash banner/native with minimum dwell; preload next-screen ads (language native, onboarding natives, interstitials) during splash.
- Offline / premium short-circuit. Callback always fires exactly once. Never hangs (consent refused, RC fail, no fill).
- Gate "ready" on core `MobileAds.isInitialized`, not on adapter-complete callback (~11 s with 9 adapters).
- Requests issued before init are queued until ready.

### 2.2 Gate (one place, ordered, logged reason)
`premium → global kill switch → placement enabled → IME context → consent canRequestAds → SDK ready → network → activity alive → unit id present → frequency → already showing/loading`.

### 2.3 Interstitial
- Preloaded (instant show) **and** on-demand (loading dialog + 4–5 s timeout; late ad kept for next time).
- Frequency: every-N counter (persisted), first-call-shows, time cooldown per placement, global gap between any full-screen ads, blackout after app open, session caps (per placement + global), first-session vs returning overrides, `force` bypass at call site.
- Continuation runs exactly once on main: dismissed / failed / timeout / skipped / premium / offline / disabled.
- Triggers seen: tile click, back press, paywall close, onboarding finish, save/print/download success, theme apply (from RecyclerView adapter / dialog), tab switch.

### 2.4 Rewarded / rewarded interstitial
- Unlock flows (watermark, template, attempts, voice effect, free print). Reward **only** on `onUserEarnedReward`, never on dismiss.
- Early close reported separately. Fallback chain: rewarded → rewarded interstitial → (optional) interstitial.
- Preload on sheet open; on-demand with loading dialog + timeout.

### 2.5 App open
- Resume ad via ProcessLifecycleOwner; skip cold start (splash owns it); 4 h expiry; preload on background and after dismiss.
- Exclusions: activity classes, any full-screen ad showing, short window after a full-screen ad / ad click, `suppressNextAppOpen()` for external flows (permission settings, billing, share, camera, picker, IME settings/picker, Bluetooth/location settings, browser), Compose `SuppressAppOpen()` scope.
- Optional cover ("Welcome back") + 400–500 ms delay + resumed/focus guard. Strict timeout, no blocking white loader.
- Separate splash vs resume units; session cap.

### 2.6 Banner
- Anchored adaptive, large anchored adaptive, inline adaptive, fixed 320×50, large 320×100, MREC 300×250, collapsible top/bottom (`setGoogleExtrasBundle{"collapsible"}`).
- Shimmer placeholder reserving height; collapse on fail/premium; lifecycle-aware destroy; reload when connectivity returns.
- Shared banner across tab activities/fragments (re-register same `BannerAd`).
- Banner vs native switch per placement via config.

### 2.7 Native
- Templates: `BANNER` strip, `SMALL`, `MEDIUM`, `LARGE`, `LARGE_ADJUSTED` (auto by screen height), `FULLSCREEN`. Custom layout via standard view ids.
- Shimmer skeleton per template; collapse on fail; CTA colour from theme/config.
- Placements: language, onboarding page, onboarding full-screen page (drop page on no-fill), full-screen native step with skip countdown, exit dialog, bottom sheet/dialog, RecyclerView rows (retain per key), home/settings/empty states.
- Preload pool keyed by placement, consume-once, TTL 1 h, waiters (hand-over between screens), `destroy()` always.
- Multi-unit waterfall (try unit 1 then unit 2).

### 2.8 Cross-cutting
- Premium: `setPremium()` / provider; live views collapse; caches cleared; in-flight loads discarded (generation check).
- Debug builds auto-swap to Google test unit ids; remote unit-id overrides ignored in test mode.
- All callbacks on main thread (Next-Gen fires on background threads).
- Revenue: `onAdPaid` for **every** format → pluggable sinks (Firebase `ad_impression`, tROAS threshold event, Adjust/AppsFlyer via listener).
- Funnel events: request / load / fail / show / impression / click / skip(reason) with latency.
- Privacy options entry point (`isPrivacyOptionsRequired`, `showPrivacyOptions`) — missing in 31/32 apps.
- Keyboard apps: refuse to render ads inside `InputMethodService` context.
- No static Activity references; lifecycle-bound everything.

## 3. Bugs found in apps that the SDK designs out
Consent ignored / splash hang on refusal / init before consent · callbacks on background thread · double navigation from stale listeners · stuck `isShowingAd` flags · reward granted on dismiss · interstitial cache shared across units · load and show under different unit id · no expiry on cached full-screen ads · natives never destroyed · native loads on `Dispatchers.IO` · prod ids in debug, test ids from RC in prod · RC empty strings overwrite ids · defaults-off first launch · `minimumFetchInterval = 0` in prod · seconds passed as ms · app open after permission/billing/settings returns · Adjust revenue double-counted · `AD_MANAGER_APP` on AdMob app.

## 4. Public API (minimal calls)

```kotlin
// 1. Application.onCreate — the only required setup
AdKit.init(this) {
    testMode = BuildConfig.DEBUG                 // auto Google test ids
    appId = "ca-app-pub-xxx~yyy"                  // or manifest meta-data
    premium(isPro)                                // Boolean or StateFlow<Boolean>
    consent { debugGeographyEea = BuildConfig.DEBUG; testDeviceHashedIds += "..." }
    remoteConfig(FirebaseAdsConfigSource())       // adkit-firebase, optional
    revenue(FirebaseRevenueSink(), TroasSink())   // adkit-firebase, optional

    interstitial("home_tile", "ca-app-pub-.../111") { everyN = 3; cooldownSec = 30 }
    rewarded("unlock", "ca-app-pub-.../222") { fallback = "unlock_ri" }
    rewardedInterstitial("unlock_ri", "ca-app-pub-.../333")
    banner("home", "ca-app-pub-.../444") { type = BannerType.COLLAPSIBLE_BOTTOM }
    native("language", "ca-app-pub-.../555") { style = NativeStyle.MEDIUM; preload = 1 }
    appOpen("resume", "ca-app-pub-.../666") { exclude(SplashActivity::class, PaywallActivity::class) }
    splash { firstLaunch = "splash_inter"; returning = "resume"; timeoutMs = 12_000 }
}

// 2. Splash
AdKit.splash(this) { goHome() }

// 3. Interstitial / rewarded
AdKit.showInterstitial(this, "home_tile") { openTool() }
AdKit.showRewarded(this, "unlock") { if (it.earned) unlock() }

// 4. Banner / native — zero code in XML
<dev.adkit.AdBannerView app:adPlacement="home" />
<dev.adkit.AdNativeView app:adPlacement="language" app:adStyle="medium" />
// Compose
AdBanner("home");  AdNative("language", NativeStyle.MEDIUM)

// 5. Misc
AdKit.showExitDialog(this, "exit") { finish() }
AdKit.showFullScreenNative(this, "onboard_full") { next() }
AdKit.suppressNextAppOpen()          // or activity.startActivityNoAppOpen(intent)
AdKit.consent.showPrivacyOptions(this)
AdKit.setPremium(true)
```

### Remote config schema (single JSON key, default `adkit_config`)
```json
{
  "enabled": true,
  "global_gap_s": 15,
  "after_app_open_gap_s": 10,
  "session_cap": 0,
  "splash": { "first": "splash_inter", "returning": "resume", "timeout_ms": 12000 },
  "placements": {
    "home_tile": { "enabled": true, "format": "interstitial", "units": ["..."], "every_n": 3,
                   "first_shows": true, "cooldown_s": 30, "session_cap": 0,
                   "first_session": { "every_n": 5 } },
    "home":      { "format": "banner", "banner": "collapsible_bottom" },
    "language":  { "format": "native", "style": "medium", "cta_color": "#FF5722" }
  }
}
```
Merge order: code defaults < `assets/adkit_config.json` < cached last-good remote < fresh remote.
Placements defined only remotely are created at runtime (format required). Unit-id overrides
ignored when `testMode`. Legacy flat keys can be mapped with a `legacyMapper` lambda.

## 5. Modules
```
adkit-core      Views. AdKit facade, config DSL, placement registry, gate, frequency store,
                consent (UMP), init/ready queue, interstitial/rewarded/RI/app-open managers,
                splash flow, banner view (+shared), native view + templates + skeletons,
                native pool, exit dialog, full-screen native activity, loading dialog,
                revenue/event listeners, test ids, IME guard, logger.
adkit-compose   AdBanner, AdNative, SuppressAppOpen, findActivity helpers.
adkit-firebase  FirebaseAdsConfigSource (JSON + legacy mapper), FirebaseRevenueSink, TroasSink.
sample          Demo app exercising every scenario with test ids.
```
Deps: ads-mobile-sdk 1.5.0 (api), UMP 4.0.0, lifecycle-process 2.11, coroutines 1.10.2,
firebase-config/analytics (adkit-firebase only). AGP 9.3.2 built-in Kotlin, Gradle 9.5.0,
compileSdk 37, minSdk 24, Java 17. Mediation adapters stay in apps (+ exclude `play-services-ads`).

## 6. Build steps
1. Gradle skeleton: settings, version catalog, 4 modules, JitPack `maven-publish`.
2. Core primitives: logger, main-thread dispatcher, test ids, network, IME guard, prefs store.
3. Config: DSL, placement model, JSON parser/merge, remote source interface.
4. Consent manager + init/ready queue.
5. Gate + frequency (counter / cooldown / gap / session caps / first-session).
6. Full-screen: interstitial, rewarded, rewarded interstitial (preload, on-demand, loading dialog, exactly-once).
7. App-open manager (lifecycle, exclusions, suppression, cover, expiry).
8. Splash flow (budget timer w/ pause, first vs returning, prepare/finish, preloads).
9. Banner view (all sizes, collapsible, shared, skeleton, lifecycle).
10. Native: pool, templates, binder, view, full-screen activity, exit dialog.
11. Revenue + event listeners.
12. Compose module. 13. Firebase module. 14. Sample app. 15. Build + README.

## 7. Later (v1.1)
Native-as-app-open replacement, Gradle task scanning release APK for test ids, Ad Inspector
debug shortcut, AdServices config merge helper doc, ironSource/Pangle consent helpers.
