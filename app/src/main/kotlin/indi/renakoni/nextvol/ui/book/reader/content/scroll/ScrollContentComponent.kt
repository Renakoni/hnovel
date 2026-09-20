@file:Suppress("AssignedValueIsNeverRead")

package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.ReaderFontFamilySettings
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentError
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentLoading
import indi.renakoni.nextvol.ui.book.reader.content.componet.readerTextColor as readerContentTextColor
import indi.renakoni.nextvol.ui.book.reader.content.readerTapGestures
import indi.renakoni.nextvol.ui.book.reader.content.readerVolumeKeys
import indi.renakoni.nextvol.ui.book.reader.content.volumeKeyScrollDistance
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechFollow
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechRanges
import indi.renakoni.nextvol.ui.book.reader.content.readerSpeechManualScroll
import indi.renakoni.nextvol.ui.components.Loading
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.readerTextColor
import indi.renakoni.nextvol.utils.rememberReaderBackgroundPainter
import indi.renakoni.nextvol.ui.book.reader.usesBackgroundImage
import indi.renakoni.nextvol.utils.rememberReaderFontFamily
import indi.renakoni.nextvol.utils.showSnackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle

@Composable
fun ScrollContentComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    ScrollContentTextComponent(
        modifier = modifier,
        uiState = uiState,
        settingState = settingState,
        fontFamilySettings = fontFamilySettings,
        paddingValues = paddingValues,
        changeIsImmersive = changeIsImmersive,
        onClickPrevChapter = onClickPrevChapter,
        onClickNextChapter = onClickNextChapter,
        chapterTitle = chapterTitle,
    )
}

