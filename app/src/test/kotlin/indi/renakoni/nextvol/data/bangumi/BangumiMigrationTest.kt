package indi.renakoni.nextvol.data.bangumi

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BangumiMigrationTest {
    @Test fun version20UpgradePreservesAcknowledgementsAndAllowsSeveralUnmatchedBooks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "bangumi-auto-migration-test"
        context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, NextVolDatabase::class.java, name).build()
        val binding = BangumiBinding("Saved", "Novel", "revision", emptyList(), acknowledged = setOf("subject:11"))
        original.bangumiBindingDao().save(BangumiBindingEntity(17, "saved-book", 10, bangumiJson.encodeToString(binding)))
        original.bangumiBindingDao().insertRecord(BangumiSyncRecord(accountId = 17, bookId = "saved-book", bookTitle = "Saved",
            target = 1, remote = 1, status = BangumiSyncStatus.SYNCED, timestamp = 123))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TABLE binding_v20 (accountId INTEGER NOT NULL, bookId TEXT NOT NULL, subjectId INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(accountId, bookId))")
            it.execSQL("INSERT INTO binding_v20 SELECT * FROM bangumi_binding")
            it.execSQL("DROP TABLE bangumi_binding")
            it.execSQL("ALTER TABLE binding_v20 RENAME TO bangumi_binding")
            it.execSQL("CREATE UNIQUE INDEX index_bangumi_binding_accountId_subjectId ON bangumi_binding (accountId, subjectId)")
            it.execSQL("DROP TABLE local_book_file_manifest")
            it.execSQL("DROP TABLE imported_book")
            it.execSQL("CREATE TABLE imported_book (bookId TEXT NOT NULL PRIMARY KEY)")
            it.version = 20
        }
        val migrated = Room.databaseBuilder(context, NextVolDatabase::class.java, name)
            .addMigrations(NextVolDatabase.MIGRATION_20_21, NextVolDatabase.MIGRATION_21_22, NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24).build()
        try {
            val dao = migrated.bangumiBindingDao()
            assertEquals(setOf("subject:11"), dao.get(17, "saved-book")!!.binding().acknowledged)
            assertEquals(1, dao.getRecords(17).size)
            dao.save(BangumiBindingEntity(17, "unmatched-one", null, bangumiJson.encodeToString(binding)))
            dao.save(BangumiBindingEntity(17, "unmatched-two", null, bangumiJson.encodeToString(binding)))
            assertEquals(3, dao.getAll(17).size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun version19UpgradePreservesBooksAndCreatesEmptyBangumiTable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "bangumi-migration-test"
        context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, NextVolDatabase::class.java, name).build()
        original.bookInformationDao().insert(BookInformation("saved-book", "Saved", "", Uri.EMPTY, "Author", "", emptyList(), "", WordCount(0), LocalDateTime.MIN, false))
        original.close()
        // Version 19 is the current schema without the new Bangumi table/index.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE bangumi_binding")
            it.execSQL("DROP TABLE bangumi_sync_record")
            it.execSQL("DROP TABLE local_book_file_manifest")
            it.execSQL("DROP TABLE imported_book")
            it.execSQL("CREATE TABLE imported_book (bookId TEXT NOT NULL PRIMARY KEY)")
            it.version = 19
        }
        val migrated = Room.databaseBuilder(context, NextVolDatabase::class.java, name)
            .addMigrations(NextVolDatabase.MIGRATION_19_20, NextVolDatabase.MIGRATION_20_21, NextVolDatabase.MIGRATION_21_22, NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24).build()
        try {
            assertEquals("Saved", migrated.bookInformationDao().get("saved-book")!!.title)
            assertTrue(migrated.bangumiBindingDao().getAll(17).isEmpty())
            migrated.bangumiBindingDao().save(BangumiBindingEntity(17, "saved-book", 123, "{}"))
            assertEquals(123, migrated.bangumiBindingDao().get(17, "saved-book")!!.subjectId)
            migrated.bangumiBindingDao().insertRecord(BangumiSyncRecord(accountId = 17, bookId = "saved-book", bookTitle = "Saved",
                target = 1, remote = 1, status = BangumiSyncStatus.OFFLINE, timestamp = 123, httpStatus = 503, pendingConfirmation = true))
            assertEquals(1, migrated.bangumiBindingDao().getRecords(17).single().remote)
            assertEquals(503, migrated.bangumiBindingDao().getRecords(17).single().httpStatus)
            assertTrue(migrated.bangumiBindingDao().getRecords(17).single().pendingConfirmation)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
