package indi.renakoni.nextvol.ui.book.detail

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.valentinilk.shimmer.shimmer
import indi.renakoni.nextvol.ui.localbook.LocalBookMissingFile
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.get
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.BookDownloadState
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.components.Loading
import indi.renakoni.nextvol.ui.components.SwitchChip
import indi.renakoni.nextvol.ui.components.rememberSkeletonShimmer
import indi.renakoni.nextvol.ui.home.bookshelf.home.BookStatusIcon
import indi.renakoni.nextvol.ui.home.settings.textformatting.rules.navigateToSettingsTextFormattingRulesDestination
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.fadeInOnce
import indi.renakoni.nextvol.utils.fadingEdge
import indi.renakoni.nextvol.utils.isScrollingUp
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.nextUp
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    uiState: DetailUiState,
    onClickExportToEpub: (ExportSettings) -> Unit,
    onClickBackButton: () -> Unit,
    onClickChapter: (String) -> Unit,
    onClickRead: () -> Unit,
    cacheBook: (String) -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickCover: (Uri) -> Unit,
    onClickMarkAsRead: () -> Unit,
    onRetry: () -> Unit = {},
    localFileMissing: Boolean = false,
    onRelink: () -> Unit = {},
    onMarkChaptersUnread: suspend (Set<String>) -> Unit = {},
) {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = LocalSnackbarHost.current
    val context = LocalContext.current

    val exportBottomSheetState = rememberBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val infoBottomSheetState = rememberBottomSheetState(initialValue = SheetValue.PartiallyExpanded)

    var showExportBottomSheet by remember { mutableStateOf(false) }
    var showInfoBottomSheet by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }

    val lazyListState = rememberLazyListState()
    val selectionListState = rememberLazyListState()
    var selectingChapters by rememberSaveable { mutableStateOf(false) }
    var selectedChapterIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmUnread by remember { mutableStateOf(false) }
    var savingUnread by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val catalogIds = uiState.bookVolumes?.get()?.volumes.orEmpty()
        .flatMap { it.chapters }.mapTo(mutableSetOf()) { it.id }
    val selectedIds = selectedChapterIds.toSet().intersect(catalogIds)
    val exitSelection = {
        selectingChapters = false
        selectedChapterIds = emptyList()
        confirmUnread = false
    }
    BackHandler(selectingChapters) { if (!savingUnread) exitSelection() }
    val volumesEmpty = uiState.bookVolumes == null

    val isCollapsed by remember {
        derivedStateOf {
            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) false
            else visibleItems.none { it.index == 0 }
        }
    }

    val claim = LocalClaimSnackbarHost.current

    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }

    val scrollingUp by lazyListState.isScrollingUp()
    val fabVisible by remember(uiState.bookVolumes, lazyListState) {
        derivedStateOf {
            val hasVolumes = uiState.bookVolumes != null
            val allowByDirection = !lazyListState.isScrollInProgress || scrollingUp
            val canGoForward = lazyListState.canScrollForward

            uiState.readingAvailable && hasVolumes && uiState.userReadingData != null && canGoForward && allowByDirection
        }
    }


    val isStartReading = uiState.userReadingData?.lastReadChapterId == null
    val fabTextRes = if (isStartReading) R.string.start_reading else R.string.continue_reading

    val fabContent = remember {
        movableContentOf<Boolean, Int, () -> Unit> { visible, textRes, onClick ->
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 4 },
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(150, easing = FastOutSlowInEasing))
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.padding(end = 28.dp, bottom = 28.dp),
                    onClick = onClick,
                    icon = { Icon(painterResource(R.drawable.filled_menu_book_24px), null) },
                    text = { Text(stringResource(textRes)) }
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                val targetBottomPad = if (fabVisible) 90.dp else 32.dp
                val snackbarBottomPad by animateDpAsState(
                    targetValue = targetBottomPad,
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                    label = "snackbarBottomPad"
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = snackbarBottomPad)
                ) { data ->
                    LnrSnackbar(data)
                }

                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    fabContent(fabVisible && !selectingChapters, fabTextRes, onClickRead)
                }
            }
        },
        bottomBar = {
            if (selectingChapters) Surface(color = colorScheme.surfaceContainerLow) {
                Button(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    enabled = selectedIds.isNotEmpty() && !savingUnread,
                    onClick = { confirmUnread = true },
                ) {
                    Text(stringResource(if (savingUnread) R.string.processing else R.string.mark_unread_action))
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectingChapters) TopAppBar(
                title = { Text(stringResource(R.string.chapters_selected, selectedIds.size),
                    style = typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = exitSelection, enabled = !savingUnread) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(enabled = !savingUnread && catalogIds.isNotEmpty(), onClick = {
                        selectedChapterIds = if (selectedIds == catalogIds) emptyList() else catalogIds.toList()
                    }) {
                        Text(stringResource(if (selectedIds == catalogIds) R.string.deselect_all else R.string.select_all))
                    }
                },
            ) else TopBar(
                title = uiState.bookInformation?.map { it.title }?.getOrElse { "" } ?: "",
                readingProgress = uiState.userReadingData?.readingProgress ?: 0f,
                volumesEmpty = volumesEmpty,
                readingAvailable = uiState.readingAvailable,
                onClickBackButton = onClickBackButton,
                onClickExport = { showExportBottomSheet = true },
                onClickTextFormatting = {
                    uiState.bookInformation?.onOk {
                        navController.navigateToSettingsTextFormattingRulesDestination(
                            it.id
                        )
                    }?.onErr {
                        Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                    }
                },
                onClickMarkAsRead = onClickMarkAsRead,
                onClickMarkAsUnread = {
                    selectedChapterIds = emptyList()
                    selectingChapters = true
                },
                canMarkUnread = catalogIds.isNotEmpty() && uiState.userReadingData != null,
                scrollBehavior = scrollBehavior,
                isCollapsed = isCollapsed
            )

            Crossfade(
                targetState = uiState.bookInformation,
                animationSpec = tween(300),
                label = "DetailScreenCrossfade"
            ) { result ->
                result?.onOk {
                    DetailContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface),
                        uiState = uiState,
                        localFileMissing = localFileMissing,
                        onRelink = onRelink,
                        bookInformation = it,
                        onClickChapter = { id ->
                            if (selectingChapters) {
                                if (!savingUnread) selectedChapterIds =
                                    if (id in selectedChapterIds) selectedChapterIds - id else selectedChapterIds + id
                            } else onClickChapter(id)
                        },
                        lazyListState = if (selectingChapters) selectionListState else lazyListState,
                        selectingChapters = selectingChapters,
                        selectedChapterIds = selectedIds,
                        selectionEnabled = !savingUnread,
                        cacheBook = cacheBook,
                        requestAddBookToBookshelf = requestAddBookToBookshelf,
                        onClickTag = onClickTag,
                        onClickCover = onClickCover,
                        onClickShowInfo = { showInfoBottomSheet = true }
                    )
                }?.onErr {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (localFileMissing) LocalBookMissingFile(onRelink)
                        Text(it.title, style = typography.titleMedium)
                        Text(it.message, style = typography.bodyMedium)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.discovery_retry)) }
                    }
                } ?: DetailContentSkeleton(
                        Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface)
                    )
            }
        }


        if (showExportBottomSheet && uiState.readingAvailable) {
            uiState.bookVolumes?.onOk { bookVolumes ->
                ExportBottomSheet(
                    sheetState = exportBottomSheetState,
                    bookVolumes = bookVolumes,
                    settings = exportSettings,
                    onSettingsChange = { exportSettings = it },
                    onDismissRequest = { showExportBottomSheet = false },
                    onClickExport = onClickExportToEpub
                )
            }
        }
        AnimatedVisibility(visible = showInfoBottomSheet) {
            uiState.bookInformation?.onOk { bookInformation ->
                BookInfoBottomSheet(
                    bookInformation = bookInformation,
                    bookVolumes = uiState.bookVolumes?.get(),
                    sheetState = infoBottomSheetState,
                    onDismissRequest = { showInfoBottomSheet = false }
                )
            }
        }
    }
    if (confirmUnread) AlertDialog(
        onDismissRequest = { if (!savingUnread) confirmUnread = false },
        title = { Text(stringResource(R.string.mark_unread_action)) },
        text = { Text(stringResource(R.string.mark_unread_confirmation, selectedIds.size)) },
        confirmButton = {
            TextButton(enabled = !savingUnread && selectedIds.isNotEmpty(), onClick = {
                savingUnread = true
                coroutineScope.launch {
                    try {
                        onMarkChaptersUnread(selectedIds)
                        exitSelection()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.e("DetailScreen", "Could not mark chapters unread", error)
                        confirmUnread = false
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.mark_unread_failed))
                        }
                    } finally {
                        savingUnread = false
                    }
                }
            }) { Text(stringResource(if (savingUnread) R.string.processing else R.string.confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !savingUnread, onClick = { confirmUnread = false }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}


@Composable
private fun DetailContentSkeleton(modifier: Modifier = Modifier) {
    val rounded = RoundedCornerShape(6.dp)
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(0.5.seconds)
        started = true
    }

    val baseColor = colorScheme.surfaceContainerLow
    val highlightColor = colorScheme.surfaceContainerHigh

    val shimmer = rememberSkeletonShimmer(
        baseColor, highlightColor
    )

    Column(
        modifier = modifier
            .then(if (started) Modifier.shimmer(shimmer) else Modifier),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(188.dp)
                .padding(horizontal = itemHorizontalPadding, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 122.dp, height = 178.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(baseColor)
            )
            Column(
                modifier = Modifier.padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(20.dp)
                            .clip(rounded)
                            .background(baseColor)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(baseColor)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(90.dp)
                        .padding(vertical = itemVerticalPadding)
                        .clip(RoundedCornerShape(8.dp))
                        .background(baseColor)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(24.dp)
                    .clip(rounded)
                    .background(baseColor)
            )
            Spacer(Modifier.height(10.dp))
            repeat(4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(rounded)
                        .background(baseColor)
                )
            }
        }
    }
}


