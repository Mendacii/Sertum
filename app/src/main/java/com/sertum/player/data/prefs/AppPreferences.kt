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

    /** Cold-start library rescan. Defaults to the historical always-scan behaviour. */
    val scanOnStartup: StateFlow<Boolean> = _scanOnStartup.asStateFlow()

    fun setScanOnStartup(enabled: Boolean) {
        if (_scanOnStartup.value == enabled) return
        _scanOnStartup.value = enabled
        prefs.edit().putBoolean(KEY_SCAN_ON_STARTUP, enabled).apply()
    }

    companion object {
        const val DEFAULT_SCAN_ON_STARTUP = true

        internal const val KEY_SCAN_ON_STARTUP = "scan_on_startup"

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
