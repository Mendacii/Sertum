package com.sertum.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sertum.player.R
import com.sertum.player.SertumApplication
import com.sertum.player.ui.navigation.MainTabs
import com.sertum.player.ui.navigation.SertumDestinations
import com.sertum.player.ui.screens.library.AlbumDetailScreen
import com.sertum.player.ui.screens.library.ArtistDetailScreen
import com.sertum.player.ui.screens.nowplaying.MiniPlayer
import com.sertum.player.ui.screens.nowplaying.NowPlayingHost
import com.sertum.player.ui.screens.nowplaying.PlayerSheet
import com.sertum.player.ui.theme.SertumTheme

private data class TopLevelDestination(
    val labelRes: Int,
    val icon: @Composable () -> Unit,
)

private val topLevel = listOf(
    TopLevelDestination(R.string.nav_songs, { Icon(Icons.Filled.LibraryMusic, contentDescription = stringResource(R.string.nav_songs)) }),
    TopLevelDestination(R.string.nav_albums, { Icon(Icons.Filled.Album, contentDescription = stringResource(R.string.nav_albums)) }),
    TopLevelDestination(R.string.nav_artists, { Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.nav_artists)) }),
    TopLevelDestination(R.string.nav_settings, { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings)) }),
)

/**
 * Shared timing for the bottom chrome and the content inset it reserves, so
 * the two stop moving on the same frame instead of one outrunning the other.
 */
