# MightyS Pulse for Android

Privacy-first analytics for Android apps and games. No advertising ID, no device IDs, offline queue.

Docs: https://pulse.mightys.dev/docs/kotlin/

MightyS Pulse lets an Android app or game send named signals (events) with an optional number and a few properties, and
see them in a dashboard. The SDK never reads an advertising id or any device identifier, uses no cookies and does no
fingerprinting. It is written in Kotlin and works from Kotlin and Java.

## Install

`mavenCentral()` is the only repository you need.

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("dev.mightys:pulse-android:0.9.8")
}
```

```groovy
// build.gradle (app module)
dependencies {
    implementation "dev.mightys:pulse-android:0.9.8"
}
```

`dev.mightys:pulse-core` is the platform-independent part and is pulled in automatically.

## Quick start

Get your app key from the Pulse dashboard, then initialize once in `Application.onCreate`:

```kotlin
import dev.mightys.pulse.Pulse

Pulse.initialize(this, "YOUR_APP_KEY")
Pulse.track("level.complete")
Pulse.track("purchase", 4.99, mapOf("sku" to "gems_100"))

Pulse.flush() // nonblocking
```

Users are counted without any further setup. If you have your own user id, you can use it instead of the anonymous
install id (optional):

```kotlin
Pulse.setUser("your-internal-user-id") // replaces the anonymous install id
Pulse.setUser(null)                    // back to the anonymous install id
```

The same from Java:

```java
import dev.mightys.pulse.Pulse;
import java.util.Collections;

