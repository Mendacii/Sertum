package com.sertum.player.audio.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether the route delivers the source's bit depth.
 *
 * The exclusive path packs to 24-bit samples. 16- and 24-bit sources pass through it untouched; a
 * 32-bit source does not, and the badge leans on this to avoid claiming bit-perfect for one.
 */
class DepthPolicyTest {

    @Test
    fun `sixteen and twenty-four bit sources survive`() {
        assertThat(depthIsPreserved(16)).isTrue()
        assertThat(depthIsPreserved(24)).isTrue()
    }

    @Test
    fun `a thirty-two bit source does not`() {
        assertThat(depthIsPreserved(32)).isFalse()
    }

    @Test
    fun `an unknown depth does not turn the badge yellow`() {
        // 0 means the metadata said nothing, which is not the same as "too deep".
        assertThat(depthIsPreserved(0)).isTrue()
    }
}
