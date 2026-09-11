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
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceDiscoveryCategory
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.*
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    state: CategoriesState,
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
    Scaffold(topBar = { DiscoveryTopBar(stringResource(R.string.categories_title), onBack, onRefresh, onSettings) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.sources.isEmpty()) {
                DiscoveryEmpty(stringResource(R.string.categories_no_sources), onManageSources)
            } else {
                ScrollableTabRow(selectedTabIndex = state.sources.indexOfFirst { it.metadata.id == state.selected }.coerceAtLeast(0)) {
                    state.sources.forEach { source ->
                        Tab(selected = source.metadata.id == state.selected,
                            onClick = { onSelect(source.metadata.id) }, text = { Text(source.metadata.item.name) })
                    }
                }
                val id = state.selected
                val content = state.content[id] ?: CategoryContent()
                if (content.loading || content.acting) LinearProgressIndicator(Modifier.fillMaxWidth())
                content.error?.let { DiscoveryFailure(it, onRefresh, onManageSources, onBack, content.errorField) }
                if (id != null) key(id) {
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
                    }
                }
            }
        }
    }
}
