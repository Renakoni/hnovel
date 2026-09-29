package indi.renakoni.nextvol.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.BaseDialog
import indi.renakoni.nextvol.ui.components.CheckBoxListItem
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.addBookToBookshelfDialog() {
    dialog<Route.AddBookToBookshelfDialog> {
        val navController = LocalNavController.current
        val addToBookshelfDialogViewModel = hiltViewModel<AddToBookshelfDialogViewModel>()
        val route = it.toRoute<Route.AddBookToBookshelfDialog>()
        LaunchedEffect(route.bookId) { addToBookshelfDialogViewModel.bookId = route.bookId }
        addToBookshelfDialogViewModel.navController = navController
        val uiState = addToBookshelfDialogViewModel.addToBookshelfDialogUiState
        AddBookToBookshelfDialog(
            onDismissRequest = addToBookshelfDialogViewModel::onDismissAddToBookshelfRequest,
            onConfirmation = addToBookshelfDialogViewModel::processAddToBookshelfRequest,
            onSelectBookshelf = addToBookshelfDialogViewModel::onSelectBookshelf,
            onDeselectBookshelf = addToBookshelfDialogViewModel::onDeselectBookshelf,
            allBookshelf = uiState.allBookShelf,
            selectedBookshelfIds = uiState.selectedBookshelfIds,
            isLoading = uiState.isLoading,
            isSaving = uiState.isSaving,
            errorMessage = uiState.errorMessage
        )
    }
}

fun NavController.navigateToAddBookToBookshelfDialog(bookId: String) {
    navigate(Route.AddBookToBookshelfDialog(bookId))
}

@Composable
fun AddBookToBookshelfDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    onSelectBookshelf: (Int) -> Unit,
    onDeselectBookshelf: (Int) -> Unit,
    allBookshelf: List<Bookshelf>,
    selectedBookshelfIds: List<Int>,
    isLoading: Boolean = false,
    isSaving: Boolean = false,
    errorMessage: Int? = null
) {
    val scrollState = rememberScrollState()
    val enabled = !isLoading && !isSaving
    BaseDialog(
        icon = painterResource(R.drawable.filled_bookmark_24px),
        title = stringResource(R.string.add_to_bookshelf),
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        onConfirmation = onConfirmation,
        dismissText = stringResource(R.string.cancel),
        confirmationText = stringResource(R.string.add_to_bookshelf),
        confirmationEnabled = enabled && allBookshelf.isNotEmpty(),
        dismissEnabled = !isSaving,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).sizeIn(minWidth = 325.dp, maxHeight = 350.dp).verticalScroll(scrollState)) {
            if (isLoading || isSaving) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            } else if (allBookshelf.isEmpty() && errorMessage == null) {
                Text(stringResource(R.string.nothing_here), Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
            }
            allBookshelf.forEachIndexed { index, bookshelf ->
                CheckBoxListItem(
                    modifier = Modifier
                        .wrapContentWidth()
                        .sizeIn(minWidth = 325.dp)
                        .padding(horizontal = 10.dp),
                    title = bookshelf.name,
                    supportingText = stringResource(R.string.bookshelf_book_count, bookshelf.allBookIds.size),
                    checked = selectedBookshelfIds.contains(bookshelf.id),
                    enabled = enabled,
                    onCheckedChange = {
                        if (it) onSelectBookshelf(bookshelf.id) else onDeselectBookshelf(
                            bookshelf.id
                        )
                    }
                )
                if (index != allBookshelf.size - 1) {
                    HorizontalDivider(Modifier.padding(horizontal = 10.dp))
                }
            }
            errorMessage?.let {
                Text(stringResource(it), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
