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
 *
 * ## Spacing
 *
 * The three gaps the eye reads - heading to its first option, one option row to the next, and
 * one option to the next along a row - are not set directly. Each is the sum of a declared gap
 * and however much empty box sits around the glyphs, which is why they measured 101px, 126px
 * and 90px when only one gap constant existed. They are aimed at 60px each.
 *
 *   heading to option = wrapper bottom + grid top, plus the empty half of the option box
 *   row to row        = OptionHeight + OptionGapY, less the glyph height
 *   option to option  = OptionGapX + the empty end of two boxes
 *
 * Row height came down from 120 to 75 to bring the second of those in line. It cannot go much
 * lower: at 75 the box still clears its own 38px icon by 18px on each side, which is what keeps
 * a row of text from reading as a row of buttons.
 */
private const val SCREEN_DPI = 440f

/** Converts a physical-pixel measurement on the reference screen into dp. */
private fun px(value: Int): Dp = (value * 160f / SCREEN_DPI).dp

/**
 * Gap between options, split by axis.
 *
 * It used to be one value for both, which made the two impossible to tune separately: the
 * vertical gap is read as the space between two rows of text, while the horizontal one is read
 * as the space between two items along a row, and those are not the same quantity. Narrowing
 * the single value to close up the rows also narrowed every option box, which *widened* the
 * space between neighbouring labels - 90px to 130px - because the labels stayed the same size
 * while the boxes shrank around them.
 *
 * Split so that each can be set to what it is measured to need. The horizontal value is
 * larger because a box is shorter than it is wide, so less of it is empty at the ends; see the
 * spacing note at the top of the file for how each gap is composed.
 */
private val OptionGapX: Dp = px(40)
private val OptionGapY: Dp = px(20)

private val OptionHeight: Dp = px(75)

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
        horizontalArrangement = Arrangement.spacedBy(OptionGapX),
        verticalArrangement = Arrangement.spacedBy(OptionGapY),
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