Pulse.initialize(this, "YOUR_APP_KEY");
Pulse.track("level.complete");
Pulse.track("purchase", 4.99, Collections.singletonMap("sku", "gems_100"));
Pulse.setUser("your-internal-user-id"); // optional, replaces the anonymous install id
Pulse.flush(() -> { /* runs on the SDK worker */ });
```

Initialization is ignored after the first successful call. Public calls never throw into your app and never block the
main thread. Hashing, disk work and sending run on background threads.

## See your first signal

1. Sign in at [app.pulse.mightys.dev](https://app.pulse.mightys.dev) and open the app whose key you used.
2. A debug or development build sends test data (see Test mode below). Turn on the **Test mode** switch in the bar
   above the figures to see it; turn it off again for real users.
3. Open **Live** in the app menu: signals appear there as they arrive. The Overview and the other screens follow shortly.

## What is sent and what is never sent

Sent with every signal (the `system` object), and only values the device can give; anything unknown is left out:

| Group | Fields |
| --- | --- |
| Device | `platform` (mobile), `deviceType` (phone, tablet, tv, watch, car), `deviceBrand` (`Build.MANUFACTURER`), `deviceModel`, `architecture`, `osName`, `osVersion`, `osApiLevel`, `isSimulator` |
| Screen | `screenWidth`, `screenHeight` (dp), `screenScale`, `screenWidthPx`, `screenHeightPx`, `screenDpi`, `screenCount`, `orientation` |
| Settings and accessibility | `colorScheme`, `layoutDirection`, `timeZone`, `utcOffsetMinutes`, `language`, `preferredLanguage`, `region`, `locale`, `fontScale`, `fontWeightAdjustment`, `boldText`, `reduceMotion`, `reduceTransparency`, `invertColors`, `increaseContrast`, `differentiateWithoutColor`, `connectionType` (wifi, cellular, ethernet, none, other), `lowPowerMode` |
| App and install | `appName`, `appVersion`, `appBuildNumber`, `bundleId`, `installSource` (playstore, galaxystore, amazon, sideload, debug, other), `isDebugBuild`, `sdkName`, `sdkVersion` |
| Usage statistics | `firstSessionDate`, `totalSessionsCount`, `distinctDaysUsed`, `distinctDaysUsedLastMonth`, `previousSessionSeconds`, `averageSessionSeconds`, `sessionSeconds`, `isNewInstall` (first session after install only) |

Besides these, every signal carries your event name, optional number and properties, a UTC capture time, the debug flag
and a random session id. The signing certificate hash is sent only if you set `sendAppIdentity = true`. Reading the
connection type needs the `ACCESS_NETWORK_STATE` permission, which the library declares (a normal permission with no
prompt). The statistics are kept in one small file in the app's own storage next to the install id and are deleted with
it on opt-out.

Automatic signals: `pulse.session_started` (each new session), `pulse.new_install` (first launch after install) and
`pulse.app_updated` (first launch after the app version or build changed, with `previousVersion` and `previousBuild`).
Each is sent at most once per session, so they are rare. They respect opt-out and the debug flag like any signal.

Sessions use a random id that changes after 30 minutes of inactivity.

Anonymous install id: random, created by the SDK on first launch, stored only in the app's own storage
(`noBackupFilesDir/mightys-pulse`), deleted with the app and replaced after opt-out. It is sent only as a SHA-256 hash,
is not derived from any device identifier and is not shared between apps.

User id: optional. If you call `setUser`, the id is hashed with SHA-256 on the device and replaces the install id hash.
The raw id is never stored or sent. The hash is pseudonymous and can link one user's signals.

Never sent or read: advertising id, Android id, IMEI, serial number, device name, account names,
location, contacts, installed apps. There are no cookies.

IP address: the SDK does not read or send the device's IP address as data. As with any HTTP request, the server sees the
connection address, uses it only to derive a two-letter country code and does not store it.

Do not put personal data in event names, properties or the app version override.

## Automatic signals

Types the SDK can send by itself: `pulse.session_started`, `pulse.new_install`, `pulse.app_updated`, `pulse.screen_view`,
`pulse.permission_changed`, `pulse.deep_link`, `pulse.app_opened`, `pulse.app_backgrounded` and `pulse.heatmap`. Switch them off in code with
`PulseOptions(automaticSignals = false)` (all of them), `automaticScreenViews = false` (screen views) and
`captureErrors = false` (crash capture and the exit reasons below) and `captureHeatmaps = false` (`pulse.heatmap`, see Heatmaps below); `Pulse.optOut = true` stops everything. The portal can
switch each type off, `pulse.app_opened` is sent only when the portal turns it on, and `pulse.heatmap` only when the portal switches heatmaps on.
Defaults and how many signals each type uses: https://pulse.mightys.dev/docs/automatic-signals/

| Signal | Default | Plan usage |
| --- | --- | --- |
| `pulse.session_started` | On | Free automatic signal |
| `pulse.app_opened` | Off, opt in through the portal | Free automatic signal |
| `pulse.app_backgrounded` | On | Free automatic signal |
| `pulse.new_install` | On | Free automatic signal |
| `pulse.app_updated` | On | Free automatic signal |
| `pulse.screen_view` | On | Counts toward your plan |
| `pulse.permission_changed` | On | Counts toward your plan |
| `pulse.deep_link` | On | Counts toward your plan |
| `pulse.heatmap` | Off, enabled through the portal | Counts toward your plan |

The five lifecycle types marked free do not count toward your plan.
When the app goes to the background, the SDK saves the session end, sends `pulse.app_backgrounded` with
`foregroundSeconds` and the system field `sessionSeconds`, then requests a flush. An unfinished send stays on disk
with its original timestamp for the next launch. At most 20 background signals are sent per session; a background
30 minutes or more after the last signal is dropped and never opens a session. The saved end still lets the next
session report the real `previousSessionSeconds`.

What the new ones carry: `pulse.app_opened` has `coldStart`, `pulse.app_backgrounded` has `foregroundSeconds`,
`pulse.deep_link` has `scheme`, `host`, `path` (numeric and uuid-like segments become `:id`, at most 128 characters) and
the `utmSource`, `utmMedium`, `utmCampaign` tags when the link has them, never the rest of the query or the fragment, and
`pulse.permission_changed` has `permission` (`notification`), `from` and `to`. Links that open the app are read from the
launching Activity's intent; call `Pulse.deepLink(uri)` from `onNewIntent` if your Activity does not call `setIntent`.
Extra system fields: `launchMs` (only on the `pulse.session_started` of a cold start, from the process start to the first
resumed Activity), `foregroundsToday`, `previousForegroundSeconds` and `notificationPermission` (granted, denied or
notDetermined, read only; the SDK never shows a permission prompt and has no tracking permission on Android). On Android
11 and later the SDK also sends why an earlier process of the app ended, from the system's exit history, as `pulse.error`
with kind `exit` and type `anr`, `crash_native`, `low_memory`, `excessive_resource`, `initialization_failure`, `signaled`
or `other` (an ANR carries the first 30 main thread frames, filtered). Normal exits and Java crashes (already captured) are
not sent; at most 10 per launch, none older than 7 days, each once.

Guards: per session at most 20 of each of app opened and app backgrounded, 10 deep links, 5 permission changes and 100
automatic screen views, and at most 300 automatic signals per day. Dropped ones are counted and the count is sent once as
`droppedAutomatic` on the next signal. Your own signals and errors are never limited by this.

## Screen views

The SDK sends `pulse.screen_view` by itself, with no code from you. It records the simple class name of each Activity
when it is resumed, and the class name of each visible AndroidX fragment when your app uses `androidx.fragment` (apps
without it are unaffected). Props: `screen` (at most 128 characters) and `previousScreen` when there is one. Showing the
same screen again with no other screen in between sends nothing. Never sent: titles, text on the screen, anything the
user typed, intent extras or URLs.

For Compose destinations or your own navigation, record a screen by hand (Java: `Pulse.screen("Settings");`):

```kotlin
Pulse.screen("Settings")
```

A name with a query or fragment loses them, and number or uuid-like path segments become `:id`. Manual calls respect
opt-out like any signal. To switch off the automatic capture (the hooks stay quiet and send nothing):

```kotlin
Pulse.initialize(this, "YOUR_APP_KEY", PulseOptions(automaticScreenViews = false))
```

## Heatmaps

Heatmaps are off until you switch them on for the app in the portal; code can only switch them off
(`PulseOptions(captureHeatmaps = false)`). A change in the portal reaches the app when it reads its settings (once per launch);
switching off takes effect at once and drops what was buffered. When on, `pulse.heatmap` signals carry the screen name (the same
name as the last `pulse.screen_view`), where the tap landed (x as a share of the window width, y in dp from the top of the
window), the width bucket (phone below 600 dp, tablet below 1024 dp), the orientation and the kind: `tap` or `rage` (the 3rd or
later tap within 1 second and 30 dp). A tap is a single finger that goes down and up without moving past the touch slop.

- **Collected:** screen name, position, kind. **Never collected:** text, view ids or class names, recordings, anything typed, the
  keyboard. Nothing is captured while the soft keyboard is visible, nor on an `EditText` or any other text editor, a password
  field, a `WebView` (a page may hold form fields the SDK cannot see) or anything inside them. With no known screen nothing is
  captured, and with screen views switched off in the portal there are no heatmaps either.
- **Ignore a view:** `Pulse.heatmapIgnore(view)` (Java: `Pulse.heatmapIgnore(view);`) on the view or any ancestor. Call it on the
  main thread; `Pulse.heatmapIgnore(view, false)` takes the mark off again. It sets a view tag, so it does not touch your own tags.
- **Compose:** taps on Compose content are captured at window level, but the SDK cannot look inside Compose (it has no Compose
  dependency), so a Compose text field is excluded only while the keyboard is visible, not on the first tap that opens it. To
  keep an area out, host it in a View that carries the mark, for example `AndroidView(factory = { FrameLayout(it).also { v -> Pulse.heatmapIgnore(v) } })`
  around the `ComposeView`, or mark the `ComposeView` itself. Dialogs and popups have their own windows and are not captured.
- **How it is sent:** one `pulse.heatmap` signal per screen and window shape, when the screen changes, the window size or
  orientation changes, the app goes to the background, 200 points are buffered, or on `flush`. At most 1000 points per session.
  `pulse.heatmap` is not counted by the automatic signal guards.
- **Screenshots, debug builds only:** when the app is debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`) and heatmaps are on, the SDK
  takes one picture of each screen about a second after it is shown (API 26 and later, never while the keyboard is visible, never
  of a `FLAG_SECURE` window), once per screen, window shape and app version, scaled to at most 2048 px and encoded off the main
  thread, and uploads it to the Pulse host so the portal can show the heatmap on it. Release builds never take or send a picture.
  At most 20 uploads an hour; failures are silent.

