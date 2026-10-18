package com.sertum.player.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.SERTUM_VERSION
import com.sertum.player.sertumBuildFingerprint
import com.sertum.player.ui.components.OptionInfo
import com.sertum.player.ui.components.SectionHeader
import com.sertum.player.ui.components.SectionInfo
import com.sertum.player.ui.components.SettingsTile
import com.sertum.player.ui.components.SettingsTileGrid
import com.sertum.player.ui.components.TileEmphasis
import com.sertum.player.ui.playback.OutputMode
import com.sertum.player.ui.settings.LanguageOption
import com.sertum.player.ui.settings.SettingsStateHolder
import java.util.Locale

@Composable
fun SettingsScreen(initialInfoSection: String? = null) {
    val state by SettingsStateHolder.state.collectAsState()

    val context = LocalContext.current
    val app = context.applicationContext as SertumApplication
    // The coordinator is the authority on the output route; the tile follows it so an automatic
    // switch shows up here too.
    val outputMode by app.playbackController.outputMode.collectAsState()
    val scanProgress by app.libraryScanner.progress.collectAsState()
    var safDirs by remember { mutableStateOf(app.safDirectoryStore.load()) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            app.safDirectoryStore.add(uri)
            safDirs = app.safDirectoryStore.load()
            app.requestLibraryScan()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(
                        app.diagnosticsStore.exportText(sertumBuildFingerprint())
                            .toByteArray(Charsets.UTF_8),
                    )
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_scanning), SectionInfo.scanning, initiallyOpen = initialInfoSection == "scan")
        // Durable in AppPreferences, not SettingsStateHolder: Application.onCreate
        // reads it before any UI exists.
        val scanOnStartup by app.preferences.scanOnStartup.collectAsState()
        SettingsTileGrid {
            SettingsTile(
                label = stringResource(R.string.settings_scan_on_startup_short),
                icon = Icons.Filled.Refresh,
                emphasis = TileEmphasis.TOGGLE,
                selected = scanOnStartup,
                onClick = { app.preferences.setScanOnStartup(!scanOnStartup) },
            )
            // Folder artwork, not scanning scope. Turning this on sends the reader to the all-files
            // page, because that grant is what allows a directory listing beside the tracks; the
            // media index is enumerated on every scan regardless of it.
            val folderCovers by app.preferences.folderCoversEnabled.collectAsState()
            SettingsTile(
                label = stringResource(R.string.settings_folder_covers_short),
                icon = Icons.Filled.FolderOpen,
                emphasis = TileEmphasis.TOGGLE,
                selected = folderCovers,
                onClick = {
                    val enabling = !folderCovers
                    val isManager = android.os.Build.VERSION.SDK_INT >= 30 &&
                        android.os.Environment.isExternalStorageManager()
                    if (enabling && !isManager) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ),
                            )
                        }
                    }
                    app.preferences.setFolderCoversEnabled(enabling)
                },
            )
            SettingsTile(
                label = stringResource(R.string.settings_add_folder_short),
                icon = Icons.Filled.CreateNewFolder,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = { treeLauncher.launch(null) },
            )
            SettingsTile(
                label = stringResource(R.string.settings_rescan_short),
                icon = Icons.Filled.Refresh,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = { app.requestLibraryScan() },
            )
            SettingsTile(
                label = stringResource(R.string.settings_incremental_scan_short),
                icon = Icons.Filled.Add,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = { app.requestIncrementalScan() },
            )
        }
        safDirs.forEach { uri ->
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment ?: uri.toString()
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        app.safDirectoryStore.remove(uri)
                        safDirs = app.safDirectoryStore.load()
                        app.requestLibraryScan()
                    },
                ) {
                    Text(stringResource(R.string.settings_remove_folder))
                }
            }
        }
        if (scanProgress.phase == "collecting" || scanProgress.phase == "parsing") {
            Text(
                text = if (scanProgress.phase == "collecting") {
                    // The count only exists once the walk has found something; before the first
                    // report there is nothing to say beyond "scanning".
                    if (scanProgress.candidates > 0) {
                        stringResource(R.string.settings_scan_collecting_count, scanProgress.candidates)
                    } else {
                        stringResource(R.string.settings_scan_collecting)
                    }
                } else {
                    stringResource(
                        R.string.settings_scan_progress,
                        scanProgress.candidates,
                        scanProgress.parsed,
                        scanProgress.failed,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SectionTitle(stringResource(R.string.settings_output_mode), SectionInfo.outputMode, initiallyOpen = initialInfoSection == "output")
        SettingsTileGrid {
            SettingsTile(
                label = stringResource(R.string.settings_output_auto_short),
                icon = Icons.Filled.Speaker,
                emphasis = TileEmphasis.TOGGLE,
                // Read from the coordinator, which is the authority on routing. A copy of the mode
                // kept in settings state is how the tile once showed a route the audio never took,
                // and how it missed the route the audio had taken automatically.
                selected = outputMode == OutputMode.STANDARD,
                onClick = { app.playbackController.switchOutputMode(OutputMode.STANDARD) },
            )
            SettingsTile(
                label = stringResource(R.string.settings_output_usb_exclusive),
                icon = Icons.Filled.Usb,
                emphasis = TileEmphasis.TOGGLE,
                selected = outputMode == OutputMode.USB_EXCLUSIVE,
                onClick = { app.playbackController.switchOutputMode(OutputMode.USB_EXCLUSIVE) },
            )
            SettingsTile(
                label = stringResource(R.string.settings_auto_exclusive),
                icon = Icons.Filled.Bolt,
                emphasis = TileEmphasis.TOGGLE,
                selected = state.autoExclusiveOnUsbAttach,
                onClick = {
                    val next = !state.autoExclusiveOnUsbAttach
                    SettingsStateHolder.update { it.copy(autoExclusiveOnUsbAttach = next) }
                    app.preferences.setAutoExclusiveOnUsbAttach(next)
                },
            )
        }

        SectionTitle(stringResource(R.string.settings_language), SectionInfo.language, initiallyOpen = initialInfoSection == "language")
        SettingsTileGrid {
            listOf(
                LanguageOption.SYSTEM to R.string.settings_language_system,
                LanguageOption.ZH to R.string.settings_language_zh,
                LanguageOption.EN to R.string.settings_language_en,
            ).forEach { (option, labelRes) ->
                SettingsTile(
                    label = stringResource(labelRes),
                    icon = Icons.Filled.Translate,
                    emphasis = TileEmphasis.TOGGLE,
                    selected = state.language == option,
                    onClick = {
                        SettingsStateHolder.update { it.copy(language = option) }
                        // Durable, or the language reverts on the next cold start.
                        app.preferences.setLanguage(option.name)
                        applyLocale(context, option)
                    },
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_theme), SectionInfo.theme, initiallyOpen = initialInfoSection == "theme")
        SettingsTileGrid {
            // Two options rather than one toggle: a switch can only say on or off, while
            // the page is really choosing between two named themes, and naming the light
            // one is what makes that visible. Each option sets its own value rather than
            // flipping, so tapping the already-active one is a no-op instead of a surprise.
            SettingsTile(
                label = stringResource(R.string.settings_dark_theme),
                icon = Icons.Filled.DarkMode,
                emphasis = TileEmphasis.TOGGLE,
                selected = state.darkTheme,
                onClick = {
                        SettingsStateHolder.update { it.copy(darkTheme = true) }
                        app.preferences.setDarkTheme(true)
                    },
            )
            SettingsTile(
                label = stringResource(R.string.settings_light_theme),
                icon = Icons.Filled.LightMode,
                emphasis = TileEmphasis.TOGGLE,
                selected = !state.darkTheme,
                onClick = {
                        SettingsStateHolder.update { it.copy(darkTheme = false) }
                        app.preferences.setDarkTheme(false)
                    },
            )
        }

        SectionTitle(
            stringResource(R.string.settings_background_playback),
            SectionInfo.background,
            initiallyOpen = initialInfoSection == "background",
            // The paragraph that used to sit under this heading now lives in the (i) dialog
            // with the rest of the help. It was the last survivor of the per-option
            // subtitles dropped in the tile redesign, and it made this one section taller
            // than every other for no reason a reader could see.
            summary = stringResource(R.string.settings_info_background_summary),
        )
        // No top padding, unlike the about section further down. The 10dp here used to separate
        // this grid from the paragraph that sat under the heading; when that paragraph moved
        // into the (i) dialog the padding stayed behind with nothing to separate, and made this
        // one section's heading-to-options gap ten dp wider than every other section's.
        SettingsTileGrid {
            SettingsTile(
                label = stringResource(R.string.settings_battery_optimization),
                icon = Icons.Filled.BatterySaver,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = {
                    openBatteryProtection(context)
                },
            )
            SettingsTile(
                label = stringResource(R.string.settings_app_details_autostart),
                icon = Icons.Filled.Settings,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = {
                    // HyperOS autostart lives on the app's own info page, which is
                    // what this opens.
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                },
            )
        }

        SectionTitle(stringResource(R.string.settings_about), bottomGap = AboutHeadingGap)
        // Just the version. The build fingerprint (commit + timestamp) used to be
        // appended here for M6 device verification, which made the version read as
        // a string of noise; it now travels in the diagnostics export instead.
        Text(
            text = "${stringResource(R.string.app_name)} $SERTUM_VERSION",
            style = MaterialTheme.typography.bodyLarge,
        )
        // The two lines below the version carry the same weight and read as one block, so they
        // are given the same gap. They did not have one: the version is bodyLarge and they are
        // bodyMedium, so their line boxes differ, and only the diagnostics line carried a 4dp
        // offset. The measured gaps were 29 / 15 / 25px - two of the three differed by ten.
        Text(
            text = stringResource(R.string.settings_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AboutLineGap),
        )
        val diagnostics = app.diagnosticsStore.counts
        Text(
            text = stringResource(
                R.string.settings_diagnostics_summary,
                diagnostics.totalEntries,
                diagnostics.totalErrors,
                diagnostics.fileCount,
                7,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AboutLineGap),
        )
        SettingsTileGrid(Modifier.padding(top = 10.dp)) {
            SettingsTile(
                label = stringResource(R.string.settings_export_diagnostics),
                icon = Icons.Filled.FileDownload,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = { exportLauncher.launch("sertum-diagnostics.txt") },
            )
        }
    }
}

private fun applyLocale(context: Context, option: LanguageOption) {
    val locale = when (option) {
        LanguageOption.SYSTEM -> ResourcesCompatSystemLocale
        LanguageOption.ZH -> Locale.SIMPLIFIED_CHINESE
        LanguageOption.EN -> Locale.ENGLISH
    }
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
    (context as? android.app.Activity)?.recreate()
}

private val ResourcesCompatSystemLocale: Locale
    get() = android.content.res.Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()

/**
 * How far the About heading sits above the version line, instead of the section default.
 *
 * This is the only section whose heading is followed by plain text rather than a tile grid, so
 * the distance can be set here without touching the other five. It is smaller than the default
 * because the three lines under this heading read as one block, and the request was that all
 * three gaps in it be 25px.
 *
 * Set through the heading rather than as an offset on the version line, because
 * `Modifier.padding` rejects negative values: a negative top there throws
 * `IllegalArgumentException: Padding must be non-negative` the moment the settings screen is
 * composed, which took the tab down with it. Reducing the space above is the same 4 physical
 * pixels of the reference screen, and legal.
 */
private val AboutHeadingGap = 2.55.dp

/**
 * Gap above each of the two lines under the version in the About section.
 *
 * One value for both, because they are one block: same style, same colour, same weight of
 * information. They used to differ by ten pixels - 15px then 25px measured - because the version
 * above them is bodyLarge while they are bodyMedium, so their line boxes are not the same height,
 * and only the second had an offset.
 *
 * 3.8dp is 10.45 physical pixels on the reference screen. The gap the eye reads is larger than
 * this, because the line box leaves its own empty space below the glyphs; the measured result is
 * 25px, which is what this number is set to produce rather than what it looks like it should be.
 */
private val AboutLineGap = 3.8.dp

@Composable
private fun SectionTitle(
    text: String,
    options: List<OptionInfo> = emptyList(),
    initiallyOpen: Boolean = false,
    summary: String? = null,
    bottomGap: Dp = 4.dp,
) {
    // The section heading and, where there is anything worth explaining, the (i)
    // button that opens those explanations. Kept as a wrapper so the screen reads the
    // same at every call site.
    // Spacing is set here rather than at each call site, so the heading sits the same distance
    // from its options in every section. 14.5dp is 40 physical pixels on the 1080x2400 / 440dpi
    // reference screen, which is the unit the sizes in SettingsTile.kt are written in; the gap
    // that reaches the eye also includes the empty top of the option box, so it is not this
    // number. See the spacing note in SettingsTile.kt.
    //
    // bottomGap is overridable for the one section whose heading is followed by text instead of
    // a grid, where the distance is read as line spacing rather than as room above a control.
    Column(Modifier.padding(top = 14.5.dp, bottom = bottomGap)) {
        SectionHeader(
            title = text,
            infoTitle = text,
            options = options,
            summary = summary,
            initiallyOpen = initiallyOpen,
        )
    }
}


/**
 * Opens the screen where this app can be exempted from the platform's background policy.
 *
 * The generic battery-optimisation list is a poor destination on MIUI: the freeze that stops
 * playback is its own "省电策略" setting, reachable per app, and the AOSP whitelist does not affect
 * it (measured: whitelisting still froze the process for nine seconds).
 */
private fun openBatteryProtection(context: android.content.Context) {
    val packageName = context.packageName
    val label = runCatching {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    }.getOrDefault(packageName)
    val miui = Intent().apply {
        component = android.content.ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        )
        putExtra("package_name", packageName)
        putExtra("package_label", label)
    }
    if (runCatching { context.startActivity(miui) }.isSuccess) return
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = android.net.Uri.parse("package:" + packageName)
    }
    if (runCatching { context.startActivity(request) }.isSuccess) return
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + packageName),
        ),
    )
}
