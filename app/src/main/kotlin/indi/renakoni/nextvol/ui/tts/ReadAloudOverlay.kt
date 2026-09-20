package indi.renakoni.nextvol.ui.tts

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.SpeechRequest
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val LocalHideReadAloudOverlay = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Count owners because the sheet and settings can overlap during navigation transitions. */
@Composable
internal fun HideReadAloudOverlay() {
    val hide = LocalHideReadAloudOverlay.current
    DisposableEffect(hide) {
        hide(true)
        onDispose { hide(false) }
    }
}

@Composable
internal fun ReadAloudOverlayHost(
    state: ReadAloudState,
    onCommand: (SpeechAction) -> Unit,
    onOpenBook: (SpeechRequest) -> Unit,
    cover: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val bookId = state.request?.takeUnless { it.isPreview }?.bookId
    var played by rememberSaveable(bookId) { mutableStateOf(false) }
    var closed by rememberSaveable(bookId) { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var hiddenOwners by remember { mutableIntStateOf(0) }
    val hide: (Boolean) -> Unit = remember { { hiddenOwners += if (it) 1 else -1 } }
    LaunchedEffect(bookId, state.phase) {
        when (state.phase) {
            SpeechPhase.Playing -> if (!closed) played = true
            SpeechPhase.Stopped, SpeechPhase.Completed -> {
                played = false
                closed = false
                collapsed = false
            }
            else -> Unit
        }
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = bookId != null && played && !closed && hiddenOwners == 0 &&
        state.phase !in setOf(SpeechPhase.Stopped, SpeechPhase.Completed) &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var playerBounds by remember { mutableStateOf(Rect.Zero) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalHideReadAloudOverlay provides hide) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInRoot() }
            .pointerInput(visible) {
                if (visible) awaitEachGesture {
                    // Observe before children consume the event, without consuming it ourselves.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!playerBounds.contains(down.position + hostOrigin)) collapsed = true
                }
            }) {
            content()
            // Keep placement across hidden panels, backgrounding and window size changes.
            ReadAloudFloatingPlayer(state, visible, collapsed, { collapsed = it }, cover,
                onCommand = {
                    if (it == SpeechAction.Stop) { closed = true; played = false }
                    onCommand(it)
                },
                onOpenBook = { state.request?.let(onOpenBook) },
                onBounds = { playerBounds = it })
        }
    }
}

