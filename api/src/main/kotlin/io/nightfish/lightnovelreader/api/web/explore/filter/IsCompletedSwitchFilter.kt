package io.nightfish.lightnovelreader.api.web.explore.filter

import io.nightfish.lightnovelreader.api.R
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.util.LocalString

/**
 * “已完结”开关过滤器
 * 用于本地过滤，开启后仅显示已标记为已完结的书本
 *
 * @since Api 2
 */
class IsCompletedSwitchFilter: SwitchFilter(LocalString(R.string.filter_completed), false), LocalFilter {
    override fun filter(bookInformation: BookInformation): Boolean =
        !this.value || bookInformation.isComplete
}
