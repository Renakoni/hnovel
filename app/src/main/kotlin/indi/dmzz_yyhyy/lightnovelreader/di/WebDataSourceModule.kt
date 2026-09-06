package indi.dmzz_yyhyy.lightnovelreader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebDataSourceModule {
    @Singleton
    @Provides
    fun provideWebDataSourceProvider(webBookDataSourceManager: WebBookDataSourceManager): WebBookDataSourceProvider {
        return webBookDataSourceManager.getWebDataSourceProvider()
    }
}