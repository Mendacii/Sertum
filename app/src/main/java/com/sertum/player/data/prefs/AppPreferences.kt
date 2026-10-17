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

    /** Cold-start library rescan. Defaults to the historical always-scan behaviour. */
    val scanOnStartup: StateFlow<Boolean> = _scanOnStartup.asStateFlow()

    fun setScanOnStartup(enabled: Boolean) {
        if (_scanOnStartup.value == enabled) return
        _scanOnStartup.value = enabled
        prefs.edit().putBoolean(KEY_SCAN_ON_STARTUP, enabled).apply()
    }

    /**
     * Whether a scan may look at the whole device instead of only the chosen folders.
     *
     * Durable here rather than in [com.sertum.player.ui.settings.SettingsStateHolder] because the
     * scanner needs it and is not a composable. It used to live only in that in-memory holder,
     * which meant it reset to `false` on every launch - and nothing read it anyway, so the setting
     * was inert in both directions. See [com.sertum.player.data.scan.LibraryScanner.scanBlocking].
     *
     * Off means the library comes from the folders the user added and nowhere else. That is the
     * whole point of the switch: a reader who never grants it should never have their device
     * indexed behind their back.
     */
    val fullScanEnabled: StateFlow<Boolean> = _fullScanEnabled.asStateFlow()

    fun setFullScanEnabled(enabled: Boolean) {
        if (_fullScanEnabled.value == enabled) return
        _fullScanEnabled.value = enabled
        prefs.edit().putBoolean(KEY_FULL_SCAN_ENABLED, enabled).apply()
    }

    companion object {
        const val DEFAULT_SCAN_ON_STARTUP = true

        /** Off unless asked for: nothing is scanned before the reader says so. */
        const val DEFAULT_FULL_SCAN_ENABLED = false

        internal const val KEY_SCAN_ON_STARTUP = "scan_on_startup"
        internal const val KEY_FULL_SCAN_ENABLED = "full_scan_enabled"

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
