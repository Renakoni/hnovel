package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import hnovel.network.sourceOrigin
import indi.dmzz_yyhyy.lightnovelreader.R

/** Also validates external discovery-provider diagnostics before displaying them. */
@Composable
internal fun SourcePermissionLabel(origin: String, kind: String) {
    if (sourceOrigin(origin) != origin) return
    Text(origin, style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(when (kind) {
        "Image" -> R.string.sources_resource_image
        "Script" -> R.string.sources_resource_script
        "Api" -> R.string.sources_resource_api
        "Import" -> R.string.sources_resource_import
        else -> R.string.sources_resource_document
    }), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun SourcePermissionCandidate(origin: String, kind: String, draft: String, busy: Boolean, onAdd: (String) -> Unit) {
    if (sourceOrigin(origin) != origin) return
    val added = draft.lineSequence().any { sourceOrigin(it.trim()) == origin }
    Column {
        SourcePermissionLabel(origin, kind)
        TextButton(onClick = { onAdd((draft.trim().takeIf(String::isNotEmpty)?.plus("\n") ?: "") + origin) },
            enabled = !busy && !added) {
            Text(stringResource(if (added) R.string.sources_origin_in_draft else R.string.sources_origin_add))
        }
    }
}
