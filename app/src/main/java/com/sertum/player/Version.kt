package com.sertum.player

/** Single source of truth for the human-readable app version shown in tests and UI. */
const val SERTUM_VERSION = "0.1.0"

/**
 * Short commit sha baked in at build time, or `"unknown"` when the build ran
 * outside a git checkout. Emitted as `BuildConfig.SERTUM_BUILD_SHA`.
 */
const val SERTUM_FALLBACK_SHA = "unknown"

/** Build timestamp baked in at build time (ISO-8601), or `""` when unavailable. */
const val SERTUM_FALLBACK_BUILD_TIME = ""

/**
 * Reads a generated `BuildConfig` string field.
 *
 * Reflective on purpose: the JVM unit-test variant does not ship the generated
 * `BuildConfig` class, and this label is presentation only - it must never be
 * able to crash the About screen.
 */
internal fun buildConfigString(field: String, fallback: String): String = runCatching {
    val clazz = Class.forName("com.sertum.player.BuildConfig")
    (clazz.getField(field).get(null) as? String)?.takeIf { it.isNotEmpty() }
}.getOrNull() ?: fallback

/**
 * The one string that identifies exactly which build is installed:
 * `0.1.0+bf1f52a 2026-08-17T00:09:12Z`.
 */
fun sertumBuildLabel(
    version: String = SERTUM_VERSION,
    sha: String = buildConfigString("SERTUM_BUILD_SHA", SERTUM_FALLBACK_SHA),
    buildTime: String = buildConfigString("SERTUM_BUILD_TIME", SERTUM_FALLBACK_BUILD_TIME),
): String = buildString {
    append(version)
    append('+')
    append(sha.ifBlank { SERTUM_FALLBACK_SHA })
    if (buildTime.isNotBlank()) {
        append(' ')
        append(buildTime)
    }
}
