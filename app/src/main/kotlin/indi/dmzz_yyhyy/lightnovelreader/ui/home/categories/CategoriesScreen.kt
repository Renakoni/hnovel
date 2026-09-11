package indi.dmzz_yyhyy.lightnovelreader.ui.home.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceDiscoveryCategory
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.HomeSettingsAction
import indi.dmzz_yyhyy.lightnovelreader.utils.bottomBarSpacer
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    state: DiscoveryPageState,
    onSelect: (Identifier) -> Unit,
    onCategory: (SourceDiscoveryCategory) -> Unit,
    onScroll: (Identifier, DiscoveryScroll) -> Unit,
    onRefresh: () -> Unit,
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onInput: (String, String) -> Unit = { _, _ -> },
    onAction: (String, Boolean) -> Unit = { _, _ -> },
) {
    Scaffold(topBar = { CategoriesTopBar(onRefresh, onSettings) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loadingSources) {
                // No inventory snapshot yet is not an authoritative empty-source result.
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (state.sources.isEmpty()) {
                DiscoveryEmpty(stringResource(R.string.categories_no_sources), onManageSources)
            } else {
                ScrollableTabRow(selectedTabIndex = state.sources.indexOfFirst { it.metadata.id == state.selected }.coerceAtLeast(0)) {
                    state.sources.forEach { source ->
                        Tab(selected = source.metadata.id == state.selected,
                            onClick = { onSelect(source.metadata.id) }, text = { Text(source.metadata.item.name) })
                    }
                }
                val id = state.selected
                val content = state.content[id] ?: DiscoveryPageContent()
                if (content.loading || content.acting) LinearProgressIndicator(Modifier.fillMaxWidth())
                content.error?.let { DiscoveryFailure(it, onRefresh, onManageSources, onBack, content.errorField) }
                if (id != null) key(id, content.resetId) {
                    val list = rememberLazyListState(content.scroll.index, content.scroll.offset)
                    LaunchedEffect(list) {
                        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                            .collect { onScroll(id, it) }
                    }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(content.filters, key = { "input:${it.id}" }) { filter ->
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) { if (!content.acting) onInput(filter.id, it) }
                            }
                        }
                        items(content.buttons, key = { "action:${it.id}" }) { button ->
                            ListItem(headlineContent = { Text(if (button.id == "custom-button") stringResource(R.string.discovery_source_action) else button.title) }, modifier = Modifier.combinedClickable(
                                enabled = !content.acting, onClick = { onAction(button.id, false) }, onLongClick = { onAction(button.id, true) }))
                        }
                        if (content.loaded && content.categories.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty()) item {
                            DiscoveryEmpty(stringResource(R.string.categories_empty), onManageSources)
                        }
                        items(content.categories, key = { it.id }) { category ->
                            ListItem(headlineContent = { Text(category.title) },
                                modifier = Modifier.clickable(enabled = category.target.target.isNotBlank()) { onCategory(category) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                        bottomBarSpacer()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesTopBar(onRefresh: () -> Unit, onSettings: () -> Unit) {
    MediumTopAppBar(title = { Text(stringResource(R.string.categories_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { Icon(painterResource(R.drawable.view_list_24px), null, Modifier.padding(12.dp)) },
        actions = {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.discovery_refresh)) }
            HomeSettingsAction(onSettings)
        }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
}
