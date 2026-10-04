package com.sertum.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sertum.player.R
import com.sertum.player.ui.theme.TextPrimary
import com.sertum.player.ui.theme.TextSecondary
import com.sertum.player.ui.theme.WarmGold

/** One option's explanation: its name, and one short sentence about it. */
data class OptionInfo(val nameRes: Int, val descriptionRes: Int)

/**
 * The explanations shown by each section's (i) button, kept together so the content
 * can be reviewed as a whole instead of being scattered across six call sites.
 *
 * Sections with more than one option list every option, because the point of the
 * dialog is to explain the choices, not just the section. A section whose options are
 * self-explanatory still gets a dialog if the options need qualifying - USB exclusive
 * in particular is the one setting users will get wrong without being told it needs a
 * compatible DAC and full media volume.
 */
object SectionInfo {
    val scanning = listOf(
        OptionInfo(R.string.settings_info_scan_on_startup, R.string.settings_info_scan_on_startup_desc),
        OptionInfo(R.string.settings_info_full_scan, R.string.settings_info_full_scan_desc),
        OptionInfo(R.string.settings_info_add_folder, R.string.settings_info_add_folder_desc),
        OptionInfo(R.string.settings_info_rescan, R.string.settings_info_rescan_desc),
    )
    val outputMode = listOf(
        OptionInfo(R.string.settings_info_output_auto, R.string.settings_info_output_auto_desc),
        OptionInfo(R.string.settings_info_output_usb, R.string.settings_info_output_usb_desc),
    )
    val language = listOf(
        OptionInfo(R.string.settings_info_language_system, R.string.settings_info_language_system_desc),
        OptionInfo(R.string.settings_info_language_zh, R.string.settings_info_language_zh_desc),
        OptionInfo(R.string.settings_info_language_en, R.string.settings_info_language_en_desc),
    )
    val theme = listOf(
        OptionInfo(R.string.settings_info_dark_theme, R.string.settings_info_dark_theme_desc),
    )
    val background = listOf(
        OptionInfo(R.string.settings_info_battery, R.string.settings_info_battery_desc),
        OptionInfo(R.string.settings_info_app_details, R.string.settings_info_app_details_desc),
    )
}

/**
 * Section heading with an (i) button that opens the explanations for everything in
 * that section.
 *
 * This exists because the settings page is otherwise silent: scanning, output mode,
 * language and theme carried no explanatory text at all after the tile redesign, and
 * the six per-state subtitles that used to explain the toggles were removed during
 * that redesign. Collapsing the help behind an icon keeps the list clean while making
 * the information reachable, instead of printing a sentence under every option.
 *
 * The icon is a text glyph in a circle rather than a Material icon, so the mark does
 * not depend on the icon set and matches the type around it.
 */
@Composable
fun SectionHeader(
    title: String,
    infoTitle: String,
    options: List<OptionInfo>,
    modifier: Modifier = Modifier,
    /** Launch-only: opens this dialog immediately, for device evidence capture. */
    initiallyOpen: Boolean = false,
) {
    var showInfo by remember { mutableStateOf(initiallyOpen) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (options.isNotEmpty()) {
            // A circled i. Padding widens the tap target without enlarging the mark.
            Text(
                text = "\u24D8",
                color = WarmGold,
                fontSize = 19.sp,
                modifier = Modifier
                    .clickable { showInfo = true }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = {
                Text(
                    text = infoTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = WarmGold,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    options.forEach { option ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = stringResource(option.nameRes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                            )
                            Text(
                                text = stringResource(option.descriptionRes),
                                fontSize = 13.sp,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.settings_info_close), color = WarmGold)
                }
            },
        )
    }
}

/** Shorthand for building the option list at a call site. */
