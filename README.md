**Languages:** [English](README.md) | [简体中文](README.zh.md)

# Sertum

**Sertum** (花环播放器) is a fully offline, local-first music player for Android, built for listeners who keep their own audio library and want unaltered playback through USB DACs.

## Status

Feature-complete for V1, in stabilisation. All ten V1 user stories are implemented and verified on the reference device, and the app is in daily use on it.

The audio chain was audited on a real playback path rather than a constructed one: the exclusive stream reports `sharing=1` (genuinely exclusive), the requested 44100 Hz is accepted unchanged (`actual=44100`, `route rate in=44100 out=44100`), music-stream gain is 0 dB and no device effects are attached. **That demonstrates a clean digital path; it does not prove end-to-end bit-perfect playback**, because gain stages downstream of the app remain outside its control and there is no ADC here for a loopback measurement. One contradiction is recorded and unresolved: the HAL's output thread reports 48000 Hz while the stream is 44100 Hz.

Known gaps are listed in `docs/aegis/STATUS.md` of the documentation repository rather than here, so this file stays a description of the project and not a bug list.

## V1 features

- 100% offline: no account, no telemetry, no network permission
- USB exclusive output, with standard Android output and Bluetooth as ordinary paths
- Library scanning: system media index, user-selected folders, and an optional full-disk scan
- Library views: Songs / Albums / Artists, with Artist → Albums → Tracks browsing
- Gapless playback of lossless tracks
- Album cover completion for albums without embedded artwork
- Chinese and English UI; dark and light themes on near-black and warm-gold tokens
- Resilient behaviour: DAC hotplug, corrupt files, permission revocation
- **Single-file disc rips are split into their tracks using the CUE sheet beside them**, each
  track clipped to its own span of the file, so a whole-disc rip lists and plays track by track
- Track numbers recovered from file names where tags do not carry them, and multi-disc
  releases ordered by disc
- Albums separated by container, so a lossless release and a lossy copy of it are two entries;
  the album page states what the release is stored as (`FLAC · 16bit / 44.1kHz`)

## Tech stack

- Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, Coil
- Android 10+ (minSdk 29), targetSdk 36, arm64-v8a
- Apache License 2.0 for project-owned code

No dependency injection framework is used. The graph is small enough that constructing it in
the application class is clearer than a container, and one was never added.

## Repository layout

- `app/` — Android application module
- `gradle/` — Gradle wrapper and version catalog
- `scripts/` — offline guard and device check helpers
- `.github/workflows/` — CI pipeline
- `CHANGELOG.md` — human-readable iteration notes
- `README.zh.md` — Simplified Chinese version of this document
- `THIRD_PARTY_LICENSES.md` — third-party notices
- `LICENSE` — Apache License 2.0

## License

Project-owned code is licensed under the Apache License 2.0. Third-party components keep their
own licenses; the notices are collected in `THIRD_PARTY_LICENSES.md`.
