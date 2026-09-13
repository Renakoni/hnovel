package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.DiagnosticStage
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.SourceCheckSummary
import java.util.Date

@Composable
internal fun SourceCheckStatus(summary: SourceCheckSummary?, current: Boolean, details: Boolean = false) {
    if (summary == null) {
        Text(stringResource(R.string.sources_not_checked), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val context = LocalContext.current
    val date = Date(summary.checkedAtMillis)
    val time = DateFormat.getDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
    val stage = stringResource(when (summary.stage) {
        DiagnosticStage.Search -> R.string.sources_check_search
        DiagnosticStage.Information -> R.string.sources_check_information
        DiagnosticStage.Directory -> R.string.sources_check_directory
        DiagnosticStage.Content -> R.string.sources_check_content
        DiagnosticStage.Discovery -> if (summary.discoveryPage) R.string.sources_check_discovery_page else R.string.sources_check_discovery_catalog
        DiagnosticStage.LoginForm -> R.string.sources_check_login
    })
    val result = stringResource(if (summary.result == "Success") R.string.sources_check_passed else R.string.sources_check_failed)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sources_check_summary, stage, result, time), style = MaterialTheme.typography.bodySmall)
        if (!current) Text(stringResource(R.string.sources_check_stale), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (details) Text(stringResource(R.string.sources_check_scope, summary.count), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
