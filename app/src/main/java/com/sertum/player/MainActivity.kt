package com.sertum.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.sertum.player.ui.SertumApp
import com.sertum.player.ui.settings.SettingsStateHolder
import com.sertum.player.ui.theme.SertumTheme

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Optional launch target for `am start`, e.g.
         * `adb shell am start -n com.sertum.player/.MainActivity --es tab settings`.
         *
         * Exists because `adb shell input tap` does not activate Compose controls
         * on the reference device, which made UI evidence impossible to capture
         * without a human present. Absent or unknown values keep the normal
         * behaviour (start on Songs), so nothing changes for real launches.
         */
        const val EXTRA_INITIAL_TAB = "tab"

        /**
         * Opens one section's explanation dialog on launch, e.g.
         * `adb shell am start -n com.sertum.player/.MainActivity --es tab settings --es info output`.
         *
         * Same reason as [EXTRA_INITIAL_TAB]: dialogs cannot be opened by injected
         * input on the reference device, so without this the dialog contents could
         * never be checked without a human tapping. Unknown values open nothing.
         */
        const val EXTRA_INFO_SECTION = "info"

        /**
         * Opens one album's detail page on launch, by album key.
         *
         * The album page is reached by tapping a cover, and taps cannot be injected on the
         * reference device, so without this the page's layout could not be inspected or
         * checked at all. Ignored on activity recreation like the other launch extras.
         */
        const val EXTRA_ALBUM = "album"

        /**
         * Selects the round cover on launch, for device evidence.
         *
         * Round cover is in-memory state, so a device check of the ring around it would
         * otherwise need a tap on the settings option, which cannot be injected.
         */
        const val EXTRA_ROUND_COVER = "roundcover"

        /** Opens the full player on launch, for device evidence. See SertumApp.openPlayer. */
        const val EXTRA_OPEN_PLAYER = "player"

        /**
         * Forces a theme for the launch, e.g. `--es theme light`, so the light scheme
         * can be inspected on device. The theme is in-memory state that defaults to
         * dark, and injected input cannot tap the theme option, so this was the only
         * way to check that the settings page follows the scheme.
         */
        const val EXTRA_THEME = "theme"

        /**
         * Sets the in-memory theme state on launch, e.g. `--es stheme light`, without
         * overriding it in the composition.
         *
         * [EXTRA_THEME] renders a theme regardless of state, which hides whether the
         * state itself is working. This one writes the state instead, so a device check
         * can tell "the theme renders" apart from "the toggle updates the state".
         */
        const val EXTRA_STATE_THEME = "stheme"

    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or denied; playback continues either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SertumApplication
        // Applies the state-level theme override before any UI is built, so the state and
        // the rendered theme agree instead of diverging.
        if (savedInstanceState == null) {
            when (intent?.getStringExtra(EXTRA_STATE_THEME)) {
                "light" -> SettingsStateHolder.update { it.copy(darkTheme = false) }
                "dark" -> SettingsStateHolder.update { it.copy(darkTheme = true) }
            }
            if (intent?.getStringExtra(EXTRA_ROUND_COVER) == "1") {
                SettingsStateHolder.update { it.copy(roundCover = true) }
            }
        }
        app.playbackController.notificationPermissionRequester = {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Only consult the extra on a genuine first launch: after a configuration
        // change (or the locale switch, which recreates the activity) the saved
        // instance state must win, otherwise changing language would snap the
        // pager back to the tab the app was originally started on.
        val requestedTab = if (savedInstanceState != null) {
            null
        } else {
            when (intent?.getStringExtra(EXTRA_INITIAL_TAB)) {
                "songs" -> 0
                "albums" -> 1
                "artists" -> 2
                "settings" -> 3
                else -> null
            }
        }
        setContent {
            SertumTheme {
                SertumApp(
                    initialTab = requestedTab,
                    openPlayer = savedInstanceState == null &&
                        intent?.getStringExtra(EXTRA_OPEN_PLAYER) == "1",
                    initialAlbumKey = if (savedInstanceState != null) {
                        null
                    } else {
                        intent?.getStringExtra(EXTRA_ALBUM)
                    },
                    initialInfoSection = if (savedInstanceState != null) {
                        null
                    } else {
                        intent?.getStringExtra(EXTRA_INFO_SECTION)
                    },

                    initialDarkTheme = if (savedInstanceState != null) {
                        null
                    } else {
                        when (intent?.getStringExtra(EXTRA_THEME)) {
                            "light" -> false
                            "dark" -> true
                            else -> null
                        }
                    },
                )
            }
        }
    }
}
