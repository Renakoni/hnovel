package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

data class ExportSettings(
    val selectedVolumeIds: Set<String> = emptySet(),
    val includeImages: Boolean = true,
    val exportType: ExportType = ExportType.BOOK
)

enum class ExportType {
    BOOK,
    VOLUMES
}