## Errors and crashes

By default the SDK catches the errors your app does not handle and sends each as a `pulse.error` signal, so you can see
where it broke. Nothing to set up.

- **What is captured.** Uncaught exceptions on any thread. The SDK installs a default uncaught-exception handler that
  calls the handler that was there before, with the same arguments, so your own crash reporting and Android's normal
  behaviour are unchanged. It never swallows a crash and never throws.
- **Crashes.** The process is ending, so the handler only writes one small file in the app's own storage
  (`noBackupFilesDir/mightys-pulse`), waiting at most about a second and using no network. The next launch sends it as
  kind `crash` (with the session it happened in) and deletes it.
- **What an error carries.** `kind` (`crash`, or `handled` for your own calls), `type` (the exception class name), a
  filtered `message` of at most 500 characters, up to 30 `frames` (top first, 300 characters each, for example
  `com.example.CartViewModel.checkout CartViewModel.kt:42`) and `symbolicated`. A `cause` chain follows the frames as a
  `Caused by: <type>` frame and the cause's own frames, within the same 30. The whole object is at most 16384 bytes.
- **Personal data filter.** On the device, in the message and in every frame, emails, URLs' query strings and fragments,
  UUIDs, IP addresses, runs of six or more digits and absolute paths (only the file name is kept) are replaced with
  `<filtered>` or shortened. Do not put personal data in exception messages anyway.
