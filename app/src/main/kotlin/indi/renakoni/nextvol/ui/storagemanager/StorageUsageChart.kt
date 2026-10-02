package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot
import indi.renakoni.nextvol.ui.home.reading.stats.predefinedColors
import indi.renakoni.nextvol.utils.formatSize
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun StorageUsageChart(snapshot: StorageUsageSnapshot, loading: Boolean) {
    val sections = snapshot.sections()
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedSection = selectedIndex?.let { sections[it] }
    val colors = MaterialTheme.colorScheme
    // Reuse the reading-statistics palette; reserve the error color for destructive actions.
    val categoryColors = listOf(predefinedColors[0], predefinedColors[4], predefinedColors[1],
        predefinedColors[2], predefinedColors[5], colors.outline)
    val updated = remember(snapshot.calculatedAt) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.calculatedAt))
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.storage_distribution), style = MaterialTheme.typography.displayMedium)
                Text(if (loading) stringResource(R.string.storage_measuring) else stringResource(R.string.storage_updated_at, updated),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sideBySide = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.15f
                val chart: @Composable () -> Unit = {
                    StorageDonut(sections, snapshot.totalBytes, categoryColors, selectedIndex)
                }
                val legend: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sections.forEachIndexed { index, section ->
                            val selected = index == selectedIndex
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                                .background(if (selected) categoryColors[index].copy(alpha = .12f) else Color.Transparent)
                                .semantics { this.selected = selected }
                                .testTag("storage_category_$index")
                                .clickable(role = Role.Button) { selectedIndex = if (selected) null else index }
                                .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).background(categoryColors[index], CircleShape))
                                if (sideBySide) Column(Modifier.weight(1f)) {
                                    Text(stringResource(section.title), style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant)
                                    Text(formatSize(section.size), style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold)
                                } else {
                                    Text(stringResource(section.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant)
                                    Text(formatSize(section.size), style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                if (sideBySide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    chart()
                    legend(Modifier.weight(1f))
                } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    chart()
                    legend(Modifier.fillMaxWidth())
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedSection != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(selectedSection.title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    val share = if (snapshot.totalBytes > 0) selectedSection.size.toDouble() / snapshot.totalBytes else 0.0
                    Text(NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(share),
                        style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
                Text(stringResource(selectedSection?.description ?: R.string.storage_chart_hint),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StorageDonut(sections: List<StorageManagerSection>, total: Long, colors: List<Color>, selected: Int?) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColors = colors.mapIndexed { index, color ->
        animateColorAsState(if (selected == null || selected == index) color else color.copy(alpha = .2f), label = "storage segment").value
    }
    val diameter = (164 * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val bounds = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), bounds, style = Stroke(stroke))
            var start = -90f
            sections.forEachIndexed { index, section ->
                val sweep = if (total > 0) (section.size.toDouble() / total * 360).toFloat() else 0f
                val gap = minOf(3f, sweep / 5)
                if (sweep > 0f) drawArc(arcColors[index], start + gap / 2, sweep - gap, false,
                    Offset(inset, inset), bounds, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        Column(Modifier.width(diameter - 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatSize(total), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(stringResource(R.string.storage_used_short), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
