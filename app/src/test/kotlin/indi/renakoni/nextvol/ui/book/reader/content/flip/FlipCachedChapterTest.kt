package indi.renakoni.nextvol.ui.book.reader.content.flip

import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.mode.CachedChapterReaderContractTest

class FlipCachedChapterTest : CachedChapterReaderContractTest() {
    override fun controller(loader: ReaderChapterLoader) = FlipReaderController(
        loader, env.records, env.scope, { _, _ -> }, ioDispatcher = env.dispatcher,
    )
}
