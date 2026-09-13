package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.components.AnimatedText
import indi.dmzz_yyhyy.lightnovelreader.ui.components.BookCardItem
import indi.dmzz_yyhyy.lightnovelreader.ui.components.EmptyPage
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryFailure
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.addToBookshelfAction
import indi.dmzz_yyhyy.lightnovelreader.utils.withHaptic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreSearchScreen(
    exploreSearchUiState: ExploreSearchUiState,
    refresh: () -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickBack: () -> Unit,
    onChangeSearchType: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClickDeleteHistory: (String) -> Unit,
    onClickClearAllHistory: () -> Unit,
    onClickBook: (String) -> Unit,
    updateSuggestions: (keyword: String) -> Unit,
    onManageSources: () -> Unit,
) {
    val searchKeyword = exploreSearchUiState.query
    Scaffold(
        topBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .semantics { isTraversalGroup = true }) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .height(56.dp)) {
                    DropdownMenu(
                        offset = DpOffset((-12).dp, 0.dp),
                        expanded = exploreSearchUiState.dropdownMenuExpanded,
                        onDismissRequest = { exploreSearchUiState.setDropdownMenuExpandedState(false) }) {
                        exploreSearchUiState.searchTypeIdList.forEach {
                            DropdownMenuItem(
                                text = {
                                    exploreSearchUiState.searchTypeNameMap[it]?.let { it1 ->
                                        Text(
                                            text = it1.resolve(),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                },
                                onClick = {
                                    exploreSearchUiState.setDropdownMenuExpandedState(false)
                                    onChangeSearchType(it)
                                }
                            )
                        }
                    }
                }
                SearchBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = if (!exploreSearchUiState.searchBarExpanded) 12.dp else 0.dp)
                        .semantics { traversalIndex = 0f },
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = searchKeyword,
                            onQueryChange = updateSuggestions,
                            onSearch = {
                                exploreSearchUiState.setSearchBarExpandedState(false)
                                onSearch(it)
                            },
                            expanded = exploreSearchUiState.searchBarExpanded,
                            onExpandedChange = exploreSearchUiState::setSearchBarExpandedState,
                            placeholder = { AnimatedText(
                                text = exploreSearchUiState.searchTip.resolve(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ) },
                            leadingIcon = {
                                IconButton(onClick = onClickBack) {
                                    Icon(painter = painterResource(R.drawable.arrow_back_24px), contentDescription = stringResource(R.string.sources_back))
                                }
                            },
                            trailingIcon = {
                                Row {
                                    if (searchKeyword.isNotBlank())
                                        IconButton(onClick = {
                                            exploreSearchUiState.setSearchBarExpandedState(true)
                                            updateSuggestions("")
                                        }) {
                                            Icon(painter = painterResource(R.drawable.close_24px), contentDescription = stringResource(R.string.search_clear_query))
                                        }
                                    if (exploreSearchUiState.searchBarExpanded)
                                        IconButton(onClick = { exploreSearchUiState.setDropdownMenuExpandedState(true) }) {
                                            Icon(painter = painterResource(R.drawable.filter_alt_24px), contentDescription = stringResource(R.string.search_choose_type))
                                        }
                                }
                            },
                        )
                    },
                    expanded = exploreSearchUiState.searchBarExpanded,
                    onExpandedChange = { if (!it) onClickBack.invoke() }
                ) {
                    Text(exploreSearchUiState.sourceName, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge)
                    (exploreSearchUiState.failure ?: exploreSearchUiState.suggestionFailure)?.let { failure ->
                        DiscoveryFailure(failure.error, refresh, onManageSources, onClickBack, failure.field, failure.permission)
                    }
                    val hasHistory = exploreSearchUiState.historyList.isNotEmpty()
                    val showHistory = exploreSearchUiState.suggestions.isEmpty() || searchKeyword.isEmpty()
                    AnimatedVisibility(
                        visible = !hasHistory && showHistory,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        EmptyPage(
                            icon = painterResource(R.drawable.schedule_90dp),
                            title = stringResource(R.string.nothing_here),
                            description = stringResource(R.string.nothing_here_desc_search)
                        )
                    }
                    AnimatedVisibility(
                        visible = hasHistory && showHistory,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            Modifier
                                .padding(vertical = 8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(id = R.string.search_history),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.W600
                                )

                                Box(Modifier.weight(2f))

                                TextButton (
                                    onClick = onClickClearAllHistory,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Transparent,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(0.dp)
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.clear_all),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.W600,
                                    )
                                }
                            }

                            Box(Modifier.height(8.dp))

                            exploreSearchUiState.historyList.forEach { history ->
                                if (history.isEmpty()) return@forEach
                                AnimatedContent(
                                    targetState = history,
                                    label = "HistoryItemAnimation"
                                ) {
                                    Row (
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .padding(horizontal = 16.dp)
                                            .clickable {
                                                exploreSearchUiState.setSearchBarExpandedState(false)
                                                onSearch.invoke(history)
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            modifier = Modifier.padding(start = 8.dp),
                                            text = it,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Box(Modifier.weight(2f))
                                        IconButton(onClick = { onClickDeleteHistory(history) }) {
                                            Icon(
                                                painter = painterResource(R.drawable.close_24px),
                                                contentDescription = stringResource(R.string.search_delete_history),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !showHistory,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(
                            Modifier
                                .padding(vertical = 8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            exploreSearchUiState.suggestions.forEach { history ->
                                if (history.isEmpty()) return@forEach
                                AnimatedContent(
                                    targetState = history,
                                    label = "SuggestionsItemAnimation"
                                ) {
                                    Row (
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .padding(horizontal = 16.dp)
                                            .clickable {
                                                exploreSearchUiState.setSearchBarExpandedState(false)
                                                onSearch.invoke(history)
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            modifier = Modifier.padding(start = 8.dp),
                                            text = it,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(LocalSnackbarHost.current)
        }
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            Text(exploreSearchUiState.sourceName, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge)
            exploreSearchUiState.failure?.let { failure ->
                DiscoveryFailure(failure.error, refresh, onManageSources, onClickBack, failure.field, failure.permission)
            }
            if (exploreSearchUiState.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (exploreSearchUiState.isLoadingComplete && exploreSearchUiState.searchResult.isEmpty() && exploreSearchUiState.failure == null) {
                EmptyPage(
                    icon = painterResource(R.drawable.not_found_90dp),
                    title = stringResource(R.string.search_no_results),
                    description = stringResource(R.string.search_no_results_desc)
                )
            }
            val density = LocalDensity.current
            val lineHeight = MaterialTheme.typography.titleMedium.lineHeight
            val titleHeight = with(density) { (lineHeight * 2.2f).toDp() }
            LazyColumn(Modifier.weight(1f)) {
                if (exploreSearchUiState.submittedKeyword.isNotBlank()) stickyHeader {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 8.dp)) {
                        AnimatedText(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 20.dp),
                            text = stringResource(R.string.search_results_title, exploreSearchUiState.submittedKeyword,
                                exploreSearchUiState.searchResult.size, if (exploreSearchUiState.isLoadingComplete) "" else "..."),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.W600,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(exploreSearchUiState.searchResult, key = { it.first }) {
                    val addToBookshelf = addToBookshelfAction.toSwipeAction { requestAddBookToBookshelf(it.first) }
                    BookCardItem(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(vertical = 3.dp),
                        bookInformationFlow = it.second,
                        onClick = { onClickBook(it.first) },
                        onLongPress = withHaptic {},
                        collected = exploreSearchUiState.allBookshelfBookIds.contains(it.first),
                        swipeToRightActions = listOf(addToBookshelf),
                        titleHeight = titleHeight
                    )
                }
                if (!exploreSearchUiState.isLoadingComplete && exploreSearchUiState.failure == null && exploreSearchUiState.submittedKeyword.isNotBlank()) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 20.dp))
                }
            }
        }
    }
}
