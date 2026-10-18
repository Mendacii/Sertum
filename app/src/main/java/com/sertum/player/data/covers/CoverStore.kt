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
class CoverStore(
    private val filesDir: File,
    /**
     * A second copy of the reader's own covers, outside the app's data directory.
     *
     * Everything else in this store can be rebuilt: embedded artwork is re-extracted from the files,
     * folder artwork is re-found beside them, and the library itself is re-scanned. A cover the
     * reader picked is the one thing that exists nowhere else, and it lived only under `filesDir` -
     * which an uninstall or "clear data" deletes. That is not hypothetical: it happened here on
     * 2026-10-18, when an installer uninstalled the app and twenty chosen covers went with it.
     *
     * So a chosen cover is also written here, on shared storage, where the app's own data directory
     * cannot take it with it, and [restoreFromMirror] brings it back on the next scan. Null disables
     * mirroring, which is what tests and any caller without storage access get.
     */
    private val mirrorDir: File? = null,
) {

    private val dir: File by lazy { File(filesDir, "covers").apply { mkdirs() } }

    fun save(albumKey: String, bytes: ByteArray): String {
        val file = fileFor(albumKey)
        file.writeBytes(bytes)
        mirror(albumKey, bytes)
        return file.absolutePath
    }

    /**
     * Copies a mirrored cover back into the app's own store.
     *
     * Returns true when this call is what made the cover available again. The caller is expected to
     * record the choice as well, since the row that held it lives in the same database an uninstall
     * removes.
     *
     * Looked up through the same key candidates as a normal read, so a cover mirrored before the
     * album key gained its format segment is still found.
     */
    fun restoreFromMirror(albumKey: String): Boolean {
        val mirror = mirrorDir ?: return false
        if (fileFor(albumKey).exists()) return false
        val source = candidateKeys(albumKey)
            .asSequence()
            .map { File(mirror, sha256(it) + MIRROR_SUFFIX) }
            .firstOrNull { it.isFile && it.length() > 0 }
            ?: return false
        return runCatching {
            fileFor(albumKey).writeBytes(source.readBytes())
            true
        }.getOrDefault(false)
    }

    /** Where [albumKey]'s mirrored copy would live, or null when mirroring is off. */
    fun mirrorFileFor(albumKey: String): File? =
        mirrorDir?.let { File(it, sha256(albumKey) + MIRROR_SUFFIX) }

    /**
     * Mirrors a cover that only exists in the app's own store.
     *
     * Covers chosen before the mirror existed are in exactly that state, and nothing would ever
     * copy them: [save] is the only writer, and it does not run again for a cover already saved.
     * The scan calls this so the guarantee catches up with the covers that came first.
     *
     * Returns true when the file was written by this call.
     */
    fun ensureMirrored(albumKey: String): Boolean {
        val mirror = mirrorDir ?: return false
        val target = File(mirror, sha256(albumKey) + MIRROR_SUFFIX)
        if (target.isFile && target.length() > 0) return false
        val source = firstExisting(albumKey) ?: return false
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(source.readBytes())
            true
        }.getOrDefault(false)
    }

    /**
     * Best effort on purpose: a device that will not let us write outside the app's data directory
     * must still be able to save a cover locally.
     */
    private fun mirror(albumKey: String, bytes: ByteArray) {
        val target = mirrorFileFor(albumKey) ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
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

    /**
     * Removes the reader's cover for [albumKey], everywhere it is stored.
     *
     * Deleting only the local copy was not a delete: [save] also writes a mirror outside the app's
     * data directory, and [restoreFromMirror] runs on every scan, so a removed cover came back the
     * next time the library was scanned. The older key forms are removed too - [userCoverFor] reads
     * through them, so leaving one behind would keep the cover visible after the reader removed it,
     * and [ensureMirrored] could copy that file back into the mirror.
     *
     * Returns true when at least one copy existed and was removed. A missing copy is not an error:
     * the postcondition callers rely on is "no cover for this key", not "one file was deleted".
     */
    fun delete(albumKey: String): Boolean {
        var removed = false
        for (key in candidateKeys(albumKey)) {
            if (fileFor(key).delete()) removed = true
            val mirror = mirrorFileFor(key) ?: continue
            if (mirror.delete()) removed = true
        }
        return removed
    }

    fun fileFor(albumKey: String): File = File(dir, sha256(albumKey) + ".img")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Kept `.img` so that no photo index picks the mirror up as gallery content. */
        const val MIRROR_SUFFIX = ".img"
    }
}
