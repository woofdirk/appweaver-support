# Support kit — spec

## 0. Decisions since the draft (9 Nov 2026, Dirk)
- **Repo:** `appweaver-support` (github.com/woofdirk/appweaver-support), Android + iOS + analysis tool
  together (§10). Android apps include `android/` as a Gradle **composite build** from a sibling folder.
- **Android package:** `be.webweaver.tools.supportkit` (room for other shared tools under `be.webweaver.tools`).
- **Local first:** share sheet only (§2.1); a central upload (support.webweaver.be) may come later with its own
  consent (§12.4).
- **Small always-on log (§12.1):** a per-app default, `SupportConfig.smallLog` — WoofPilot on (as iOS E63:
  `app`, `system`, `audio`, `listen`, `hb`, 14 days, 2 × 100 kB, hourly heartbeat), games off.
- **Banner (§12.2):** always while support mode is on (`SupportBanner`). **Gesture (§12.3):** 1.5 s on the version line.
- **FileProvider authority:** `${applicationId}.supportkit` (its own `SupportFileProvider` subclass, so it never
  clashes with an app's own provider) instead of `${applicationId}.files`.
- **Crash info (§12.7):** Android 11+ `ApplicationExitInfo` → `[app] previous exit reason=…` at launch.
- **First milestone (§13) changed:** WoofPilot Android is the first app on the kit (parity 36); MyOtherLife migrates next.
- Line redaction: the kit replaces the app's private strings with `‹private›` before writing (a safety net).

Original input spec (Erik, 5 Nov 2026) below.


*Draft, 5 Nov 2026, written from what exists in WoofPilot iOS (E43, `Support.swift`), the
MyOtherLife Android spike (`SupportLog.kt`, `SystemEvents.kt`, `scripts/analyze-log.py`) and the
HomeSim iOS spike (`SupportLog.swift`). Not decided yet: the open questions are at the end.*

## 1. Goal
One support kit for every app (WoofPilot, SnorePilot, the Appweaver games, …) and both platforms,
so that when something goes wrong on a real phone we can see what happened, without collecting
data and without building it again for each app. Different apps need different data; the kit
gives the frame (log, heartbeat, device facts, support mode, pack, sharing, analysis) and each
app plugs in its own categories, snapshot values and pack items.

**Why now:** three apps already have their own copy (WoofPilot iOS `DiagLog` + `SupportPack`,
HomeSim iOS `SupportLog`, MyOtherLife Android `SupportLog`); WoofPilot Android still has to build
one ("todo Dirk", `working-together.md`); more apps follow.

## 2. Principles (from E43, A3 and the Google Play work)
1. **Local only.** Nothing is sent by the kit. The owner shares a pack through the system share
   sheet; the app needs no network permission for it. This keeps Google Play's Data safety and
   Apple's privacy labels at "no data collected" (`android-release/05-data-safety.md`).
2. **Off by default, opt-in, visible.** Switched on by the owner (hidden gesture, then a
   confirmation that lists what is kept); a banner on every screen while it is on.
3. **Events and numbers only.** Never names (person, pet), e-mail, free text typed by the user,
   account data, or audio — unless an app adds a pack item that is explicitly marked and ticked
   by the owner (WoofPilot clips).
4. **Same format everywhere.** One line format, one pack format, same core categories on Android
   and iOS, so logs of two platforms or two apps can be compared and analysed by one tool.
5. **Bounded.** Rotating files (≈ 2 MB total by default), excluded from every backup, deleted
   when the owner switches it off or taps "Delete".
6. **Cheap.** One writer thread, no work when off, no wake-ups of its own (the heartbeat only
   runs while the app runs).

## 3. Parts
| Part | Android | iOS | Notes |
|---|---|---|---|
| Log writer | Kotlin library module | Swift package | Line format §4, rotation, flush |
| System events | lifecycle, memory, power, thermal, battery, boot | same with iOS sources | Core categories §5 |
| Heartbeat | every N minutes while the app runs | same | App adds values |
| Support mode UI | Compose screens: switch + confirmation, banner, pack screen | SwiftUI | Strings NL/EN (+FR/DE for WoofPilot) |
| Pack | zip + `manifest.json` + `README.txt` | same | §7 |
| Sharing | share sheet via `FileProvider` | share sheet | No upload |
| Analysis tool | Python, one script/package | — | Reads `.txt` logs and `.zip` packs; app plugins §9 |
| Docs | privacy-policy paragraph, Data safety answer, Help text | same | Copy into each app |

## 4. Line format (`support-log/1`)
```
2026-11-05 21:14:03 +01:00 [category] message key=value key=value
```
- Local time **with offset** (so time-zone and DST changes are visible), one event per line, UTF-8.
- `category`: lowercase `[a-z0-9_]+`. Core categories are reserved (§5); apps register their own.
- Message: a short fixed phrase (`catch-up`, `delivered`), then `key=value` pairs. Values without
  spaces; times inside values as `yyyy-MM-ddTHH:mm:ssXXX`; units in the key or value (`gap=120s`,
  `took=3.1ms`, `battery=82%`). No free text from the user, ever.
- **Today's lines differ a little** (e.g. `next=2026-11-05 18:40:33 +01:00` with spaces in
  MyOtherLife, `v0.5(6)` in WoofPilot): the kit's version is stricter, the analysis tool reads both.
