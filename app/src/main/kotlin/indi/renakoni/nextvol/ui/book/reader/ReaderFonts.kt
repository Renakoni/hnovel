package indi.renakoni.nextvol.ui.book.reader

import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import indi.renakoni.nextvol.R

/** Stable selections share the existing font URI key, preserving imported-font preferences. */
internal enum class ReaderFont(val uri: Uri, val title: Int, val description: Int, val resource: Int?) {
    System(Uri.EMPTY, R.string.reader_font_system, R.string.reader_font_system_description, null),
    SourceHanSerif(Uri.parse("reader-font:source-han-serif"), R.string.reader_font_serif, R.string.reader_font_serif_description, R.font.source_han_serif_regular),
    WenKai(Uri.parse("reader-font:lxgw-wenkai"), R.string.reader_font_wenkai, R.string.reader_font_wenkai_description, R.font.lxgw_wenkai_regular);

    fun family(): FontFamily = resource?.let { FontFamily(Font(it)) } ?: FontFamily.Default
}
