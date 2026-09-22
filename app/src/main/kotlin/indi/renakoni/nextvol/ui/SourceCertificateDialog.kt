package indi.renakoni.nextvol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hnovel.network.CertificateIssue
import hnovel.network.CertificateProblem
import indi.renakoni.nextvol.R

@Composable
internal fun SourceCertificateDialog(problem: CertificateProblem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_certificate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(problem.origin, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when (problem.issue) {
                    CertificateIssue.Expired -> R.string.source_certificate_expired
                    CertificateIssue.NotYetValid -> R.string.source_certificate_not_yet_valid
                    CertificateIssue.Untrusted -> R.string.source_certificate_untrusted
                }))
                Text(stringResource(R.string.source_certificate_help))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.source_certificate_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}
