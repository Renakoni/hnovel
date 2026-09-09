package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.app.Application
import androidx.room.Room
import androidx.work.*
import androidx.work.impl.WorkContinuationImpl
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.EnqueueRunnable
import androidx.work.impl.utils.forNameInline
import indi.dmzz_yyhyy.lightnovelreader.data.book.SourceBookId
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookWorkIdentityTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")

    @Test fun persistentInputsKeepAndCancellationAreIndependentForSameRemoteIdsAndTaskTypes() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WorkDatabase::class.java)
            .allowMainThreadQueries().build()
        val manager = mockk<WorkManagerImpl> {
            every { workDatabase } returns db
            every { configuration } returns Configuration.Builder().build()
            every { schedulers } returns emptyList()
            every { processor } returns mockk(relaxed = true)
        }
        fun submit(book: SourceBookId, export: Boolean = false): OneTimeWorkRequest {
            val request = (if (export) OneTimeWorkRequestBuilder<ExportBookToEPUBWork>()
                else OneTimeWorkRequestBuilder<CacheBookWork>()).setInputData(workDataOf("bookId" to book.storageKey)).build()
            val name = if (export) ExportBookToEPUBWork.ofId(book.storageKey) else CacheBookWork.ofId(book.storageKey)
            EnqueueRunnable.addToDatabase(WorkContinuationImpl(manager, name, ExistingWorkPolicy.KEEP, listOf(request)))
            return request
        }
        try {
            val firstA = submit(a)
            val firstB = submit(b)
            val exportA = submit(a, true)
            val duplicateA = submit(a)
            assertNull(db.workSpecDao().getWorkSpec(duplicateA.id.toString()))
            assertEquals(a, db.workSpecDao().getWorkSpec(firstA.id.toString())!!.input.sourceBook())
            assertEquals(b, db.workSpecDao().getWorkSpec(firstB.id.toString())!!.input.sourceBook())
            assertEquals(a, db.workSpecDao().getWorkSpec(exportA.id.toString())!!.input.sourceBook())
            forNameInline(CacheBookWork.ofId(a.storageKey), manager)
            assertEquals(WorkInfo.State.CANCELLED, db.workSpecDao().getState(firstA.id.toString()))
            assertEquals(WorkInfo.State.ENQUEUED, db.workSpecDao().getState(firstB.id.toString()))
            assertEquals(WorkInfo.State.ENQUEUED, db.workSpecDao().getState(exportA.id.toString()))
            val replacement = submit(a)
            assertEquals(listOf(replacement.id.toString()), db.workSpecDao()
                .getWorkSpecIdAndStatesForName(CacheBookWork.ofId(a.storageKey)).map { it.id })
        } finally { db.close() }
    }

    @Test fun workersRejectBareAndMalformedIdsInsteadOfInferringABrowsingSource() {
        for (id in listOf("same", "", "lnr1.b.invalid")) assertNull(workDataOf("bookId" to id).sourceBook())
        val data = workDataOf("bookId" to a.storageKey)
        assertEquals(setOf("bookId"), data.keyValueMap.keys)
        assertEquals(a, data.sourceBook())
        assertNotEquals(CacheBookWork.ofId(a.storageKey), CacheBookWork.ofId(b.storageKey))
    }
}
