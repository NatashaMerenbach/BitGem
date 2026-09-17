package com.bitgem.colorcam.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitgem.colorcam.R
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.ui.theme.ColorCamTheme
import com.bitgem.colorcam.ui.theme.PanelBackground
import com.bitgem.colorcam.ui.theme.PanelMutedTextColor
import com.bitgem.colorcam.ui.theme.PanelTextColor

/**
 * The breakdown panel: an opaque black overlay pinned to the trailing edge of the preview.
 *
 * Structure (from the reference UI): a heading, then one row per colour, sorted by percentage
 * descending — the ordering comes from the domain layer, this composable never sorts anything.
 * Each row is a rounded swatch filled with the colour itself holding the percentage, with the
 * RGB values as a smaller line *below* the swatch, in white on the panel.
 *
 * Rows share the available height equally, so the panel looks identical whether the analyser
 * found five colours or two.
 */
@Composable
fun ColorBreakdownPanel(
    colorResults: List<ColorResult>,
    isWaitingForFrames: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            // Opaque black: this is what produces the hard vertical edge over the preview.
            .background(PanelBackground)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.color_breakdown_title),
            style = MaterialTheme.typography.titleMedium,
            color = PanelTextColor,
        )

        if (colorResults.isEmpty()) {
            if (isWaitingForFrames) {
                Text(
                    text = stringResource(R.string.color_panel_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMutedTextColor,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = stringResource(R.string.color_panel_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMutedTextColor,
                )
            }
            return@Column
        }

        colorResults.forEach { colorResult ->
            ColorBreakdownRow(
                colorResult = colorResult,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/** One swatch: the colour as a filled box holding the percentage, the RGB values underneath. */
@Composable
fun ColorBreakdownRow(
    colorResult: ColorResult,
    modifier: Modifier = Modifier,
) {
    val argb = colorResult.argb
    // Not hard-coded to white or black: whichever of the two has the higher WCAG contrast ratio
    // against the swatch wins (pure domain function, covered by RgbColorTest).
    val onSwatch = remember(argb) { Color(colorResult.rgb.contrastingTextColor().argb) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(argb))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.color_card_percentage, colorResult.percentage),
                color = onSwatch,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Text(
            text = stringResource(R.string.color_card_rgb, colorResult.rgb.r, colorResult.rgb.g, colorResult.rgb.b),
            color = PanelTextColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Preview(heightDp = 480, widthDp = 140)
@Composable
private fun ColorBreakdownPanelPreview() {
    ColorCamTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            ColorBreakdownPanel(
                colorResults = listOf(
                    ColorResult(RgbColor(116, 114, 94), 8.24f),
                    ColorResult(RgbColor(101, 99, 77), 7.11f),
                    ColorResult(RgbColor(119, 120, 116), 3.12f),
                    ColorResult(RgbColor(235, 236, 230), 1.29f),
                    ColorResult(RgbColor(119, 120, 115), 0.14f),
                ),
                isWaitingForFrames = false,
            )
        }
    }
}
