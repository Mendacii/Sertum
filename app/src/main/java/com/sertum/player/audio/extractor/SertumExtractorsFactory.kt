package com.sertum.player.audio.extractor

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * Media3 tries extractors in order. AIFF has no native Media3 extractor, so
 * the self-written [AiffExtractor] is probed first (sniffing FORM....AIFF),
 * then the standard set handles FLAC/WAV/MP4-ALAC/MP3/etc.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SertumExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        withAiffFirst(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = withAiffFirst(delegate.createExtractors(uri, responseHeaders))

    /**
     * AIFF in front of everything else.
     *
     * The order is the whole point of this class: Media3 asks each extractor in turn whether it
     * recognises the input, and none of the standard ones recognises AIFF, so a file only plays
     * because ours is asked first. Extracted so the ordering can be tested without a Media3 source
     * or a Uri - the two overrides above then differ only in which delegate call they wrap.
     */
    internal fun withAiffFirst(base: Array<Extractor>): Array<Extractor> =
        arrayOf(AiffExtractor(), *base)
}
