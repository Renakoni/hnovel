package indi.renakoni.nextvol.ui.book.reader.content.flip

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.animatePageTurns
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentError
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentLoading
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.readerTapGestures
import indi.renakoni.nextvol.ui.book.reader.content.readerPageSwipe
import indi.renakoni.nextvol.ui.book.reader.content.ReaderVolumeDirection
import indi.renakoni.nextvol.ui.book.reader.content.readerVolumeKeys
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.rememberReaderBackgroundPainter
import indi.renakoni.nextvol.ui.book.reader.usesBackgroundImage
import indi.renakoni.nextvol.utils.showSnackbar
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
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
        ChapterContentError(it)
    } ?: ChapterContentLoading()
}

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
    var slippedContentComponentList by remember(chapterContent.id, chapterContent.content) { mutableStateOf(emptyList<AbstractContentComponent<*>>()) }
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
    SideEffect {
        pagination.syncInput(paginationInput)
        // A user page change establishes a new position; a reflow keeps the original character.
        if (visiblePage != null && visiblePage !== anchoredPage) {
            readingAnchor = visiblePage.anchor
            anchoredPage = visiblePage
        }
    }
    DisposableEffect(pagination) {
        onDispose { pagination.close() }
    }
    LaunchedEffect(paginationInput) {
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

    // 仅在启用背景图时才创建 painter：rememberReaderBackgroundPainter 会发起图片加载副作用，
    // 无条件调用会导致未开启背景时也去联网加载内置牛皮纸，失败时误报「加载失败」(见 issue #444)。
    val bgPainter = if (
        settingState.usesBackgroundImage &&
        settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop
    ) {
        rememberReaderBackgroundPainter(settingState)
    } else null

    val windowInfo = LocalWindowInfo.current
    val screenWidthPx = windowInfo.containerSize.width.toFloat()
    val readerFirstPageText = stringResource(R.string.reader_first_page)
    val previousChapterText = stringResource(R.string.previous_chapter)
    suspend fun lastPage(pagerState: PagerState) {
        if (pagerState.currentPage != 0) {
            if (settingState.animatePageTurns) {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            } else {
                pagerState.scrollToPage(pagerState.currentPage - 1)
            }
        } else if (settingState.fastChapterChange && slippedContentComponentList.isNotEmpty()) {
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

    suspend fun nextPage(pagerState: PagerState) {
        if (pagerState.currentPage + 1 < pagerState.pageCount) {
            if (settingState.animatePageTurns) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            } else {
                pagerState.scrollToPage(pagerState.currentPage + 1)
            }
        } else if (settingState.fastChapterChange && slippedContentComponentList.isNotEmpty()) {
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
                .readerPageSwipe(enabled = !settingState.animatePageTurns) { forward ->
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
                            position.x < screenWidthPx / 3f -> scope.launch { lastPage(uiState.pagerState) }
                            position.x > screenWidthPx * 2f / 3f -> scope.launch { nextPage(uiState.pagerState) }
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
    }
}

