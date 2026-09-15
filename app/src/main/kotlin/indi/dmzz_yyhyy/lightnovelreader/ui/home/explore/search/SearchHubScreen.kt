package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.ui.components.BookCardItem
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHubScreen(
    state: SearchHubState, onQuery: (String) -> Unit, onSearch: (String) -> Unit,
    onSelect: (Identifier?) -> Unit, onHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit, onClearHistory: () -> Unit,
    onOpenSource: (Identifier, String) -> Unit, onBook: (String) -> Unit, onBack: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Search") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchBar(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), query = state.query,
                onQueryChange = onQuery, onSearch = { onSearch(it) }, active = false, onActiveChange = {}) {}
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(state.aggregate, { onSelect(null) }, label = { Text("All sources") }) }
                items(state.sources, key = { it.id.toString() }) { source ->
                    FilterChip(state.selected == source.id, { onSelect(source.id) }, label = { Text(source.name) })
                }
            }
            if (state.query.isBlank() && state.history.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Search history"); TextButton(onClick = onClearHistory) { Text("Clear") }
                }
                state.history.forEach { value ->
                    Row(Modifier.fillMaxWidth().clickable { onHistory(value) }.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(value); TextButton(onClick = { onDeleteHistory(value) }) { Text("Delete") }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.sources.filter { state.aggregate || it.id == state.selected }, key = { it.id.toString() }) { source ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable { onOpenSource(source.id, state.submittedKeyword) }.padding(horizontal = 16.dp, vertical = 8.dp))
                        if (source.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (source.error) Text("Search failed", Modifier.padding(horizontal = 16.dp))
                        if (source.books.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(source.books, key = { it.id }) { book ->
                                BookCardItem(modifier = Modifier.width(180.dp), bookInformationFlow = book.information,
                                    onClick = { onBook(book.id) }, onLongPress = {}, titleHeight = 56.dp)
                            }
                        }
                        if (source.searched && source.books.isEmpty() && !source.error) Text("No results", Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
