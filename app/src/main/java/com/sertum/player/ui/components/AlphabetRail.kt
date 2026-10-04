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

/** The rail bucket a title belongs to: its first letter, or '#' for anything else. */
fun firstLetterOf(title: String): Char {
    val first = title.trim().firstOrNull() ?: return '#'
    return if (first.isLetter() && first.uppercaseChar() in 'A'..'Z') first.uppercaseChar() else '#'
}

/**
 * True when a title starts with a letter outside A-Z - Chinese, Japanese, Cyrillic and the
 * like.
 *
 * Distinct from `firstLetterOf(title) == '#'`, which is also true for titles starting with
 * a digit or punctuation. Those sort before 'A', and they are what an earlier version of
 * the '#' jump landed on by mistake.
 *
 * A non-letter returns false. Writing this as `!(first.isLetter() && ...)` inverts exactly
 * that case and classifies "2V-ALK" and "'Sick" as non-Latin, which is wrong and was caught
 * by the test below.
 */
fun startsWithNonLatin(title: String): Boolean {
    val first = title.trim().firstOrNull() ?: return false
    if (!first.isLetter()) return false
    return first.uppercaseChar() !in 'A'..'Z'
}

/**
 * Index of the list entry the rail should jump to for [letter], or null when the list has
 * nothing under it.
 *
 * '#' means the special-character and non-Latin run, which is not where '#' sorts. Titles
 * starting with punctuation or a digit sort before 'A' ("'Sick", "12"), while non-Latin
 * titles - Chinese, Japanese, Cyrillic - sort after 'Z' because their code points are
 * higher. A plain first-match lookup therefore sends '#' to the head of the list, which is
 * where the rail already is, and the non-Latin run at the end becomes unreachable without
 * dragging through every letter in between.
 *
 * '#' resolves to the FIRST non-Latin entry, not to the last entry in the list. Those are
 * different places: the non-Latin run is a range, and the rail belongs at the start of it,
 * the same way 'E' goes to the first E. Landing on the final entry would drop the reader
 * past every non-Latin item into the middle of the run.
 *
 * The first entry is found by taking the first '#' bucket that follows a letter bucket, so
 * that punctuation and digits - which are also '#' - are not mistaken for the run.
 *
 * [buckets] is the bucket letter of each entry, in display order.
 */
fun railIndexFor(letter: Char, buckets: List<Char>): Int? {
    if (buckets.isEmpty()) return null
    if (letter != '#') return buckets.indexOf(letter).takeIf { it >= 0 }
    val afterLetters = buckets.withIndex().firstOrNull { (i, bucket) ->
        bucket == '#' && buckets.take(i).any { it in 'A'..'Z' }
    }
    return (afterLetters ?: buckets.withIndex().firstOrNull { (_, bucket) -> bucket == '#' })
        ?.index
}

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
