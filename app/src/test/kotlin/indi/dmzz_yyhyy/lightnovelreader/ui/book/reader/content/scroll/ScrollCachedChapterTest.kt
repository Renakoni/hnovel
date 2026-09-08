package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode.CachedChapterReaderContractTest
import kotlinx.coroutines.flow.flowOf

class ScrollCachedChapterTest : CachedChapterReaderContractTest() {
    override fun controller(loader: ReaderChapterLoader) = ScrollReaderController(
        loader, env.records, env.scope,
        object : ContinuousScrollSettings {
            override fun getFlow() = flowOf(false)
            override suspend fun isEnabled() = false
        },
        { _, _ -> }, ioDispatcher = env.dispatcher, mainDispatcher = env.dispatcher,
    )
}
