package io.nightfish.lightnovelreader.api.web.explore.filter

import io.nightfish.lightnovelreader.api.R
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.util.LocalString

/**
 * 字数限制过滤器
 * 用于过滤字数不足特定阈値的书本
 * 属于[LocalFilter]，支持本地过滤
 *
 * @since Api 2
 */
class WordCountFilter : SliderFilter(
    title = LocalString(R.string.filter_word_limit),
    // Preserve the legacy String API; the host uses the localized presentation methods below.
    description = "\u4ec5\u663e\u793a\u5b57\u6570\u5927\u4e8e\u8be5\u5024\u7684\u4e66\u672c\uff0c\u82e5\u4e3a0\u5219\u663e\u793a\u5168\u90e8\u4e66\u672c\u3002",
    defaultValue = 0f,
    valueRange = 0f..200_0000f,
    steps = 9
), LocalFilter {
    override var enabled: Boolean
        get() = value != 0f
        set(value) { if (!value) this.value = 0f }
    override val displayValue: String
        get() = if (value == 0f) "\u65e0\u9650\u5236" else "${(value / 1000).toInt()}K"

    override val displayTitle = LocalString(R.string.filter_word_count)
    override fun getDescriptionText() = LocalString(R.string.filter_word_limit_description)
    override fun getValueText(): LocalString =
        if (value == 0f) LocalString(R.string.filter_no_limit) else LocalString(displayValue)
    override fun filter(bookInformation: BookInformation): Boolean =
        !enabled || bookInformation.wordCount.count >= value
}
