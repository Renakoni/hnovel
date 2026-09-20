package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.animatePageTurns
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentError
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentLoading
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.readerTapGestures
import indi.renakoni.nextvol.ui.book.reader.content.readerPageSwipe
import indi.renakoni.nextvol.ui.book.reader.content.readerBoundarySwipe
import indi.renakoni.nextvol.ui.book.reader.content.ReaderVolumeDirection
import indi.renakoni.nextvol.ui.book.reader.content.readerVolumeKeys
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.rememberReaderBackgroundPainter
import indi.renakoni.nextvol.ui.book.reader.usesBackgroundImage
import indi.renakoni.nextvol.utils.showSnackbar
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun FlipPageContentComponent(
    modifier: Modifier,
    uiState: FlipPageContentUiState,
    settingState: ReaderSettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    uiState.readingChapterContent?.onOk {
        SimpleFlipPageTextComponent(
            modifier = modifier,
            paddingValues = paddingValues,
            uiState = uiState,
            chapterContent = it,
            settingState = settingState,
            changeIsImmersive = changeIsImmersive,
            onClickNextChapter = onClickNextChapter,
            onClickPrevChapter = onClickPrevChapter,
        )
    }?.onErr {
        ChapterContentError(it, uiState.readingChapterId?.let(chapterTitle)) {
            uiState.readingChapterId?.let(uiState.changeChapter)
        }
    } ?: ChapterContentLoading()
}

private data class PreparedFlipChapter(
    val input: FlipPaginationInput,
    val pages: List<AbstractContentComponent<*>>,
)

