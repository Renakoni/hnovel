package indi.dmzz_yyhyy.lightnovelreader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AtomicFile
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.R
import java.io.File
import java.security.MessageDigest

/** Local artwork shared by thumbnails, the image viewer and EPUB; never a cached remote response. */
object DefaultBookCoverRenderer {
    const val DEFAULT_WIDTH = 600
    const val DEFAULT_HEIGHT = 870
    private const val STYLE_VERSION = "v2"
    data class Text(val bookId: String, val title: String, val author: String = "")

    // Fixed ink/paper pairs remain legible in both app themes and in exported files.
    private val palettes = listOf(
        intArrayOf(0xFF243D43.toInt(), 0xFFF4EBDC.toInt(), 0xFFCBAA74.toInt()),
        intArrayOf(0xFF2D374F.toInt(), 0xFFEAE6DE.toInt(), 0xFFBBA277.toInt()),
        intArrayOf(0xFF493A45.toInt(), 0xFFF5ECE2.toInt(), 0xFFD5AF95.toInt()),
        intArrayOf(0xFF334738.toInt(), 0xFFF0EFDC.toInt(), 0xFFB5BE87.toInt()),
        intArrayOf(0xFF663F36.toInt(), 0xFFF4E7D6.toInt(), 0xFFD5B68A.toInt())
    )

    fun displayTitle(context: Context, title: String) = title.trim().ifBlank { context.getString(R.string.cover_untitled) }

    /** Layout is constructed once per input, then scaled as artwork, not as UI text in sp. */
    class Artwork(context: Context, text: Text) {
        private val title = displayTitle(context, text.title).take(512)
        private val author = text.author.trim().take(256)
        private val colors = palettes[(digest(text.bookId.ifBlank { "$title\u0000$author" })[0].toInt() and 255) % palettes.size]
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titleLayout = layout(title, if (title.length <= 6) 86f else if (title.length <= 20) 72f else 60f, 4, true)
        private val authorLayout = layout(author, 30f, 2, false)

        private fun layout(value: String, size: Float, lines: Int, bold: Boolean): StaticLayout {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors[1]
                textSize = size
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            return StaticLayout.Builder.obtain(value, 0, value.length, textPaint, 448)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(8f, 1f).build()
        }

        fun draw(canvas: Canvas, width: Float, height: Float) = canvas.withSave {
            scale(width / DEFAULT_WIDTH, height / DEFAULT_HEIGHT)
            clipRect(0f, 0f, DEFAULT_WIDTH.toFloat(), DEFAULT_HEIGHT.toFloat())
            drawColor(colors[0])
            paint.style = Paint.Style.FILL
            paint.color = colors[2]
            paint.alpha = 40
            drawRect(0f, 0f, 20f, 870f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.alpha = 130
            drawRect(40f, 38f, 562f, 832f, paint)
            paint.alpha = 40
            drawCircle(556f, 822f, 207f, paint)
            drawCircle(556f, 822f, 231f, paint)
            paint.alpha = 255
            paint.strokeWidth = 4f
            drawLine(76f, 150f, 142f, 150f, paint)
            withTranslation(76f, 218f) { titleLayout.draw(this) }
            if (author.isNotEmpty()) withTranslation(76f, 586f) { authorLayout.draw(this) }
            // A small open-book mark, also drawn locally in the exported artwork.
            paint.strokeWidth = 3f
            drawLine(76f, 739f, 111f, 748f, paint)
            drawLine(111f, 748f, 146f, 739f, paint)
            drawLine(76f, 739f, 76f, 779f, paint)
            drawLine(146f, 739f, 146f, 779f, paint)
            drawLine(76f, 779f, 111f, 788f, paint)
            drawLine(111f, 788f, 146f, 779f, paint)
            drawLine(111f, 748f, 111f, 788f, paint)
        }
    }

    fun render(context: Context, title: String, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT,
        bookId: String = "", author: String = ""): Bitmap = createBitmap(width, height).also {
        Artwork(context, Text(bookId, title, author)).draw(Canvas(it), width.toFloat(), height.toFloat())
    }

    fun writeTo(context: Context, file: File, title: String, bookId: String = "", author: String = "") {
        file.parentFile?.mkdirs()
        val bitmap = render(context, title, bookId = bookId, author = author)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            val format = if (file.extension.equals("png", true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            check(bitmap.compress(format, 100, output))
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        finally { bitmap.recycle() }
    }

    @Synchronized
    fun cacheUri(context: Context, title: String, bookId: String = "", author: String = ""): Uri {
        // Length framing includes all actual artwork inputs; FB/Ea and changed authors cannot collide.
        val fields = listOf(STYLE_VERSION, bookId, displayTitle(context, title).take(512), author.trim().take(256),
            DEFAULT_WIDTH.toString(), DEFAULT_HEIGHT.toString())
        val key = digest(fields.joinToString("") { "${it.length}:$it" }).joinToString("") { "%02x".format(it) }
        val file = File(File(context.cacheDir, "default_book_covers"), "${STYLE_VERSION}_$key.png")
        if (!file.exists() || file.length() == 0L) writeTo(context, file, title, bookId, author)
        return file.toUri()
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
