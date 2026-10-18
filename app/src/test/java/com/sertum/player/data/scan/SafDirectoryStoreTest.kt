package com.sertum.player.data.scan

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The store is a set of strings in a preference entry. What is worth pinning down is how it treats
 * that set - adds, removals, duplicates, and the entries it cannot understand - because getting
 * one of those wrong loses a directory the user granted and the scan silently stops seeing it.
 */
class SafDirectoryStoreTest {

    private val prefs = FakePreferences()
    private val store = SafDirectoryStore(prefs)

    @Test
    fun `an empty store reads back empty`() {
        assertThat(store.raw()).isEmpty()
    }

    @Test
    fun `adding stores the uri`() {
        store.addRaw("content://tree/a")

        assertThat(store.raw()).containsExactly("content://tree/a")
    }

    @Test
    fun `adding the same uri twice keeps one entry`() {
        store.addRaw("content://tree/a")
        store.addRaw("content://tree/a")

        assertThat(store.raw()).containsExactly("content://tree/a")
    }

    @Test
    fun `entries come back sorted so the scan order is stable`() {
        store.addRaw("content://tree/c")
        store.addRaw("content://tree/a")
        store.addRaw("content://tree/b")

        assertThat(store.raw()).containsExactly("content://tree/a", "content://tree/b", "content://tree/c").inOrder()
    }

    @Test
    fun `removing takes only the named entry`() {
        store.addRaw("content://tree/a")
        store.addRaw("content://tree/b")

        store.removeRaw("content://tree/a")

        assertThat(store.raw()).containsExactly("content://tree/b")
    }

    @Test
    fun `removing something absent changes nothing`() {
        store.addRaw("content://tree/a")

        store.removeRaw("content://tree/missing")

        assertThat(store.raw()).containsExactly("content://tree/a")
    }

    @Test
    fun `added entries are not lost when another is added`() {
        // The regression this guards: add used to round-trip the stored set through load() and
        // back, so an entry this platform could not parse was dropped by the next add.
        store.addRaw("garbage://not a tree")

        store.addRaw("content://tree/a")

        assertThat(store.raw()).containsExactly("garbage://not a tree", "content://tree/a")
    }

    @Test
    fun `load skips entries it cannot parse without dropping the rest`() {
        store.addRaw("content://tree/a")

        // On the JVM android.net.Uri is a stub, so every entry fails to parse; what matters here
        // is that the call returns rather than throwing, and that raw() still holds the record.
        assertThat(store.load()).isEmpty()
        assertThat(store.raw()).containsExactly("content://tree/a")
    }
}

/** Minimal in-memory SharedPreferences: only the strings this store uses are implemented. */
private class FakePreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()

        override fun putString(key: String?, value: String?) = apply {
            pending[key ?: return@apply] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
            pending[key ?: return@apply] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int) = apply { pending[key ?: return@apply] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key ?: return@apply] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key ?: return@apply] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key ?: return@apply] = value }

        override fun remove(key: String?) = apply { pending.remove(key) }

        override fun clear() = apply { pending.clear() }

        override fun commit(): Boolean {
            values.putAll(pending)
            return true
        }

        override fun apply() {
            values.putAll(pending)
        }
    }
}
