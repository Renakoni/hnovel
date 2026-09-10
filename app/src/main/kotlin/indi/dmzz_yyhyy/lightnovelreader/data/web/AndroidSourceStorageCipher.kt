package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import hnovel.network.StorageCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** AES-GCM key never leaves Android Keystore. Namespace/key AAD prevents moving encrypted records across accounts. */
@Singleton
class AndroidSourceStorageCipher @Inject constructor() : StorageCipher {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun seal(bytes: ByteArray, identity: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
    }
    override fun open(bytes: ByteArray, identity: String): ByteArray {
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
    }
    companion object { private const val ALIAS = "source-runtime-storage-v1" }
}
