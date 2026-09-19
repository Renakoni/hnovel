package indi.renakoni.nextvol.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import indi.renakoni.nextvol.tts.RepositorySpeechChapterSource
import indi.renakoni.nextvol.tts.RepositorySpeechProgressStore
import indi.renakoni.nextvol.tts.SpeechChapterSource
import indi.renakoni.nextvol.tts.SpeechProgressStore

@Module
@InstallIn(SingletonComponent::class)
abstract class SpeechModule {
    @Binds abstract fun chapters(source: RepositorySpeechChapterSource): SpeechChapterSource
    @Binds abstract fun progress(store: RepositorySpeechProgressStore): SpeechProgressStore
}
