package indi.renakoni.nextvol.data.bangumi

import android.app.Application
import hnovel.network.StorageCipher
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BangumiAccountStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: BangumiAccountStore
    private val cipher = TestCipher()
    @Before fun setup() = runBlocking { store = BangumiAccountStore(context, cipher); store.disconnect() }
    @After fun close() = runBlocking { store.disconnect() }

    @Test fun tokenIsEncryptedOutsideBackupAndRestoresWithTheSameCipher() = runBlocking {
        store.connect(BangumiUser(17, "test"), "test-only-private-token")
        val file = File(context.noBackupFilesDir, "bangumi-account.enc")
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("test-only-private-token"))
        val restored = BangumiAccountStore(context, cipher)
        assertEquals(17, restored.session()!!.user.id)
        assertFalse(store.state.value.toString().contains("test-only-private-token"))
        val wrongKey = BangumiAccountStore(context, TestCipher())
        assertNull(wrongKey.session())
        assertTrue(wrongKey.state.value.unreadable)
    }

    @Test fun replacingAuthorizationRevokesOldSessionBeforeItCanSendAgain() = runBlocking {
        val old = store.connect(BangumiUser(17, "test"), "old-test-token")
        store.connect(BangumiUser(18, "another"), "new-test-token")
        assertTrue(runCatching { old.checkActive() }.exceptionOrNull() is CancellationException)
        assertEquals(18, store.session()!!.user.id)
        store.disconnect()
        assertNull(store.session())
        assertFalse(File(context.noBackupFilesDir, "bangumi-account.enc").exists())
    }

    @Test fun disconnectCancelsAnAlreadyRegisteredHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val session = store.connect(BangumiUser(17, "test"), "test-only-token")
            val api = BangumiApi(OkHttpClient(), server.url("/"))
            val request = async(Dispatchers.IO) { runCatching { api.collection(session, 1) } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            store.disconnect()
            assertTrue(withTimeout(3000) { request.await() }.isFailure)
            assertTrue(runCatching { api.updateVolumes(session, 1, 2) }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    private class TestCipher : StorageCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        override fun seal(bytes: ByteArray, identity: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); updateAAD(identity.toByteArray()); iv + doFinal(bytes)
        }
        override fun open(bytes: ByteArray, identity: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(identity.toByteArray()); doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
}
