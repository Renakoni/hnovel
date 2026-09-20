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
            it.version = 19
        }
        val migrated = Room.databaseBuilder(context, NextVolDatabase::class.java, name)
            .addMigrations(NextVolDatabase.MIGRATION_19_20).build()
        try {
            assertEquals("Saved", migrated.bookInformationDao().get("saved-book")!!.title)
            assertTrue(migrated.bangumiBindingDao().getAll(17).isEmpty())
            migrated.bangumiBindingDao().save(BangumiBindingEntity(17, "saved-book", 123, "{}"))
            assertEquals(123, migrated.bangumiBindingDao().get(17, "saved-book")!!.subjectId)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
