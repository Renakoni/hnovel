package indi.renakoni.nextvol.ui.book.reader.content

import androidx.compose.runtime.compositionLocalOf

/** Outgoing animated content remains visible, but no longer owns reading interactions. */
internal val LocalReaderRendererActive = compositionLocalOf { true }
