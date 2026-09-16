package indi.renakoni.nextvol.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.renakoni.nextvol.data.content.ComponentDataRegistry
import indi.renakoni.nextvol.data.content.ContentComponentRegistry

@Module
@InstallIn(SingletonComponent::class)
abstract class ContentAccessModule {
    @Binds
    abstract fun bindComponentDataRegistry(registry: ContentComponentRegistry): ComponentDataRegistry
}
