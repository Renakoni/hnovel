package indi.renakoni.nextvol.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.onErr
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.local.LocalDataManager
import indi.renakoni.nextvol.data.local.cbor.AppLocalData
import indi.renakoni.nextvol.utils.readAppLocalData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import java.io.FileInputStream

@HiltWorker
class ImportDataWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localDataManager: LocalDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ImportDataWork"
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        val fileUri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val overwrite = inputData.getBoolean("overwrite", false)
        val appLocalData = try {
            applicationContext.contentResolver.openFileDescriptor(fileUri, "r")?.use { parcelFileDescriptor ->
                FileInputStream(parcelFileDescriptor.fileDescriptor).use { inputStream ->
                    Cbor.decodeFromByteArray<AppLocalData>(inputStream.readAppLocalData())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load file")
            e.printStackTrace()
            return Result.failure()
        } ?: return Result.failure()
        try {
            localDataManager.validateBackup(appLocalData)
        } catch (failure: IllegalArgumentException) {
            Log.e(TAG, "Invalid backup identities", failure)
            return Result.failure()
        }
        if (overwrite) {
            localDataManager.cleanDatabaseWithoutGlobalUserData()
        }
        localDataManager.importAppLocalData(appLocalData)
            .onErr {
                Log.e(TAG, "Failed to import the data")
                it.printStackTrace()
                return Result.failure()
            }
        return Result.success()
    }
}