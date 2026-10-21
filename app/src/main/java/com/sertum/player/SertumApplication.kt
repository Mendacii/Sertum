package com.sertum.player

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import com.sertum.player.ui.settings.LanguageOption
import java.io.File
import java.util.Locale

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
     * Applies the stored language to this Application's own Resources.
     *
     * A snackbar, the media notification and anything else fetched through the application context
     * do not use the activity's Resources, so a reader who picked English would still get Chinese
     * messages without this. The activity itself gets the same choice from
     * `MainActivity.attachBaseContext`, which runs before the activity's resources resolve.
     *
     * Called once from `MainActivity.onCreate` before the first composition. Not called from the
     * composition, and not called on every locale change of its own: the caller recreates the
     * activity, whose base context is configured again from the same stored value.
     */
    fun applySavedLocale() {
        val option = runCatching { LanguageOption.valueOf(preferences.language.value) }
            .getOrDefault(LanguageOption.SYSTEM)
        // SYSTEM means "put the device's own language back", not "change nothing". These Resources
        // may still carry a forced locale from an earlier call, so returning early here would leave
        // the application context in the language the reader just switched away from.
        val locale = forcedLocaleFor(option) ?: systemLocale()
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        // The same process default the settings option sets when it is tapped, so "what locale is
        // this process in" does not depend on whether the reader ever opened that screen.
        Locale.setDefault(locale)
    }

    /** The device's own language: the target to restore when the reader picks "follow system". */
    private fun systemLocale(): Locale =
        android.content.res.Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()

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

    // The diagnostics store is shared: the debug PCM tap writes its per-second `pcm` lines into the
    // same rolling file as the transport and route logs, so one export contains the whole chain.
    val playerEngine: PlayerEngine by lazy { PlayerEngine(this, diagnosticsStore) }

    val playbackController: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            context = this,
            engine = playerEngine,
            diagnostics = diagnosticsStore,
            markTrackUnplayable = database.libraryDao()::markTrackUnplayable,
        )
    }
}

/**
 * The locale the reader forced for the UI, or null for "follow the system".
 *
 * Single owner of that mapping: the activity's base context, this application's own Resources and
 * the settings option all resolve it here, so the stored choice cannot mean different things in
 * different places.
 */
internal fun forcedLocaleFor(option: LanguageOption): Locale? = when (option) {
    LanguageOption.SYSTEM -> null
    LanguageOption.ZH -> Locale.SIMPLIFIED_CHINESE
    LanguageOption.EN -> Locale.ENGLISH
}
