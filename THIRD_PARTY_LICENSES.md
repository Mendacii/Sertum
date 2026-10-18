# Third-Party Notices

Project-owned code is licensed under the Apache License 2.0 (see `LICENSE`).

This file lists every third-party component bundled with or linked by the application, with its
license and notice text. `gradle/libs.versions.toml` declares 21 library entries; all 21 are
accounted for below, either as their own row or in the Compose BOM family row. Versions are the
catalog versions; BOM-managed artifacts resolve to the versions the BOM selects.

## Direct dependencies

| Component | Version | License | Notice / Link |
|-----------|---------|---------|---------------|
| AndroidX Compose BOM — `androidx.compose:compose-bom`, governing `androidx.compose.ui:ui`, `androidx.compose.ui:ui-graphics`, `androidx.compose.ui:ui-tooling-preview`, `androidx.compose.ui:ui-tooling` (debug-only), `androidx.compose.material3:material3`, `androidx.compose.material:material-icons-extended` | 2026.06.01 (BOM) | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/compose — https://www.apache.org/licenses/LICENSE-2.0 |
| AndroidX Activity Compose — `androidx.activity:activity-compose` | 1.12.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/activity |
| AndroidX Media3 ExoPlayer — `androidx.media3:media3-exoplayer` | 1.11.0 | Apache-2.0 | https://github.com/androidx/media |
| AndroidX Media3 Session — `androidx.media3:media3-session` | 1.11.0 | Apache-2.0 | https://github.com/androidx/media |
| KotlinX Coroutines Android — `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Room Runtime — `androidx.room:room-runtime` | 2.8.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/room |
| AndroidX Room KTX — `androidx.room:room-ktx` | 2.8.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/room |
| AndroidX Room Compiler — `androidx.room:room-compiler` (KSP, build-time only; not packaged in the APK) | 2.8.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/room |
| AndroidX DocumentFile — `androidx.documentfile:documentfile` | 1.0.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/documentfile |
| AndroidX Navigation Compose — `androidx.navigation:navigation-compose` | 2.9.8 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/navigation |
| Coil 3 Compose — `io.coil-kt.coil3:coil-compose` | 3.5.0 | Apache-2.0 | https://github.com/coil-kt/coil |
| JUnit 4 — `junit:junit` (unit and instrumentation tests) | 4.13.2 | EPL-1.0 | https://junit.org/junit4/ — https://www.eclipse.org/legal/epl-v10.html |
| Google Truth — `com.google.truth:truth` (unit and instrumentation tests) | 1.4.5 | Apache-2.0 | https://github.com/google/truth |
| AndroidX Test Ext JUnit — `androidx.test.ext:junit` (instrumentation tests) | 1.3.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/test |
| AndroidX Test Runner — `androidx.test:runner` (instrumentation tests) | 1.7.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/test |

BOM resolution verified 2026-10-19 against `gradle/libs.versions.toml` and
`gradlew :app:dependencies --configuration releaseRuntimeClasspath`: the BOM resolves
`compose.ui:ui`, `ui-graphics` and `ui-tooling-preview` to 1.11.4, `material3` to 1.4.0 and
`material-icons-extended` to 1.7.8. `ui-tooling` is a debug-only dependency and is not in the
release APK.

## Transitive runtime dependencies

These come in through the direct dependencies above and are linked into the APK. All are
Apache-2.0. This table groups them by family; `gradlew :app:dependencies --configuration
releaseRuntimeClasspath` prints the resolved individual modules.

| Family | Version(s) | Representative modules |
|--------|-----------|------------------------|
| AndroidX | various (catalog-pinned where declared) | `activity`, `annotation`, `appcompat-resources`, `arch.core`, `collection`, `concurrent`, `core`, `customview`, `dynamicanimation`, `emoji2`, `exifinterface`, `graphics`, `interpolator`, `legacy`, `lifecycle`, `loader`, `localbroadcastmanager`, `media`, `navigation`, `navigationevent`, `print`, `profileinstaller`, `savedstate`, `sqlite`, `startup`, `tracing`, `transition`, `vectordrawable`, `versionedparcelable`, `window` |
| Jetpack Compose | 1.11.4 (`material3` 1.4.0, `material-icons` 1.7.8) | `androidx.compose.runtime`, `ui`, `ui-text`, `ui-unit`, `ui-geometry`, `foundation`, `animation`, `material-ripple` |
| Media3 | 1.11.0 | `media3-common`, `media3-container`, `media3-decoder`, `media3-extractor`, `media3-datasource`, `media3-database` |
| Room / SQLite | 2.8.4 | `room-common`, `sqlite`, `sqlite-framework`, `sqlite-bundled` |
| Coil 3 | 3.5.0 | `coil`, `coil-core`, `coil-android`, `coil-core-android`, `coil-compose-core` |
| Kotlin / KotlinX | stdlib 2.4.0, coroutines 1.11.0 | `kotlin-stdlib`, `kotlinx-coroutines-core`, `kotlinx-coroutines-core-jvm` |
| JetBrains Compose Multiplatform | 1.11.1 | `org.jetbrains.compose.ui` / `foundation` / `animation` / `runtime` wrappers pulled in by `coil-compose-core` |
| Okio | 3.17.0 | `okio`, `okio-jvm` (Coil file I/O) |
| Guava | 33.3.1-android, `failureaccess` 1.0.2 | Media3 session futures; `listenablefuture` is an intentionally empty marker artifact |
| JSpecify | 1.0.0 | Nullness annotations used by AndroidX |
| JetBrains annotations | 23.0.0 | Nullness annotations |

## License texts

- Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0
- Eclipse Public License 1.0 (JUnit 4 only) — https://www.eclipse.org/legal/epl-v10.html

## Update policy

Every direct dependency added to `gradle/libs.versions.toml` must be listed here in the same
commit, including debug-only and test-only dependencies. Transitive runtime dependencies are
covered by the family table above; a new dependency outside those Apache-2.0 families needs its
own row here before it is merged.
