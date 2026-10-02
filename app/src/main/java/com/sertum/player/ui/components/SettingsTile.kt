package com.sertum.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sertum.player.ui.theme.HairlineDark
import com.sertum.player.ui.theme.PureBlack
import com.sertum.player.ui.theme.SurfaceBlack
import com.sertum.player.ui.theme.SurfaceRaised
import com.sertum.player.ui.theme.TextPrimary
import com.sertum.player.ui.theme.TextSecondary
import com.sertum.player.ui.theme.WarmGold

/**
 * Settings tile language (user-preference rounds 2026-10-02).
 *
 * The settings page used to be full-width rows and Material switches. It is now
 * a grid of small uniform tiles:
 *
 * - **selected** is carried by a gold border alone - no switch, no check mark.
 * - **not selected** keeps a grey surface and hairline border, and the label
 *   always stays at full brightness. The grey never touches the type.
 * - every tile is the same width, so the grid stays aligned even though the
 *   labels differ in length.
 *
 * The visible box is 28dp, which is below the 48dp accessibility touch minimum,
 * so each tile is wrapped in a fixed-height touch target. The visual stays
 * small and the tap area meets the guideline.
 */
val SettingsTileHeight: Dp = 28.dp
val SettingsTileTouchTarget: Dp = 48.dp
val SettingsTileWidth: Dp = 90.dp
private val TileCorner = 8.dp
private val TileBorder = 1.dp

enum class TileEmphasis {
    /** Tap runs an action; no selected state. */
    NAVIGABLE,

    /** Selected state shown by the gold border. */
    TOGGLE,
}

/**
 * [SettingsTileGrid] wraps tiles onto as many lines as needed while keeping one
 * uniform width, which is what makes the grid read as a grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTileGrid(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
fun SettingsTile(
    label: String,
    icon: ImageVector,
    emphasis: TileEmphasis,
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isOn = emphasis == TileEmphasis.TOGGLE && selected
    Box(
        modifier
            .width(SettingsTileWidth)
            .height(SettingsTileTouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .height(SettingsTileHeight)
                .clip(RoundedCornerShape(TileCorner))
                .background(if (emphasis == TileEmphasis.NAVIGABLE) SurfaceRaised else SurfaceBlack)
                .border(
                    width = TileBorder,
                    color = if (isOn) WarmGold else HairlineDark,
                    shape = RoundedCornerShape(TileCorner),
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isOn) WarmGold else TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                // Fixed rather than a theme style: labelLarge is 13sp, and at
                // that size a four-character label does not fit the uniform tile
                // width. 11sp fits every label except "应用详情 / 自启动", which is
                // split into two tiles instead of widening the grid.
                fontSize = 11.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