private val itemHorizontalPadding = 18.dp
private val itemVerticalPadding = 8.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    modifier: Modifier = Modifier,
    uiState: DetailUiState,
    bookInformation: BookInformation,
    lazyListState: LazyListState,
    onClickChapter: (String) -> Unit,
    cacheBook: (String) -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickCover: (Uri) -> Unit,
    onClickShowInfo: () -> Unit,
    selectingChapters: Boolean,
    selectedChapterIds: Set<String>,
    selectionEnabled: Boolean,
    localFileMissing: Boolean,
    onRelink: () -> Unit,
) {
    var hideReadChapters by remember { mutableStateOf(false) }
    val deferred = 6
    val framesPerStep = 2
    var visible by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (visible < deferred) {
            repeat(framesPerStep) { withFrameNanos { } }
            visible += 1
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier
    ) {
        if (visible >= 1 && !selectingChapters) item {
            BookCardBlock(
                bookInformation = bookInformation,
                showReadingMetadata = uiState.readingAvailable,
                modifier = Modifier
                    .fadeInOnce("book")
                    .graphicsLayer {
                        translationY = lazyListState.firstVisibleItemScrollOffset * 0.5f
                    }
                    .fillMaxWidth(),
                onClickCover = onClickCover
            )
        }

        if (visible >= 2 && !selectingChapters) item {
            TagsBlock(
                modifier = Modifier.fadeInOnce("tags"),
                bookInformation = bookInformation,
                onClickTag = onClickTag
            )
        }

        if (visible >= 3 && !selectingChapters) item {
            QuickOperationsBlock(
                modifier = Modifier.fadeInOnce("op"),
                isInBookshelf = uiState.isInBookshelf,
                downloadState = uiState.downloadState,
                canCache = uiState.canCache,
                downloadItem = uiState.downloadItem,
                onClickAddToBookShelf = { requestAddBookToBookshelf(bookInformation.id) },
                onClickCache = { cacheBook(bookInformation.id) },
                onClickShowInfo = onClickShowInfo
            )
        }

        if (visible >= 4 && !selectingChapters) item {
            IntroBlock(
                modifier = Modifier.fadeInOnce("intro"),
                description = bookInformation.description
            )
        }

        if (visible >= 5 && !uiState.readingAvailable) item {
            if (localFileMissing) LocalBookMissingFile(onRelink,
                Modifier.padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding))
            else Text(stringResource(if (uiState.metadataOnly) R.string.source_metadata_only else R.string.source_reading_unavailable),
                Modifier.padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
                style = typography.bodyMedium, color = colorScheme.onSurfaceVariant)
        }
        if (visible >= 5 && uiState.readingAvailable && !selectingChapters) item {
            Row(
                modifier = Modifier
                    .fadeInOnce("contents")
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.detail_contents),
                    style = typography.displayMedium,
                    fontWeight = FontWeight.W600
                )
                Spacer(Modifier.width(12.dp))
                SwitchChip(
                    label = stringResource(R.string.hide_read),
                    selected = hideReadChapters,
                    onClick = { hideReadChapters = !hideReadChapters }
                )
            }
        }

        if (visible >= 6 && uiState.readingAvailable) {
            uiState.bookVolumes?.onOk { bookVolumes ->
                items(
                    items = bookVolumes.volumes,
                    key = { it.volumeId }
                ) { volume ->
                    VolumeItem(
                        modifier = Modifier.fadeInOnce(volume.volumeId),
                        volume = volume,
                        hideReadChapters = hideReadChapters && !selectingChapters,
                        selectingChapters = selectingChapters,
                        selectedChapterIds = selectedChapterIds,
                        selectionEnabled = selectionEnabled,
                        chapterReadingProgress = uiState.userReadingData?.maxChapterReadingProgressMap ?: emptyMap(),
                        onClickChapter = onClickChapter,
                        volumesSize = bookVolumes.volumes.size,
                        lastReadingChapterId = uiState.userReadingData?.lastReadChapterId
                    )
                }
            }?.onErr {
                //TODO 错误显示
            } ?: item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Loading()
                }
            }
        }

        item {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    readingProgress: Float,
    volumesEmpty: Boolean,
    readingAvailable: Boolean,
    onClickBackButton: () -> Unit,
    onClickExport: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickMarkAsRead: () -> Unit,
    onClickMarkAsUnread: () -> Unit,
    canMarkUnread: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    isCollapsed: Boolean
) {
    val titleProgress by animateFloatAsState(
        targetValue = if (isCollapsed) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "titleProgress"
    )

    val barAlpha by animateFloatAsState(
        targetValue = if (isCollapsed) 1f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "barAlpha"
    )

    val barProgress by animateFloatAsState(
        targetValue = readingProgress.coerceIn(0f, 1f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "barProgress"
    )

    Box {
        TopAppBar(
            title = {
                val offset = 16.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = stringResource(R.string.detail_title),
                        maxLines = 1,
                        style = typography.displayLarge,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (-offset * titleProgress).toPx().toInt()
                                )
                            }
                            .graphicsLayer { alpha = 1f - titleProgress }
                    )
                    Text(
                        text = title,
                        maxLines = 1,
                        style = typography.displayLarge,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (offset * (1f - titleProgress)).toPx().toInt()
                                )
                            }
                            .graphicsLayer { alpha = titleProgress }
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onClickBackButton) {
                    Icon(painterResource(id = R.drawable.arrow_back_24px), contentDescription = stringResource(R.string.sources_back))
                }
            },
            actions = {
                if (readingAvailable) TopBarActions(
                    volumesEmpty = volumesEmpty,
                    onClickExport = onClickExport,
                    onClickTextFormatting = onClickTextFormatting,
                    onClickMarkAsRead = onClickMarkAsRead,
                    onClickMarkAsUnread = onClickMarkAsUnread,
                    canMarkUnread = canMarkUnread,
                )
            },
            scrollBehavior = scrollBehavior
        )

        Box(
            Modifier
                .matchParentSize()
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .alpha(barAlpha)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(barProgress)
                        .background(colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun TopBarActions(
    volumesEmpty: Boolean,
    onClickExport: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickMarkAsRead: () -> Unit,
    onClickMarkAsUnread: () -> Unit,
    canMarkUnread: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    IconButton(enabled = !volumesEmpty, onClick = onClickExport) {
        Icon(painterResource(id = R.drawable.file_export_24px), contentDescription = stringResource(R.string.export))
    }
    IconButton(enabled = !volumesEmpty, onClick = onClickTextFormatting) {
        Icon(painterResource(id = R.drawable.find_replace_24px), contentDescription = stringResource(R.string.settings_text_formatting))
    }
    Box {
        IconButton(enabled = !volumesEmpty, onClick = { menuExpanded = true }) {
            Icon(painterResource(id = R.drawable.more_vert_24px), contentDescription = stringResource(R.string.action_more_options))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mark_as_unread), style = typography.bodyLarge) },
                enabled = canMarkUnread,
                onClick = {
                    menuExpanded = false
                    onClickMarkAsUnread()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mark_as_read), style = typography.bodyLarge) },
                onClick = {
                    menuExpanded = false
                    onClickMarkAsRead()
                }
            )
        }
    }
}


