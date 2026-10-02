# Changelog

Human-readable iteration notes for the public repository. Fine-grained history lives in commit messages.

## 2026-10-02 — M6 user feedback round

- Build fingerprint: the About row now shows `<version>+<short sha> <build time>`, so the
  question "which build is on the phone?" is answerable from the device alone.
- Scanning: new **Scan on startup** switch (default on, matching previous behaviour).
  Turned off, the library is only refreshed when you ask for it. The setting is durable
  via `AppPreferences`; the output-mode selection now survives restarts too, which it
  previously did not.
- Now playing: round draggable thumb instead of the Material slider, plus elapsed/duration
  readouts next to it.
- Now playing: the cover-shape control moved out of the artwork onto its own labelled row
  and toggles both ways with one button.
- Queue: tapping a row now starts that track.
- Dark theme: backgrounds go fully black. The bottom sheet's default Material container
  colour was the cause of the grey player surface; unset `surfaceContainer*` roles let the
  baseline palette leak into sheets and bars. Divider tones carry the structure instead.
- Library pages: the A-Z rail no longer stretches to the bottom of the screen when the
  full player opens or closes. The Scaffold's bottom inset used to change instantly while
  the bottom bar animated, so every page resized a frame early; the inset is now animated
  on the bar's own timing and the rail clips its transient measurements.
- Digital-path audit scaffolding for USB exclusive: an append-only PCM observation hook on
  the exclusive adapter with bit-exact transfer tests, a read-only device evidence script,
  and an instrumented audit that records the native stream's sharing mode and device.

## 2026-08-16 — M4 UI

- Design tokens: near-pure black + warm gold, serif display/sans body, dark/light themes.
- Four-tab navigation with state restoration; songs/albums/artists views bound to Room flows.
- Artist → albums → tracks flow (the HiBy gap); album cover grid via Coil3 with bounded caches.
- Mini player, now-playing screen with framed USB badge (green/yellow semantics, no "bit-perfect" text), queue screen.
- Substring search on all library views.
- Settings: SAF folder picker, opt-in full-disk scan switch (All files access guidance), output mode, language, theme, about.
- Cover add/replace/remove via Photo Picker; permission-aware empty states.
- Device screenshots captured and checked with the visual-acceptance loop (modlens/vision + pixel color sampling).

## 2026-08-16 — M3 scanning and library

- Room schema v1: tracks/albums/artists/covers with album identity key (album artist > artist > folder fallback).
- Metadata model and GBK/GB18030 ID3v2.3 text fixer with sample-based unit tests.
- Three scan sources behind one ScanCandidate model: MediaStore, SAF folders, optional full-disk scan (20k cap).
- ScanEngine: path-normalized dedupe, incremental add/update diff, orphan cleanup.
- Cover storage (non-destructive app-private files) and four-level cover priority resolver.

## 2026-08-16 — M2 audio core

- AudioOutputBackend contract, BitPerfectState and volume policy with unit tests.
- Media3 1.11 playback engine, StandardBackend, custom AIFF extractor (16/24/32-bit PCM).
- Queue engine (repeat/shuffle/remove/move), resume position store, playback coordinator with gapless queue and sample-rate hooks.
- Production native AAudio EXCLUSIVE backend; device smoke passed 8/8 (16/24-bit × 4 sample rates, zero mismatches).
- Gapless smoke: same-rate transitions near-seamless; sample-rate reconfiguration latency on the standard path is tracked as a follow-up before the bit-perfect acceptance gate.

## 2026-08-16 — M1 USB-exclusive spike

- Built and ran Spike-1 (Java AudioTrack + native AAudio) and Spike-2 (UAC2 takeover probe) on the reference device.
- Evidence: Java system path resamples everything to a fixed 384 kHz mixer (fail); native AAudio EXCLUSIVE passes the full 16/24-bit × 6-rate matrix with zero mismatches (pass); UAC2 control plane is claimable as a fallback.
- Decision: native AAudio EXCLUSIVE is the V1 USB-exclusive backend; UAC2 is a conditional fallback (ADR-0001, private workspace).

## 2026-08-15 — M0 project scaffold

- Buildable Android app skeleton: AGP 9.2 (built-in Kotlin), Jetpack Compose, minSdk 29 / targetSdk 36.
- Offline boundary: manifest declares zero permissions (no INTERNET).
- CI workflow: unit tests, lint, offline-manifest guard, debug APK assembly.
- Gradle wrapper 9.7.0; Compose BOM pinned to 2026.06.01 for compileSdk 36 compatibility.

## 2026-08-15 — bilingual README

- Added `README.zh.md` (Simplified Chinese) and a language switcher at the top of `README.md`.

## 2026-08-15 — repository bootstrap

- Created the public repository skeleton: `README.md`, Apache-2.0 `LICENSE`, `.gitignore`, and this changelog policy.
- No application code yet. Private planning documents (requirements, decisions, plans) are maintained outside this repository.
