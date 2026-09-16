package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import indi.renakoni.nextvol.data.work.SaveBookshelfWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfHomeScreen(
    init: () -> Unit,
    uiState: BookshelfHomeUiState,
    onSettings: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val backgroundColor by animateColorAsState(
        if (uiState.selectMode) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface
    )
    val listState = rememberSaveable(uiState.selectedBookshelfId, saver = LazyListState.Saver) { LazyListState() }
    val gridState = rememberSaveable(uiState.selectedBookshelfId, saver = LazyGridState.Saver) { LazyGridState() }

    BackHandler(uiState.selectMode) {
        uiState.onDisableSelectMode()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        init()
    }

    val shareBookshelf: () -> Unit = remember(
        context,
        workManager,
        coroutineScope,
        uiState.selectedBookshelfId
    ) {
        {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.applicationInfo.processName}.provider",
                File(context.cacheDir, "NextVolBookshelfData.lnr")
            )
            val workRequest = OneTimeWorkRequestBuilder<SaveBookshelfWork>()
                .setInputData(
                    workDataOf(
                        "bookshelfId" to uiState.selectedBookshelfId,
                        "uri" to uri.toString(),
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                uri.toString(),
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            coroutineScope.launch(Dispatchers.IO) {
                workManager.getWorkInfoByIdFlow(workRequest.id).collect {
                    when (it?.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            ShareCompat.IntentBuilder(context)
                                .setType("application/zip")
                                .setSubject("分享文件")
                                .addStream(uri)
                                .setChooserTitle("分享书架")
                                .startChooser()
                        }

                        else -> return@collect
                    }
                }
            }
        }
    }

    Column {
        BookshelfHomeTopBar(
            scrollBehavior = scrollBehavior,
            backgroundColor = backgroundColor,
            uiState = uiState,
            onShareBookshelf = shareBookshelf,
            onSettings = onSettings
        )

        BookshelfHomeContent(
            uiState = uiState,
            listState = listState,
            gridState = gridState,
            scrollBehavior = scrollBehavior
        )
    }
}
