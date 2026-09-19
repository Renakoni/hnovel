package indi.renakoni.nextvol.tts

import android.content.ContextWrapper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import hnovel.speech.HttpSpeechClient
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.data.web.AndroidSourceStorageCipher
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class HttpSpeechInstrumentedTest {
    @Test fun isolatedSpeechPostReachesActualPlaybackWithoutReinterpretingBookText(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "http-speech-test-${UUID.randomUUID()}").apply { mkdirs() }
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            MockWebServer().use { server ->
                server.start()
                val url = server.url("/").toString()
                val rule = "@js:" + JsonPrimitive("$url,") + "+JSON.stringify({method:'POST',body:{text:speakText,speed:speakSpeed}})"
                val source = previewHttpSpeech(buildJsonObject { put("name", "Device voice"); put("url", rule) }.toString()).sources.single()
                val audio = wave()
                server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(okio.Buffer().write(audio)))
                SourceBroker(File(directory, "runtime").toPath()).use { owner ->
                    HttpSpeechClient(source, owner, listOf(NetworkGrant(url, true)), authority, executor::execute).use { client ->
                        val text = "中文 {{40+2}} {{speakSpeed}} @js:literal <js>literal</js>"
                        val file = File(directory, "speech.wav").apply { writeBytes(client.synthesize(text)) }
                        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        assertEquals(text, body.getValue("text").jsonPrimitive.content)
                        assertEquals(10, body.getValue("speed").jsonPrimitive.int)
                        assertArrayEquals(audio, file.readBytes())
                        validateHttpSpeechAudio(file)
                        withContext(Dispatchers.Main) {
                            val player = ExoPlayer.Builder(context).build()
                            try {
                                var playing = false
                                val playback = ExoSpeechPlayback(player, context)
                                withTimeout(10_000) {
                                    playback.play(SpeechClip(SpeechChapter("", "", "", "", text), SpeechSegment(0, text.length, text), 0, 1, file), true) { phase, _ ->
                                        if (phase == SpeechPhase.Playing) playing = true
                                    }
                                }
                                assertTrue(playing)
                            } finally { player.release() }
                        }
                        file.writeText("{\"error\":\"not audio\"}")
                        assertThrows(SpeechException::class.java) { validateHttpSpeechAudio(file) }
                    }
                }
            }
        } finally { executor.close(); directory.deleteRecursively() }
    }

    @Test fun importedDefinitionsUseTheProductionCipherAndSurviveReopening() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "http-speech-store-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val raw = """{"id":123,"name":"Voice","url":"https://example.org","header":{"Authorization":"private-test-token"},"unknown":{"value":42}}"""
            val source = previewHttpSpeech(raw).sources.single()
            val authority = ExecutionAuthority()
            HttpSpeechRepository(isolated, AndroidSourceStorageCipher(), authority).save(listOf(SavedHttpSpeechSource(source, listOf("https://example.org:443"))))
            assertFalse(File(directory, "http-speech.enc").readBytes().toString(Charsets.UTF_8).contains("private-test-token"))
            val reopened = HttpSpeechRepository(isolated, AndroidSourceStorageCipher(), authority)
            assertEquals(source.raw, reopened.sources().single().definition.raw)
            val oldTicket = reopened.withSource(source.id) { _, runtime ->
                runtime.mkdirs()
                File(runtime, "test-cache").writeText("old state")
                authority.issue(source.id, "http-tts", source.revision, "speech")
            }!!
            reopened.recordDeniedOrigins(source.id, listOf("https://audio.example.org:443", "https://audio.example.org/private?token=secret"))
            assertEquals(listOf("https://audio.example.org:443"), reopened.deniedOrigins.value[source.id])
            reopened.save(listOf(reopened.sources().single().copy(origins = listOf("https://example.org:443", "https://audio.example.org:443"))))
            assertFalse(authority.accepts(oldTicket))
            reopened.withSource(source.id) { _, runtime -> assertFalse(runtime.exists()) }
            assertTrue(reopened.deniedOrigins.value[source.id].isNullOrEmpty())
            reopened.delete(source.id)
            assertTrue(reopened.sources().isEmpty())
            assertNull(reopened.withSource(source.id) { _, _ -> error("Deleted source cannot reopen") })
        } finally { directory.deleteRecursively() }
    }

    private fun wave(): ByteArray {
        val rate = 16000
        val samples = ShortArray(rate) { (sin(it * 2 * Math.PI * 440 / rate) * 4000).toInt().toShort() }
        return ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2); samples.forEach(::putShort)
        }.array()
    }
}
