package indi.renakoni.nextvol.pluginfixture

import android.content.Context
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.ApiMetadata
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.plugin.PluginContext
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File

@Plugin(name = "Installation fixture", version = 1, versionName = "1", author = "Test",
    description = "Device regression fixture", updateUrl = "", apiVersion = ApiMetadata.API_VERSION)
class InstallationFixturePlugin(private val context: PluginContext) : LightNovelReaderPlugin {
    override fun onLoad() {
        context.dataDir.mkdirs()
        context.dataDir.resolve("lifecycle").appendText("load\n")
    }
    override fun onUnload() {
        context.dataDir.resolve("lifecycle").appendText("unload\n")
    }
}

@WebDataSource(name = "Installation fixture source", provider = "Test")
class InstallationFixtureSource(context: Context) : WebBookDataSource {
    init {
        check(!File(context.cacheDir, "plugin-fixture-reject-source").exists()) { "Fixture registration failure" }
    }
    override val id = Identifier("installation-test", "fixture")
    override val offLine = false
    override val isOffLineFlow = MutableStateFlow(false)
    override suspend fun isOffLine() = false
    override val searchProvider: SearchProvider get() = error("Not used by this fixture")
    override val explorePageProvider: ExplorePageProvider get() = error("Not used by this fixture")
    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> = error("Not used by this fixture")
    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> = error("Not used by this fixture")
    override suspend fun getChapterContent(chapterId: String, bookId: String) =
        Ok(ChapterContent(chapterId, "Fixture chapter", JsonObject(emptyMap())))
}