@Composable
private fun SimpleFlipPageTextComponent(
    modifier: Modifier,
    paddingValues: PaddingValues,
    uiState: FlipPageContentUiState,
    chapterContent: ChapterContentUiState,
    settingState: ReaderSettings,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var preparedChapter by remember { mutableStateOf<PreparedFlipChapter?>(null) }
    var slippedContentComponentList by remember(chapterContent.id, chapterContent.content) {
        mutableStateOf(preparedChapter?.takeIf {
            it.input.chapterId == chapterContent.id && it.input.content === chapterContent.content
        }?.pages.orEmpty())
    }
    var readingAnchor by remember(chapterContent.id, chapterContent.content) { mutableStateOf<ReaderContentAnchor?>(null) }
    var anchoredPage by remember(chapterContent.id, chapterContent.content) { mutableStateOf<ReaderPage?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val readerStyle = LocalReaderStyle.current
    val textLayout = LocalReaderTextLayout.current
    val textLocaleList = LocalTextLocaleList.current
    val horizontalPadding = with(density) {
        (paddingValues.calculateStartPadding(layoutDirection) + paddingValues.calculateEndPadding(layoutDirection)).toPx()
    }.toInt()
    val verticalPadding = with(density) {
        (paddingValues.calculateTopPadding() + paddingValues.calculateBottomPadding()).toPx()
    }.toInt()
    val pagination = remember(scope) { FlipPaginationCoordinator(scope) }
    val adjacentPagination = remember(scope) { FlipPaginationCoordinator(scope) }
    val paginationInput = FlipPaginationInput(
        chapterId = chapterContent.id,
        content = chapterContent.content,
        contentSize = contentSize,
        horizontalPadding = horizontalPadding,
        verticalPadding = verticalPadding,
        density = density,
        layoutDirection = layoutDirection,
        fontSize = textLayout?.settings?.fontSize ?: readerStyle.fontSize,
        fontLineHeight = textLayout?.settings?.lineSpacing ?: readerStyle.fontLineHeight,
        fontWeight = textLayout?.settings?.fontWeight ?: readerStyle.fontWeight,
        fontFamilyUri = settingState.fontFamilyUri,
        textLocaleList = textLocaleList,
        textLayout = textLayout,
    )
    val visiblePage = slippedContentComponentList.getOrNull(uiState.pagerState.settledPage) as? ReaderPage
    val pending = uiState.pendingChapter
    val pendingContent = pending?.result?.get()
    val pendingInput = pendingContent?.let { paginationInput.copy(chapterId = it.id, content = it.content) }
    val isDragged by uiState.pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pending, uiState.pagerState) {
        val pager = uiState.pagerState
        if (pending == null) return@LaunchedEffect
        snapshotFlow {
            val boundary = if (pending.entry == ChapterEntry.End) 0 else pager.pageCount - 1
            isDragged && (pager.currentPage + pager.currentPageOffsetFraction - boundary).absoluteValue > 0.001f
        }.collect { movedInward ->
            if (movedInward && uiState.pendingChapter === pending && uiState.pagerState === pager)
                uiState.cancelPendingChapter()
        }
    }
    SideEffect {
        pagination.syncInput(paginationInput)
        if (pendingInput != null) adjacentPagination.syncInput(pendingInput)
        else adjacentPagination.cancelPending()
        // A user page change establishes a new position; a reflow keeps the original character.
        if (visiblePage != null && visiblePage !== anchoredPage) {
            readingAnchor = visiblePage.anchor
            anchoredPage = visiblePage
        }
    }
    DisposableEffect(pagination) {
        onDispose { pagination.close(); adjacentPagination.close() }
    }
    LaunchedEffect(paginationInput) {
        if (uiState.readingChapterContent?.get() !== chapterContent) return@LaunchedEffect
        val prepared = preparedChapter
        preparedChapter = null
        if (prepared?.input == paginationInput) return@LaunchedEffect
        val width = contentSize.width - horizontalPadding
        val height = contentSize.height - verticalPadding
        if (width <= 0 || height <= 0) {
            pagination.cancelPending()
            slippedContentComponentList = emptyList()
            uiState.updatePageState(PagerState { 0 })
            return@LaunchedEffect
        }
        slippedContentComponentList = emptyList()
        uiState.updatePageState(PagerState { 0 })
        pagination.submit(paginationInput, chapterContent.content, height, width) { result ->
            if (uiState.readingChapterContent?.get() !== chapterContent) return@submit
            slippedContentComponentList = result
            val anchor = readingAnchor
            val target = if (anchor == null) -1 else result.indexOfFirst { (it as? ReaderPage)?.contains(anchor) == true }
            if (target >= 0) {
                anchoredPage = result[target] as ReaderPage
                uiState.updateAnchoredPageState(PagerState(currentPage = target) { result.size })
            } else {
                readingAnchor = null
                anchoredPage = null
                uiState.updatePageState(PagerState { result.size })
            }
        }
    }
    val snackbarHostState = LocalSnackbarHost.current
    val retryLabel = stringResource(R.string.action_retry)
    val emptyChapterError = WebRequestError(stringResource(R.string.reader_chapter), stringResource(R.string.reader_empty_chapter))
    val paginationError = WebRequestError(stringResource(R.string.reader_chapter), stringResource(R.string.reader_pagination_failed))
    LaunchedEffect(pending, pendingInput) {
        if (pending == null || pendingInput == null) return@LaunchedEffect
        val width = pendingInput.contentSize.width - pendingInput.horizontalPadding
        val height = pendingInput.contentSize.height - pendingInput.verticalPadding
        if (width <= 0 || height <= 0) {
            adjacentPagination.cancelPending()
            return@LaunchedEffect
        }
        adjacentPagination.submit(pendingInput, pendingInput.content, height, width,
            onError = { uiState.failPendingChapter(pending, paginationError) }) { pages ->
            if (uiState.pendingChapter !== pending) return@submit
            if (pages.isEmpty()) {
                uiState.failPendingChapter(pending, emptyChapterError)
                return@submit
            }
            val firstPage = if (pending.entry == ChapterEntry.End) pages.lastIndex else 0
            val prepared = PreparedFlipChapter(pendingInput, pages)
            preparedChapter = prepared
            if (uiState.commitPendingChapter(pending, PagerState(currentPage = firstPage) { pages.size })) {
                pagination.cancelPending()
            } else {
                preparedChapter = null
            }
        }
    }
    LaunchedEffect(pending) {
        pending?.result?.onErr { error ->
            if (snackbarHostState.showSnackbar(
                    message = "${error.title}: ${error.message}", actionLabel = retryLabel,
                    duration = SnackbarDuration.Indefinite,
                ) == SnackbarResult.ActionPerformed && uiState.pendingChapter === pending) {
                uiState.retryPendingChapter()
            }
        }
    }

    // 仅在启用背景图时才创建 painter：rememberReaderBackgroundPainter 会发起图片加载副作用，
    // 无条件调用会导致未开启背景时也去联网加载内置牛皮纸，失败时误报「加载失败」(见 issue #444)。
    val bgPainter = if (
        settingState.usesBackgroundImage &&
        settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop
    ) {
        rememberReaderBackgroundPainter(settingState)
    } else null

    val pageWidthPx = contentSize.width.toFloat()
    val readerFirstPageText = stringResource(R.string.reader_first_page)
    val previousChapterText = stringResource(R.string.previous_chapter)
    val reachedStartText = stringResource(R.string.reader_reached_start)
    suspend fun lastPage(pagerState: PagerState) {
        if (pagerState.pageCount == 0 || slippedContentComponentList.isEmpty()) return
        if (uiState.pendingChapter?.entry == ChapterEntry.Start) uiState.cancelPendingChapter()
        if (pagerState.currentPage != 0) {
            uiState.cancelPendingChapter()
            if (settingState.animatePageTurns) {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            } else {
                pagerState.scrollToPage(pagerState.currentPage - 1)
            }
        } else if (!chapterContent.hasPrevChapter()) {
            snackbarHostState.showSnackbar(reachedStartText)
        } else if (settingState.fastChapterChange) {
            uiState.loadPrevChapter.invoke()
        } else {
            showSnackbar(
                coroutineScope = scope,
                hostState = snackbarHostState,
                duration = SnackbarDuration.Short,
                message = readerFirstPageText,
                actionLabel = previousChapterText
            ) {
                if (it == SnackbarResult.ActionPerformed) {
                    onClickPrevChapter()
                }
            }

        }
    }

    val readerLastPageText = stringResource(R.string.reader_last_page)
    val nextPageText = stringResource(R.string.next_chapter)
    val reachedEndText = stringResource(R.string.reader_reached_end)

    suspend fun nextPage(pagerState: PagerState) {
        if (pagerState.pageCount == 0 || slippedContentComponentList.isEmpty()) return
        if (uiState.pendingChapter?.entry == ChapterEntry.End) uiState.cancelPendingChapter()
        if (pagerState.currentPage + 1 < pagerState.pageCount) {
            uiState.cancelPendingChapter()
            if (settingState.animatePageTurns) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            } else {
                pagerState.scrollToPage(pagerState.currentPage + 1)
            }
        } else if (!chapterContent.hasNextChapter()) {
            snackbarHostState.showSnackbar(reachedEndText)
        } else if (settingState.fastChapterChange) {
            uiState.loadNextChapter.invoke()
        } else {
            showSnackbar(
                coroutineScope = scope,
                hostState = snackbarHostState,
                duration = SnackbarDuration.Short,
                message = readerLastPageText,
                actionLabel = nextPageText
            ) {
                if (it == SnackbarResult.ActionPerformed) {
                    onClickNextChapter()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { contentSize = it }
            .then(
                if (bgPainter != null)
                    Modifier.paint(
                        painter = bgPainter,
                        contentScale = ContentScale.Crop
                    )
                else Modifier
            )
    ) {
        HorizontalPager(
            state = uiState.pagerState,
            key = { it },
            userScrollEnabled = settingState.animatePageTurns,
            modifier = modifier
                .readerBoundarySwipe(uiState.pagerState, enabled = settingState.animatePageTurns &&
                    slippedContentComponentList.isNotEmpty()) { forward ->
                    scope.launch {
                        if (forward) nextPage(uiState.pagerState) else lastPage(uiState.pagerState)
                    }
                }
                .readerPageSwipe(enabled = !settingState.animatePageTurns, gestureKey = uiState.pagerState) { forward ->
                    scope.launch {
                        if (forward) nextPage(uiState.pagerState) else lastPage(uiState.pagerState)
                    }
                }
                .readerVolumeKeys(
                    enabled = settingState.isUsingVolumeKeyFlip && settingState.isUsingFlipPage &&
                        slippedContentComponentList.isNotEmpty(),
                    intervalSeconds = settingState.volumeKeyContinuousFlipInterval,
                ) { direction ->
                    when (direction) {
                        ReaderVolumeDirection.Backward -> lastPage(uiState.pagerState)
                        ReaderVolumeDirection.Forward -> nextPage(uiState.pagerState)
                    }
                }
                .draggable(
                    enabled = settingState.isUsingFlipPage,
                    interactionSource = remember { MutableInteractionSource() },
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState {},
                    onDragStopped = {
                        if (it.absoluteValue > 60) changeIsImmersive.invoke()
                    }
                )
                .readerTapGestures { position ->
                    if (settingState.isUsingFlipPage && settingState.isUsingClickFlipPage)
                        when {
                            position.x < pageWidthPx / 3f -> scope.launch {
                                if (layoutDirection == LayoutDirection.Rtl) nextPage(uiState.pagerState) else lastPage(uiState.pagerState)
                            }
                            position.x > pageWidthPx * 2f / 3f -> scope.launch {
                                if (layoutDirection == LayoutDirection.Rtl) lastPage(uiState.pagerState) else nextPage(uiState.pagerState)
                            }
                            else -> changeIsImmersive.invoke()
                        }
                    else changeIsImmersive.invoke()
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (settingState.usesBackgroundImage && settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop) {
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        painter = rememberReaderBackgroundPainter(settingState),
                        contentDescription = null,
                        contentScale = ContentScale.Crop
                    )
                }
                slippedContentComponentList.getOrNull(it)?.Content(
                    modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }
        if (pending != null && pending.result?.isErr != true) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

