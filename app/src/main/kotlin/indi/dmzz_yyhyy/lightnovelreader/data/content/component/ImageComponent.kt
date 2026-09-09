package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderImageContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData

class ImageComponent(
    data: ImageComponentData
): AbstractContentComponent<ImageComponentData>(data) {
    override val id = ImageComponentData.id

    @Composable
    override fun Content(modifier: Modifier) {
        ReaderImageContent(data.uri, modifier)
    }
}
