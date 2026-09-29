package indi.renakoni.nextvol.ui.book.detail

import indi.renakoni.nextvol.data.export.ExportType

data class ExportSettings(
    val selectedVolumeIds: Set<String> = emptySet(),
    val includeImages: Boolean = true,
    val exportType: ExportType = ExportType.BOOK
)