- **Limits.** At most 10 `pulse.error` signals per session and error type; more are dropped. Opt-out stops capture and
  deletes a waiting crash file.
- **Handled errors.** Send an error you caught yourself (Java: `Pulse.trackError(error)`):

```kotlin
try {
    checkout()
} catch (e: Exception) {
    Pulse.trackError(e, mapOf("screen" to "cart"))
    showRetry()
}
```

- **Less detail.** `PulseOptions(errorDetail = ErrorDetail.TYPE_ONLY)` sends only the type: no message, no frames.
- **Switch it off.** `PulseOptions(captureErrors = false)` installs no handler. `Pulse.trackError` still sends what you
  pass to it.
- **Symbols.** If you shrink your app with R8, class names in frames are short and not readable yet; those errors are
  marked `symbolicated: false`. Mapping files are not uploaded by the SDK.

## Options

```kotlin
import dev.mightys.pulse.PulseOptions

Pulse.initialize(this, "YOUR_APP_KEY", PulseOptions(
    appVersion = "1.2.3",
    debug = false,
    sendAppIdentity = false,
))
```

| Option | Default | Behaviour |
| --- | --- | --- |
| `host` | `https://in.mightys.dev` | HTTP(S) base URL without user info, query or fragment. An invalid host ignores initialization so it can be retried. HTTP is subject to Android cleartext policy. |
| `flushIntervalSeconds` | 20 | Seconds between batches after the first. Clamped to 0.05 through 3600. |
| `maxBatchSize` | 100 | Signals per request. 1 through 1000. |
| `maxQueueSize` | 1000 | Signals kept offline. 1 through 10000. The oldest are dropped first. |
| `maxQueueAgeDays` | 7 | Days a queued signal is kept. Capped at 29. |
| `gzip` | true | Compresses bodies larger than 1024 bytes. |
| `debug` | follows the build, see Test mode | Marks signals as test data and logs short diagnostics to Logcat, never signal contents. |
| `sendAppIdentity` | false | Adds the first signing certificate SHA-256 (the package name is always sent as `bundleId`). |
| `appVersion` | package version name | Overrides the app version. |
| `automaticScreenViews` | true | Sends `pulse.screen_view` when an Activity or AndroidX fragment is shown. False sends no automatic screen views. |
| `automaticSignals` | true | Allows automatic signals, including `pulse.app_backgrounded` (on by default). False switches automatic signals off. |
| `captureErrors` | true | Catches unhandled errors and crashes and sends them as `pulse.error`. False installs no handler at all. |
| `errorDetail` | `ErrorDetail.FULL` | `FULL` sends type, filtered message and frames. `TYPE_ONLY` sends the error type alone. |
| `captureHeatmaps` | true | True follows the portal (heatmaps are off until it switches them on); false never captures taps. See Heatmaps. |
| `control` | `ControlMode.Automatic` | App Control: `Automatic` checks the rules and shows the built-in screens, `Manual` checks and keeps the state but shows nothing, `Off` makes no App Control request at all. See App Control. |