@Composable
private fun BookCardBlock(
    bookInformation: BookInformation,
    showReadingMetadata: Boolean,
    modifier: Modifier,
    onClickCover: (Uri) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var coverFallback by remember(bookInformation.id, bookInformation.coverUri) {
        mutableStateOf(bookInformation.coverUri.toString().isBlank())
    }
    val updateText = if (bookInformation.isComplete) {
        stringResource(R.string.book_completed)
    } else {
        stringResource(
            R.string.book_info_update_date,
            bookInformation.lastUpdated.format(dateFormatter())
        )
    }
    val wordCountText = bookInformation.wordCount.get()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(188.dp)
            .padding(horizontal = itemHorizontalPadding, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .clickable(
                    onClick = {
                        if (coverFallback) {
                            coroutineScope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    DefaultBookCoverRenderer.cacheUri(
                                        context,
                                        bookInformation.title,
                                        bookInformation.id,
                                        bookInformation.author
                                    )
                                }
                                onClickCover(uri)
                            }
                        } else {
                            onClickCover(bookInformation.coverUri)
                        }
                    }
                )
        ) {
            Cover(
                bookId = bookInformation.id,
                height = 178.dp,
                width = 122.dp,
                uri = bookInformation.coverUri,
                title = bookInformation.title,
                author = bookInformation.author,
                rounded = 8.dp,
                onFallbackChanged = { coverFallback = it }
            )
        }
        Column(
            modifier = Modifier
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = bookInformation.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.W600,
                style = typography.displayMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (bookInformation.subtitle.isNotEmpty()) {
                Text(
                    text = bookInformation.subtitle,
                    maxLines = 2,
                    color = colorScheme.secondary,
                    style = typography.bodyMedium
                )
            }
            Text(
                text = bookInformation.author,
                maxLines = 1,
                fontWeight = FontWeight.W600,
                color = colorScheme.primary,
                style = typography.bodyLarge
            )
            if (showReadingMetadata) Column {
                InfoRow(
                    icon = { BookStatusIcon(bookInformation.isComplete) },
                    text = updateText
                )
                Spacer(Modifier.height(2.dp))
                InfoRow(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.text_snippet_24px),
                            contentDescription = null,
                            tint = colorScheme.outline,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                    },
                    text = wordCountText
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Text(
            text = text,
            maxLines = 1,
            style = typography.labelMedium,
            color = colorScheme.secondary
        )
    }
}


