package indi.renakoni.nextvol.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun uriLauncher(persistPermission: Boolean = false, block: (Uri) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val context = androidx.compose.ui.platform.LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            activityResult.data?.data?.let { uri ->
                if (persistPermission) {
                    val flags = (activityResult.data?.flags ?: 0) and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    try { context.contentResolver.takePersistableUriPermission(uri, flags) }
                    catch (_: SecurityException) { /* Some providers only offer the current grant. */ }
                }
                block(uri)
            }
        }
    }
}

@Composable
fun uriLauncherWithFlag(
    block: (Uri, Boolean) -> Unit
): Pair<ManagedActivityResultLauncher<Intent, ActivityResult>, (Boolean) -> Unit> {
    var isDarkFlag by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            activityResult.data?.data?.let { uri ->
                block(uri, isDarkFlag)
            }
        }
    }

    val setFlag: (Boolean) -> Unit = { flag ->
        isDarkFlag = flag
    }

    return launcher to setFlag
}
