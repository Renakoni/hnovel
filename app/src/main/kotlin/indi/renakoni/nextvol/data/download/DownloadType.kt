package indi.renakoni.nextvol.data.download

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import indi.renakoni.nextvol.R

enum class DownloadType(
    @field:DrawableRes val icon: Int,
    @field:StringRes val typeNameRes: Int
) {
    EPUB_EXPORT(R.drawable.output_24px, R.string.download_type_epub),
    CACHE(R.drawable.downloading_24px, R.string.download_type_cache)
}
