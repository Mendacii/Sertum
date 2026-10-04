package com.sertum.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sertum.player.ui.theme.WarmGold
import com.sertum.player.ui.theme.sertumUnselectedIcon
import com.sertum.player.ui.theme.sertumUnselectedText

/**
 * Settings option: icon plus label, with no box.
 *
 * This replaces the bordered tile grid. The direction came from the user - drop the
 * frames entirely, keep the icon left of the label, up to three per row, 40px apart,
 * with the type sizes unchanged.
 *
 * With no border or fill left, selection is carried by colour: the selected option's
 * icon and label turn gold. That is the only affordance remaining in a borderless
 * list, and it extends the existing rule that gold marks the active choice rather
 * than decorating everything.
 *
 * Renaming is deliberately deferred: the call sites still say "tile", and the widget
 * is no longer a tile. The names will be settled once this look is confirmed, so a
 * rejected direction does not leave churn behind.
 *
 * ## Units
 *
 * Geometry is in **physical pixels on the 1080x2400 reference screen**, converted by
 * [px]. An earlier revision treated these numbers as dp, which multiplied everything
 * by the density factor (440/160 = 2.75).
 */
private const val SCREEN_DPI = 440f

/** Converts a physical-pixel measurement on the reference screen into dp. */
private fun px(value: Int): Dp = (value * 160f / SCREEN_DPI).dp

/** Gap between options, both along a row and between rows. */
private val OptionGap: Dp = px(40)

/**
 * Row height for the tap target. The text is only about 40px tall, so the row is
 * given a fixed height rather than hugging its content, to keep rows from looking
 * cramped now that no surrounding box supplies vertical padding.
 */
private val OptionHeight: Dp = px(120)

private val IconSize: Dp = px(38)
private val IconLabelGap: Dp = px(12)

/**
 * Unchanged from the tile revision, as requested: the label keeps its own explicit
 * size rather than a theme token, because no token matches it.
 */
private const val LABEL_SP = 15f

enum class TileEmphasis {
    /** Tap runs an action; no selected state. */
    NAVIGABLE,

    /** Selected state shown by colour. */
    TOGGLE,
}

/**
 * Lays options out left to right, wrapping to a new row after [maxPerRow] options or
 * when the next one would not fit. Each option is as wide as its own content, so rows
 * pack tightly instead of aligning to a grid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTileGrid(
    modifier: Modifier = Modifier,
    maxPerRow: Int = 3,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OptionGap),
        verticalArrangement = Arrangement.spacedBy(OptionGap),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxItemsInEachRow = maxPerRow,
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
    // No container at all: just the icon and the label, with colour carrying state.
    Box(
        modifier
            .height(OptionHeight)
            .clipToBounds()
            // No ripple. The colour change is the feedback now, and a rectangular press
            // highlight flashing across a borderless row both duplicated it and drew
            // attention to a shape the row deliberately does not have.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isOn) WarmGold else sertumUnselectedIcon,
                modifier = Modifier.size(IconSize),
            )
            Spacer(Modifier.width(IconLabelGap))
            Text(
                text = label,
                fontSize = LABEL_SP.sp,
                color = if (isOn) WarmGold else sertumUnselectedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
