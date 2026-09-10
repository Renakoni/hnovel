package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.data.web.AndroidSourceStorageCipher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceAccountInstrumentedTest {
    @Test fun keystoreEncryptionSurvivesRecreationAndRejectsAnotherAccountOrModifiedBytes() {
        val secret = "synthetic-password-cookie-token".toByteArray()
        val encrypted = AndroidSourceStorageCipher().seal(secret, "source-A/account-1/login")
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("synthetic-password"))
        val next = AndroidSourceStorageCipher()
        assertArrayEquals(secret, next.open(encrypted, "source-A/account-1/login"))
        assertTrue(runCatching { next.open(encrypted, "source-B/account-1/login") }.isFailure)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { next.open(encrypted, "source-A/account-1/login") }.isFailure)
    }
}
