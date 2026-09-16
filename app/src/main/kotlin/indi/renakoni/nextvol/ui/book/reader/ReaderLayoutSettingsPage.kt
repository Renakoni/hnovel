package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextContent
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.utils.readerBackgroundColor
import indi.renakoni.nextvol.utils.rememberReaderBackgroundPainter
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import kotlin.math.roundToInt

@Composable
internal fun ReaderLayoutSettingsPage(settings: ReaderSettingsEditor) {
    val stored = ReaderLayoutSettings.from(settings)
    var draft by remember(stored) { mutableStateOf(stored) }
    val previewSettings = object : ReaderSettingsEditor by settings {
        override val fontSize = draft.fontSize
        override val fontWeigh = draft.fontWeight
        override val fontLineHeight = draft.lineSpacing
        override val paragraphSpacing = draft.paragraphSpacing
        override val autoPadding = draft.autoPadding
        override val topPadding = draft.top
        override val bottomPadding = draft.bottom
        override val leftPadding = draft.start
        override val rightPadding = draft.end
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewHeight = minOf(204.dp, maxHeight * 0.35f)
            Column(Modifier.fillMaxSize()) {
                // Keep the actual page visible while the independent controls scroll beneath it.
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)) {
                    Text(stringResource(R.string.reader_layout_preview),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReaderLayoutPreview(previewSettings, Modifier.fillMaxWidth().height(previewHeight))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SettingsCategory(title = stringResource(R.string.settings_theme_text_font)) { ReaderFontEntry(settings) }
                    SettingsCategory(title = stringResource(R.string.reader_layout_typography)) {
                        ReaderTypographyControls(settings, draft) { draft = it }
                    }
                    SettingsCategory(title = stringResource(R.string.margin_settings)) {
                        SettingsSwitchEntry(
                            title = stringResource(R.string.settings_reader_auto_margin),
                            description = stringResource(R.string.settings_reader_auto_margin_desc),
                            checked = draft.autoPadding, booleanUserData = settings.autoPaddingUserData,
                        )
                        if (!draft.autoPadding) {
                            ReaderLayoutSlider(R.string.settings_reader_top_margin, draft.top, ReaderLayoutValues.marginRange, "dp", settings.topPaddingUserData) { draft = draft.copy(top = it) }
                            ReaderLayoutSlider(R.string.settings_reader_bottom_margin, draft.bottom, ReaderLayoutValues.marginRange, "dp", settings.bottomPaddingUserData) { draft = draft.copy(bottom = it) }
                            ReaderLayoutSlider(R.string.settings_reader_left_margin, draft.start, ReaderLayoutValues.marginRange, "dp", settings.leftPaddingUserData) { draft = draft.copy(start = it) }
                            ReaderLayoutSlider(R.string.settings_reader_right_margin, draft.end, ReaderLayoutValues.marginRange, "dp", settings.rightPaddingUserData) { draft = draft.copy(end = it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderTypographyControls(settings: ReaderSettingsEditor, layout: ReaderLayoutSettings, onChange: (ReaderLayoutSettings) -> Unit) {
    ReaderLayoutSlider(R.string.settings_reader_font_size, layout.fontSize, ReaderLayoutValues.fontSizeRange, "sp", settings.fontSizeUserData) { onChange(layout.copy(fontSize = it)) }
    ReaderLayoutSlider(R.string.settings_theme_text_font_weight, layout.fontWeight, ReaderLayoutValues.fontWeightRange, "", settings.fontWeighUserData, step = 100f) { onChange(layout.copy(fontWeight = it)) }
    ReaderLayoutSlider(R.string.settings_reader_line_spacing, layout.lineSpacing, ReaderLayoutValues.lineSpacingRange, "sp", settings.fontLineHeightUserData) { onChange(layout.copy(lineSpacing = it)) }
    ReaderLayoutSlider(R.string.reader_paragraph_spacing, layout.paragraphSpacing, ReaderLayoutValues.paragraphSpacingRange, "sp", settings.paragraphSpacingUserData) { onChange(layout.copy(paragraphSpacing = it)) }
}

@Composable
private fun ReaderLayoutSlider(
    title: Int, value: Float, range: ClosedFloatingPointRange<Float>, unit: String,
    userData: FloatUserData, step: Float = 0.5f, onChange: (Float) -> Unit,
) {
    val label = stringResource(title)
    // Accessibility can change and finish the value before Compose gets another frame.
    var pendingValue by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text("${if (value % 1f == 0f) value.toInt().toString() else value.toString()} $unit",
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            modifier = Modifier.semantics { contentDescription = label },
            value = value,
            onValueChange = {
                pendingValue = ((it / step).roundToInt() * step).coerceIn(range)
                onChange(pendingValue)
            },
            onValueChangeFinished = { userData.asynchronousSet(pendingValue) },
            valueRange = range,
        )
    }
}

@Composable
internal fun ReaderLayoutPreview(settings: ReaderSettingsEditor, modifier: Modifier = Modifier) {
    val window = LocalWindowInfo.current.containerSize
    val layout = rememberReaderTextLayout(settings)
    val indicators = settings.enableTimeIndicator || settings.enableReadingChapterProgressIndicator || settings.enableChapterTitleIndicator
    val padding = readerPadding(layout.settings, if (indicators) 40.dp else 0.dp)
    val sample = stringResource(R.string.reader_layout_sample)
    // Measure a full-size reader viewport first; uniformly scale only its drawing into the preview.
    Layout(modifier = modifier.clipToBounds().padding(8.dp), content = {
        Box(Modifier.fillMaxSize().clipToBounds().background(readerBackgroundColor(settings))) {
            if (settings.enableBackgroundImage) Image(
                painter = rememberReaderBackgroundPainter(settings), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
            CompositionLocalProvider(LocalReaderTextLayout provides layout) {
                ReaderTextContent(sample, settings.fontFamilyUriUserData, Modifier.fillMaxSize().padding(padding), paginate = settings.isUsingFlipPage)
            }
        }
    }) { measurables, constraints ->
        val width = window.width.coerceAtLeast(1)
        val height = window.height.coerceAtLeast(1)
        val page = measurables.single().measure(Constraints.fixed(width, height))
        val scale = minOf(constraints.maxWidth.toFloat() / width, constraints.maxHeight.toFloat() / height)
        layout(constraints.maxWidth, constraints.maxHeight) {
            page.placeWithLayer(((constraints.maxWidth - width * scale) / 2).roundToInt(), 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
            }
        }
    }
}