@Composable
fun ScrollContentTextComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    val snackbarHostState = LocalSnackbarHost.current
    val density = LocalDensity.current
    val screenHeight = LocalResources.current.displayMetrics.heightPixels
    val listState = uiState.lazyListState
    val scope = rememberCoroutineScope()
    var lazyColumnSize by remember { mutableStateOf(IntSize(0, 0)) }
    val textLayout = LocalReaderTextLayout.current
    val preparedChapters = uiState.contentList.mapIndexed { index, entry ->
        key(listState, entry?.first ?: "placeholder-$index") {
            rememberPreparedScrollChapter(entry?.second?.get(), textLayout,
                lazyColumnSize.width, lazyColumnSize.height)
        }
    }
    val speech by rememberUpdatedState(LocalReaderSpeechFollow.current)
    val latestPrepared by rememberUpdatedState(preparedChapters)
    val reduceMotion by rememberUpdatedState(settingState.reduceMotion)
    fun speechTarget(initialPlacement: Boolean = false): Pair<Int, Int>? {
        if (!speech.following || !speech.active && !initialPlacement) return null
        val index = latestPrepared.indexOfFirst { it?.content?.id == speech.position?.chapterId }
        val prepared = latestPrepared.getOrNull(index) ?: return null
        val anchor = speech.anchor(prepared.content) ?: return null
        return prepared.offsetFor(anchor)?.let { index to it }
    }

    val reachedTopMsg = stringResource(R.string.reader_reached_top)
    val prevChapterLabel = stringResource(R.string.previous_chapter)
    val reachedBottomMsg = stringResource(R.string.reader_reached_bottom)
    val nextChapterLabel = stringResource(R.string.next_chapter)
    val confirmLabel = stringResource(R.string.confirm)
    val reachedStartMsg = stringResource(R.string.reader_reached_start)
    val reachedEndMsg = stringResource(R.string.reader_reached_end)

    LaunchedEffect(listState) {
        snapshotFlow {
            uiState.readingChapterContent?.isOk == true && lazyColumnSize.height > 0 &&
                latestPrepared.getOrNull(1)?.content?.id == uiState.readingChapterId &&
                listState.layoutInfo.visibleItemsInfo.isNotEmpty()
        }.first { it }
        val restoredProgress = uiState.readingProgress
        // Let the prepared current item replace its loading layout before positioning it.
        // Otherwise a cached previous chapter can pin the viewport while we wait for the
        // current chapter's geometry, which cannot be placed outside the lazy window.
        withFrameNanos { }
        listState.scrollToItem(1)
        val item = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key == uiState.readingChapterId && it.contentType == true
            }
        }.filterNotNull().first()
        // The incoming speech anchor wins over the ordinary percentage restore.
        snapshotFlow {
            val prepared = latestPrepared.getOrNull(1)
            val anchor = prepared?.let { speech.anchor(it.content) }
            anchor == null || anchor.componentIndex !in prepared.text || prepared.offsetFor(anchor) != null
        }.first { it }
        val initialTarget = speechTarget(initialPlacement = true)
        val offset = if (initialTarget != null) {
            (initialTarget.second - lazyColumnSize.height * 0.22f).toInt().coerceAtLeast(0)
        } else if (restoredProgress <= 0f) {
            0
        } else {
            ((item.size * restoredProgress).toInt() - lazyColumnSize.height).coerceAtLeast(0)
        }
        listState.scrollToItem(initialTarget?.first ?: 1, offset)
        uiState.onProgressRestored(listState)
        snapshotFlow { speechTarget() }.collectLatest { target ->
            if (target == null) return@collectLatest
            val viewport = listState.layoutInfo.viewportSize.height
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target.first }
            val y = visible?.offset?.plus(target.second)
            if (y != null && y in (viewport * 0.15f).toInt()..(viewport * 0.72f).toInt()) return@collectLatest
            val targetOffset = (target.second - viewport * 0.22f).toInt().coerceAtLeast(0)
            if (!reduceMotion && y != null && kotlin.math.abs(y) < viewport * 2)
                listState.animateScrollToItem(target.first, targetOffset)
            else listState.scrollToItem(target.first, targetOffset)
        }
    }
    LaunchedEffect(listState) {
        var atTop = false
        var atBottom = false

        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val layoutInfo = listState.layoutInfo
                    val totalCount = layoutInfo.totalItemsCount
                    val firstIndex = listState.firstVisibleItemIndex
                    val firstOffset = listState.firstVisibleItemScrollOffset
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()

                    val isAtTop = firstIndex == 0 && firstOffset == 0
                    val isAtBottom = lastVisible != null &&
                            lastVisible.index == totalCount - 1 &&
                            (lastVisible.offset + lastVisible.size) <= layoutInfo.viewportEndOffset

                    when {
                        isAtTop -> {
                            if (atTop) {
                                if (uiState.readingChapterContent?.map { it.hasPrevChapter() }?.get() == true)
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedTopMsg,
                                            actionLabel = prevChapterLabel
                                        ) { if (it == SnackbarResult.ActionPerformed) onClickPrevChapter() }
                                    }
                                else
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedStartMsg,
                                            actionLabel = confirmLabel
                                        )
                                    }
                            }
                            atTop = true; atBottom = false
                        }

                        isAtBottom -> {
                            if (atBottom) {
                                if (uiState.readingChapterContent?.map { it.hasNextChapter() }?.get() == true)
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedBottomMsg,
                                            actionLabel = nextChapterLabel
                                        ) { if (it == SnackbarResult.ActionPerformed) onClickNextChapter() }
                                    }
                                else
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedEndMsg,
                                            actionLabel = confirmLabel
                                        )
                                    }
                            }
                            atBottom = true; atTop = false
                        }

                        else -> {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            atTop = false; atBottom = false
                        }
                    }
                }
            }
    }

    if (settingState.usesBackgroundImage && settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop) {
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) {
                    screenHeight.toDp()
                })
                .offset(y = with(density) {
                    ((uiState.lazyListState.layoutInfo.visibleItemsInfo.getOrNull(0)?.offset
                        ?: 0) % screenHeight + screenHeight).toDp()
                }),
            painter = rememberReaderBackgroundPainter(settingState),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) {
                    screenHeight.toDp()
                })
                .offset(y = with(density) {
                    ((uiState.lazyListState.layoutInfo.visibleItemsInfo.getOrNull(0)?.offset
                        ?: 0) % screenHeight).toDp()
                }),
            painter = rememberReaderBackgroundPainter(settingState),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        uiState.writeProgressRightNow()
    }
    AnimatedVisibility(
        uiState.contentList.getOrNull(1) == null,
        enter = if (settingState.reduceMotion) EnterTransition.None else fadeIn(),
        exit = if (settingState.reduceMotion) ExitTransition.None else fadeOut()
    ) {
        Loading()
    }
    AnimatedVisibility(
        uiState.contentList.getOrNull(1) != null,
        enter = if (settingState.reduceMotion) EnterTransition.None else fadeIn(),
        exit = if (settingState.reduceMotion) ExitTransition.None else fadeOut()
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .readerSpeechManualScroll()
                .readerVolumeKeys(
                    enabled = settingState.isUsingVolumeKeyFlip && !settingState.isUsingFlipPage &&
                        !uiState.isRestoringProgress &&
                        uiState.readingChapterContent?.get() != null && lazyColumnSize.height > 0,
                    intervalSeconds = settingState.volumeKeyContinuousFlipInterval,
                ) { direction ->
                    speech.onManualNavigation()
                    listState.scrollBy(volumeKeyScrollDistance(
                        listState.layoutInfo.viewportSize.height,
                        settingState.volumeKeyScrollFraction,
                        direction,
                    ))
                }
                .readerTapGestures { changeIsImmersive() }
                .onGloballyPositioned {
                    scope.launch {
                        withFrameNanos { }
                        uiState.setLazyColumnSize(it.size)
                        lazyColumnSize = it.size
                    }
                },
            state = listState,
            userScrollEnabled = !uiState.isRestoringProgress || uiState.readingChapterContent?.isErr == true,
        ) {
            itemsIndexed(
                items = uiState.contentList,
                key = { index, pair -> pair?.first ?: "placeholder-$index" },
                contentType = { index, pair -> pair?.second?.isOk == true && preparedChapters[index] != null },
            ) { index, pair ->
                pair?.second.let { result ->
                    uiState.contentList.getOrNull(index + 1)?.second?.get()?.let {
                        if (!it.hasPrevChapter()) return@itemsIndexed
                    }
                    uiState.contentList.getOrNull(index - 1)?.second?.get()?.let {
                        if (!it.hasNextChapter()) return@itemsIndexed
                    }
                    result?.onOk {
                        val prepared = preparedChapters[index]
                        if (prepared == null) ChapterContentLoading() else TextContent(
                            modifier = modifier,
                            settingState = settingState,
                            fontFamilySettings = fontFamilySettings,
                            prepared = prepared,
                        )
                    }?.onErr {
                        ChapterContentError(it, pair?.first?.let(chapterTitle)) {
                            pair?.first?.let(uiState.retryChapter)
                        }
                    } ?: ChapterContentLoading()
                }
            }
        }
    }
}

