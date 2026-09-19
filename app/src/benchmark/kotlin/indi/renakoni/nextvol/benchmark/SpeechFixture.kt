package indi.renakoni.nextvol.benchmark

import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sin

/** Deterministic transport/playback fixture only; online services have separate real acceptance. */
internal fun speechFixture(): SavedHttpSpeechSource {
    val rate = 16000
    val samples = ShortArray(rate) { (sin(it * 2 * Math.PI * 440 / rate) * 4000).toInt().toShort() }
    val wave = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(samples.size * 2); samples.forEach(::putShort)
    }.array()
    val raw = buildJsonObject {
        put("id", "benchmark-speech"); put("name", "Imported test voice")
        put("url", "@js:'data:audio/wav;base64,${Base64.getEncoder().encodeToString(wave)}'")
    }
    return SavedHttpSpeechSource(previewHttpSpeech(raw.toString()).sources.single(), emptyList())
}
