# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows
[Semantic Versioning](https://semver.org/).

## [0.9.7] - 2026-11-16

- The built-in update and maintenance screens are not counted as app screens or taps.
- App Control: after `Pulse.initialize` the app can show a required update, a suggested update, a blocked version,
  maintenance (also scheduled, applied by the device clock), an announcement or a what's new card, as set in the portal.
  No extra code is needed (`ControlMode.Automatic`); `ControlMode.Manual` keeps `Pulse.control.state` up to date and
  draws nothing; `ControlMode.Off` makes no request. `Pulse.control` has `state`, `addListener`, `removeListener`,
  `checkNow()`, `openStore()` and `dismiss()`. Built-in screens in 12 languages, light and dark, with the text size and
  TalkBack respected, Back never closes a locking screen. Google Play In-App Updates is used only when the app already has
  it. Fails open: the check takes at most 5 seconds, never delays `initialize`, is never retried in a loop, and any error
  keeps the last known state or normal. New option `control`. The library manifest declares one internal Activity.
- Errors and crashes with where they happened: unhandled exceptions are sent as `pulse.error` with the error type, a
  filtered message and readable frames (class, method, file and line, with `Caused by` frames for the cause chain). A
  crash is saved to a small file and sent on the next launch. `Pulse.trackError(error, props)` sends errors you handle
  yourself. Emails, URLs' query strings, UUIDs, IP addresses, long digit runs and file paths are filtered on the device.
  New options `captureErrors` (default true; false installs no handler) and `errorDetail` (`TYPE_ONLY` sends the type
  alone). At most 10 per session and error type.
- A crash right after start is now kept and sent on the next launch.
- New automatic data: `pulse.deep_link` (scheme, host and a path without ids, plus the three utm_ tags), `pulse.permission_changed`
  (notifications), `pulse.app_opened` and `pulse.app_backgrounded` (only when the portal switches them on), and the system
  fields `launchMs`, `foregroundsToday`, `previousForegroundSeconds` and `notificationPermission`. `Pulse.deepLink(uri)` reports
  a link by hand. The portal can pause types (`pausedSignals`). Guards: per session and type caps, 300 automatic signals per
  day, dropped ones counted once as `droppedAutomatic`.
- Why the app ended (Android 11+): ANR, native crash, low memory and similar exits from the system's exit history are sent
  once each as `pulse.error` with kind `exit` (at most 10 per launch, none older than 7 days).
- Heatmaps of taps. Off until the portal switches `heatmaps` on for the app. The SDK sends `pulse.heatmap` with the screen
  name, the position (x as a fraction of the window width, y in dp from the top) and the kind (`tap`, `rage`), at most 200
  points per signal and 1000 per session; it is not counted by the automatic signal guards. Never captured: the soft keyboard,
  `EditText` and other text editors, password fields, `WebView`, and any view marked with `Pulse.heatmapIgnore(view)`. No text,
  ids or class names. New option `captureHeatmaps` (default true follows the portal; false never captures). In a debuggable
  build only, one screenshot per screen, window shape and app version is taken (API 26+) and uploaded; release builds never
  send a picture.
- Fixed: a `pulse.session_started` (or `pulse.new_install`, `pulse.app_updated`) opened by an error signal no longer carries a
  copy of that error object.

## [0.9.5] - 2026-11-16

- A batch that is sent again after a lost answer is stored only once.

## [0.9.4] - 2026-11-16

- Signals captured in the same millisecond keep their order: timestamps are strictly increasing.
- Fixed: the first Activity of a launch now gets its automatic screen view even when `Pulse.initialize` is called from
  `onCreate` or `onResume`. An app update is no longer forgotten when the automatic signals were switched off on the
  launch that first saw the new version: the next launch that allows it sends `pulse.app_updated`.
- Settings from the portal: once per launch the SDK reads your app's settings from the portal and applies them from the
  next launch, so screen views, automatic signals and groups of device fields can be switched off without an update.
  Everything stays on by default and whenever the settings cannot be read. The options `automaticScreenViews` and the new
  `automaticSignals` can switch things off in code; they never turn on what the portal switched off.

- Automatic screen views: `pulse.screen_view` with `screen` (the Activity class name, or the AndroidX fragment class name
  when your app uses fragments) and `previousScreen`. Turn it off with `PulseOptions(automaticScreenViews = false)`.
- `Pulse.screen("Name")` records a screen by hand, for Compose destinations or custom navigation.

## [0.9.3] - 2026-11-15

- Every signal now carries the non-sensitive device, screen, settings, accessibility, app and install details Android can
  give (see "What is sent" in the README), plus simple usage statistics such as sessions and days used.
- Three automatic signals: `pulse.session_started`, `pulse.new_install` and `pulse.app_updated`, at most one per session.
- The package name is sent by default as `bundleId`. The library declares the `ACCESS_NETWORK_STATE` permission to read
  the connection type.

## [0.9.2] - 2026-11-15

- Users are counted without any setup: the SDK sends a random anonymous install id (hashed) until you call `setUser`.
- The app version is never empty: it falls back to the build number when the package has no version name. The device
  model and locale are never empty either.

## [0.9.1] - 2026-11-15

- Clearer package descriptions on Maven Central and a link to the documentation in the README.

## [0.9.0] - 2026-11-15

Initial public release.

- `Pulse.initialize`, `track` (event name, optional number, optional properties), `setUser`, `flush` and an app-wide
  `optOut`, usable from Kotlin and Java.
- No advertising id, no device identifier, no cookies. Random sessions that rotate after 30 minutes of inactivity.
  The optional user id is hashed with SHA-256 on the device.
- Batched, gzipped delivery with an offline queue on disk, retries and backoff.
- One-call opt-out that is remembered across launches.
- `PulseOptions` for host, flush interval, batch and queue limits, queue age, gzip, debug mode, optional app identity
  and app version override.
- Android library, minimum SDK 21, with the `INTERNET` permission merged automatically and no consumer ProGuard or R8
  rules needed.

[0.9.2]: https://github.com/MightyS-dev/pulse-android/releases/tag/0.9.2
[0.9.1]: https://github.com/MightyS-dev/pulse-android/releases/tag/0.9.1
[0.9.0]: https://github.com/MightyS-dev/pulse-android/releases/tag/0.9.0
