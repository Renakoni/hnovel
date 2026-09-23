package indi.renakoni.nextvol.sourcebrowser

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Routes gesture and button back through the same browser-owned action on API 33+. */
internal class SourceBrowserBack(private val activity: Activity, private val action: () -> Unit) {
    private var callback: OnBackInvokedCallback? = null

    fun register() {
        if (Build.VERSION.SDK_INT < 33) return
        OnBackInvokedCallback { action() }.also {
            callback = it
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT < 33) return
        callback?.let(activity.onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        callback = null
    }
}
