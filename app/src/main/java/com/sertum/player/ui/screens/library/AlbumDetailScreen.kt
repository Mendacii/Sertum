package com.sertum.player.ui.screens.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.sertum.player.data.covers.CoverResolver
import com.sertum.player.data.db.CoverEntity
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.ui.theme.sertumMutedText
import com.sertum.player.ui.theme.SurfaceBlack
import com.sertum.player.ui.theme.WarmGold
import kotlinx.coroutines.launch

@Composable
fun AlbumDetailScreen(albumKey: String) {
    val context = LocalContext.current
    val app = context.applicationContext as SertumApplication
    val dao = app.database.libraryDao()
    val tracks by dao.tracksForAlbum(albumKey).collectAsState(initial = emptyList())
    val albums by dao.observeAlbums().collectAsState(initial = emptyList())
    val covers by dao.observeCovers().collectAsState(initial = emptyList())
    val album = albums.firstOrNull { it.albumKey == albumKey }
    val hasUserCover = covers.any { it.albumKey == albumKey }
    val firstTrack = tracks.firstOrNull()
    val scope = rememberCoroutineScope()

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val path = app.coverStore.save(albumKey, bytes)
                dao.setAlbumCover(albumKey, path)
                dao.insertCover(CoverEntity(albumKey, path, System.currentTimeMillis()))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .background(SurfaceBlack, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverRef = album?.coverRef?.takeUnless {
                        it == com.sertum.player.data.covers.CoverResolver.PLACEHOLDER_REF
                    }
                    if (coverRef != null) {
                        AsyncImage(
                            model = coverRef,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = (album?.title ?: firstTrack?.title ?: "?").take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        text = album?.title ?: firstTrack?.albumTitle ?: stringResource(R.string.album_default_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = album?.albumArtist ?: firstTrack?.albumArtist ?: stringResource(R.string.unknown_artist),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AlbumActionGrid(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                AlbumAction(
                    label = stringResource(R.string.play_all),
                    icon = Icons.Filled.PlayArrow,
                    enabled = tracks.isNotEmpty(),
                    onClick = {
                        val coverRef = album?.coverRef
                        app.playbackController.playTracks(
                            tracks.map { it.toPlayable().copy(coverRef = coverRef) },
                            startIndex = 0,
                        )
                    },
                )
                AlbumAction(
                    label = stringResource(
                        if (hasUserCover) R.string.replace_cover else R.string.add_cover,
                    ),
                    icon = Icons.Filled.AddPhotoAlternate,
                    onClick = {
                        pickCover.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                if (hasUserCover) {
                    AlbumAction(
                        label = stringResource(R.string.remove_cover),
                        icon = Icons.Filled.HideImage,
                        onClick = {
                            scope.launch {
                                app.coverStore.delete(albumKey)
                                dao.deleteCover(albumKey)
                                val fallback = CoverResolver.resolveAfterUserRemoval(
                                    album?.embeddedCoverPath,
                                    album?.folderCoverPath,
                                ).reference
                                dao.setAlbumCover(albumKey, fallback)
                            }
                        },
                    )
                }
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                onClick = {
                    val coverRef = album?.coverRef
                    app.playbackController.playTracks(
                        tracks.map { it.toPlayable().copy(coverRef = coverRef) },
                        startIndex = index,
                    )
                },
            )
        }
    }
}

/**
 * Sizes for the album's actions, in physical pixels, matching the ones the settings page
 * settled on so the two pages agree: 40px between options, a 38px icon, 12px from icon to
 * label, and a 15sp label.
 *
 * Physical pixels rather than dp because that is how the sizes were specified, and the
 * device is 440dpi, so 1dp is 2.75px.
 */
private const val SCREEN_DPI = 440f

private fun px(value: Int): Dp = (value * 160f / SCREEN_DPI).dp

private val ActionGap = px(40)
private val ActionHeight = px(120)
private val ActionIconSize = px(38)
private val ActionIconLabelGap = px(12)
private const val ACTION_LABEL_SP = 15f

/**
 * Lays the album's actions out in rows of three.
 *
 * A FlowRow rather than a Column of Material buttons, so an action appearing or
 * disappearing - 绉婚櫎灏侀潰 only exists once a cover has been chosen - reflows instead of
 * leaving a gap, and so a fourth action, if one is ever added, wraps rather than
 * overflowing.
 */
@Composable
private fun AlbumActionGrid(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ActionGap),
        verticalArrangement = Arrangement.spacedBy(ActionGap),
        maxItemsInEachRow = 3,
        content = content,
    )
}

/**
 * One album action: an icon beside a label, with no container.
 *
 * Colour carries the state the way it does in the settings list - gold when enabled, the
 * muted tone when not - so 鎾斁鍏ㄩ儴 still reads as unavailable on an empty album without
 * needing the outline a button would draw.
 */
@Composable
private fun AlbumAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .requiredHeight(ActionHeight)
            .clipToBounds()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) WarmGold else sertumMutedText,
            modifier = Modifier.size(ActionIconSize),
        )
        Spacer(Modifier.width(ActionIconLabelGap))
        Text(
            text = label,
            fontSize = ACTION_LABEL_SP.sp,
            color = if (enabled) WarmGold else sertumMutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
