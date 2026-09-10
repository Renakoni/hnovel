package hnovel.network

/** The Android host supplies a Keystore cipher; JVM fixtures can use plain local test storage. */
interface StorageCipher {
    fun seal(bytes: ByteArray, identity: String): ByteArray
    fun open(bytes: ByteArray, identity: String): ByteArray
    object Plain : StorageCipher {
        override fun seal(bytes: ByteArray, identity: String) = bytes
        override fun open(bytes: ByteArray, identity: String) = bytes
    }
}
