package hnovel.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class InlineRequestTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun dataBytesAreDecodedWithoutDnsAndTypeReturnsHexText(): Unit = runBlocking {
        SourceBroker(folder.root.toPath(), okhttp3.Dns { error("Inline data must not resolve DNS") }).use { broker ->
            val session = broker.open(SourceScope("inline", "source", "legado"), emptyList())
            val rule = "data:;base64,AAH/QUJD,{\"type\":\"custom-label\"}"
            val compiled = RequestCompiler().compile("inline", rule, "https://fixture.invalid/")
            assertTrue(compiled.toString(), compiled is CompiledRequest.Ready)
            val response = session.execute((compiled as CompiledRequest.Ready).request) as BrokerResult.Success
            assertEquals("0001ff414243", response.response.text())
            assertArrayEquals(byteArrayOf(0, 1, -1, 65, 66, 67), response.response.body)
            assertEquals("http://localhost/", response.response.finalUrl)
            assertEquals("data", response.response.protocol)
            val limited = session.execute(compiled.request.copy(maxResponseBytes = 2)) as BrokerResult.Failure
            assertEquals(FailureCode.ResponseTooLarge, limited.code)
            val invalid = session.execute(compiled.request.copy(url = "data:;base64,broken!")) as BrokerResult.Failure
            assertEquals(FailureCode.InvalidRequest, invalid.code)
        }
    }

    @Test fun typeConvertsHttpBytesAfterTheNormalNetworkChecks(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath()).use { broker ->
                val session = broker.open(SourceScope("binary", "source", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val compiled = RequestCompiler().compile("binary", "${server.url("/bytes")},{\"type\":\"bytes\"}", "")
                assertTrue(compiled.toString(), compiled is CompiledRequest.Ready)
                server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0, -1, 65))))
                val request = (compiled as CompiledRequest.Ready).request
                val result = session.execute(request) as BrokerResult.Success
                assertEquals("00ff41", result.response.text())
                val denied = session.execute(request.copy(url = "https://not-granted.invalid/")) as BrokerResult.Failure
                assertEquals(FailureCode.OriginDenied, denied.code)
                assertEquals(1, server.requestCount)
            }
        }
    }
}
