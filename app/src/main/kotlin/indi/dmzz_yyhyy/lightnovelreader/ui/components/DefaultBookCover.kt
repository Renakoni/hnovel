package indi.dmzz_yyhyy.lightnovelreader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.utils.DefaultBookCoverRenderer

@Composable
fun DefaultBookCover(title: String, width: Dp, height: Dp, bookId: String = "", author: String = "") {
    val context = LocalContext.current
    val displayTitle = title.trim().ifBlank { stringResource(R.string.cover_untitled) }
    val description = stringResource(R.string.cover_description, displayTitle)
    val artwork = remember(displayTitle, author, bookId) {
        DefaultBookCoverRenderer.Artwork(context, DefaultBookCoverRenderer.Text(bookId, displayTitle, author))
    }
    // This is an image. Its text scales with its bounds; the complete title remains accessible.
    Canvas(Modifier.size(width, height).semantics { contentDescription = description }) {
        artwork.draw(drawContext.canvas.nativeCanvas, size.width, size.height)
    }
}
