package indi.renakoni.nextvol.data.download

import androidx.annotation.DrawableRes
import indi.renakoni.nextvol.R

enum class DownloadType(
    @field:DrawableRes val icon: Int,
    val typeName: String
) {
    EPUB_EXPORT(R.drawable.output_24px, "导出为EPUB"),
    CACHE(R.drawable.downloading_24px, "下载")
}
