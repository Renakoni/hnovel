package indi.renakoni.nextvol.ui.storagemanager

import androidx.annotation.StringRes
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot

data class StorageManagerSection(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val size: Long
)

internal fun StorageUsageSnapshot.sections() = listOf(
    StorageManagerSection(R.string.storage_manager_section_app_title, R.string.storage_manager_section_app_description, appBytes),
    StorageManagerSection(R.string.storage_manager_section_database_title, R.string.storage_manager_section_database_description, databaseDiskBytes),
    StorageManagerSection(R.string.storage_download_images, R.string.storage_download_images_desc, downloadImageBytes),
    StorageManagerSection(R.string.storage_imported_books, R.string.storage_imported_books_desc, importedFileBytes),
    StorageManagerSection(R.string.storage_manager_section_cache_title, R.string.storage_temporary_desc, cacheBytes),
    StorageManagerSection(R.string.storage_manager_section_other_title, R.string.storage_other_desc, otherFileBytes),
)
