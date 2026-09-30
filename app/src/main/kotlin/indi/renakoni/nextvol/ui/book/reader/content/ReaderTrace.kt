package indi.renakoni.nextvol.ui.book.reader.content

import android.os.Trace

/** Synchronous work only: a trace section must end on the thread that started it. */
internal inline fun <T> readerTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}
