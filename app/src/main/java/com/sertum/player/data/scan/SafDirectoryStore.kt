package com.sertum.player.data.scan

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Persists the user-selected SAF tree URIs. The system grant itself lives in
 * the platform's persisted-uri-permissions table; this list is the app-side
 * record of which directories to scan and display.
 *
 * The preferences are passed in rather than reached for through a Context, so the set semantics
 * this class is responsible for - keep every entry, add without duplicating, remove only the one
 * asked for, and do not drop records that fail to parse on this platform - can be tested on the
 * JVM. The `Uri` conversions sit at the edge, where a test cannot follow them.
 */
class SafDirectoryStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun load(): List<Uri> =
        raw().mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    fun add(uri: Uri) = addRaw(uri.toString())

    fun remove(uri: Uri) = removeRaw(uri.toString())

    /**
     * The stored strings, sorted.
     *
     * Sorted rather than returned as the platform hands them over: a `Set` has no order, and the
     * scan walks these directories, so an arbitrary order would make the library's own order
     * change between launches.
     */
    internal fun raw(): List<String> =
        prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().sorted()

    /**
     * Adds without re-serialising what is already stored.
     *
     * The first version went through `load()` and back, which passed every existing entry through
     * `Uri.parse` first: one entry this platform could not parse would be dropped by the next add,
     * silently forgetting a directory the user had granted.
     */
    internal fun addRaw(uri: String) = write(raw() + uri)

    internal fun removeRaw(uri: String) = write(raw() - uri)

    private fun write(values: List<String>) {
        prefs.edit().putStringSet(KEY_URIS, values.toSet()).apply()
    }

    private companion object {
        const val PREFS_NAME = "saf_dirs"
        const val KEY_URIS = "uris"
    }
}
