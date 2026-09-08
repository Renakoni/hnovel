package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalAppTheme
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.navigateToImageViewerDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.components.ZoomableImage
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi

@Composable
internal fun ReaderTextContent(text: String, fontFamilyUriUserData: UriUserData, modifier: Modifier) {
    val combinedStyle = LocalReaderStyle.current
    SimpleTextComponentContent(
        modifier = modifier,
        text = text,
        fontSize = combinedStyle.fontSize.sp,
        fontLineHeight = combinedStyle.fontLineHeight.sp,
        fontWeight = FontWeight(combinedStyle.fontWeight.toInt()),
        fontFamily = rememberReaderFontFamily(fontFamilyUriUserData),
        color = readerTextColor(combinedStyle.textColor, combinedStyle.textDarkColor)
    )
}

@Composable
private fun readerTextColor(textColor: Color, textDarkColor: Color): Color {
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val onSurface = localTheme.colorScheme.onSurface

    val color = remember(isDark, textColor, textDarkColor, onSurface) {
        when {
            isDark && textDarkColor.isUnspecified -> onSurface
            !isDark && textColor.isUnspecified -> onSurface
            isDark -> textDarkColor
            else -> textColor
        }
    }

    return color
}

@Composable
internal fun ReaderImageContent(uri: Uri, webBookDataSourceManagerApi: WebBookDataSourceManagerApi, modifier: Modifier) {
    val imageHeader = remember(webBookDataSourceManagerApi.getWebDataSource()) { webBookDataSourceManagerApi.getWebDataSource().imageHeader }
    val navController = LocalNavController.current
    ZoomableImage(
        imageUri = uri,
        modifier = modifier.fillMaxSize(),
        onViewImage = {
            navController.navigateToImageViewerDialog(uri)
        },
        header = imageHeader
    )
}

@Composable
internal fun ReaderErrorContent(message: String) {
    Column {
        Text("ERROR")
        Text(message)
    }
}
