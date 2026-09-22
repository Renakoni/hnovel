package indi.renakoni.nextvol.ui

import android.app.Application
import indi.renakoni.nextvol.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class PluginSignatureResourcesTest {
    @Test @Config(qualifiers = "en")
    fun englishPublicKeyDetailsAcceptLengthAndAlgorithm() {
        assertPublicKeyDetails("2048-bit RSA")
    }

    @Test @Config(qualifiers = "zh-rCN")
    fun simplifiedChinesePublicKeyDetailsAcceptLengthAndAlgorithm() {
        assertPublicKeyDetails("2048 位 RSA")
    }

    @Test @Config(qualifiers = "zh-rTW")
    fun traditionalChinesePublicKeyDetailsAcceptLengthAndAlgorithm() {
        assertPublicKeyDetails("2048 位 RSA")
    }

    @Test @Config(qualifiers = "ru")
    fun russianPublicKeyDetailsAcceptLengthAndAlgorithm() {
        assertPublicKeyDetails("2048-bit RSA")
    }

    @Test @Config(qualifiers = "ru-rRU")
    fun russianRegionalPublicKeyDetailsAcceptLengthAndAlgorithm() {
        assertPublicKeyDetails("2048-bit RSA")
    }

    private fun assertPublicKeyDetails(expected: String) {
        assertEquals(expected, RuntimeEnvironment.getApplication().getString(
            R.string.plugin_signature_public_key_value, 2048, "RSA"))
    }
}
