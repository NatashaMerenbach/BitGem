package com.bitgem.colorcam.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitgem.colorcam.R
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.ui.theme.ColorCamTheme

/**
 * One swatch: the colour itself as the background, the percentage large and bold, the RGB
 * values small underneath.
 *
 * The text colour is not hard-coded to white or black — [RgbColor.contrastingTextColor]
 * picks whichever of the two has the higher WCAG contrast ratio against the swatch, which is
 * a pure domain function and therefore unit-tested (`RgbColorTest.contrastSelection`).
 */
@Composable
fun ColorSwatchCard(
    color: ColorResult,
    displayPercentage: Int,
    modifier: Modifier = Modifier,
) {
    val argb = color.argb
    val contentColor = remember(argb) { Color(color.rgb.contrastingTextColor().argb) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(argb))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.color_card_percentage, displayPercentage),
            color = contentColor,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.color_card_rgb, color.rgb.r, color.rgb.g, color.rgb.b),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Preview(widthDp = 140)
@Composable
private fun ColorSwatchCardPreview() {
    ColorCamTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A light swatch and a dark one, to show the auto-contrast in action.
            ColorSwatchCard(ColorResult(RgbColor(242, 201, 76), 41f), 41, Modifier.fillMaxWidth())
            ColorSwatchCard(ColorResult(RgbColor(18, 22, 30), 23f), 23, Modifier.fillMaxWidth())
        }
    }
}
