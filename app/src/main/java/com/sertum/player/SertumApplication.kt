package com.sertum.player

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import coil3.SingletonImageLoader
import com.sertum.player.audio.PlaybackCoordinator
import com.sertum.player.audio.PlayerEngine
import com.sertum.player.audio.UsbHotplugController
import com.sertum.player.audio.session.PlaybackService
import com.sertum.player.data.covers.CoverStore
import com.sertum.player.data.db.SertumDatabase
import com.sertum.player.data.diagnostics.DiagnosticLevel
import com.sertum.player.data.diagnostics.DiagnosticsStore
import com.sertum.player.data.prefs.AppPreferences
import com.sertum.player.data.scan.LibraryScanner
import com.sertum.player.data.scan.SafDirectoryStore
import com.sertum.player.data.scan.ScanStats
import com.sertum.player.ui.imaging.buildSertumImageLoader
import java.io.File

class SertumApplication : Application() {

    private var hotplugController: UsbHotplugController? = null
    private var mediaController: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { context -> buildSertumImageLoader(context) }
        hotplugController = UsbHotplugController(
            this,
            onUsbAttached = { playbackController.onUsbDeviceAttached() },
            onUsbDetached = { playbackController.onUsbDeviceDetached() },
            onBluetoothChanged = { playbackController.setBluetoothConnected(it) },
        )
        // Consume the durable preference before anything observes it. The
        // output mode is deliberately not seeded here: it always starts on the
        // standard route (see AppPreferences for why).
        if (preferences.scanOnStartup.value && hasMediaPermission()) {
            requestLibraryScan()
        }
        ensureMediaController()
    }

    /**
     * Media3 only posts the media notification and promotes the service to
     * the foreground when at least one MediaController is connected to the
     * session. Keep one alive for the whole process.
     */
    private fun ensureMediaController() {
        if (mediaController != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                runCatching { mediaController = future.get() }
                    .onSuccess { android.util.Log.i("SertumSession", "controller connected") }
                    .onFailure { android.util.Log.i("SertumSession", "controller failed: $it") }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun hasMediaPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Bootstrap scan; safe to call repeatedly (full-rescan semantics). */
    fun requestLibraryScan(onDone: (Result<ScanStats>) -> Unit = {}) {
        requestScan(incremental = false, onDone = onDone)
    }

    /**
     * Reads only files that are new or have changed since the last scan, and drops the ones that
     * have gone.
     *
     * The same list of files is enumerated either way - listing is what a scan has to do to know
     * what exists - but a file whose size and modification time match the stored row is not opened
     * again, which is where nearly all of a scan's time goes.
     */
    fun requestIncrementalScan(onDone: (Result<ScanStats>) -> Unit = {}) {
        requestScan(incremental = true, onDone = onDone)
    }

    private fun requestScan(incremental: Boolean, onDone: (Result<ScanStats>) -> Unit) {
        libraryScanner.scanNow(incremental) { result ->
            result.onSuccess {
                diagnosticsStore.log(
                    DiagnosticLevel.INFO,
                    "scan",
                    (if (it.incremental) "incremental" else "full") +
                        " candidates=${it.candidates} parsed=${it.parsed} " +
                        "unchanged=${it.unchanged} failed=${it.failed} " +
                        "albums=${it.albums} artists=${it.artists}",
                )
            }.onFailure {
                diagnosticsStore.log(DiagnosticLevel.ERROR, "scan", "failed: ${it.message}")
            }
            onDone(result)
        }
    }

    val database: SertumDatabase by lazy {
        Room.databaseBuilder(this, SertumDatabase::class.java, "sertum.db")
            .addMigrations(
                SertumDatabase.MIGRATION_1_2,
                SertumDatabase.MIGRATION_2_3,
                SertumDatabase.MIGRATION_3_4,
                SertumDatabase.MIGRATION_4_5,
                SertumDatabase.MIGRATION_5_6,
                SertumDatabase.MIGRATION_6_7,
                SertumDatabase.MIGRATION_7_8,
            )
            .build()
    }

    val coverStore: CoverStore by lazy {
        // The mirror sits on shared storage, beside the music, because that is the only place an
        // uninstall cannot reach. A hidden directory and `.img` names keep it out of the gallery.
        CoverStore(
            filesDir = filesDir,
            mirrorDir = File(android.os.Environment.getExternalStorageDirectory(), "Music/.sertum-covers"),
        )
    }

    /** Durable user preferences; read once in [onCreate] before the first scan. */
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    val safDirectoryStore: SafDirectoryStore by lazy { SafDirectoryStore(this) }

    val diagnosticsStore: DiagnosticsStore by lazy {
        DiagnosticsStore(File(filesDir, "diagnostics"))
    }

    val libraryScanner: LibraryScanner by lazy {
        LibraryScanner(
            this,
            database.libraryDao(),
            coverStore,
            safDirectoryStore,
            // The switch lives in preferences, which outlive the process; the scanner asks for it
            // when a scan starts rather than capturing the value at construction.
            folderCoversEnabled = { preferences.folderCoversEnabled.value },
        )
    }

    val playerEngine: PlayerEngine by lazy { PlayerEngine(this) }

    val playbackController: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            context = this,
            engine = playerEngine,
            diagnostics = diagnosticsStore,
            markTrackUnplayable = database.libraryDao()::markTrackUnplayable,
        )
    }
}
