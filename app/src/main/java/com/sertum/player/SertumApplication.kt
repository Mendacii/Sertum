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
import com.sertum.player.domain.playback.RoomResumePositionStore
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
        preferences.restoreIntoMemory()
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
        libraryScanner.scanNow { result ->
            result.onSuccess {
                diagnosticsStore.log(
                    DiagnosticLevel.INFO,
                    "scan",
                    "mediaStore candidates=${it.candidates} parsed=${it.parsed} " +
                        "failed=${it.failed} albums=${it.albums} artists=${it.artists}",
                )
            }.onFailure {
                diagnosticsStore.log(DiagnosticLevel.ERROR, "scan", "failed: ${it.message}")
            }
            onDone(result)
        }
    }

    val database: SertumDatabase by lazy {
        Room.databaseBuilder(this, SertumDatabase::class.java, "sertum.db")
            .addMigrations(SertumDatabase.MIGRATION_1_2, SertumDatabase.MIGRATION_2_3)
            .build()
    }

    val coverStore: CoverStore by lazy { CoverStore(filesDir) }

    /** Durable user preferences; read once in [onCreate] before the first scan. */
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    val safDirectoryStore: SafDirectoryStore by lazy { SafDirectoryStore(this) }

    val diagnosticsStore: DiagnosticsStore by lazy {
        DiagnosticsStore(File(filesDir, "diagnostics"))
    }

    val libraryScanner: LibraryScanner by lazy {
        LibraryScanner(this, database.libraryDao(), coverStore, safDirectoryStore)
    }

    private val resumeStore: RoomResumePositionStore by lazy {
        RoomResumePositionStore(database.libraryDao())
    }

    val playerEngine: PlayerEngine by lazy { PlayerEngine(this) }

    val playbackController: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            context = this,
            engine = playerEngine,
            resumeStore = resumeStore,
            diagnostics = diagnosticsStore,
            preferences = preferences,
            markTrackUnplayable = database.libraryDao()::markTrackUnplayable,
        )
    }
}
