package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderChapterLoader
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.mode.CachedChapterReaderContractTest

class FlipCachedChapterTest : CachedChapterReaderContractTest() {
    override fun controller(loader: ReaderChapterLoader) = FlipReaderController(
        loader, env.records, env.scope, { _, _ -> }, ioDispatcher = env.dispatcher,
    )
}
