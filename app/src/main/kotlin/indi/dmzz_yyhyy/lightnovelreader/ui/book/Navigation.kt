package indi.dmzz_yyhyy.lightnovelreader.ui.book

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.dmzz_yyhyy.lightnovelreader.ui.book.detail.bookDetailDestination
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.bookReaderDestination
import io.nightfish.lightnovelreader.api.Route

fun NavGraphBuilder.bookNavigation() {
    navigation<Route.Book>(
        startDestination = Route.Book.Detail(""),
    ) {
        bookDetailDestination()
        bookReaderDestination()
    }
}