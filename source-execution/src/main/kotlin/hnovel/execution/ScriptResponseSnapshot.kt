package hnovel.execution

import hnovel.network.BrokerResponse
import kotlinx.serialization.json.*

/** Shared bounded-data shape for network bridges and host-fetched login hooks. */
fun BrokerResponse.scriptSnapshot(binary: Boolean) = buildJsonObject {
    if (binary) put("bytes", java.util.Base64.getEncoder().encodeToString(this@scriptSnapshot.body))
    else {
        put("body", this@scriptSnapshot.text())
        put("bodySize", this@scriptSnapshot.body.size)
    }
    put("url", this@scriptSnapshot.finalUrl)
    put("status", this@scriptSnapshot.status)
    put("message", this@scriptSnapshot.message)
    put("headers", JsonObject(this@scriptSnapshot.headers.mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }))
    put("charset", this@scriptSnapshot.declaredCharset?.let(::JsonPrimitive) ?: JsonNull)
    put("method", this@scriptSnapshot.method)
    put("protocol", this@scriptSnapshot.protocol)
    put("sentAt", this@scriptSnapshot.sentAt)
    put("receivedAt", this@scriptSnapshot.receivedAt)
}
