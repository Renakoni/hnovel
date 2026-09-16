package indi.renakoni.nextvol.utils

import dalvik.system.PathClassLoader
import indi.renakoni.nextvol.data.plugin.PluginClassLoader

fun classLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader?
): PathClassLoader = PluginClassLoader(dexPath, librarySearchPath, parent)