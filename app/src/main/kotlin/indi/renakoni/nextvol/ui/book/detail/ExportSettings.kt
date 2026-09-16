package indi.renakoni.nextvol.ui.book.detail

data class ExportSettings(
    val selectedVolumeIds: Set<String> = emptySet(),
    val includeImages: Boolean = true,
    val exportType: ExportType = ExportType.BOOK
)

enum class ExportType {
    BOOK,
    VOLUMES
}