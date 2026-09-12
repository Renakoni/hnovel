package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.components.Cover
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
import indi.dmzz_yyhyy.lightnovelreader.utils.bottomBarSpacer
import indi.dmzz_yyhyy.lightnovelreader.utils.fadingEdge
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreHomeScreen(
    state: DiscoveryPageState,
    onSelect: (Identifier) -> Unit,
    onScroll: (Identifier, DiscoveryScroll) -> Unit,
    onRefresh: () -> Unit,
    onMore: (SourceDiscoverySection) -> Unit,
    onBook: (SourceBookId) -> Unit,
    onSearch: () -> Unit,
    onCategories: () -> Unit,
    onManageSources: () -> Unit,
    onInput: (String, String) -> Unit,
    onAction: (String, Boolean) -> Unit,
) {
    val selected = state.sources.firstOrNull { it.metadata.id == state.selected }
    val capabilities = selected?.metadata?.capabilities.orEmpty()
    Scaffold(topBar = {
        MediumTopAppBar(
            title = { Text(stringResource(R.string.nav_explore), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { Icon(painterResource(R.drawable.outline_explore_24px), null, Modifier.padding(12.dp)) },
            actions = {
                if (SourceCapability.Categories in capabilities)
                    TextButton(onClick = onCategories) { Text(stringResource(R.string.categories_title)) }
                IconButton(onClick = onSearch, enabled = SourceCapability.Search in capabilities) {
                    Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loadingSources) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (state.sources.isEmpty()) {
                DiscoveryEmpty(stringResource(R.string.explore_no_sources), onManageSources)
            } else {
                ScrollableTabRow(selectedTabIndex = state.sources.indexOfFirst { it.metadata.id == state.selected }.coerceAtLeast(0)) {
                    state.sources.forEach { source ->
                        Tab(selected = source.metadata.id == state.selected, onClick = { onSelect(source.metadata.id) },
                            text = { Text(source.metadata.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                val id = state.selected
                val content = state.content[id] ?: DiscoveryPageContent()
                if (content.acting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (id != null) key(id, content.resetId) {
                    val list = rememberLazyListState(content.scroll.index, content.scroll.offset)
                    LaunchedEffect(list) {
                        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                            .collect { onScroll(id, it) }
                    }
                    val titleHeight = with(LocalDensity.current) { (16.sp * 2.2f).toDp() }
                    PullToRefreshBox(isRefreshing = content.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            content.error?.let { error -> item(key = "error") {
                                DiscoveryFailure(error, onRefresh, onManageSources, back = null, field = content.errorField)
                            } }
                            items(content.filters, key = { "input:" + it.id }) { filter ->
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) {
                                        if (!content.acting && !content.loading) onInput(filter.id, it)
                                    }
                                }
                            }
                            items(content.buttons, key = { "action:" + it.id }) { button ->
                                ListItem(headlineContent = { Text(if (button.id == "custom-button")
                                    stringResource(R.string.discovery_source_action) else button.title) },
                                    modifier = Modifier.combinedClickable(enabled = !content.acting && !content.loading,
                                        onClick = { onAction(button.id, false) }, onLongClick = { onAction(button.id, true) }))
                            }
                            if (content.loaded && content.sections.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty())
                                item { DiscoveryEmpty(stringResource(R.string.explore_empty), onManageSources) }
                            items(content.sections, key = { "section:" + it.id }) { section ->
                                ExploreRowSection(Modifier, section, titleHeight, onMore, onBook)
                            }
                            bottomBarSpacer()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreRowSection(
    modifier: Modifier,
    row: SourceDiscoverySection,
    titleHeight: androidx.compose.ui.unit.Dp,
    onClickExpand: (SourceDiscoverySection) -> Unit,
    onClickBook: (SourceBookId) -> Unit
) {
    Column(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(2f),
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (row.more != null) {
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        onClickExpand(row)
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_forward_24px),
                        contentDescription = stringResource(R.string.explore_more)
                    )
                }
            }
        }

        val lazyRowState = rememberLazyListState()
        val validBooks = remember(row.books) {
            row.books.filter { it.id.remoteId.isNotBlank() }.distinctBy { it.id }
        }

        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .fadingEdge(
                        Brush.horizontalGradient(
                            0.01f to Color.Transparent,
                            0.03f to Color.White,
                            0.97f to Color.White,
                            0.99f to Color.Transparent
                        )
                )
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                state = lazyRowState,
                flingBehavior = rememberSnapFlingBehavior(lazyRowState)
            ) {
                item {
                    Box(modifier = Modifier.width(10.dp))
                }

                items(
                    items = validBooks,
                    key = { it.id.storageKey }
                ) { exploreDisplayBook ->
                    ExploreBookCard(
                        book = exploreDisplayBook,
                        titleHeight = titleHeight,
                        onClickBook = onClickBook
                    )
                }

                item {
                    Box(modifier = Modifier.width(12.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            HorizontalDivider()
        }
    }
}

@Composable
private fun ExploreBookCard(
    book: SourceDiscoveryBook,
    titleHeight: androidx.compose.ui.unit.Dp,
    onClickBook: (SourceBookId) -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClickBook(book.id) }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Cover(
                bookId = book.id.storageKey,
                width = 98.dp,
                height = 138.dp,
                uri = Uri.parse(book.coverUrl),
                title = book.title,
                rounded = 6.dp
            )
        }
        Column(
            modifier = Modifier
                .width(100.dp)
                .padding(horizontal = 2.dp)
                .padding(top = 8.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                modifier = Modifier
                    .height(titleHeight)
                    .wrapContentHeight(Alignment.Top),
                text = book.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    letterSpacing = 0.5.sp
                ),
                fontWeight = FontWeight.W500,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (book.author.isNotEmpty()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
