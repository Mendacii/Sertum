package com.sertum.player.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sertum.player.ui.theme.WarmGold
import com.sertum.player.ui.theme.sertumBodyText
import com.sertum.player.ui.theme.sertumUnselectedIcon
import com.sertum.player.ui.theme.sertumUnselectedText

/**
 * The search bar and the space under it, as one unit, for the three library tabs.
 *
 * ## Why this owns more than a text field
 *
 * Leaving the search state used to have no defined path. Tapping the list did nothing, so the
 * field kept focus; the first back gesture dismissed the keyboard but left the field focused and
 * therefore still lit; the second left the app. Reopening it showed the same lit field, because
 * focus was never given up - only the keyboard was.
 *
 * So leaving is handled here, once, for every tab:
 *
 *  - tapping anywhere below the bar (option 1): keyboard down, focus cleared, bar unlit;
 *  - the back gesture (option 2), in two steps: with the keyboard showing it goes down and
 *    nothing else changes; with the keyboard down it clears focus. A third press belongs to
 *    the app again, because the handler is disabled once neither is true.
 *
 * Both are needed: one covers a tap, the other a gesture, and a user trying either should not
 * have to discover which one this app honours.
 *
 * ## What "no longer lit" means
 *
 * The bar has no border and, at rest, no fill - the same choice as the settings tiles, where
 * colour carries state and nothing draws a box. Focus is what lights it: a low-alpha wash of the
 * theme's text colour behind the row and a gold magnifier. Both animate back to rest over 180ms,
 * which is the dimming on exit. The wash is derived from `onSurface` rather than a new colour
 * token so it follows the in-app theme without adding an owner for one component to read.
 *
 * Typed text is never cleared on exit. Leaving search is a way out of the keyboard, not a
 * discard, and the query stays visible so a filtered list still explains itself.
 */
@Composable
fun SearchScaffold(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val imeVisible = rememberImeVisible()

    // True while the bar owns the interaction, which is exactly when the content below it should
    // take a tap as "leave search" rather than as a tap on a list row.
    val searching = focused || imeVisible

    fun leaveSearch() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    // Disabled once neither is true, so back belongs to the app again - the third press in the
    // sequence above, and any press when search was never entered.
    BackHandler(enabled = searching) {
        if (imeVisible) keyboard?.hide() else leaveSearch()
    }

    Column(modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = placeholder,
            interactionSource = interactionSource,
            active = focused,
            onSubmit = { keyboard?.hide() },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // Leaving search on a blank tap, decided by whether anything else took the
                // gesture. An observer on this box, read in the Final pass - after children have
                // had their turn - so consumption is already settled: if a row or the list
                // consumed the press or the release, the tap belonged to it and search stays; if
                // nothing did, it landed on blank space and search ends.
                //
                // Two earlier attempts failed, both verified on device rather than reasoned away:
                //
                //  - a tap catcher stacked *above* the content took every tap, so playing a song
                //    mid-search needed two taps;
                //  - moving the catcher *below* the content fixed that and broke tapping blank
                //    space entirely, because the LazyColumn above it consumes the press for its
                //    own scrolling and hit testing never reaches the layer underneath.
                //
                // The Final pass is the piece neither had: it sees the same event the children
                // saw, along with the answer to whether they used it.
                .then(if (searching) Modifier.dismissSearchOnBlankTap(::leaveSearch) else Modifier),
        ) {
            content()
        }
    }
}

/**
 * Calls [onBlankTap] when a gesture ends without any child having consumed it.
 *
 * Reads the Final pass so the answer is already known; a scroll is excluded by the same touch
 * slop every other gesture here uses, which keeps a flick through the list from also leaving
 * search.
 *
 * No key on `pointerInput` and the callback goes through [rememberUpdatedState] on purpose. The
 * call site passes `::leaveSearch`, which is a fresh reference on every recomposition, so using
 * it as the key would restart this coroutine and drop any gesture already in flight - a tap would
 * work or not depending on whether a recomposition happened between press and release. That is
 * the kind of failure that looks random on device and that no amount of re-reading the gesture
 * body explains.
 */
@Composable
private fun Modifier.dismissSearchOnBlankTap(onBlankTap: () -> Unit): Modifier {
    val current by rememberUpdatedState(onBlankTap)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var handled = down.isConsumed
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    handled = handled || change.isConsumed
                    break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                handled = handled || change.isConsumed
            }
            if (!moved && !handled) current()
        }
    }
}

/**
 * Borderless search field: a magnifier and the text, with no box around them.
 *
 * Colour carries every state, matching the settings tiles. At rest the magnifier is the muted
 * icon colour and the placeholder the muted text colour; focused, the magnifier is gold and a
 * faint wash of the theme's text colour sits behind the row.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    interactionSource: MutableInteractionSource,
    active: Boolean,
    onSubmit: () -> Unit,
) {
    val wash by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "searchWash",
    )
    val iconTint by animateColorAsState(
        targetValue = if (active) WarmGold else sertumUnselectedIcon,
        animationSpec = tween(durationMillis = 180),
        label = "searchIcon",
    )

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(wash)
            .padding(horizontal = 12.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = sertumBodyText),
        cursorBrush = SolidColor(WarmGold),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    // Decorative: the field already announces itself and its placeholder, so a
                    // description here would read the word "search" twice.
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.width(20.dp).heightIn(min = 20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = sertumUnselectedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

/**
 * Whether the soft keyboard is showing.
 *
 * Two signals, either of which is enough. The two-step back sequence depends on telling "keyboard
 * up" from "keyboard down" apart, and each signal alone has a way to be wrong:
 *
 *  - the IME insets, which is what the platform can answer directly, but an activity whose
 *    `windowSoftInputMode` resolves to adjustResize consumes them;
 *  - the window's visible frame, which survives adjustResize but says nothing when the IME
 *    animates without the frame updating.
 *
 * Measuring both and treating "shown" as the union costs one extra read and removes the case
 * where the first back press silently does nothing.
 *
 * The 15% floor keeps the navigation bar from reading as a keyboard: it is about 5% of the screen
 * on this reference device, an order of magnitude below any real keyboard.
 */
@Composable
private fun rememberImeVisible(): Boolean {
    val view = LocalView.current
    val density = LocalDensity.current
    val insetVisible = WindowInsets.ime.getBottom(density) > 0

    var frameVisible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val height = view.rootView.height
            if (height > 0) {
                val frame = Rect()
                view.getWindowVisibleDisplayFrame(frame)
                frameVisible = height - frame.bottom > height * 0.15f
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return insetVisible || frameVisible
}
