# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows
[Semantic Versioning](https://semver.org/).

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
