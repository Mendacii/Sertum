package com.sertum.player.data.covers

import com.sertum.player.data.scan.AudioFormat
import java.io.File
import java.security.MessageDigest

/**
 * Non-destructive user cover storage (PRD 7.8): files live under
 * filesDir/covers/<sha256(albumKey)>.img; the original image is kept and
 * thumbnail generation is the UI/image-pipeline's job when rendering.
 *
 * ## Why the key is not the only name looked for
 *
 * The album key is not stable: it is `albumArtist|title`, or `albumArtist|title|format` since
 * the container format joined it, and every one of those parts comes from tags. When the key
 * changes, the file name derived from it changes with it, and a cover the user added stays on
 * disk under the old name where nothing looks for it. That happened for real: adding the format
 * on 2026-10-16 orphaned nine covers at once, and the only visible symptom was that they had to
 * be added again.
 *
 * So a look-up tries the current key, then the keys it may previously have been stored under.
 * The fallback is deliberately narrow - one shape, the key with its format suffix removed - so
 * that it cannot reach a different album's artwork.
 */
class CoverStore(private val filesDir: File) {

    private val dir: File by lazy { File(filesDir, "covers").apply { mkdirs() } }

    fun save(albumKey: String, bytes: ByteArray): String {
        val file = fileFor(albumKey)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun saveEmbedded(albumKey: String, bytes: ByteArray): String {
        val file = autoFileFor(albumKey, "embedded")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun autoFileFor(albumKey: String, kind: String): File =
        File(dir, "$kind-${sha256(albumKey)}.img")

    fun deleteEmbedded(albumKey: String): Boolean = autoFileFor(albumKey, "embedded").delete()

    /**
     * The user's cover for this album, or null if there is none.
     *
     * Reads the file rather than trusting the key alone, so a cover added before the key changed
     * is still found. Returns the path it was actually read from, which may not be [albumKey]'s
     * own file; callers that store it should also call [adoptFallback] so the next look-up is
     * direct.
     */
    fun userCoverFor(albumKey: String): String? =
        firstExisting(albumKey)?.absolutePath

    /** True when the cover for [albumKey] was found under an older key rather than its own. */
    fun hasOnlyFallbackCover(albumKey: String): Boolean =
        !fileFor(albumKey).exists() && userCoverFor(albumKey) != null

    /**
     * Copies a fallback cover to the name this key expects, so it is found directly next time.
     *
     * A copy rather than a rename: the old file is left alone, because a guess about which key
     * is "the" key is exactly the kind of assumption that orphaned these files in the first
     * place. The cost is one duplicate image per album per key change.
     */
    fun adoptFallback(albumKey: String): Boolean {
        if (fileFor(albumKey).exists()) return false
        val source = firstExisting(albumKey) ?: return false
        return runCatching {
            fileFor(albumKey).writeBytes(source.readBytes())
            true
        }.getOrDefault(false)
    }

    private fun firstExisting(albumKey: String): File? =
        (listOf(albumKey) + legacyKeysFor(albumKey))
            .asSequence()
            .map { fileFor(it) }
            .firstOrNull { it.exists() && it.length() > 0 }

    /**
     * The key forms this album may previously have been stored under.
     *
     * Only one shape is tried: the key without its trailing format segment. That covers the
     * change that actually happened, and it cannot reach another album, because dropping a
     * suffix makes the name less specific rather than different.
     *
     * The suffix is matched against the labels [AudioFormat] produces rather than tested for
     * "looks like a format". A shape test gets this wrong on real names: the album
     * `At the Gates|Slaughter of the Soul [Reissue]` ends in `[Reissue]`, which any reasonable
     * character rule rejects, so the fallback silently stopped working for the albums most
     * likely to need it.
     */
    private fun legacyKeysFor(albumKey: String): List<String> {
        val cut = albumKey.lastIndexOf('|')
        if (cut <= 0) return emptyList()
        val base = albumKey.substring(0, cut)
        val suffix = albumKey.substring(cut + 1)
        if (suffix !in AudioFormat.formatLabels()) return emptyList()
        // A folder-keyed album's key ends in a path segment; dropping it would produce a
        // different path, not an older name for the same album.
        if (base.startsWith("__FOLDER__") || base.startsWith("__UNKNOWN__")) return emptyList()
        return listOf(base)
    }

    /** Test seam: the keys a look-up for [albumKey] would try, in order. */
    internal fun candidateKeys(albumKey: String): List<String> = listOf(albumKey) + legacyKeysFor(albumKey)

    fun delete(albumKey: String): Boolean = fileFor(albumKey).delete()

    fun fileFor(albumKey: String): File = File(dir, sha256(albumKey) + ".img")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
