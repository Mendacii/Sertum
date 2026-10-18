package com.sertum.player.audio.extractor

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The class exists for one reason - AIFF has no standard Media3 extractor, so ours has to be asked
 * first - and that ordering is what these tests pin down. A delegate stands in for the standard
 * extractors, so the test is about position rather than about which formats Media3 ships.
 */
class SertumExtractorsFactoryTest {

    private class FakeExtractor : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = false
        override fun init(output: ExtractorOutput) = Unit
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    @Test
    fun `aiff is asked first, then the delegate's extractors in order`() {
        val first = FakeExtractor()
        val second = FakeExtractor()
        val factory = SertumExtractorsFactory(
            delegate = object : ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> = arrayOf(first, second)
                override fun createExtractors(
                    uri: android.net.Uri,
                    responseHeaders: Map<String, List<String>>,
                ): Array<Extractor> = arrayOf(first, second)
            },
        )

        val extractors = factory.createExtractors()

        assertThat(extractors).hasLength(3)
        assertThat(extractors[0]).isInstanceOf(AiffExtractor::class.java)
        assertThat(extractors[1]).isSameInstanceAs(first)
        assertThat(extractors[2]).isSameInstanceAs(second)
    }

    @Test
    fun `withAiffFirst keeps an empty delegate empty apart from ours`() {
        val factory = SertumExtractorsFactory(
            delegate = object : ExtractorsFactory {
                override fun createExtractors(): Array<Extractor> = emptyArray()
                override fun createExtractors(
                    uri: android.net.Uri,
                    responseHeaders: Map<String, List<String>>,
                ): Array<Extractor> = emptyArray()
            },
        )

        assertThat(factory.withAiffFirst(emptyArray())).hasLength(1)
        assertThat(factory.withAiffFirst(emptyArray())[0]).isInstanceOf(AiffExtractor::class.java)
    }

    // The default delegate is deliberately not exercised here: DefaultExtractorsFactory asks
    // android.net.Uri which file type it is, and on the JVM that stub returns null, so the call
    // cannot run outside a device. The ordering it would confirm is the same one the fake delegate
    // pins down above; what the default would add is a list of Media3's formats, which is not ours
    // to assert.
}
