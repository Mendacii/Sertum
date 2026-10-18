import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    jacoco
}

// Build fingerprint for the "About" row. Several review rounds were spent
// guessing which APK was on the phone; a commit sha plus a build timestamp
// makes that question answerable from the device alone.
val sertumGitSha: String = runCatching {
    val process = providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
    }
    process.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

val sertumBuildTime: String = Instant.now().toString()

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.sertum.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sertum.player"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SERTUM_BUILD_SHA", "\"$sertumGitSha\"")
        buildConfigField("String", "SERTUM_BUILD_TIME", "\"$sertumBuildTime\"")

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    ndkVersion = "30.0.15729638"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Renders @Preview in Android Studio and powers the Layout Inspector.
    // Debug-only so it never reaches the release APK.
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// PRD A-19: unit-testable core must stay at >= 70% instruction coverage.
// Scope = behavioral core listed in PRD 10.1 (domain rules, metadata parsing,
// scan merge/dedup, cover priority, resume rules) plus the AIFF extractor and,
// since the 2026-10-19 correction below, the exclusive-output audio engine.
// Explicitly excluded from the denominator:
//  - Android I/O adapters (MediaStore/SAF/full-scan sources, CoverStore file
//    I/O): verified by the PRD device matrix instead of JVM unit tests.
//  - Room-bound resume implementation (same rules tested via InMemory store).
//  - Pure data carriers / contracts / enum labels with no business logic.
//
// 2026-10-19 denominator correction: until this date the scope above measured
// 6 packages / 34 classes / 3931 instructions / 91.07%, while the debug build
// compiles 287 class files. audio/backend, audio/uac2 and PlaybackCoordinator -
// the entire exclusive-output engine - were outside the number the project
// quoted. They are in the denominator now. Measured on 2026-10-19 with
// `gradlew testDebugUnitTest jacocoTestReport`: 9 packages / 87 classes /
// 11137 instructions, 4723 covered, 6414 missed = 42.41%.
// Those areas are Media3 / UsbManager / JNI adapters that JVM unit tests cannot
// execute (PlaybackCoordinator alone measured 0 of 2644 instructions), so 70%
// was never a number for this scope. The gate below is a ratchet against
// regression, not the PRD target: measured baseline minus one percentage point
// (42.41 - 1 = 41.41). It passes today and fails if coverage drops by more than
// one point.
val coreCoverageMinimum: java.math.BigDecimal = "0.4141".toBigDecimal()

val coreCoveragePackages = listOf(
    "com/sertum/player/domain/**",
    "com/sertum/player/data/metadata/**",
    "com/sertum/player/data/scan/**",
    "com/sertum/player/data/covers/**",
    "com/sertum/player/audio/extractor/**",
    "com/sertum/player/audio/backend/**",
    "com/sertum/player/audio/uac2/**",
    "com/sertum/player/audio/PlaybackCoordinator*",
)

val coreCoverageExcludes = listOf(
    "**/domain/model/AlbumKey.class",
    "**/domain/playback/AudioOutputBackend.class",
    "**/domain/playback/AudioOutputBackend\$DefaultImpls.class",
    "**/domain/playback/BackendCapabilities.class",
    "**/domain/playback/StreamSpec.class",
    "**/domain/playback/PlaybackState.class",
    // "**/domain/playback/RoomResumePositionStore*" was here. The class is gone - resume
    // position was removed at the user's request and its schema dropped in migration 6 to 7 -
    // so the pattern matched nothing. Removed rather than left, because this list is the
    // coverage denominator and the project already has one unexplained number in it (90.2%
    // recorded at M5 against 80.0% measured at M6); a rule naming a class that does not exist
    // is the last thing that needs to sit here unexplained.
    "**/data/metadata/TrackMetadata.class",
    "**/data/scan/MediaStoreSource*",
    "**/data/scan/SafSource*",
    "**/data/scan/FullScanSource*",
    "**/data/scan/LibraryScanner*",
    "**/data/covers/CoverStore*",
    // The MediaStore shell left around CueSheetLoader once the decisions moved out of it: the
    // loader is measured, and the query it feeds is the same kind of adapter as MediaStoreSource.
    "**/data/scan/CueSource.class",
    // Data carriers. The policy above says these do not belong in the denominator, and until now
    // they were in it by accident: they are declared in LibraryScanner.kt, whose *class* pattern is
    // excluded, and a pattern on the file name cannot catch classes declared beside it.
    "**/data/scan/ScanStats.class",
    "**/data/scan/ScanProgress.class",
)

fun coreCoverageClassDirs(): ConfigurableFileCollection {
    val classDirs = listOf(
        "$buildDir/tmp/kotlin-classes/debug",
        "$buildDir/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    ).filter { file(it).exists() }.map { file(it) }
    val dirs = files(classDirs).asFileTree.matching {
        include(coreCoveragePackages)
        exclude(coreCoverageExcludes)
    }
    return files(dirs)
}

tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "JaCoCo coverage report for the PRD 10.1 core packages and the exclusive audio engine."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(coreCoverageClassDirs())
    executionData.setFrom(
        files("$buildDir/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    )
}

tasks.register<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("testDebugUnitTest")
    mustRunAfter("jacocoTestReport")
    group = "verification"
    description = "Fails when the core denominator falls below 41.41% instruction coverage " +
        "(42.41% measured 2026-10-19 with the widened scope, minus one point)."

    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(coreCoverageClassDirs())
    executionData.setFrom(
        files("$buildDir/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = coreCoverageMinimum
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoCoverageVerification")
}
