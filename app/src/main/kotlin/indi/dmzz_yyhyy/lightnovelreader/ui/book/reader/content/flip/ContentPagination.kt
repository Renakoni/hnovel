package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent

/** Host adaptation of the plugin split contract; preserves ordering and indivisible instances. */
internal suspend fun paginateComponents(
    components: List<AbstractContentComponent<*>>,
    height: Int,
    width: Int,
): List<AbstractContentComponent<*>> {
    val result = mutableListOf<AbstractContentComponent<*>>()
    components.forEach {
        if (it is AbstractDivisibleContentComponent<*, *>) {
            result.addAll(it.split(height, width))
        } else {
            result.add(it)
        }
    }
    return result
}
