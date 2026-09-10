package indi.dmzz_yyhyy.lightnovelreader.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionEpochStore
import indi.dmzz_yyhyy.lightnovelreader.sourceexecution.AndroidIsolatedExecutor
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.sync.Mutex

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import javax.inject.Singleton
import kotlinx.coroutines.sync.withLock

@Module
@InstallIn(SingletonComponent::class)
object WebDataSourceModule {
    @Singleton
    @Provides
    fun provideSourceStorageCipher(cipher: indi.dmzz_yyhyy.lightnovelreader.data.web.AndroidSourceStorageCipher): hnovel.network.StorageCipher = cipher

    @Singleton
    @Provides
    fun provideRuleTaskRunner(executor: AndroidIsolatedExecutor): RuleTaskRunner {
        val queue = Mutex()
        return RuleTaskRunner { identity, task, limits, broker ->
            queue.withLock { executor.execute(identity, task, limits, broker) }
        }
    }

    @Singleton
    @Provides
    fun provideSourceSessionEpochStore(@ApplicationContext context: Context):
        SourceSessionEpochStore {
        val preferences = context.getSharedPreferences("source_account_generations", Context.MODE_PRIVATE)
        return object : SourceSessionEpochStore {
            private fun key(source: Identifier) =
                BookIdentity.encode("account", listOf(source.namespace, source.id))
            override fun read(source: Identifier) = preferences.getLong(key(source), 0)
            override fun write(source: Identifier, generation: Long) {
                check(preferences.edit().putLong(key(source), generation).commit()) { "Could not persist source account generation" }
            }
        }
    }

    @Singleton
    @Provides
    fun provideExecutionAuthority() = ExecutionAuthority()

    @Singleton
    @Provides
    fun provideWebDataSourceProvider(webBookDataSourceManager: WebBookDataSourceManager): WebBookDataSourceProvider {
        return webBookDataSourceManager.getWebDataSourceProvider()
    }
}
