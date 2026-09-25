package hnovel.content

import hnovel.network.BrokerRequest
import hnovel.network.SourceNetworkRoute
import kotlinx.serialization.json.JsonObject

/** Short handoff from successful homepage previews to a resumed feed or its full list. */
internal class DiscoveryPreviewDocuments {
    data class Key(val request: BrokerRequest, val route: SourceNetworkRoute, val cookies: List<String>,
        val headers: Map<String, String>, val environment: JsonObject?)
    data class Entry(val document: PageDocument, val userAgent: String?, val savedAt: Long)
    private val entries = LinkedHashMap<Key, Entry>()

    @Synchronized fun get(key: Key, now: Long = System.nanoTime()): Entry? = entries[key]?.let {
        if (key.route.available && now - it.savedAt < 60_000_000_000L) it
        else { entries.remove(key); null }
    }

    @Synchronized fun put(key: Key, document: PageDocument, userAgent: String?, now: Long = System.nanoTime()) {
        if (document.body.length > 256 * 1024 || !key.route.available) return
        entries.remove(key)
        if (entries.size == 4) entries.remove(entries.keys.first())
        entries[key] = Entry(document, userAgent, now)
    }

    @Synchronized fun clear() = entries.clear()
}
