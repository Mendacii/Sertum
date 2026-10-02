package com.sertum.player.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
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
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.sertumBuildLabel
import com.sertum.player.ui.components.SettingsTile
import com.sertum.player.ui.components.SettingsTileGrid
import com.sertum.player.ui.components.TileEmphasis
import com.sertum.player.ui.playback.OutputMode
import com.sertum.player.ui.settings.LanguageOption
import com.sertum.player.ui.settings.SettingsStateHolder
import java.util.Locale

@Composable
fun SettingsScreen() {
    val state by SettingsStateHolder.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as SertumApplication
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
                    out.write(app.diagnosticsStore.exportText().toByteArray(Charsets.UTF_8))
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
        SectionTitle(stringResource(R.string.settings_scanning))
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
            SettingsTile(
                label = stringResource(R.string.settings_full_scan_short),
                icon = Icons.Filled.FolderOpen,
                emphasis = TileEmphasis.TOGGLE,
                selected = state.fullScanEnabled,
                onClick = {
                    val enabling = !state.fullScanEnabled
                    val isManager = android.os.Build.VERSION.SDK_INT >= 30 &&
                        Environment.isExternalStorageManager()
                    if (enabling && !isManager) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + context.packageName),
                            ),
                        )
                    }
                    SettingsStateHolder.update { it.copy(fullScanEnabled = enabling) }
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
                    stringResource(R.string.settings_scan_collecting)
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

        SectionTitle(stringResource(R.string.settings_output_mode))
        SettingsTileGrid {
            SettingsTile(
                label = stringResource(R.string.settings_output_auto_short),
                icon = Icons.Filled.Speaker,
                emphasis = TileEmphasis.TOGGLE,
                selected = state.outputMode == OutputMode.STANDARD,
                onClick = {
                    SettingsStateHolder.update { it.copy(outputMode = OutputMode.STANDARD) }
                    app.playbackController.switchOutputMode(OutputMode.STANDARD)
                },
            )
            SettingsTile(
                label = stringResource(R.string.settings_output_usb_exclusive),
                icon = Icons.Filled.Usb,
                emphasis = TileEmphasis.TOGGLE,
                selected = state.outputMode == OutputMode.USB_EXCLUSIVE,
                onClick = {
                    SettingsStateHolder.update { it.copy(outputMode = OutputMode.USB_EXCLUSIVE) }
                    app.playbackController.switchOutputMode(OutputMode.USB_EXCLUSIVE)
                },
            )
        }

        SectionTitle(stringResource(R.string.settings_language))
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
                        applyLocale(context, option)
                    },
                )
            }
        }

        SectionTitle(stringResource(R.string.settings_theme))
        SettingsTileGrid {
            SettingsTile(
                label = stringResource(R.string.settings_dark_theme),
                icon = Icons.Filled.DarkMode,
                emphasis = TileEmphasis.TOGGLE,
                selected = state.darkTheme,
                onClick = { SettingsStateHolder.update { it.copy(darkTheme = !state.darkTheme) } },
            )
        }

        SectionTitle(stringResource(R.string.settings_background_playback))
        Text(
            text = stringResource(R.string.settings_background_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsTileGrid(Modifier.padding(top = 10.dp)) {
            SettingsTile(
                label = stringResource(R.string.settings_battery_optimization),
                icon = Icons.Filled.BatterySaver,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
            )
            SettingsTile(
                label = stringResource(R.string.settings_app_details_short),
                icon = Icons.Filled.Settings,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                },
            )
            SettingsTile(
                label = stringResource(R.string.settings_autostart_short),
                icon = Icons.Filled.Settings,
                emphasis = TileEmphasis.NAVIGABLE,
                onClick = {
                    // Same destination as app details: HyperOS autostart lives on
                    // the app's own info page, which is what the old combined
                    // button opened.
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ),
                    )
                },
            )
        }

        SectionTitle(stringResource(R.string.settings_about))
        Text("Sertum ${sertumBuildLabel()}", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.settings_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.padding(top = 4.dp),
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}
