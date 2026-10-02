package com.sertum.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionTest {

    @Test
    fun `sertum version constant is the M0 baseline`() {
        assertThat(SERTUM_VERSION).isEqualTo("0.1.0")
    }

    @Test
    fun `build fingerprint carries version, sha and build time`() {
        val fingerprint = sertumBuildFingerprint(
            version = "0.1.0",
            sha = "bf1f52a",
            buildTime = "2026-08-17T00:09:12Z",
        )

        assertThat(fingerprint).isEqualTo("0.1.0+bf1f52a 2026-08-17T00:09:12Z")
    }

    @Test
    fun `build fingerprint falls back to unknown sha instead of dropping the marker`() {
        val fingerprint = sertumBuildFingerprint(version = "0.1.0", sha = "", buildTime = "")

        assertThat(fingerprint).isEqualTo("0.1.0+unknown")
    }

    @Test
    fun `build fingerprint omits the timestamp when the build had none`() {
        val fingerprint = sertumBuildFingerprint(version = "0.1.0", sha = "abc1234", buildTime = "")

        assertThat(fingerprint).isEqualTo("0.1.0+abc1234")
    }

    @Test
    fun `default arguments never throw outside a generated BuildConfig`() {
        // The unit-test variant has no generated BuildConfig class; the
        // reflective lookup must degrade to the documented fallbacks.
        val fingerprint = sertumBuildFingerprint()

        assertThat(fingerprint).startsWith("$SERTUM_VERSION+")
        assertThat(fingerprint).isNotEmpty()
    }

    /**
     * The About screen shows the version alone. Guards the regression the user
     * reported: the fingerprint must not creep back into the user-facing version.
     */
    @Test
    fun `the user-facing version carries no build fingerprint`() {
        assertThat(SERTUM_VERSION).isEqualTo("0.1.0")
        assertThat(SERTUM_VERSION).doesNotContain("+")
        assertThat(SERTUM_VERSION).doesNotContain("unknown")
    }
}
