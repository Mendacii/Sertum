package com.sertum.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import com.sertum.player.ui.SertumApp
import com.sertum.player.ui.screens.library.hasMediaPermission
import com.sertum.player.ui.screens.library.mediaPermissionName
import com.sertum.player.ui.settings.LanguageOption
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
         * Opens the queue layer on launch, e.g. `--es player 1 --es queue 1`.
         *
         * The queue is one tap deeper than the player - a button inside the player sheet - and
         * injected input cannot tap it on the reference device, so its controls could not be
         * looked at at all. Same reason as [EXTRA_OPEN_PLAYER], one layer down.
         */
        const val EXTRA_OPEN_QUEUE = "queue"

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

    /**
     * Asked for on first launch, because without it the app cannot see a single file.
     *
     * The permission was declared and read but never requested: the library went empty on a fresh
     * install, the startup scan skipped silently, and the only way forward was the app's system
     * settings page - which is not where a reader looks. Nothing had been scanned while it was
     * missing, so granting it has to produce a library.
     */
    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) (application as SertumApplication).requestLibraryScan()
        }

    /**
     * Transparent bars whose icons contrast with the app's own theme.
     *
     * The theme is an in-app setting, so `SystemBarStyle.auto` - which asks the system whether it
     * is in dark mode - would be wrong whenever the two disagree.
     */
    private fun systemBarStyleFor(): SystemBarStyle {
        val transparent = android.graphics.Color.TRANSPARENT
        return if (SettingsStateHolder.state.value.darkTheme) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
    }

    private fun askForMediaAccessIfNeeded() {
        if (hasMediaPermission(this)) return
        mediaPermissionLauncher.launch(mediaPermissionName())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SertumApplication
        // Applies the state-level theme override before any UI is built, so the state and
        // the rendered theme agree instead of diverging.
        if (savedInstanceState == null) {
            // Seed the in-memory settings projection from the durable values before any UI is built.
            // Without this the holder always started at its defaults, so a chosen theme, cover shape
            // or language was forgotten the moment the process died - the reader saw the light theme
            // revert to dark on the next launch. Seeding here rather than in the composition keeps it
            // to one frame: the first frame drawn is already the saved theme.
            val preferences = app.preferences
            SettingsStateHolder.update {
                it.copy(
                    darkTheme = preferences.darkTheme.value,
                    roundCover = preferences.roundCover.value,
                    language = runCatching {
                        LanguageOption.valueOf(preferences.language.value)
                    }.getOrDefault(LanguageOption.SYSTEM),
                )
            }
            // Diagnostic overrides come after the seed so a device check can force a state without
            // the saved value winning. They deliberately do not persist: they exist to test
            // rendering, not to change the reader's choice.
            when (intent?.getStringExtra(EXTRA_STATE_THEME)) {
                "light" -> SettingsStateHolder.update { it.copy(darkTheme = false) }
                "dark" -> SettingsStateHolder.update { it.copy(darkTheme = true) }
            }
            if (intent?.getStringExtra(EXTRA_ROUND_COVER) == "1") {
                SettingsStateHolder.update { it.copy(roundCover = true) }
            }
            // After the seed and the diagnostic overrides, so the dialog belongs to the launch the
            // reader is actually looking at.
            askForMediaAccessIfNeeded()
        }
        // Also called after a recreation, which is the case that broke: a language change recreates
        // this activity, and a recreated window does not inherit the platform's edge-to-edge, so the
        // theme's legacy statusBarColor (#000000) showed through in the strip above a light page.
        // Owning it here means every window the app builds is edge-to-edge; the legacy colours in
        // the XML theme no longer decide anything.
        enableEdgeToEdge(
            statusBarStyle = systemBarStyleFor(),
            navigationBarStyle = systemBarStyleFor(),
        )
        // Belt and braces: with edge-to-edge the system still paints a bar background when it thinks
        // the window draws one, and this is what survived a recreation as a black strip. Transparent
        // leaves nothing for it to paint.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // The window background is what shows anywhere the composition does not reach. It is a
        // fixed colour in the XML theme (#000000, the dark default), so a recreated window that
        // lays its content out below the status bar showed a black strip above a light page -
        // exactly what a language change did. Following the app's theme means whatever is exposed
        // matches the page behind it, and a cold start flashes the right colour too.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (SettingsStateHolder.state.value.darkTheme) {
                    com.sertum.player.ui.theme.PureBlack.toArgb()
                } else {
                    com.sertum.player.ui.theme.PaperWhite.toArgb()
                },
            ),
        )
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
                    openQueue = savedInstanceState == null &&
                        intent?.getStringExtra(EXTRA_OPEN_QUEUE) == "1",
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
