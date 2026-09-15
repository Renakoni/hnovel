package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.home

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.github.michaelbull.result.map
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery.DiscoveryPageViewModel
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ExploreHomeViewModel @Inject constructor(
    registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    saved: SavedStateHandle,
    private val text: TextProcessingRepository,
) : DiscoveryPageViewModel(registry, accounts, saved, SourceCapability.Explore) {
    override fun feedUpdates(discovery: SourceDiscovery) = discovery.feedUpdates().map { result -> result.map { sections ->
        sections.map { section -> section.copy(books = section.books.map { book ->
            val display = text.processExploreBooksRow(ExploreDisplayBook(
                id = book.id.storageKey, title = book.title, author = book.author, coverUri = Uri.parse(book.coverUrl)))
            book.copy(title = display.title, author = display.author, coverUrl = display.coverUri.toString())
        }) }
    } }

    fun more(section: SourceDiscoverySection): Route.Main.DiscoveryResults? {
        val id = state.value.selected ?: return null
        val content = state.value.content[id] ?: return null
        val target = section.more ?: return null
        if (target.sourceId != id || target.target.isBlank() || section !in content.sections) return null
        return Route.Main.DiscoveryResults(id.namespace, id.id, target.target, section.title,
            UUID.randomUUID().toString(), section.categoryId, Json.encodeToString(content.values))
    }

    fun search(): Route.Main.Explore.SearchHub = Route.Main.Explore.SearchHub

    fun categories(): Route.Main.Categories? = selected(SourceCapability.Categories)?.let {
        Route.Main.Categories(it.namespace, it.id)
    }

    private fun selected(capability: SourceCapability) = state.value.selected?.takeIf { id ->
        state.value.sources.any { it.metadata.id == id && capability in it.metadata.capabilities }
    }
}
