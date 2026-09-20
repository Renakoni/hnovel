package indi.renakoni.nextvol.ui.book.detail

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.navigateToBookReaderDestination
import indi.renakoni.nextvol.ui.book.reader.navigateToImageViewerDialog
import indi.renakoni.nextvol.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.renakoni.nextvol.ui.dialog.navigateToMarkAllChaptersAsReadDialog
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import indi.renakoni.nextvol.utils.showSnackbar
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
fun NavGraphBuilder.bookDetailDestination() {
    composable<Route.Book.Detail> { entry ->
        val navController = LocalNavController.current
        val bookId = BookIdentity.bookKey(entry.toRoute<Route.Book.Detail>().bookId)
        val viewModel = hiltViewModel<DetailViewModel>(entry)
        androidx.lifecycle.compose.LifecycleStartEffect(viewModel) {
            viewModel.setActive(true)
            onStopOrDispose { viewModel.setActive(false, navController.currentBackStackEntry?.id == entry.id) }
        }
        androidx.compose.runtime.DisposableEffect(viewModel, entry) {
            onDispose { if (navController.currentBackStackEntry?.id != entry.id) viewModel.setActive(false) }
        }
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val exportResult = viewModel.exportResult
        val submissionFailed = viewModel.exportSubmissionFailed
        LaunchedEffect(exportResult, submissionFailed, lifecycleOwner) {
            if (exportResult == null && !submissionFailed) return@LaunchedEffect
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.clearExportResult()
                if (exportResult?.state == WorkInfo.State.SUCCEEDED) {
                    context.startActivity(EpubShareActivity.intent(context, exportResult.id, automatic = true))
                } else {
                    Toast.makeText(context, exportResult?.outputData?.getString("message")
                        ?: context.getString(R.string.epub_export_notification_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        val snackbarHostState = LocalSnackbarHost.current

        LaunchedEffect(bookId) {
            viewModel.init(bookId)
        }
        DetailScreen(
            uiState = viewModel.uiState,
            onRetry = viewModel::retryInformation,
            onMarkChaptersUnread = viewModel::markChaptersUnread,
            onClickExportToEpub = { settings ->
                viewModel.exportSettings = settings

                viewModel.uiState.bookInformation
                    ?.map { it.title }
                    ?.onOk { title ->
                        viewModel.startEpubExport(bookId, title)
                        Toast.makeText(context, context.getString(R.string.export_book_started, title), Toast.LENGTH_SHORT).show()
                    }?.onErr {
                        Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                    }
            },
            onClickBackButton = navController::popBackStackIfResumed,
            onClickChapter = {
                navController.navigateToBookReaderDestination(bookId, it, context)
            },
            onClickRead = {
                if (viewModel.uiState.userReadingData?.lastReadChapterId == null)
                    viewModel.uiState.bookVolumes
                        ?.map {
                            it.volumes.firstOrNull()?.chapters?.firstOrNull()?.id
                        }?.onOk { id ->
                            id?.let {
                                navController.navigateToBookReaderDestination(bookId, it, context)
                            }
                        }?.onErr {
                            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                        }
                else {
                    navController.navigateToBookReaderDestination(bookId, viewModel.uiState.userReadingData!!.lastReadChapterId!!, context)
                }
            },
            cacheBook = { bookId ->
                coroutineScope.launch {
                    viewModel.cacheBook(bookId).collect { workInfo ->
                        if (workInfo == null) {
                            viewModel.uiState.bookInformation
                                ?.map { it.title }
                                ?.onOk { title ->
                                    showSnackbar(
                                        coroutineScope = coroutineScope,
                                        hostState = snackbarHostState,
                                        message = context.getString(
                                            R.string.cache_book_started,
                                            title
                                        )
                                    ) { }
                                }?.onErr {
                                    showSnackbar(
                                        coroutineScope = coroutineScope,
                                        hostState = snackbarHostState,
                                        message = it.message
                                    ) { }
                                }
                            return@collect
                        }
                        when (workInfo.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                showSnackbar(
                                    coroutineScope = coroutineScope,
                                    hostState = snackbarHostState,
                                    message = context.getString(R.string.cache_book_finished)
                                ) { }
                            }
                            WorkInfo.State.FAILED -> {
                                showSnackbar(
                                    coroutineScope = coroutineScope,
                                    hostState = snackbarHostState,
                                    message = context.getString(R.string.cache_book_error)
                                ) { }
                            }
                            WorkInfo.State.RUNNING -> {
                                showSnackbar(
                                    coroutineScope = coroutineScope,
                                    hostState = snackbarHostState,
                                    message = context.getString(R.string.cache_book_running)
                                ) { }
                            }
                            else -> {}
                        }
                    }
                }
            },
            requestAddBookToBookshelf = navController::navigateToAddBookToBookshelfDialog,
            onClickTag = { tag ->
                coroutineScope.launch {
                    viewModel.tagPage(tag)?.onOk { page ->
                        if (page != null && navController.isResumed()) navController.navigate(Route.Main.DiscoveryResults(
                            page.sourceId.namespace, page.sourceId.id, page.target, tag, java.util.UUID.randomUUID().toString()))
                    }?.onErr { error ->
                        snackbarHostState.showSnackbar(
                            if (error.kind == WebRequestErrorKind.SourceUnavailable) context.getString(R.string.sources_unavailable)
                            else error.title
                        )
                    }
                }
            },
            onClickCover = { uri -> navController.navigateToImageViewerDialog(uri, bookId, cover = true) },
            onClickMarkAsRead = {
                navController.navigateToMarkAllChaptersAsReadDialog(bookId)
            }
        )
    }
}

fun NavController.navigateToBookDetailDestination(bookId: String) {
    if (!this.isResumed()) return
    navigate(Route.Book.Detail(BookIdentity.bookKey(bookId)))
}

