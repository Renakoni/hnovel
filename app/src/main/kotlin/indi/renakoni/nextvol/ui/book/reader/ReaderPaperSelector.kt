package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.LocalLightColorScheme

@Composable
internal fun ReaderPaperSelector(settings: ReaderSettingsEditor) {
    val selected = ReaderPaper.fromId(settings.paperId)
    val defaultScheme = LocalLightColorScheme.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val columns = if (maxWidth >= 360.dp && fontScale <= 1.3f) 4 else 2
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderPaper.entries.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { paper ->
                        val colors = paper.colors
                        val foreground = colors?.text ?: defaultScheme.onSurface
                        val accent = colors?.accent ?: defaultScheme.primary
                        val isSelected = paper == selected
                        val shape = RoundedCornerShape(12.dp)
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = shape,
                            color = colors?.background ?: defaultScheme.background,
                            contentColor = foreground,
                            border = BorderStroke(if (isSelected) 2.dp else 1.dp,
                                if (isSelected) accent else foreground.copy(alpha = 0.18f)),
                        ) {
                            Column(
                                Modifier.testTag("reader-paper-${paper.id}").selectable(selected = isSelected, role = Role.RadioButton,
                                    onClick = { settings.paperIdUserData.asynchronousSet(paper.id) })
                                    .heightIn(min = 84.dp).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.reader_paper_sample),
                                        style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                                    Box(Modifier.size(18.dp)) {
                                        if (isSelected) Icon(painterResource(R.drawable.check_24px),
                                            contentDescription = null, tint = accent)
                                    }
                                }
                                Text(stringResource(paper.label), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