@Composable
private fun TagsBlock(
    modifier: Modifier,
    bookInformation: BookInformation,
    onClickTag: (String) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = itemVerticalPadding, horizontal = itemHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (bookInformation.publishingHouse.isNotEmpty()) {
            SuggestionChip(
                label = { Text(bookInformation.publishingHouse) },
                onClick = {}
            )
        }

        bookInformation.tags.forEach { tag ->
            SuggestionChip(
                label = { Text(tag) },
                onClick = { onClickTag(tag) }
            )
        }
    }
}


@Composable
fun QuickOperationButton(
    icon: Painter,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Button(
        contentPadding = PaddingValues(12.dp),
        modifier = modifier
            .heightIn(min = 88.dp)
            .fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorScheme.surfaceContainerLow,
            contentColor = colorScheme.primary,
        ),
        shape = RoundedCornerShape(0.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                modifier = Modifier.size(18.dp),
                painter = icon,
                contentDescription = null,
            )
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let {
                Text(it, style = typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QuickOperationsBlock(
    modifier: Modifier,
    isInBookshelf: Boolean,
    downloadState: BookDownloadState,
    canCache: Boolean,
    downloadItem: DownloadItem?,
    onClickAddToBookShelf: () -> Unit,
    onClickCache: () -> Unit,
    onClickShowInfo: () -> Unit
) {
    val bookmark = painterResource(R.drawable.bookmark_add_24px)
    val filledBookmark = painterResource(R.drawable.filled_bookmark_add_24px)
    val cloud = painterResource(R.drawable.cloud_download_24px)
    val filledCloud = painterResource(R.drawable.filled_cloud_download_24px)
    val info = painterResource(R.drawable.info_24px)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding)
            .clip(RoundedCornerShape(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isInBookshelf) {
            QuickOperationButton(
                icon = filledBookmark,
                title = stringResource(R.string.activity_collections),
                onClick = onClickAddToBookShelf,
                modifier = Modifier.weight(1f)
            )
        } else {
            QuickOperationButton(
                icon = bookmark,
                title = stringResource(R.string.add_to_bookshelf),
                onClick = onClickAddToBookShelf,
                modifier = Modifier.weight(1f)
            )
        }

        val phase = downloadState.phase
        if (canCache || phase != BookDownloadPhase.None) {
            val status = when (phase) {
                BookDownloadPhase.None -> R.string.cached_false
                BookDownloadPhase.Partial -> R.string.book_download_partial
                BookDownloadPhase.Complete -> R.string.cached
                BookDownloadPhase.Updating -> R.string.book_download_updating
                BookDownloadPhase.Failed -> R.string.book_download_failed
                BookDownloadPhase.Outdated -> R.string.book_download_outdated
            }
            val action = when (phase) {
                BookDownloadPhase.None, BookDownloadPhase.Updating -> null
                BookDownloadPhase.Complete -> R.string.book_download_check_updates
                BookDownloadPhase.Partial -> R.string.book_download_continue
                BookDownloadPhase.Failed -> R.string.book_download_retry
                BookDownloadPhase.Outdated -> R.string.book_download_update
            }
            QuickOperationButton(
                icon = if (phase == BookDownloadPhase.Complete) filledCloud else cloud,
                title = stringResource(status),
                supportingText = if (phase == BookDownloadPhase.Updating)
                    downloadItem?.progress?.takeIf { it >= 0f && it < 1f }?.let { "${(it * 100).toInt()}%" }
                else action?.takeIf { canCache }?.let { stringResource(it) },
                enabled = canCache && phase != BookDownloadPhase.Updating,
                onClick = onClickCache,
                modifier = Modifier.weight(1f),
            )
        }

        QuickOperationButton(
            icon = info,
            title = stringResource(R.string.action_show_info),
            onClick = onClickShowInfo,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun IntroBlock(
    modifier: Modifier,
    description: String
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val overflowed = remember(description) { description.length > 220 }

    val fadingBrush = remember {
        Brush.verticalGradient(
            0.7f to Color.White,
            1f to Color.Transparent
        )
    }
    val whiteBrush = remember {
        Brush.verticalGradient(listOf(Color.White, Color.White))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            modifier = Modifier.padding(vertical = 16.dp),
            text = stringResource(R.string.detail_introduction),
            style = typography.displayMedium,
            fontWeight = FontWeight.W600
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .fadingEdge(
                    if (!expanded && overflowed) fadingBrush else whiteBrush
                )
        ) {
            Text(
                text = description,
                style = typography.bodyLarge,
                maxLines = if (!expanded && overflowed) 4 else Int.MAX_VALUE,
                color = colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (overflowed) {
            val rotation by animateFloatAsState(if (expanded) 0f else 180f)
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = { expanded = !expanded },
                colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent)
            ) {
                Icon(
                    painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                    tint = colorScheme.primary
                )
                Text(
                    text = if (expanded)
                        stringResource(R.string.collapse)
                    else
                        stringResource(R.string.expand),
                    color = colorScheme.primary
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun VolumeItem(
    modifier: Modifier,
    volume: Volume,
    hideReadChapters: Boolean = false,
    chapterReadingProgress: Map<String, Float>,
    onClickChapter: (String) -> Unit,
    volumesSize: Int,
    lastReadingChapterId: String?,
    selectingChapters: Boolean,
    selectedChapterIds: Set<String>,
    selectionEnabled: Boolean,
) {
    val readIds = remember(chapterReadingProgress) {
        chapterReadingProgress.filterValues { it >= 1f }.keys
    }
    val (readCount, totalCount) = remember(volume.volumeId, readIds) {
        val count = volume.chapters.count { it.id in readIds }
        count to volume.chapters.size
    }
    val isFullyRead = readCount >= totalCount
    var expanded by rememberSaveable {
        mutableStateOf(readCount < totalCount || volumesSize > 8)
    }
    val rotation by animateFloatAsState(targetValue = if (expanded) 90f else 0f, animationSpec = tween(200))

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier
                .weight(5f)
                .padding(vertical = 12.dp)
            ) {
                Text(
                    text = volume.volumeTitle,
                    style = typography.titleMedium,
                    color = if (isFullyRead) colorScheme.secondary
                    else colorScheme.onSurface
                )
                Text(
                    text = if (isFullyRead) stringResource(R.string.info_reading_finished)
                    else stringResource(R.string.info_reading_progress, readCount, totalCount),
                    style = typography.titleSmall,
                    fontWeight = FontWeight.Normal,
                    color = colorScheme.secondary
                )
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = !hideReadChapters || !isFullyRead,
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInHorizontally(
                            animationSpec = tween(180),
                            initialOffsetX = { it / 4 }
                        ),
                exit = fadeOut(animationSpec = tween(140)) +
                        slideOutHorizontally(
                            animationSpec = tween(140),
                            targetOffsetX = { it / 4 }
                        )
            ) {
                Icon(
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                    painter = painterResource(id = R.drawable.arrow_forward_ios_24px),
                    contentDescription = null
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.animateContentSize(animationSpec = tween(250))) {
            if (expanded) {
                volume.chapters.forEach { chapter ->
                    val visible = !(hideReadChapters && chapter.id in readIds)
                    if (visible) {
                        ChapterItem(
                            chapter = chapter,
                            isRead = chapter.id in readIds,
                            isLastRead = chapter.id == lastReadingChapterId,
                            readingProgress = chapterReadingProgress[chapter.id] ?: 0f,
                            onClick = { onClickChapter(chapter.id) },
                            selecting = selectingChapters,
                            selected = chapter.id in selectedChapterIds,
                            selectionEnabled = selectionEnabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: ChapterInformation,
    isRead: Boolean,
    isLastRead: Boolean,
    readingProgress: Float,
    onClick: () -> Unit,
    selecting: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .then(if (selecting) Modifier.toggleable(
                value = selected, enabled = selectionEnabled, role = Role.Checkbox,
                onValueChange = { onClick() },
            ) else Modifier.clickable(onClick = onClick))
            .padding(vertical = 12.dp)
            .padding(start = 32.dp, end = 27.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(
                checked = selected, onCheckedChange = null, enabled = selectionEnabled,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = chapter.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.titleMedium,
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.W600,
                    color = if (isRead) colorScheme.secondary
                    else colorScheme.onSurface
                )
                if (isLastRead) {
                    Text(
                        text = stringResource(R.string.last_read),
                        maxLines = 1,
                        style = typography.titleSmall,
                        fontWeight = FontWeight.Normal,
                        color = colorScheme.primary
                    )
                }
            }
            if (isLastRead) {
                Icon(
                    modifier = Modifier
                        .padding(start = 22.dp)
                        .size(24.dp),
                    painter = painterResource(R.drawable.target_24px),
                    tint = colorScheme.primary,
                    contentDescription = stringResource(R.string.last_read)
                )
            } else if (readingProgress > 0f) {
                // Correct Float rounding at integer percentages without reporting completion early.
                val percent = (readingProgress.coerceIn(0f, 1f) * 100).nextUp().toInt()
                    .coerceAtMost(if (isRead) 100 else 99)
                Text(
                    modifier = Modifier.padding(start = 22.dp),
                    text = "$percent%",
                    maxLines = 1,
                    style = typography.titleSmall,
                    fontWeight = FontWeight.Normal,
                    color = if (isRead) colorScheme.secondary else colorScheme.primary
                )
            }
        }
    }
}
