package com.sertum.player.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App preferences that must outlive the process.
 *
 * [com.sertum.player.ui.settings.SettingsStateHolder] stays the in-memory
 * reactive projection the UI reads, while this is the durable source of truth.
 *
 * `scanOnStartup` is consumed in `Application.onCreate`, before any UI exists,
 * so it cannot live only in a Compose-observed holder. That startup ordering is
 * the reason this class exists.
 *
 * Note: the output-mode selection is deliberately NOT persisted. It resets to
 * the standard route on every launch, because a restored "USB exclusive" state
 * with no DAC attached would silently fall back to a shared stream while the UI
 * still claimed exclusive output. Restoring it correctly needs a device-attached
 * check at startup, which is not implemented.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sertum_app_prefs", Context.MODE_PRIVATE)

    private val _scanOnStartup = MutableStateFlow(
        prefs.getBoolean(KEY_SCAN_ON_STARTUP, DEFAULT_SCAN_ON_STARTUP),
    )

    private val _fullScanEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_FULL_SCAN_ENABLED, DEFAULT_FULL_SCAN_ENABLED),
    )

    private val _darkTheme = MutableStateFlow(
        prefs.getBoolean(KEY_DARK_THEME, DEFAULT_DARK_THEME),
    )

    private val _autoExclusive = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_EXCLUSIVE, DEFAULT_AUTO_EXCLUSIVE),
    )

    private val _roundCover = MutableStateFlow(
        prefs.getBoolean(KEY_ROUND_COVER, DEFAULT_ROUND_COVER),
    )

    private val _language = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE,
    )

    /** Cold-start library rescan. Defaults to the historical always-scan behaviour. */
    val scanOnStartup: StateFlow<Boolean> = _scanOnStartup.asStateFlow()

    fun setScanOnStartup(enabled: Boolean) {
        if (_scanOnStartup.value == enabled) return
        _scanOnStartup.value = enabled
        prefs.edit().putBoolean(KEY_SCAN_ON_STARTUP, enabled).apply()
    }

    /**
     * Whether folder artwork (`cover.jpg` beside the tracks) may be read.
     *
     * Backed by the same stored key the old "full scan" switch used: it was never a scanning scope
     * - it gated MediaStore, the only device-wide source, and turning it off emptied the candidate
     * list, which the next full scan turned into a deleted library. What the grant behind it really
     * enables is reading files beside the music, so the switch now names that and nothing else.
     */
    val folderCoversEnabled: StateFlow<Boolean> = _fullScanEnabled.asStateFlow()

    fun setFolderCoversEnabled(enabled: Boolean) {
        if (_fullScanEnabled.value == enabled) return
        _fullScanEnabled.value = enabled
        prefs.edit().putBoolean(KEY_FULL_SCAN_ENABLED, enabled).apply()
    }

    /**
     * Theme, cover shape and language.
     *
     * These lived only in [com.sertum.player.ui.settings.SettingsStateHolder] and reset to their
     * defaults on every cold start - the theme was the visible symptom: switching to light, closing
     * the app and reopening it came back dark, because the holder is a plain in-memory object and
     * nothing wrote the choice anywhere durable. They are not read before the UI exists, unlike
     * [scanOnStartup], so they are seeded into the holder from here in `MainActivity.onCreate`
     * before the first composition, and written back whenever the reader changes them.
     */
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    fun setDarkTheme(dark: Boolean) {
        if (_darkTheme.value == dark) return
        _darkTheme.value = dark
        prefs.edit().putBoolean(KEY_DARK_THEME, dark).apply()
    }

    /**
     * Whether plugging in a DAC selects the exclusive route.
     *
     * On by default: a player whose whole point is the exclusive path should not need a tap each
     * time the DAC is connected. Switching still goes through the coordinator, so it is refused
     * when nothing is attached rather than assumed.
     */
    val autoExclusiveOnUsbAttach: StateFlow<Boolean> = _autoExclusive.asStateFlow()

    fun setAutoExclusiveOnUsbAttach(enabled: Boolean) {
        if (_autoExclusive.value == enabled) return
        _autoExclusive.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_EXCLUSIVE, enabled).apply()
    }

    val roundCover: StateFlow<Boolean> = _roundCover.asStateFlow()

    fun setRoundCover(round: Boolean) {
        if (_roundCover.value == round) return
        _roundCover.value = round
        prefs.edit().putBoolean(KEY_ROUND_COVER, round).apply()
    }

    /** Stored by enum name; an unrecognised value falls back to [DEFAULT_LANGUAGE]. */
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(tag: String) {
        if (_language.value == tag) return
        _language.value = tag
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
    }

    companion object {
        const val DEFAULT_SCAN_ON_STARTUP = true

        /** Off unless asked for: reading beside the music needs the all-files grant. */
        const val DEFAULT_FULL_SCAN_ENABLED = false

        /** Dark is the designed default (PRD 7.10); light is the opt-in. */
        const val DEFAULT_DARK_THEME = true

        /** Plugging the DAC in selects the exclusive route unless the reader turns this off. */
        const val DEFAULT_AUTO_EXCLUSIVE = true

        /** Square covers are the default; the round shape is a choice. */
        const val DEFAULT_ROUND_COVER = false

        /** Stored as the enum's name rather than an ordinal, so reordering cannot remap it. */
        const val DEFAULT_LANGUAGE = "SYSTEM"

        internal const val KEY_SCAN_ON_STARTUP = "scan_on_startup"
        internal const val KEY_FULL_SCAN_ENABLED = "full_scan_enabled"
        internal const val KEY_DARK_THEME = "dark_theme"
        internal const val KEY_ROUND_COVER = "round_cover"
        internal const val KEY_AUTO_EXCLUSIVE = "auto_exclusive_on_usb"
        internal const val KEY_LANGUAGE = "language"

        /** Retired key, cleared on first access so it cannot linger on disk. */
        internal const val KEY_OUTPUT_MODE_RETIRED = "output_mode"
    }

    init {
        // The persisted output mode was removed; drop any value a previous
        // build may have written so a stale preference cannot confuse a later
        // implementation that does restore it properly.
        if (prefs.contains(KEY_OUTPUT_MODE_RETIRED)) {
            prefs.edit().remove(KEY_OUTPUT_MODE_RETIRED).apply()
        }
    }
}
