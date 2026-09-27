package indi.renakoni.nextvol.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionTask
import hnovel.execution.SourceExecutionBroker
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.web.SourceSessionEpochStore
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import io.nightfish.lightnovelreader.api.identifier.Identifier

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebDataSourceModule {
    @Singleton
    @Provides
    fun provideSourceBrowser(browser: indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser): hnovel.network.BrowserExecutor = browser

    @Singleton
    @Provides
    fun provideSourceStorageCipher(cipher: indi.renakoni.nextvol.data.web.AndroidSourceStorageCipher): hnovel.network.StorageCipher = cipher

    @Singleton
    @Provides
    fun provideRuleTaskRunner(executor: AndroidIsolatedExecutor): RuleTaskRunner {
        return object : RuleTaskRunner {
            override suspend fun prepareIndependent(identity: ExecutionIdentity, count: Int) =
                executor.prepareIndependent(identity, count)

            override suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask,
                limits: ExecutionLimits, broker: SourceExecutionBroker) = executor.execute(identity, task, limits, broker)
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

}
