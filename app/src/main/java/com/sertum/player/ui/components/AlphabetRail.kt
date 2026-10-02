package com.sertum.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sertum.player.ui.theme.WarmGold
import com.sertum.player.ui.theme.WarmGoldDim

val ALPHABET_RAIL_LETTERS: List<Char> = ('A'..'Z').toList() + '#'

/**
 * Vertical A-Z rail for LazyColumn/LazyVerticalGrid pages. Press or drag
 * along the rail to jump to the first item of that letter (the host screen
 * owns the actual scroll state and item index map).
 */
@Composable
fun BoxScope.AlphabetRail(
    selected: Char?,
    onSelect: (Char) -> Unit,
    modifier: Modifier = Modifier,
    letters: List<Char> = ALPHABET_RAIL_LETTERS,
) {
    var active by remember { mutableStateOf(false) }

    Column(
        modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(22.dp)
            // The rail is deliberately sized by its parent so it tracks the
            // list area; clipping keeps a transient measure (a bottom sheet
            // opening, a bar animating back) from painting letters outside
            // the rail while the parent height is still settling.
            .clipToBounds()
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    active = true
                    var current = letterAt(down.position.y, size.height, letters)
                    var lastY = down.position.y
                    onSelect(current)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position.y != lastY) {
                            lastY = change.position.y
                            val next = letterAt(change.position.y, size.height, letters)
                            if (next != current) {
                                current = next
                                onSelect(next)
                            }
                        }
                        change.consume()
                    }
                    active = false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { letter ->
            Text(
                text = letter.toString(),
                color = if (letter == selected) WarmGold else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                fontWeight = if (letter == selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }

    if (active && selected != null) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(64.dp)
                .clip(CircleShape)
                .background(WarmGoldDim),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = selected.toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private fun letterAt(y: Float, height: Int, letters: List<Char>): Char {
    if (height <= 0) return letters.first()
    val ratio = (y / height).coerceIn(0f, 1f)
    val index = (ratio * letters.size).toInt().coerceIn(0, letters.lastIndex)
    return letters[index]
}