**Test mode.** Every signal carries a test flag. By default it follows the build: debuggable builds (what Android Studio
runs) send test signals, release builds send real ones, with the same app key. Set `debug = true` or `debug = false` to
decide yourself. In the dashboard, the test mode switch chooses which of the two you are looking at.

## Settings from the portal

Once per launch, in the background, the SDK reads your app's settings from `<host>/api/v1/config` using your app key and
keeps them in its own storage. In the portal you can switch off screen views, the automatic signals, individual automatic
signal types and groups of device fields (device, screen, settings, network, app, usage). A change applies from the next
launch of the app, never in the middle of a run, and no package update is needed. The package name and the base fields
(platform, OS, app version, device model, locale) are never removed.

Everything is on when the settings cannot be read (offline, an error, nothing saved yet). A code option can only
switch things off: `automaticScreenViews = false` or `automaticSignals = false` turn them off whatever the portal says,
while true (the default) follows the portal and never turns on what the portal switched off. Opting out
deletes the saved settings with the rest of the SDK's data.

## App Control

App Control lets you act on a version from the portal without shipping a new build: ask for an update, suggest one, put
the app into maintenance, block a version that has a known problem, or show an announcement or a what's new card. It
needs no extra code: after `Pulse.initialize` the SDK checks the rules in the background and shows the matching screen.

```kotlin
// Nothing to add. The default is ControlMode.Automatic.
Pulse.initialize(this, "YOUR_APP_KEY")
```

What the user sees: a full screen they cannot close for a required update, a blocked version (without "continue") and
maintenance; a dialog with "Update" and "Not now" for a suggested update; a card at the bottom for an announcement or
what's new; a bar for maintenance that starts soon. Texts are built in for English, German, French, Spanish, Italian,
Brazilian Portuguese, Japanese, Korean, Simplified Chinese, Turkish, Russian and Serbian (Latin), chosen by the app's
language, then the system's, then English, and you can replace any title, text or button in the portal. The screens use
the system theme (light and dark), respect the text size setting and read well with TalkBack. The Back button never
closes a locking screen: it sends the app to the background.

**Your own screens.** With `ControlMode.Manual` the SDK only keeps the state up to date and you draw whatever you like:

```kotlin
Pulse.initialize(this, "YOUR_APP_KEY", PulseOptions(control = ControlMode.Manual))

Pulse.control.addListener { state ->               // called on the main thread
    when (state.kind) {
        ControlState.Kind.UpdateRequired -> showMyBlockingScreen(state.title, state.body)
        ControlState.Kind.Maintenance -> showMaintenance(state.endsAt)
        else -> hideMyScreen()
    }
}
// Pulse.control.state is the current value; checkNow() asks again; openStore() opens the store;
// dismiss() lets the user leave a state that allows it (a suggestion, a card, allowContinue).
```

`ControlMode.Off` makes no App Control request at all and the state is always `Normal`.

**Google Play In-App Updates (optional).** If your app is installed from Google Play and already includes
`com.google.android.play:app-update`, the Update button starts the immediate flow for required updates and blocked
versions and the flexible flow for suggestions. The SDK does not add this library: it only uses it when it is there.
Without it, Update opens the store link from the rules, or your Play page (the Play app first, then the web page).

**Reviews.** App Control never asks for a rating. If you want a store review prompt, use the Google Play In-App Review
API yourself at a moment you choose.