- Export header (first lines of the shared file, before the log):
  `format: support-log/1`, app id, version (build), platform, OS, device model, locale, time zone,
  kit version, then one empty line.

## 5. Core categories (kit-provided, same on both platforms)
| Category | Lines |
|---|---|
| `log` | `on`, `off`, `cleared`, `rotated` |
| `app` | `launch cold\|warm`, `foreground`, `background`, `active`, `inactive`, `trim memory level=…`, `low memory`, `crash previous-run` (if the platform tells), `save failed …` (helper) |
| `system` | `boot`, `update` (app updated), `power charging\|unplugged`, `battery level=…%`, `low power on\|off`, `thermal …`, `time zone …`, `locale …`, `notifications allowed\|blocked` |
| `hb` | every N minutes while running (default 5; WoofPilot 10): `battery=… thermal=… lowPower=… memory=…MB` + app values (§6) |

App categories seen so far: MyOtherLife `sim`, `widget`, `notify`, `render`; WoofPilot `audio`,
`listen`, `bark`, `window`, `response`, `clip`, `voice`. The kit lists registered categories in
the export header so the analysis tool knows them.

## 6. What an app plugs in
- **Categories** with a one-line description each (for the header and the analysis tool).
- **Heartbeat values**: a callback returning `key=value` pairs (WoofPilot: engine, mic state,
  level, samples received, route, volume; MyOtherLife: `activity=`).
- **Pack items** (§7), each with: id, title and description (NL/EN), **sensitivity**
  (`numbers` / `settings` / `history` / `audio`), ticked by default or not, a size estimate, and a
  function that writes its files into the pack folder. Examples:
  - WoofPilot: detection history (`detections.json`, …), settings without name (`settings.txt`),
    last 3 or 10 recordings (`audio`, never ticked by default).
  - MyOtherLife: game state (`resident.json`) **with the resident's and dog's names removed**,
    planned notifications.
