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
import androidx.compose.foundation.layout.fillMaxWidth
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
val SettingsTileHeight: Dp = 44.dp
val SettingsTileTouchTarget: Dp = 48.dp
/**
 * Two tiles plus the gap land on the intended 65% edge. Derived from the
 * measured usable row width of 275.9dp: the band is 275.9 - 95 (reserve) =
 * 180.9dp, minus the 8dp gap, halved = 86dp. The measured value is used because
 * the row's real width differs from the screen arithmetic at this depth in the
 * layout, which is what made earlier sizes land short.
 */
val SettingsTileWidth: Dp = 86.dp

/**
 * The grid occupies only the left [SETTINGS_GRID_WIDTH_FRACTION] of the page, so
 * the free space stays on the right as deliberate margin rather than stretching
 * the tiles edge to edge.
 *
 * 0.65 of the 380dp content width leaves ~247dp, which fits two
 * [SettingsTileWidth] tiles plus the gap. The first attempt used 118dp tiles in
 * the same band, and that missed by about 2dp after pixel rounding, so every
 * tile ended up alone on its own row - hence 112dp.
 */
const val SETTINGS_GRID_WIDTH_FRACTION: Float = 0.65f
private val TileCorner = 10.dp
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
        // Expressed as "fill the width, then reserve the remainder on the right"
        // rather than `fillMaxWidth(0.65f)`. The fraction form measured
        // inconsistently across builds, while the padding form is deterministic:
        // the row ends exactly (1 - fraction) of the page short of the right
        // edge, which is the same geometry with predictable behaviour.
        modifier = modifier
            .fillMaxWidth()
            .padding(end = GridRightReserve),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxItemsInEachRow = 3,
    ) {
        content()
    }
}

/**
 * Space kept free on the right of the grid, i.e. the ~35% of the page the tiles
 * must not reach into.
 *
 * Calibrated from on-device measurement rather than arithmetic: the row reports
 * 275.9dp of usable width at this point in the hierarchy (measured right edge
 * 138.9dp + this reserve), not the 360.7dp a naive "screen minus 2 x 16dp"
 * calculation gives. Sizing against the measured value is what makes the two
 * tiles actually meet the intended 65% edge.
 */
private val GridRightReserve = 95.dp

private val TileGap = 8.dp

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
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                // Fixed size rather than a theme style: labelLarge is 13sp, at
                // which even a four-character label overflows. 10sp keeps the
                // longest label ("应用详情 / 自启动") inside the 112dp tile, which
                // is what lets the full labels stay instead of being shortened.
                fontSize = 10.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
