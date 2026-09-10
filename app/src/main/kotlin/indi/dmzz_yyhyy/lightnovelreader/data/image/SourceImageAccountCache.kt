package indi.dmzz_yyhyy.lightnovelreader.data.image

import android.content.Context
import coil3.ImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import io.nightfish.lightnovelreader.api.identifier.Identifier
import java.lang.ref.WeakReference
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Records opaque image keys before a cache write so logout can delete only the retired account. */
@Singleton
class SourceImageAccountCache @Inject constructor(@ApplicationContext private val context: Context,
    private val accounts: SourceSessionManager) {
    private val index = context.getSharedPreferences("source_account_image_keys", Context.MODE_PRIVATE)
    private val loaders = mutableListOf<WeakReference<ImageLoader>>()
    @Synchronized internal fun attach(loader: ImageLoader) {
        loaders.removeAll { it.get() == null }
        if (loaders.none { it.get() === loader }) loaders += WeakReference(loader)
    }
    internal fun remember(source: Identifier, generation: Long, key: String) = accounts.withCurrent(source) {
        check(it.generation == generation)
        val owner = owner(source, generation)
        val keys = index.getStringSet(owner, emptySet()).orEmpty() + key
        check(index.edit().putStringSet(owner, keys).commit())
    }
    internal fun commit(source: Identifier, generation: Long, action: () -> Unit) = accounts.withCurrent(source) {
        check(it.generation == generation); action()
    }
    fun purge(source: Identifier, generation: Long) {
        val owner = owner(source, generation)
        val keys = index.getStringSet(owner, emptySet()).orEmpty().toSet()
        if (keys.isEmpty()) return
        // Handles persisted entries before the first image request after process recreation.
        attach(coil3.SingletonImageLoader.get(context))
        keys.forEach(::purgeKey)
        check(index.edit().remove(owner).commit())
    }
    @Synchronized internal fun purgeKey(key: String) {
        loaders.mapNotNull { it.get() }.forEach { loader ->
            loader.diskCache?.remove(key)
            loader.memoryCache?.let { memory -> memory.keys.filter { it.key == key }.forEach(memory::remove) }
        }
    }
    private fun owner(source: Identifier, generation: Long) = MessageDigest.getInstance("SHA-256")
        .digest(BookIdentity.encode("image-account", listOf(source.namespace, source.id, generation.toString())).toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
