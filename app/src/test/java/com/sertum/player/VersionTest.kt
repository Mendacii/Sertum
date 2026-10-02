package com.sertum.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionTest {

    @Test
    fun `sertum version constant is the M0 baseline`() {
        assertThat(SERTUM_VERSION).isEqualTo("0.1.0")
    }

    @Test
    fun `build label carries version, sha and build time`() {
        val label = sertumBuildLabel(
            version = "0.1.0",
            sha = "bf1f52a",
            buildTime = "2026-08-17T00:09:12Z",
        )

        assertThat(label).isEqualTo("0.1.0+bf1f52a 2026-08-17T00:09:12Z")
    }

    @Test
    fun `build label falls back to unknown sha instead of dropping the marker`() {
        val label = sertumBuildLabel(version = "0.1.0", sha = "", buildTime = "")

        assertThat(label).isEqualTo("0.1.0+unknown")
    }

    @Test
    fun `build label omits the timestamp when the build had none`() {
        val label = sertumBuildLabel(version = "0.1.0", sha = "abc1234", buildTime = "")

        assertThat(label).isEqualTo("0.1.0+abc1234")
    }

    @Test
    fun `default arguments never throw outside a generated BuildConfig`() {
        // The unit-test variant has no generated BuildConfig class; the
        // reflective lookup must degrade to the documented fallbacks.
        val label = sertumBuildLabel()

        assertThat(label).startsWith("$SERTUM_VERSION+")
        assertThat(label).isNotEmpty()
    }
}