@Composable
private fun TextContent(
    modifier: Modifier,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    prepared: PreparedScrollChapter,
) {
    val content = prepared.content
    val speechRanges = LocalReaderSpeechFollow.current.ranges(content)
    val density = LocalDensity.current
    val screenHeight = LocalResources.current.displayMetrics.heightPixels
    val textColor = readerTextColor(settingState)
    val fontFamily = rememberReaderFontFamily(fontFamilySettings)
    Column(
        Modifier.defaultMinSize(
            minHeight = with(density) {
                screenHeight.toDp()
            }
        )
    ) {
        if (settingState.isUsingContinuousScrolling) {
            val titleRegex = Regex("^(第[一二三四五六七八九十]+卷)\\s+(.*)")
            val matchResult = titleRegex.find(content.title)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (matchResult != null) {
                    val (volumeTitle, chapterTitle) = matchResult.destructured
                    Text(
                        text = volumeTitle,
                        textAlign = TextAlign.Center,
                        fontSize = (settingState.fontSize + 2).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        color = textColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        text = chapterTitle,
                        textAlign = TextAlign.Center,
                        fontSize = (settingState.fontSize + 6).sp,
                        lineHeight = (settingState.fontSize + settingState.fontLineHeight + 6).sp,
                        fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100)),
                        fontFamily = fontFamily,
                        color = textColor
                    )
                } else {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        text = content.title,
                        textAlign = TextAlign.Center,
                        fontSize = (settingState.fontSize + 6).sp,
                        lineHeight = (settingState.fontSize + settingState.fontLineHeight + 6).sp,
                        fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100)),
                        fontFamily = fontFamily,
                        color = textColor
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(
                        modifier = Modifier.width(48.dp),
                        color = textColor
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        val components = content.content.withIndex().filterNot { (it.value as? SimpleTextComponent)?.data?.text?.isEmpty() == true }
        val paragraphSpacing = LocalReaderTextLayout.current?.paragraphSpacingPx ?: 0
        val colors = LocalReaderStyle.current
        components.forEachIndexed { index, (componentIndex, component) ->
            if (component is SimpleTextComponent && components.getOrNull(index - 1)?.value is SimpleTextComponent) {
                Spacer(Modifier.height(with(density) { paragraphSpacing.toDp() }))
            }
            val text = prepared.text[componentIndex]
            Box(Modifier.onGloballyPositioned {
                prepared.componentOffsets[componentIndex] = it.positionInParent().y.toInt()
            }) {
                CompositionLocalProvider(LocalReaderSpeechRanges provides speechRanges) {
                    if (text != null) ScrollTextContent(text, readerContentTextColor(colors.textColor, colors.textDarkColor), modifier)
                    else component.Content(modifier)
                }
            }
        }
    }
}
