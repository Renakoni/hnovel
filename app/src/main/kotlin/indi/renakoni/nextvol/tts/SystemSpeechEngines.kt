package indi.renakoni.nextvol.tts

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Older Android clients bind directly. Restrict those binds to an explicitly selected engine. */
internal class SpeechEngineContext(base: Context, private val requestedPackage: String) : ContextWrapper(base) {
    @Volatile var boundPackage: String? = null
        private set

    override fun getApplicationContext(): Context = this

    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val target = service.`package` ?: service.component?.packageName
        if (requestedPackage.isNotEmpty() && target != requestedPackage) return false
        return super.bindService(service, connection, flags).also { if (it) boundPackage = target }
    }
}

@Singleton
class SystemSpeechEngines @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun installed(): List<SpeechEngine> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.MATCH_ALL)
            .filter { it.serviceInfo.enabled && it.serviceInfo.exported && it.serviceInfo.applicationInfo.enabled }
            .distinctBy { it.serviceInfo.packageName }
            .map { SpeechEngine(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .sortedBy { it.name }
    }

    suspend fun inspect(engine: String): SpeechEngineState {
        val client = connect(engine)
        return try {
            withContext(Dispatchers.IO) {
                SpeechEngineState(client.engine, client.tts.voices.orEmpty().map { voice ->
                    SpeechVoice(voice.name, voice.locale.toLanguageTag(), voice.isNetworkConnectionRequired,
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty())
                }.sortedWith(compareBy({ it.locale }, { it.id })), client.tts.voice?.name)
            }
        } finally { client.tts.shutdown() }
    }

    fun synthesizer(): SpeechSynthesizer = SystemSpeechSynthesizer(this)

    internal suspend fun connect(engine: String): SystemSpeechClient {
        val engines = installed()
        if (engines.isEmpty()) throw SpeechException(SpeechError.NoEngine)
        if (engine.isNotEmpty() && engines.none { it.packageName == engine }) throw SpeechException(SpeechError.EngineUnavailable)
        val bindingContext = SpeechEngineContext(context, engine)
        val initialized = CompletableDeferred<Int>()
        // A failed constructor may invoke onInit synchronously. The callback never accesses tts.
        var owned: TextToSpeech? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                owned = TextToSpeech(bindingContext, { initialized.complete(it) }, engine.takeIf(String::isNotEmpty))
            }
            val tts = checkNotNull(owned)
            val status = try { withTimeout(10_000) { initialized.await() } }
            catch (_: TimeoutCancellationException) { throw SpeechException(SpeechError.InitializationTimeout) }
            if (status != TextToSpeech.SUCCESS) throw SpeechException(SpeechError.EngineUnavailable)
            // Newer Android connects through its system TTS manager, not Context.bindService.
            // That manager reports a failed requested connection through onInit(ERROR).
            val packageName = bindingContext.boundPackage ?: engine.takeIf(String::isNotEmpty) ?: tts.defaultEngine
            val actual = installed().firstOrNull { it.packageName == packageName }
                ?: throw SpeechException(SpeechError.EngineUnavailable)
            return SystemSpeechClient(tts, actual)
        } catch (failure: Throwable) {
            owned?.shutdown()
            throw failure
        }
    }
}

internal data class SystemSpeechClient(val tts: TextToSpeech, val engine: SpeechEngine)

private class SystemSpeechSynthesizer(private val engines: SystemSpeechEngines) : SpeechSynthesizer {
    private val client = AtomicReference<SystemSpeechClient?>()
    private val mutex = Mutex()
    private val pending = AtomicReference<Pair<String, CompletableDeferred<Unit>>?>()

    override suspend fun open(settings: SpeechSettings): String = mutex.withLock {
        close()
        val active = engines.connect(settings.engine)
        client.set(active)
        withContext(Dispatchers.IO) {
            val tts = active.tts
            if (settings.voice.isNotEmpty()) {
                val voice = tts.voices.orEmpty().find { it.name == settings.voice }
                    ?: throw SpeechException(SpeechError.MissingVoice)
                if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty()) {
                    throw SpeechException(SpeechError.MissingLanguageData)
                }
                if (tts.setVoice(voice) == TextToSpeech.ERROR) throw SpeechException(SpeechError.MissingVoice)
            }
            if (tts.voice?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
                throw SpeechException(SpeechError.MissingLanguageData)
            }
            if (settings.rate?.let { tts.setSpeechRate(it) == TextToSpeech.ERROR } == true ||
                settings.pitch?.let { tts.setPitch(it) == TextToSpeech.ERROR } == true) {
                throw SpeechException(SpeechError.SynthesisFailed)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { operation(utteranceId)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
                override fun onError(utteranceId: String?, errorCode: Int) {
                    val error = when (errorCode) {
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> SpeechError.MissingLanguageData
                        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> SpeechError.Network
                        TextToSpeech.ERROR_OUTPUT -> SpeechError.InvalidAudio
                        else -> SpeechError.SynthesisFailed
                    }
                    operation(utteranceId)?.completeExceptionally(SpeechException(error))
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    operation(utteranceId)?.completeExceptionally(SpeechException(SpeechError.SynthesisFailed))
                }
                private fun operation(id: String?) = pending.get()?.takeIf { it.first == id }?.second
            })
            listOfNotNull(active.engine.name, tts.voice?.locale?.getDisplayName()).joinToString(" · ")
        }
    }

    override suspend fun synthesize(text: String, output: File): Unit = mutex.withLock {
        val active = client.get() ?: throw SpeechException(SpeechError.EngineUnavailable)
        if (text.length > TextToSpeech.getMaxSpeechInputLength()) throw SpeechException(SpeechError.SynthesisFailed)
        val id = UUID.randomUUID().toString()
        val complete = CompletableDeferred<Unit>()
        val operation = id to complete
        pending.set(operation)
        val partial = File(output.parentFile, output.name + ".part")
        try {
            withContext(Dispatchers.IO) {
                if (active.tts.synthesizeToFile(text, null, partial, id) != TextToSpeech.SUCCESS) {
                    throw SpeechException(SpeechError.SynthesisFailed)
                }
            }
            try { withTimeout(30_000) { complete.await() } }
            catch (_: TimeoutCancellationException) { throw SpeechException(SpeechError.SynthesisTimeout) }
            withContext(Dispatchers.IO) {
                validateSpeechWav(partial)
                if (!partial.renameTo(output)) throw SpeechException(SpeechError.Storage)
            }
        } catch (_: IOException) {
            throw SpeechException(SpeechError.Storage)
        } finally {
            pending.compareAndSet(operation, null)
            if (!complete.isCompleted) active.tts.stop()
            partial.delete()
        }
    }

    override fun cancel() {
        pending.getAndSet(null)?.second?.cancel()
        client.get()?.tts?.stop()
    }

    override fun close() {
        cancel()
        client.getAndSet(null)?.tts?.shutdown()
    }
}