**How it fails safely.** The check never delays `initialize` or the first frame, takes at most 5 seconds and is never
retried in a loop (the next try is the next return to the foreground, at most once a minute). Anything that goes wrong
(no network, a server error, a malformed answer, a wrong version of the format) keeps the last known state, or normal;
nothing is ever blocked because something is unknown. A required update or a block saved on the device is ignored after
30 days, saved maintenance only counts inside its own window and never longer than 72 hours, a required update turns into
a suggestion when the store has nothing newer than the installed version, after three returns from the store on the same
version, when Google Play reports no update, or when there is no store link. The app is never closed by App Control, and
a debug build shows a "Test build" bar with a "Continue" button on every locking screen. The request carries no key
header and no identifier of the user or device, it works when analytics is opted out, and it has its own small cache.

## Delivery and offline behaviour

- The first batch after `initialize` is sent at once. Later batches follow the interval, the batch size, an explicit
  flush, or ProcessLifecycle ON_STOP after the last Activity stops.
- Signals are saved to a checksummed file under `noBackupFilesDir/mightys-pulse` and survive restarts. One process must
  own that directory; multiprocess writers are not supported.
- Network errors and server errors (408, 425, 429, 5xx) are retried with exponential backoff from 1 second to 5 minutes.
  `Retry-After` is honoured up to one hour. When the server asks for a pause or a different interval, the SDK follows it.
  A 401 keeps the queue and stops sending until the process restarts.
- Each batch carries an id, so a batch sent again after a lost answer is stored only once.
- Delivery is at least once: after a crash a batch can be sent again. If Android ends the process abruptly, the last
  signals may be lost.
- `Pulse.flush()` is nonblocking. `Pulse.flush(callback)` calls back on the SDK worker when the queue has drained, on
  opt-out, or when a 401 halts sending; move UI work to the main thread. `Pulse.flush(timeoutMillis)` is for background
  threads only and returns false at once on the main thread.
- The transport uses `HttpURLConnection` with no redirects, no HTTP cache and a fixed `pulse-android/<version>`
  User-Agent. It never sets or reads cookies.

## Requirements

| | Support |
| --- | --- |
| Android | minimum SDK 21 (Android 5.0), compile SDK 36 |
| Language | Kotlin and Java, Java 11 language level |
| Permissions | `INTERNET`, declared in the library manifest and merged into your app |
| R8 / ProGuard | consumer rules ship with the library; they only silence warnings about optional libraries (AndroidX Fragment, Google Play In-App Updates) |
| Dependencies | `pulse-core`, the Kotlin standard library and AndroidX Lifecycle Process |

## Opt-out

`Pulse.optOut = true` (Java: `Pulse.setOptOut(true)`) is app-wide and remembered across restarts. It stops collection at
once, clears queued signals, the user hash, the install id and a waiting crash file, and cancels active requests where possible. Requests already sent cannot
be recalled. `Pulse.optOut = false` resumes collection. Before `initialize` the getter returns true, so do not write it
back into a saved preference. If the stored choice cannot be read, the SDK counts as opted out. Opting out does not switch App Control off: it sends nothing about the user and keeps its own cache; use `ControlMode.Off` for that.

## Licence

MIT. See `LICENSE.md`. Each published artifact carries the licence text under `META-INF/LICENSE-<module>.md`.

## Reference

**Signal rules.** Names need non-whitespace content and at most 200 UTF-16 units. Values must be finite. Props accept
`String`, `Boolean` (sent as `"true"` or `"false"`), `Int`, `Long` and finite `Float` or `Double`; other values are
dropped. At most 32 props are kept, with keys up to 128 and strings up to 1024 UTF-16 units, within 8192 bytes
serialized. Only the first 256 entries of the input map are copied, so put the props you want directly in the map.

**Building from source.** JDK 17, Android SDK platform and build tools 36, and the Gradle wrapper. Put your SDK path in
the ignored `local.properties` as `sdk.dir=/path/to/Android/sdk`.

```sh
./gradlew --no-daemon :pulse-core:test :pulse-android:testReleaseUnitTest :pulse-android:lintRelease
```

`sample` is a small app that shows initialize, track, setUser, flush and opt-out. Pass your app key at build time:
`./gradlew :sample:assembleDebug -PpulseAppKey=YOUR_KEY`.
