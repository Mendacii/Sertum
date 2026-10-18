package com.sertum.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sertum.player.R

/**
 * The way back from a detail page.
 *
 * Shared rather than written twice, because the two copies drifted the first time: the album page
 * put an `IconButton` in a row padded 4dp, which lands the glyph at 16dp and lines it up with the
 * cover below; the artist page put the same button inside its grid, which inherits the grid's own
 * 8dp padding, and the arrow ended up 22px further right than the artist's name.
 *
 * The glyph is aligned to the leading edge of its 48dp touch target, so the caller positions it
 * with the padding that matches its own page's content edge - 16dp on the album page (the cover's
 * edge) and 8dp on the artist page (the grid's edge). A minimum 48dp target is kept even though
 * the icon is 24dp, because a bare icon is too small to hit reliably.
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
            modifier = Modifier.size(24.dp),
        )
    }
}
