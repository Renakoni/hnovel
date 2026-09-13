package indi.dmzz_yyhyy.lightnovelreader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.utils.DefaultBookCoverRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DefaultBookCoverInstrumentedTest {
    @Test fun sharedArtworkMatchesEnlargedPngAndRemainsBoundedAcrossSizesAndLocales() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = listOf("长夜将明" to "林舟", "The Quiet Library" to "Alex Chen",
            "穿越群星之后我们终于在世界尽头找到那座失落的图书馆" to "远行的人",
            "A Very Long Journey Through the Forgotten Cities Beyond the Sea" to "A. Reader", "" to "")
        val sheet = createBitmap(1400, 1000)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(243, 240, 233))
        canvas.drawRect(0f, 520f, 1400f, 1000f, Paint().apply { color = Color.rgb(25, 26, 30) })
        for ((index, sample) in samples.withIndex()) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(if (index == 4) Locale.US else Locale.SIMPLIFIED_CHINESE)
                fontScale = if (index % 2 == 0) 2f else 1.3f
            })
            val id = "sample-$index"
            val bitmap = DefaultBookCoverRenderer.render(context, sample.first, bookId = id, author = sample.second)
            val uri = DefaultBookCoverRenderer.cacheUri(context, sample.first, id, sample.second)
            val cached = BitmapFactory.decodeFile(uri.path)
            assertTrue(bitmap.sameAs(cached))
            val direct = createBitmap(600, 870)
            DefaultBookCoverRenderer.Artwork(context, DefaultBookCoverRenderer.Text(id, sample.first, sample.second))
                .draw(Canvas(direct), 600f, 870f)
            assertTrue(bitmap.sameAs(direct))
            val left = index * 280 + 20
            canvas.drawBitmap(bitmap, null, Rect(left, 25, left + 240, 373), null)
            canvas.drawBitmap(bitmap, null, Rect(left, 555, left + 120, 729), null)
            canvas.drawBitmap(bitmap, null, Rect(left + 154, 555, left + 226, 659), null)
            bitmap.recycle(); cached.recycle(); direct.recycle()
        }
        val output = File(app.getExternalFilesDir(null), "default-cover-samples.png")
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }
}