@Composable
private fun ReadAloudFloatingPlayer(
    state: ReadAloudState,
    visible: Boolean,
    collapsed: Boolean,
    onCollapsed: (Boolean) -> Unit,
    cover: @Composable () -> Unit,
    onCommand: (SpeechAction) -> Unit,
    onOpenBook: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    var dockLeft by rememberSaveable { mutableStateOf(false) }
    var heightFraction by rememberSaveable { mutableFloatStateOf(0.22f) }
    if (!visible) return
    // Docking and the cover/play/close order refer to physical screen edges.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val margin = with(density) { 12.dp.toPx() }
            val playerHeight = with(density) { 48.dp.toPx() }
            val maxY = (heightPx - playerHeight - margin).coerceAtLeast(margin)
            val width by animateDpAsState(if (collapsed) 40.dp else 128.dp, tween(220), label = "playerWidth")
            val playerWidth = with(density) { width.toPx() }
            var dragging by remember { mutableStateOf(false) }
            var dragX by remember { mutableFloatStateOf(0f) }
            var dragY by remember { mutableFloatStateOf(0f) }
            val dockMargin = if (collapsed) 0f else margin
            val dockX = if (dockLeft) dockMargin else (widthPx - playerWidth - dockMargin).coerceAtLeast(0f)
            val snappedX by animateFloatAsState(if (dragging) dragX else dockX,
                if (dragging) snap() else tween(240), label = "playerDock")
            val x = if (dragging) dragX else snappedX
            val y = if (dragging) dragY else (heightFraction * heightPx).coerceIn(margin, maxY)
            val settle = {
                dockLeft = dragX + playerWidth / 2 < widthPx / 2
                heightFraction = dragY.coerceIn(margin, maxY) / heightPx.coerceAtLeast(1f)
                if (dragX <= 0f || dragX + playerWidth >= widthPx) onCollapsed(true)
                dragging = false
            }
            val currentPosition by rememberUpdatedState(Offset(x, y))
            val currentWidth by rememberUpdatedState(playerWidth)
            val currentSettle by rememberUpdatedState(settle)
            val collapseLabel = stringResource(R.string.collapse)
            Box(Modifier.absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(width, 48.dp)
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .testTag("read-aloud-floating-player")
                .semantics {
                    if (!collapsed) customActions = listOf(CustomAccessibilityAction(collapseLabel) {
                        onCollapsed(true); true
                    })
                }
                .pointerInput(widthPx, heightPx) {
                    detectDragGestures(
                        onDragStart = { dragX = currentPosition.x; dragY = currentPosition.y; dragging = true },
                        onDragEnd = { currentSettle() },
                        onDragCancel = { currentSettle() },
                    ) { change, delta ->
                        change.consume()
                        dragX = (dragX + delta.x).coerceIn(0f, (widthPx - currentWidth).coerceAtLeast(0f))
                        dragY = (dragY + delta.y).coerceIn(margin, maxY)
                    }
                }, contentAlignment = if (dockLeft) Alignment.CenterStart else Alignment.CenterEnd) {
                if (collapsed) {
                    Surface(
                        onClick = { onCollapsed(false) },
                        modifier = Modifier.size(40.dp, 48.dp).testTag("read-aloud-expand"),
                        color = androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        Box(contentAlignment = if (dockLeft) Alignment.CenterStart else Alignment.CenterEnd) {
                            Surface(
                                modifier = Modifier.size(20.dp, 40.dp),
                                shape = RoundedCornerShape(
                                    topStart = if (dockLeft) 0.dp else 10.dp,
                                    bottomStart = if (dockLeft) 0.dp else 10.dp,
                                    topEnd = if (dockLeft) 10.dp else 0.dp,
                                    bottomEnd = if (dockLeft) 10.dp else 0.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shadowElevation = 2.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.arrow_forward_ios_24px),
                                        stringResource(R.string.tts_expand_player),
                                        Modifier.size(14.dp).graphicsLayer { rotationZ = if (dockLeft) 0f else 180f })
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shadowElevation = 3.dp,
                    ) {
                        Row(Modifier.requiredWidth(128.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            var rotation by remember { mutableFloatStateOf(0f) }
                            LaunchedEffect(state.phase) {
                                var previousFrame = 0L
                                while (state.phase == SpeechPhase.Playing && isActive) {
                                    withInfiniteAnimationFrameNanos { frame ->
                                        if (previousFrame != 0L) rotation =
                                            (rotation + (frame - previousFrame) / 16_000_000_000f * 360f) % 360f
                                        previousFrame = frame
                                    }
                                }
                            }
                            val openLabel = stringResource(R.string.tts_return_to_book, state.bookTitle)
                            IconButton(onClick = onOpenBook, modifier = Modifier.size(40.dp).testTag("read-aloud-cover")) {
                                Box(Modifier.size(40.dp).clip(CircleShape)
                                    .clearAndSetSemantics { contentDescription = openLabel }
                                    .graphicsLayer { rotationZ = rotation }) { cover() }
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = {
                                onCommand(if (state.isActive) SpeechAction.Pause else SpeechAction.Resume)
                            }, modifier = Modifier.size(40.dp)) {
                                Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color.Transparent,
                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))) {
                                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                        Icon(painterResource(if (state.isActive) R.drawable.pause_24px else R.drawable.play_arrow_24px),
                                            stringResource(if (state.isActive) R.string.tts_pause else R.string.tts_resume), Modifier.size(16.dp))
                                    }
                                }
                            }
                            IconButton(onClick = { onCommand(SpeechAction.Stop) }, modifier = Modifier.size(36.dp, 40.dp)) {
                                Icon(painterResource(R.drawable.close_24px), stringResource(R.string.tts_close_player), Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f))
                            }
                        }
                    }
                }
            }
        }
    }
}
