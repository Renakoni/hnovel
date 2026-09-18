package indi.renakoni.nextvol.ui.home.categories

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceDiscoveryCategory
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
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
    val content = state.content[state.selected] ?: DiscoveryPageContent()
    Scaffold(topBar = { CategoriesTopBar(onRefresh, onSettings,
        loading = !state.loadingSources && (content.loading || content.acting)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loadingSources) {
                // No inventory snapshot yet is not an authoritative empty-source result.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.sources.isEmpty()) {
                DiscoveryEmpty(stringResource(R.string.categories_no_sources), onManageSources)
            } else {
                PrimaryScrollableTabRow(
                    selectedTabIndex = state.sources.indexOfFirst { it.metadata.id == state.selected }.coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 0.dp,
                    divider = {},
                ) {
                    state.sources.forEach { source ->
                        Tab(selected = source.metadata.id == state.selected,
                            onClick = { onSelect(source.metadata.id) },
                            text = { Text(source.metadata.item.name, Modifier.widthIn(max = 208.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                val id = state.selected
                content.error?.let { DiscoveryFailure(it, onRefresh, onManageSources, onBack, content.errorField, content.errorPermission) }
                if (id != null) key(id, content.resetId) {
                    val list = rememberLazyListState(content.scroll.index, content.scroll.offset)
                    LaunchedEffect(list) {
                        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                            .collect { onScroll(id, it) }
                    }
                    if (content.buttons.isNotEmpty()) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(content.buttons, key = { it.id }) { button ->
                                Surface(shape = MaterialTheme.shapes.medium, color = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.primary.copy(alpha = if (content.acting || content.loading) 0.38f else 1f),
                                    modifier = Modifier.heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium).combinedClickable(
                                        enabled = !content.acting && !content.loading, role = Role.Button,
                                        onClick = { onAction(button.id, false) }, onLongClick = { onAction(button.id, true) })) {
                                    Text(if (button.id == "custom-button") stringResource(R.string.discovery_source_action) else button.title,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    // Keep bottom clearance out of the item keys so delayed content starts at the top.
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                        items(content.filters, key = { "input:${it.id}" }) { filter ->
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) { if (!content.acting) onInput(filter.id, it) }
                            }
                        }
                        if (content.loaded && content.categories.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty()) item {
                            DiscoveryEmpty(stringResource(R.string.categories_empty), onManageSources)
                        }
                        if (content.categories.isNotEmpty()) item(key = "category-tags") {
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                content.categories.forEach { category -> key(category.id) {
                                    if (category.target.target.isBlank()) {
                                        Text(category.title, style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { heading() })
                                    } else {
                                        SuggestionChip(onClick = { onCategory(category) }, shape = RoundedCornerShape(50),
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                            label = { Text(category.title.ifBlank { stringResource(R.string.discovery_unnamed_entry) },
                                                style = MaterialTheme.typography.bodyMedium) })
                                    }
                                } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesTopBar(onRefresh: () -> Unit, onSettings: () -> Unit, loading: Boolean = false) {
    val refreshLabel = stringResource(R.string.discovery_refresh)
    TopAppBar(title = {}, expandedHeight = 56.dp,
        navigationIcon = { Icon(painterResource(R.drawable.view_list_24px), stringResource(R.string.categories_title), Modifier.padding(12.dp)) },
        actions = {
            IconButton(onClick = onRefresh) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = refreshLabel }, strokeWidth = 2.dp)
                } else {
                    Icon(painterResource(R.drawable.refresh_24px), refreshLabel)
                }
            }
            HomeSettingsAction(onSettings)
        }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
}
