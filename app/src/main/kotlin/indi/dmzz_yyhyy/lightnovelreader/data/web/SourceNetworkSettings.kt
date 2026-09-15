package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.SourceNetworkMode
import hnovel.network.SourceRouteProvider
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Only the device's per-source route preference; revisions and accounts do not own it. */
@Singleton
class SourceNetworkSettings @Inject constructor(@ApplicationContext context: Context,
    private val networks: AndroidSourceNetworks) {
    private val snapshot = AtomicFile(File(context.filesDir, "source-network-modes.json"))
    private var modes: Set<String>? = null

    private fun key(source: Identifier) = BookIdentity.encode("network", listOf(source.namespace, source.id))

    @Synchronized fun mode(source: Identifier): SourceNetworkMode =
        if (key(source) in read()) SourceNetworkMode.BypassVpn else SourceNetworkMode.SystemDefault

    @Synchronized fun setBypassVpn(source: Identifier, enabled: Boolean) {
        val current = read()
        val next = if (enabled) current + key(source) else current - key(source)
        if (next == current) return
        val output = snapshot.startWrite()
        try {
            output.write(Json.encodeToString(next).toByteArray(Charsets.UTF_8))
            snapshot.finishWrite(output)
        } catch (failure: Exception) { snapshot.failWrite(output); throw failure }
        modes = next
    }

    fun forSource(source: Identifier) = SourceRouteProvider { networks.route(mode(source)) }

    /** Temporary diagnostic/revision identities still execute on the real owner's mode. */
    fun snapshotFor(source: Identifier): SourceRouteProvider {
        val mode = mode(source)
        return SourceRouteProvider { networks.route(mode) }
    }

    private fun read(): Set<String> {
        modes?.let { return it }
        val stored = try {
            snapshot.openRead().use {
                check(it.channel.size() <= 2 * 1024 * 1024) { "Source network preferences exceed quota" }
                Json.decodeFromString<Set<String>>(it.readBytes().toString(Charsets.UTF_8))
            }
        } catch (_: java.io.FileNotFoundException) { emptySet() }
        return stored.also { modes = it }
    }
}
