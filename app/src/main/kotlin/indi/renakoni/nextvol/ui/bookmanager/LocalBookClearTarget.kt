package indi.renakoni.nextvol.ui.bookmanager

import androidx.annotation.StringRes
import indi.renakoni.nextvol.R

enum class LocalBookClearTarget(
    @param:StringRes val label: Int
) {
    VolumeAndChapterIndex(R.string.local_book_clear_index),
    ChapterContent(R.string.local_book_clear_content),
    ReadingRecord(R.string.local_book_clear_reading)
}
