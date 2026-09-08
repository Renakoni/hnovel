package indi.dmzz_yyhyy.lightnovelreader.data.content

import io.nightfish.lightnovelreader.api.content.ContentComponentRepositoryApi
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the plugin registration API stable; host consumers use their own content capabilities. */
@Singleton
class ContentComponentRepository @Inject constructor(
    private val registry: ContentComponentRegistry,
) : ContentComponentRepositoryApi {
    override val registrar get() = registry.registrar
}
