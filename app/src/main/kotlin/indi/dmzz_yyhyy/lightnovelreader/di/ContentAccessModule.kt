package indi.dmzz_yyhyy.lightnovelreader.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.ComponentDataRegistry
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRegistry

@Module
@InstallIn(SingletonComponent::class)
abstract class ContentAccessModule {
    @Binds
    abstract fun bindComponentDataRegistry(registry: ContentComponentRegistry): ComponentDataRegistry
}
