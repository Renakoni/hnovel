package indi.renakoni.nextvol.ui.book

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import indi.renakoni.nextvol.ui.book.detail.bookDetailDestination
import indi.renakoni.nextvol.ui.book.reader.bookReaderDestination
import io.nightfish.lightnovelreader.api.Route

fun NavGraphBuilder.bookNavigation(onReaderActiveChanged: (Boolean) -> Unit) {
    navigation<Route.Book>(
        startDestination = Route.Book.Detail(""),
    ) {
        bookDetailDestination()
        bookReaderDestination(onReaderActiveChanged)
    }
}
