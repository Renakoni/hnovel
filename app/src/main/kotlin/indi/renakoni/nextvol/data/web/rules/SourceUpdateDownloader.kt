package indi.renakoni.nextvol.data.web.rules

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.imports.*
import hnovel.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID
import javax.inject.Inject

/** Update downloads have disposable storage and no access to a source's account or cookies. */
open class SourceUpdateDownloader @Inject constructor(@ApplicationContext private val context: Context) {
    open suspend fun preview(importer: SourceDefinitionImporter, address: String, profile: String,
        downloadGrant: NetworkGrant? = null): ImportPreview = withContext(Dispatchers.IO) {
        val parent = File(context.cacheDir, "source-update-downloads").apply { mkdirs() }
        val root = File(parent, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            SourceBroker(root.toPath(), limits = BrokerLimits(maxResponseBytes = ImportLimits().maxBytes)).use { broker ->
                val grant = downloadGrant ?: NetworkGrant(origin(address))
                require(origin(grant.origin) == origin(address) && grant.headers.isEmpty())
                val session = broker.open(SourceScope("update-download", UUID.randomUUID().toString(), profile), listOf(grant))
                importer.previewUrl(address, session, profile)
            }
        } finally { root.deleteRecursively() }
    }

    private fun origin(address: String): String {
        val uri = URI(address)
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/", null, null).toString()
    }
}
