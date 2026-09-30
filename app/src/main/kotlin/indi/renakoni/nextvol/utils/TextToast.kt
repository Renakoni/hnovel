package indi.renakoni.nextvol.utils

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes

fun textToast(context: Context, @StringRes message: Int, duration: Int): Toast =
    textToast(context, context.getText(message), duration)

/** Foreground feedback uses text-only content, including above modal sheets. */
@Suppress("DEPRECATION")
fun textToast(context: Context, message: CharSequence?, duration: Int): Toast {
    val app = context.applicationContext
    val density = app.resources.displayMetrics.density
    return Toast.makeText(app, message, duration).apply {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        // Android blocks custom Toast content while the app is in the background.
        if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return@apply
        view = TextView(app).apply {
            id = android.R.id.message
            text = message
            textSize = 14f
            setTextColor(0xFFF5F5F5.toInt())
            gravity = Gravity.CENTER
            maxWidth = (resources.displayMetrics.widthPixels - 48 * density).toInt()
            setPadding((18 * density).toInt(), (12 * density).toInt(),
                (18 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF28282B.toInt())
                cornerRadius = 22 * density
            }
        }
    }
}
