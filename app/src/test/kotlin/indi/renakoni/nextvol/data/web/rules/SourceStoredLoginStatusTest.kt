package indi.renakoni.nextvol.data.web.rules

import hnovel.network.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceStoredLoginStatusTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun legacyPersistentCookiesRestoreOnlyASavedSessionFactAcrossRestart() {
        val scope = SourceScope("test", "legacy-login", "legado")
        val url = "https://example.org/"
        SourceBroker(directory.root.toPath()).use { broker ->
            val session = broker.open(scope, listOf(NetworkGrant(url)))
            assertNull(SourceLoginService.storedStatus(session))
            session.updateNativeBrowserCookies(url, listOf("sid=temporary; Path=/"))
            assertNull(SourceLoginService.storedStatus(session))
            session.updateNativeBrowserCookies(url, listOf("sid=expired; Path=/; Max-Age=0"))
            assertNull(SourceLoginService.storedStatus(session))
            session.updateNativeBrowserCookies(url, listOf("sid=saved; Path=/; Max-Age=3600; HttpOnly"))
            assertEquals("session", SourceLoginService.storedStatus(session))
            assertEquals(StorageResult.Value(null), session.read(StorageRequest(StorageArea.Account, "login/status")))
        }
        // Host settings can read the saved fact without loading source scripts or granting network access.
        SourceBroker(directory.root.toPath()).use { broker ->
            val restored = broker.open(scope, emptyList())
            assertEquals(LoginStatus.SessionSaved, SourceLoginService.savedStatus(SourceLoginService.storedStatus(restored)))
            restored.write(StorageRequest(StorageArea.Account, "login/status", "required"))
            assertEquals(LoginStatus.Required, SourceLoginService.savedStatus(SourceLoginService.storedStatus(restored)))
            assertNull(SourceLoginService.storedStatus(broker.open(scope.copy(accountGeneration = 1), emptyList())))
            assertNull(SourceLoginService.storedStatus(broker.open(scope.copy(sourceId = "other"), emptyList())))
        }
        val pixivUrl = "https://www.pixiv.net/"
        val pixivScope = scope.copy(sourceId = "pixiv")
        SourceBroker(directory.root.toPath()).use { broker ->
            val session = broker.open(pixivScope, listOf(NetworkGrant(pixivUrl)))
            session.write(StorageRequest(StorageArea.Account, "login/status", "session"))
            session.updateNativeBrowserCookies(pixivUrl, listOf("visitor=saved; Path=/; Max-Age=3600"))
            assertNull(SourceLoginService.storedStatus(session, pixiv = true))
            for (value in listOf("0_guest", "12345_", "12345_valid")) {
                session.updateNativeBrowserCookies(pixivUrl, listOf("PHPSESSID=$value; Path=/; Max-Age=3600; HttpOnly"))
                assertEquals(if (value == "12345_valid") "session" else null,
                    SourceLoginService.storedStatus(session, pixiv = true))
            }
        }
        SourceBroker(directory.root.toPath()).use { broker ->
            val restored = broker.open(pixivScope, emptyList())
            assertEquals("session", SourceLoginService.storedStatus(restored, pixiv = true))
            restored.write(StorageRequest(StorageArea.Account, "login/status", "required"))
            assertEquals("required", SourceLoginService.storedStatus(restored, pixiv = true))
        }
    }
}
