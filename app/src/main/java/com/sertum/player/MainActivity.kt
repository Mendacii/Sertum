package com.sertum.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.sertum.player.ui.SertumApp
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
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or denied; playback continues either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SertumApplication
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
                SertumApp(initialTab = requestedTab)
            }
        }
    }
}
