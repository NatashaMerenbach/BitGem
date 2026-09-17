package com.bitgem.colorcam.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbColorTest {

    @Test
    fun `packs into argb`() {
        assertEquals(0xFFE85A3C.toInt(), RgbColor(232, 90, 60).argb)
    }

    @Test
    fun `unpacks from argb and ignores alpha`() {
        assertEquals(RgbColor(232, 90, 60), RgbColor.fromArgb(0x80E85A3C.toInt()))
    }

    @Test
    fun `luminance of black and white are the extremes`() {
        assertEquals(0.0, RgbColor.Black.relativeLuminance, 0.0001)
        assertEquals(1.0, RgbColor.White.relativeLuminance, 0.0001)
    }

    @Test
    fun `contrast ratio between black and white is 21`() {
        assertEquals(21.0, RgbColor.Black.contrastRatioWith(RgbColor.White), 0.0001)
    }

    @Test
    fun `light swatches get dark text`() {
        val paleYellow = RgbColor(242, 201, 76)
        assertEquals(RgbColor.Black, paleYellow.contrastingTextColor())
        assertEquals(RgbColor.Black, RgbColor.White.contrastingTextColor())
        assertEquals(RgbColor.Black, RgbColor(180, 220, 140).contrastingTextColor())
    }

    @Test
    fun `dark swatches get light text`() {
        val navy = RgbColor(18, 22, 30)
        assertEquals(RgbColor.White, navy.contrastingTextColor())
        assertEquals(RgbColor.White, RgbColor.Black.contrastingTextColor())
        assertEquals(RgbColor.White, RgbColor(120, 20, 60).contrastingTextColor())
    }

    @Test
    fun `the chosen text colour always has the higher contrast ratio`() {
        val swatches = listOf(
            RgbColor(242, 201, 76),
            RgbColor(18, 22, 30),
            RgbColor(128, 128, 128),
            RgbColor(90, 120, 200),
            RgbColor(200, 90, 30),
        )

        for (swatch in swatches) {
            val chosen = swatch.contrastingTextColor()
            val alternative = if (chosen == RgbColor.Black) RgbColor.White else RgbColor.Black
            assertTrue(
                "for $swatch the chosen text colour was not the higher-contrast one",
                swatch.contrastRatioWith(chosen) >= swatch.contrastRatioWith(alternative),
            )
        }
    }

    @Test
    fun `computed components are clamped`() {
        assertEquals(RgbColor(255, 255, 0), RgbColor.of(300, 999, -10))
        assertEquals(RgbColor(255, 0, 128), RgbColor.of(255.4, -0.2, 127.6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `raw construction outside 0-255 is rejected`() {
        RgbColor(256, 0, 0)
    }
}
