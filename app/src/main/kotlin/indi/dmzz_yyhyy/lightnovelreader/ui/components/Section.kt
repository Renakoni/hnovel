package indi.dmzz_yyhyy.lightnovelreader.ui.components

import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
fun SectionHeader(
    modifier: Modifier = Modifier,
    text: String
) {
    Text(
        text = text,
        modifier = modifier,
        color = colorScheme.onSurfaceVariant,
        style = typography.bodyLarge,
        fontWeight = FontWeight.W600
    )
}

@Composable
fun SectionDescription(
    modifier: Modifier = Modifier,
    text: String
) {
    Text(
        text = text,
        modifier = modifier,
        color = colorScheme.onSurfaceVariant,
        style = typography.bodyMedium
    )
}
