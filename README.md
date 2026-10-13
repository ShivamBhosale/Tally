# Tally

Personal, sideloaded Android app that counts the Instagram Reels and YouTube Shorts you watch, with a live floating counter. On-device only: **no `INTERNET` permission**, no analytics, no network calls.

Flutter UI + Kotlin `AccessibilityService`. Min SDK 26, target SDK 36.

## Build, install, test

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
flutter build apk --release        # or --debug (debug adds decision logs, see below)
adb install -r build/app/outputs/flutter-apk/app-release.apk   # app-debug.apk for a debug build
(cd android && ./gradlew :app:testDebugUnitTest)               # counting-logic unit tests (20)
```

No hot reload: with no network permission, `flutter run` cannot attach. Rebuild and reinstall instead.
After a reinstall the accessibility service stays enabled. `adb shell am force-stop` (and Force stop in App info) switches it off; re-enable it in Accessibility, or over adb: `adb shell settings put secure enabled_accessibility_services <existing>:com.shivam.tally/com.shivam.tally.service.TallyAccessibilityService`.

## First-time setup (Pixel / Android 13+)

1. Open Tally → **SETUP** → **OPEN APP INFO** → ⋮ (top right) → **Allow restricted settings**.
2. Settings → Accessibility → Installed apps → **Tally** → On.
3. SETUP → **EXEMPT** (battery optimization). The SETUP tab shows service enabled / alive, battery exemption, active rules version, and OEM guidance for the detected phone brand.

## How it works

`AccessibilityEvent` (package-filtered, own package ignored) → throttled `evaluate()` reads the active window with `detection_rules.json` → pure-Kotlin `CountingEngine` (dwell, dedupe, debounce) → SQLite → overlay, notification and Flutter UI observe the same store.

- A video counts after it has been continuously visible for the minimum dwell (default 2 s, 1 to 10 s in Settings).
- Leaving the player, switching app, or the screen turning off ends the view; it counts only if dwell was met.
- The same identity is not counted twice within 30 s (back-swipes). Identity slots that are momentarily unreadable do not split a video into two.
- A 1 s heartbeat runs only while Instagram/YouTube is in the foreground; no work happens otherwise.

Code map: `core/` pure Kotlin + tests, `data/` SQLite + settings, `service/` service, detector, overlay, notifier, `rules/` rules loader, `debug/` calibration recorder, `bridge/` Flutter channels.

## Detection rules

`android/app/src/main/assets/detection_rules.json` holds every selector, with `rulesVersion` and a `targetAppVersion` per app. Current rules were derived from dumps on a Pixel 8a (Android 17):

| App | Version | Player on screen | Identity |
|---|---|---|---|
| Instagram | 450.0.0.50.77 | `clips_viewer_view_pager` visible | `clips_media_component` desc + caption desc |
| YouTube | 21.40.161 | `reel_recycler` visible | "Go to channel …" desc + duration from `reel_time_bar` seekbar |

Seekbar events (about 20 per second) are ignored by class.

### After an app update breaks counting

1. Check the SETUP tab: the rule version and target app versions are shown. A mismatch with `adb shell dumpsys package com.instagram.android | grep versionName` means the app updated.
2. Re-capture dumps (below), compare the player and identity nodes with the table above, and edit the JSON.
3. Either rebuild, or push without rebuilding (the override wins over the bundled file, and applies on the next settings change or service restart):

```sh
adb push detection_rules.json /sdcard/Android/data/com.shivam.tally/files/detection_rules.json
```

### Calibration capture

Tally → DEBUG → **START RECORDING**. In each state, run:

```sh
adb shell am broadcast -a com.shivam.tally.SNAPSHOT --es label ig_reel_1
adb pull /sdcard/Android/data/com.shivam.tally/files/dumps ./dumps
```

States worth capturing: feed, first Reel/Short, next, next, back-swipe, comments open, opened from the feed, long video. Dumps are JSON lines (`event`, `snapshot`, `mark`) and contain truncated captions and usernames, so keep them private. Stop recording afterwards (the file stops growing at 80 MB regardless).

Debug builds log decisions: `adb logcat -s Tally:D` (COUNT / END / eval lines). Release builds log nothing.

## Overlay

Drawn by the service with `TYPE_ACCESSIBILITY_OVERLAY` (no overlay permission). Shown only while Instagram or YouTube is foreground and the screen is on. Tap: expanded / dot. Long-press: −1 / +1. Drag to move; the position is saved per app and clamped to the screen and cutouts. Settings: on/off, size, opacity, only inside the player.

## App screens

- **TODAY:** a large blinking eye that wears out with your count (same formula as the overlay: count / (4 × warning interval), worst of the two apps), a one-word verdict (FRESH, TIRED, STRAINED, BLOODSHOT, FRIED), and one ledger card per app with the count, real tally marks (groups of five), counted on-screen time, and progress to the next eye-check. A red banner appears if counting is off. + / − only exist to fix a miss.
- **TRENDS:** 7 or 30 days stacked bars, a 24-hour "when you scroll" histogram (late night in red), daily average, worst and quietest day, total counted on-screen time.
- **SETTINGS:** counting, overlay, eye and warning, notification, data. Tools: Setup / Health, and Debug / Calibration.

On-screen time is the sum of dwell for auto-counted views only; manual corrections add none.

## Eye and milestone warning

The pill shows an eye that wears out as the number on the pill climbs: lid droops, whites redden with more veins, iris dulls, bags appear. It is fully worn out at 4 × N videos (N = warning interval, default 15, so 60). Every Nth counted video (per app, the same number you see on the pill) the pill wobbles, hops and the eye flashes red, and the phone vibrates. Settings → EYE & WARNING: eye on/off, interval 5 to 50, vibrate, dance. A manual +1 in Tally does not trigger it, and neither does −1; only a count going up while Instagram/YouTube is in the foreground.

Debug builds only, to preview without watching 60 videos:

```sh
adb shell am broadcast -a com.shivam.tally.EYE --ef level 0.6   # pin the eye (0 to 1); --ef level -1 releases it
adb shell am broadcast -a com.shivam.tally.WARN                 # fire the dance + vibration now
```

## Status: what has and has not been tested

Verified on a Pixel 8a (Android 17):
- Instagram and YouTube detection, dwell counting, swipe-to-next, no double count on back-swipe (YouTube), Home press ending a view without counting, screen-off ending a view, ignored seekbar spam, the Reels count matching a manual tally (Instagram).
- Unit tests for the counting logic (20, passing).
- The manifest has no `INTERNET` permission.

**Not yet verified on a device:** how the milestone dance and vibration feel in real use (the vibration request was accepted by the system and the eye rendering was checked at levels 0 to 1, but nobody has watched 15 in a row); overlay drag, long-press −1/+1 and position persistence; rotation and display cutouts; the ongoing notification; CSV export sharing; Doze and long-running survival (leave it for a day and check SETUP and the counts); Instagram Reels opened from the feed or a profile (the dump showed the same player container, but it was not counted live); sponsored Reels (they count, like any Reel).

**Known limitations:** detection depends on undocumented view ids and will break on some app updates. Two different videos from the same channel with an unreadable duration at the moment you swipe could be merged into one view. Shorts shelves embedded in the YouTube home feed were never tested; only the full-screen Shorts player is known to be detected.

## Credits

Fonts bundled under the SIL Open Font License 1.1: [Big Shoulders Display](https://github.com/xotypeco/big_shoulders) and [DM Mono](https://github.com/googlefonts/dm-mono).

Personal project, built for sideloading. It is not affiliated with Instagram or YouTube. Detection relies on undocumented UI internals and will need maintenance after app updates.