- **Confirmation text**: what the app keeps when support mode is on (the kit adds the core part).
- **Redaction**: the kit offers a helper and a test that fails if a pack or log contains strings
  the app marks as private (names, e-mail) — apps register them at runtime (e.g. the dog's name).

## 7. Pack (`support-pack/2`)
A zip, made on request in the cache/temporary folder, deleted after sharing or on next start:
- `manifest.json`: `format: "support-pack/2"`, `appId`, `version`, `build`, `platform`, `os`,
  `deviceModel`, `locale`, `timeZone`, `createdAt` (ISO 8601 with offset), `kitVersion`,
  `logFormat: "support-log/1"`, `items: [{id, file(s), sensitivity, bytes}]`.
  Compatible with WoofPilot's `support-pack/1` (same keys; `items` was a list of names): the
  analysis tool reads both.
- `log.txt`: the whole log, oldest first, with the export header.
- One file or folder per ticked item.
- `README.txt` (NL or EN, the phone's language): what is inside, and what is **not** (names,
  e-mail, account, …), in plain words.

**Pack screen:** list of items with switches (log always on), size, "audio" items with a warning,
"Share" (share sheet) and "Delete log". The share sheet decides where it goes (mail, Drive,
AirDrop, Dropbox); the kit logs `log pack shared parts=… bytes=…` without the destination.

## 8. Platform notes
- **Android:** files in `noBackupFilesDir` (also excluded from device-to-device transfer, A3);
  `FileProvider` authority `${applicationId}.files`; `flush()` for broadcast receivers that end
  right after logging (alarms, boot); `ApplicationExitInfo` (Android 11+) for "why did the
  process die" at the next launch (crash, ANR, low memory, user swipe) — very useful for spike
  tests, not in today's code. A release build can't be read over `adb`: sharing is the only way.
- **iOS:** Application Support with `isExcludedFromBackup`; App Group container when a widget
  extension also logs (HomeSim); `MetricKit` diagnostics (crash, hang) as an optional pack item.
- **Both:** device model name (iOS needs a mapping table, `DeviceModelName` in WoofPilot), OS
  version, locale, time zone in the header and the manifest.

## 9. Analysis tool
Starts from MyOtherLife's `scripts/analyze-log.py` (tests and fixture included) and generalises:
- Reads `.txt` and `.zip` (manifest + log + items), both line-format generations, several files
  in a row (rotation), `--since`.
- **Core report** for every app: time span, versions seen, app sessions (launch → background),
  **gaps** (no line where one was expected, e.g. missing heartbeats = process killed), restarts,
  battery per hour while open and while closed (from `hb`, flagged when charging), thermal and
  low-power periods, memory warnings, unusual lines.
- **App plugins**: one small module per app or test (MyOtherLife spike S1–S4: catch-up, widget
  lateness, notification lateness and duplicates, fps; WoofPilot: barks per hour, window scores,
  responses, mic interruptions). A plugin declares the categories it reads.
- Output: Markdown (to paste into a results doc), optional JSON.
- Shared fixtures: the same made-up logs/packs for both platforms, used by the tool's tests and
  by each platform's format tests (writer output must parse).

## 10. Distribution (to decide)
- Android: a library module in its own Git repo, included as a **composite build** or published
  to GitHub Packages; versioned with tags.
- iOS: a Swift package in its own repo (or the same repo, `Package.swift` at the root).
- Analysis tool: in the same repo, runnable as one script.
- CI in that repo: unit tests on both platforms, format round-trip against the shared fixtures.

## 11. Migration
| App | Today | With the kit |
|---|---|---|
| MyOtherLife Android | `SupportLog.kt`, `SystemEvents.kt`, `DiagnosticsScreen`, `analyze-log.py` | Replace with the kit; `sim`/`widget`/`notify`/`render` as app categories; S1–S4 as an analysis plugin |
| HomeSim / MyOtherLife iOS | `SupportLog.swift`, `SystemEvents.swift`, `SupportViews.swift` | Same, Swift package |
| WoofPilot iOS | `DiagLog`, `SupportPack` (`support-pack/1`), orange banner | Kit; history, settings and clips as pack items; keep the banner |
| WoofPilot Android | not built yet (todo) | Build directly on the kit: no second migration |
| SnorePilot, next Appweaver apps | — | Kit from the start |

## 12. Open questions
1. **Always-on minimal log** (Erik's proposal of 25 Oct, WoofPilot `DECISIONS-IOS.md`, battery section): keep a small log (lifecycle, charger,
   interruptions, errors, hourly heartbeat; ≈ 200 kB, 14 days) even when support mode is off,
   with a "Send log to support" button in Help? Helps with "it stopped last night" reports;
   must then be in every privacy policy and stay local. Default in the kit: off, per-app switch.
2. **Banner while on:** always (WoofPilot) or app's choice (MyOtherLife has none)? Proposal:
   always, it is the visible side of the consent.
3. **Support mode gesture:** keep "hold the version line 1.5 s" everywhere?
4. **Server upload later** (WoofPilot spec 14 proposal): out of scope for the kit's first version;
   if it comes, a separate consent and Data safety change. Note: WoofPilot's parity checklist
   points to `spec/14-help-and-release-notes.md`, which does not exist in either WoofPilot repo.
5. **Repo and distribution** (§10), name of the package (`be.webweaver.supportkit`?).
6. **Languages** of the kit's own strings: NL/EN for all, FR/DE when an app needs them (WoofPilot).
7. **Crash information:** use the platform's own (`ApplicationExitInfo`, MetricKit) as a pack item —
   no crash-reporting SDK (that would be "data collected").

## 13. First milestone (proposal)
Android library + analysis tool, migrated into MyOtherLife (smallest app, already has tests),
then WoofPilot Android builds on it; iOS package second, migrated into HomeSim/MyOtherLife iOS
and WoofPilot iOS together with Erik.