private const val BOTTOM_CHROME_ANIM_MS = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SertumApp(
    initialTab: Int? = null,
    initialInfoSection: String? = null,
    /** Launch-only theme override for device evidence. Null keeps the stored setting. */
    initialDarkTheme: Boolean? = null,
    /** Launch-only: opens one album's detail page. Null on normal launches. */
    initialAlbumKey: String? = null,
    /**
     * Launch-only: opens the full player, and starts the first track when there is nothing
     * loaded so it has artwork to show.
     *
     * The player is reached by tapping the mini player, and taps cannot be injected on the
     * reference device, so without this the page - and anything animated inside it - could
     * not be inspected at all.
     */
    openPlayer: Boolean = false,
    /**
     * Launch-only: opens the queue layer. Meaningful together with [openPlayer], since the queue
     * slides over the player.
     *
     * The queue is one tap deeper than the player, behind a button in the player sheet, and taps
     * cannot be injected on the reference device - so its row controls could not be inspected
     * without this.
     */
    openQueue: Boolean = false,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab ?: 0) }
    var showNowPlaying by rememberSaveable { mutableStateOf(openPlayer) }
    var showQueue by rememberSaveable { mutableStateOf(openQueue) }
    val settings by com.sertum.player.ui.settings.SettingsStateHolder.state.collectAsState()
    val context = LocalContext.current
    // The bars are made transparent by MainActivity on every window it builds; what changes while
    // this composition is alive is which way their icons have to point. Switching the theme does not
    // recreate the activity, so nothing else would update them - and white icons on the light theme's
    // paper background are simply invisible.
    val view = LocalView.current
    // The window background is what shows in the strip the composition does not reach (the status
    // bar area after a recreation, for one). Set once at activity creation it would keep the theme
    // the process started in, so switching themes has to update it as well.
    val windowBackground = if (settings.darkTheme) {
        com.sertum.player.ui.theme.PureBlack
    } else {
        com.sertum.player.ui.theme.PaperWhite
    }
    LaunchedEffect(windowBackground) {
        context.findActivity()?.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(windowBackground.toArgb()),
        )
    }
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !settings.darkTheme
            isAppearanceLightNavigationBars = !settings.darkTheme
        }
    }
    val controller = (context.applicationContext as SertumApplication).playbackController
    val snackbarHostState = remember { SnackbarHostState() }
    val userMessage by controller.userMessage.collectAsState()

    LaunchedEffect(userMessage) {
        val message = userMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            controller.consumeUserMessage()
        }
    }

    val layoutDirection = LocalLayoutDirection.current

    // Album deep link for device evidence: the album page is reached by tapping a cover,
    // and taps cannot be injected on the reference device. Runs once, and only on a
    // genuine first launch, so it cannot drag the pager back after a recreation.
    val albumOpened = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialAlbumKey) {
        val key = initialAlbumKey
        if (key != null && !albumOpened.value) {
            albumOpened.value = true
            navController.navigate(SertumDestinations.albumDetail(key))
        }
    }

    // initialDarkTheme is a launch-only override used for device evidence: the theme
    // lives in in-memory state, so without it the light theme could only be inspected
    // by tapping the theme option, which injected input cannot do.
    SertumTheme(darkTheme = initialDarkTheme ?: settings.darkTheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                // The mini player stays on every browsing surface; the full
                // player is a full-screen bottom sheet of its own. When the
                // sheet slides away the bottom chrome slides back up quickly.
                AnimatedVisibility(
                    visible = !showNowPlaying,
                    enter = slideInVertically { it } + fadeIn(tween(BOTTOM_CHROME_ANIM_MS)),
                    exit = slideOutVertically { it } + fadeOut(tween(BOTTOM_CHROME_ANIM_MS)),
                ) {
                    Column {
                        MiniPlayer(onExpand = { showNowPlaying = true })
                        // No ripple on the tabs.
                        //
                        // Clearing `indicatorColor` removed the resting capsule but not the one
                        // that blooms under a finger: NavigationBarItem calls `ripple()` itself
                        // rather than reading LocalIndication, so overriding the indication has no
                        // effect on it. `LocalRippleConfiguration provides null` is the switch
                        // Material provides for exactly this, and it is scoped to the bar so the
                        // rest of the app keeps its press feedback.
                        CompositionLocalProvider(LocalRippleConfiguration provides null) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                topLevel.forEachIndexed { index, dest ->
                                    NavigationBarItem(
                                        selected = selectedTab == index,
                                        onClick = {
                                            selectedTab = index
                                            if (currentRoute != SertumDestinations.MAIN) {
                                                navController.navigate(SertumDestinations.MAIN) {
                                                    popUpTo(SertumDestinations.MAIN) { inclusive = false }
                                                    launchSingleTop = true
                                                }
                                            }
                                        },
                                        icon = dest.icon,
                                        label = { Text(stringResource(dest.labelRes)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = com.sertum.player.ui.theme.WarmGold,
                                            selectedTextColor = com.sertum.player.ui.theme.WarmGold,
                                            // No pill behind the selected tab either. The gold icon
                                            // and label are the only indication of where you are.
                                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            // Reserve the bottom chrome's space permanently.
            //
            // The first attempt animated this inset and dropped it to zero
            // while the full player was open, which made the content area
            // taller and stretched the A-Z rail (measured on device: letter
            // pitch 62px -> 77px, rail bottom 1985 -> 2353, i.e. past the
            // bottom bar). The rail is sized by whatever height it is given,
            // so no amount of easing fixes that - the height must not change
            // at all. The bar fades and slides inside space that is always
            // reserved, and the rail therefore never re-lays-out. That also
            // matches what was asked for: keep it fixed at the size it has
            // when the bar is in its normal position.
            //
            // `coerceAtLeast` keeps the reserve even if a frame reports a
            // collapsed bar, and the non-zero guard avoids a one-frame flash
            // of the un-reserved height during the first composition.
            var reservedBottomInset by remember { mutableStateOf(0.dp) }
            val reportedBottomInset = padding.calculateBottomPadding()
            if (reportedBottomInset > 0.dp) reservedBottomInset = reportedBottomInset
            NavHost(
                navController = navController,
                startDestination = SertumDestinations.MAIN,
                modifier = Modifier.padding(
                    PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = reservedBottomInset,
                    ),
                ),
                // A fade in both directions, and no slide.
                //
                // Predictive back was enabled here for one build (the manifest flag is now
                // removed, deliberately). It made the platform slide the page away with the
                // finger and slide it back if the gesture was released without completing,
                // which reads as the page vanishing mid-swipe rather than as a screen
                // transition. The request was for the return to feel smooth, not for a
                // live preview, so the back gesture resolves into a transition on release
                // and nothing moves while the finger is down.
                //
                // The durations are shorter on the way in because the incoming page is
                // ready, and longer on the way back because the list underneath is being
                // rebuilt at that moment.
                enterTransition = { fadeIn(tween(140)) },
                exitTransition = { fadeOut(tween(90)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(180)) },
            ) {
                composable(SertumDestinations.MAIN) {
                    MainTabs(
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it },
                        onAlbumClick = { key -> navController.navigate(SertumDestinations.albumDetail(key)) },
                        onArtistClick = { name -> navController.navigate(SertumDestinations.artistDetail(name)) },
                        initialInfoSection = initialInfoSection,

                    )
                }
                composable(
                    SertumDestinations.ALBUM_DETAIL,
                    arguments = listOf(navArgument("albumKey") { type = NavType.StringType }),
                ) { entry ->
                    AlbumDetailScreen(
                        albumKey = entry.arguments?.getString("albumKey")?.let(android.net.Uri::decode).orEmpty(),
                        // popBackStack rather than navigate: both detail pages are pushed on top of
                        // the tab they were opened from, so going back must return to that tab and
                        // not add another entry.
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    SertumDestinations.ARTIST_DETAIL,
                    arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
                ) { entry ->
                    ArtistDetailScreen(
                        artistName = entry.arguments?.getString("artistName")?.let(android.net.Uri::decode).orEmpty(),
                        onAlbumClick = { key -> navController.navigate(SertumDestinations.albumDetail(key)) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        if (showNowPlaying) {
            // Presented by our own PlayerSheet instead of Material's. The sheet's dismissal could
            // not be given a duration - it settles a drag with a velocity spring, measured at
            // 1048ms against 502ms for a back gesture - and since the bottom chrome sits behind it,
            // that difference was the difference in when the bar came back. Owning the transition
            // makes every exit the same fixed slide. See PlayerSheet for the measurements.
            PlayerSheet(
                showQueue = showQueue,
                onDismissRequest = { showNowPlaying = false },
            ) {
                NowPlayingHost(
                    showQueue = showQueue,
                    onShowQueueChange = { showQueue = it },
                )
            }
        }
    }
}

/** The Activity a view is attached to, through whatever context wrappers sit in between. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
