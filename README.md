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
    implementation("dev.mightys:pulse-android:0.9.4")
}
```

```groovy
// build.gradle (app module)
dependencies {
    implementation "dev.mightys:pulse-android:0.9.4"
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
| `automaticSignals` | true | Sends `pulse.session_started`, `pulse.new_install` and `pulse.app_updated`. False sends none of them. |

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

## Delivery and offline behaviour

- The first batch after `initialize` is sent at once. Later batches follow the interval, the batch size, an explicit
  flush, or the last started Activity stopping.
- Signals are saved to a checksummed file under `noBackupFilesDir/mightys-pulse` and survive restarts. One process must
  own that directory; multiprocess writers are not supported.
- Network errors and server errors (408, 425, 429, 5xx) are retried with exponential backoff from 1 second to 5 minutes.
  `Retry-After` is honoured up to one hour. When the server asks for a pause or a different interval, the SDK follows it.
  A 401 keeps the queue and stops sending until the process restarts.
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
| R8 / ProGuard | no consumer rules needed |
| Dependencies | `pulse-core` and the Kotlin standard library |

## Opt-out

`Pulse.optOut = true` (Java: `Pulse.setOptOut(true)`) is app-wide and remembered across restarts. It stops collection at
once, clears queued signals, the user hash and the install id, and cancels active requests where possible. Requests already sent cannot
be recalled. `Pulse.optOut = false` resumes collection. Before `initialize` the getter returns true, so do not write it
back into a saved preference. If the stored choice cannot be read, the SDK counts as opted out.

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
