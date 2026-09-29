package indi.renakoni.nextvol.data.content

import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import javax.inject.Inject
import javax.inject.Singleton

/** Supported built-in content types, without external registration or component construction. */
@Singleton
class ContentComponentRegistry @Inject constructor() : ComponentDataRegistry {
    override val serializeMap = mapOf(
        SimpleTextComponentData.id.toString() to SimpleTextComponentData.jsonSerializer,
        ImageComponentData.id.toString() to ImageComponentData.jsonSerializer,
    )
    override val dataKClassMap = mapOf(
        SimpleTextComponentData.id.toString() to SimpleTextComponentData::class,
        ImageComponentData.id.toString() to ImageComponentData::class,
    )

    internal fun serializer(id: String) = serializeMap[id]
}
