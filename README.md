# appweaver-support

One support kit for all our apps (WoofPilot, SnorePilot, the Appweaver games, …): a local
diagnostic log with an optional small always-on part, support mode with consent, and a support
pack the owner shares through the share sheet. **Nothing is sent by the kit.**

- [`SPEC.md`](SPEC.md) — the input spec (Erik, 5 Nov 2026) with the decisions taken since (§0).
- `android/` — Android library `be.webweaver.tools.supportkit` (Kotlin, Compose).
- `tools/` — analysis tool (to come: from MyOtherLife's `analyze-log.py`).
- iOS Swift package — to come (moved in from `woofpilot-ios`, `Support.swift`).

## Using it from an Android app

The app's repo sits next to this one; include it as a composite build:

```kotlin
// settings.gradle.kts of the app
includeBuild("../appweaver-support/android")
// app/build.gradle.kts
implementation("be.webweaver.tools:supportkit")
```

Keep `agp` and `kotlin` in `android/gradle/libs.versions.toml` equal to the app's (one plugin
classpath). Then, in `Application.onCreate`:

```kotlin
SupportKit.init(this, SupportConfig(
    filePrefix = "woofpilot", version = "0.1", build = "162",
    appCategories = mapOf("listen" to "listening start/stop"),
    smallLog = true, smallLogCategories = SupportKit.CORE_CATEGORIES + "listen",
    heartbeatValues = { "listening=true" },
    privateStrings = { listOf(dogName) },
    packItems = listOf(/* PackItem … */),
    texts = SupportTexts(intro = R.string.…, consent = R.string.…, kept = listOf(…), smallLog = R.string.…, notIncluded = R.string.…),
))
SupportKit.log("listen", "start sensitivity=MEDIUM")
SupportKit.log("window") { "score=${score} threshold=${t}" }   // built only when kept
```

UI: `SupportScreen(onDone)` (open it from a 1.5 s long-press on the version line) and
`SupportBanner(onClick)` at the top of every screen.

## Build and test

```bash
cd android && ./gradlew :supportkit:testDebugUnitTest
```
