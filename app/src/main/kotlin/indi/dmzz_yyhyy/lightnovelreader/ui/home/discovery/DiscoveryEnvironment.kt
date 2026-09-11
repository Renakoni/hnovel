package indi.dmzz_yyhyy.lightnovelreader.ui.home.discovery

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryEnvironment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Data snapshots of the current host appearance, without preference handles or local file paths. */
@Composable
internal fun discoveryEnvironment(): DiscoveryEnvironment {
    val colors = MaterialTheme.colorScheme
    val reading = LocalReaderStyle.current
    return DiscoveryEnvironment(if (colors.background.luminance() < 0.5f) "2" else "1",
        buildJsonObject {
            put("primaryColor", colors.primary.toArgb()); put("backgroundColor", colors.background.toArgb())
            put("textColor", colors.onBackground.toArgb())
        }.toString(), buildJsonObject {
            put("fontSize", reading.fontSize); put("lineSpacingExtra", reading.fontLineHeight); put("fontWeight", reading.fontWeight)
        }.toString())
}
