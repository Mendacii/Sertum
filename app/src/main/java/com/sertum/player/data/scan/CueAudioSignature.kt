package com.sertum.player.data.scan

/** Extensions that hold audio, used when a CUE's declared name does not match a real file. */
private val AUDIO_EXTENSIONS = listOf("flac", "wav", "ape", "mp3", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wv")

private fun String.extension(): String = substringAfterLast('.', "").lowercase()

/**
 * The library path a CUE sheet's audio actually lives at, or null when it is not in the library.
 *
 * [declaredName] is what the CUE says, which is often not a file that exists. Two reasons show
 * up in the reference library, and both are handled here rather than by guessing:
 *
 * - **The extension is wrong.** A rip names the `.wav` it was cut from while the collection kept
 *   a `.flac`, so the base name is retried against the other audio extensions. Agony carries
 *   both `Agony (Japan).flac.cue` and `Agony (Japan).wav.cue` and only the FLAC exists.
 * - **The audio is gone.** ANKM-0002 still names an `.ape` that was deleted while eight working
 *   per-track FLACs sit beside it. No extension swap finds it, so nothing resolves - which is
 *   the point. Splitting on that CUE would create eight tracks pointing at a missing file and
 *   hide the eight that play.
 *
 * Only the CUE's own directory and its parent are considered, because that is where a CUE's
 * audio sits in practice: two releases here keep the sheet in a `cue/` subfolder beside the
 * files. Restricting to those two is what stops a file of the same name in another folder from
 * being taken as this release's audio.
 *
 * [libraryPaths] is the set of audio files the scan found. Requiring a match in it means a CUE
 * only takes effect when its audio is something the app can actually play.
 */
internal fun matchCueAudio(
    cuePath: String,
    declaredName: String,
    libraryPaths: Collection<String>,
): String? {
    val cueDirectory = cuePath.substringBeforeLast('/', "")
    val parentDirectory = cueDirectory.substringBeforeLast('/', "")
    val leaf = declaredName.substringAfterLast('\\').substringAfterLast('/')
    if (leaf.isBlank()) return null
    // A CUE is not its own audio. Without this, a malformed `FILE "disc.cue"` line resolves to
    // the sheet itself and playback is handed the wrong file rather than nothing.
    if (leaf.extension() !in AUDIO_EXTENSIONS) return null

    val directories = listOf(cueDirectory, parentDirectory).filter { it.isNotEmpty() }.distinct()
    for (directory in directories) {
        val exact = "$directory/$leaf"
        if (exact in libraryPaths) return exact
    }

    // The declared extension was not there. Retry the same base name against every audio
    // extension, in the same two directories only.
    val base = leaf.substringBeforeLast('.', leaf)
    if (base.isBlank()) return null
    for (directory in directories) {
        for (extension in AUDIO_EXTENSIONS) {
            val candidate = "$directory/$base.$extension"
            if (candidate in libraryPaths) return candidate
        }
    }
    return null
}
