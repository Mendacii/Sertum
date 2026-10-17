package com.sertum.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The up and down controls in the queue, drawn rather than imported.
 *
 * Material's `ArrowUpward` and `ArrowDownward` are solid silhouettes: a short shaft with a wide
 * triangular head. At the 24dp the row renders them, that reads as two heavy wedges sitting
 * beside a thin track of text, and the queue's own thumb and seek bar are 2dp lines - the icons
 * were the heaviest marks on the screen (user: "too thick, feels primitive", 2026-10-17).
 *
 * So these are a single stroke instead of a filled shape: a 1.75dp chevron with round caps, no
 * shaft. The weight matches the gold rule under the seek bar and the hairline structure elsewhere,
 * so the pair reads as part of the type rather than as buttons bolted on.
 *
 * The chevron spans 8 of the 24 units across and 5 of them down, which leaves the optical centre
 * near the middle of the icon box where `Icon` and `IconButton` expect it. A wider head would
 * start to read as the filled arrow it replaces.
 */
val ThinChevronUp: ImageVector by lazy { thinChevron(up = true) }
val ThinChevronDown: ImageVector by lazy { thinChevron(up = false) }

/** Icon-grid units, so this scales with the 24dp box rather than with density. */
private const val CHEVRON_STROKE = 1.75f

private fun thinChevron(up: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (up) "ThinChevronUp" else "ThinChevronDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            // Tinted by Icon's colorFilter, so the colour here is never seen.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = CHEVRON_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            if (up) {
                moveTo(8f, 14.5f)
                lineTo(12f, 9.5f)
                lineTo(16f, 14.5f)
            } else {
                moveTo(8f, 9.5f)
                lineTo(12f, 14.5f)
                lineTo(16f, 9.5f)
            }
        }
    }.build()
