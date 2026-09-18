package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSmootherTest {

    private val red = ColorResult(RgbColor(200, 20, 20), 60f)
    private val green = ColorResult(RgbColor(20, 200, 20), 40f)

    @Test
    fun `first frame passes through unchanged`() {
        val smoother = ColorSmoother(alpha = 0.35f)

        val result = smoother.smooth(listOf(red, green))

        assertEquals(60f, result[0].percentage, 0.001f)
        assertEquals(RgbColor(200, 20, 20), result[0].rgb)
        assertEquals(40f, result[1].percentage, 0.001f)
    }

    @Test
    fun `a sudden change is only partially applied`() {
        val smoother = ColorSmoother(alpha = 0.5f, matchDistance = 64.0)
        smoother.smooth(listOf(red))

        // The same color, but its share jumps from 60% to 100%.
        val result = smoother.smooth(listOf(ColorResult(RgbColor(200, 20, 20), 100f)))

        // 60 + 0.5 * (100 - 60) = 80, then re-normalised (only one color => 100%).
        assertEquals(1, result.size)
        assertEquals(100f, result[0].percentage, 0.001f)
    }

    @Test
    fun `matching colors are blended rather than replaced`() {
        // matchDistance must exceed the distance between the two greys: sqrt(3 * 40^2) = 69.3.
        val smoother = ColorSmoother(alpha = 0.5f, matchDistance = 80.0)
        smoother.smooth(listOf(ColorResult(RgbColor(100, 100, 100), 50f), green))

        val result = smoother.smooth(
            listOf(ColorResult(RgbColor(140, 140, 140), 50f), green),
        )

        val blended = result.first { it.rgb.g == it.rgb.r && it.rgb.b == it.rgb.r }
        // 100 + 0.5 * (140 - 100) = 120
        assertEquals(120, blended.rgb.r)
    }

    @Test
    fun `a color further away than matchDistance is adopted as-is`() {
        val smoother = ColorSmoother(alpha = 0.5f, matchDistance = 10.0)
        smoother.smooth(listOf(ColorResult(RgbColor(100, 100, 100), 50f), green))

        val result = smoother.smooth(listOf(ColorResult(RgbColor(140, 140, 140), 50f), green))

        assertTrue(result.any { it.rgb == RgbColor(140, 140, 140) })
    }

    @Test
    fun `an unmatched color is adopted immediately`() {
        val smoother = ColorSmoother(alpha = 0.35f, matchDistance = 10.0)
        smoother.smooth(listOf(red))

        val result = smoother.smooth(listOf(red, ColorResult(RgbColor(0, 0, 255), 10f)))

        assertTrue(result.any { it.rgb == RgbColor(0, 0, 255) })
    }

    @Test
    fun `an empty frame clears the history`() {
        val smoother = ColorSmoother()
        smoother.smooth(listOf(red))

        assertTrue(smoother.smooth(emptyList()).isEmpty())

        val fresh = smoother.smooth(listOf(ColorResult(RgbColor(10, 10, 10), 100f)))
        assertEquals(RgbColor(10, 10, 10), fresh[0].rgb)
    }

    @Test
    fun `alpha of 1 disables smoothing`() {
        val smoother = ColorSmoother(alpha = 1f, matchDistance = 64.0)
        smoother.smooth(listOf(red, green))

        val result = smoother.smooth(
            listOf(ColorResult(RgbColor(0, 0, 0), 60f), ColorResult(RgbColor(20, 200, 20), 40f)),
        )

        // With alpha = 1 the frame passes straight through (still re-normalised to 100%).
        assertEquals(60f, result.first { it.rgb == RgbColor(0, 0, 0) }.percentage, 0.01f)
        assertEquals(40f, result.first { it.rgb == RgbColor(20, 200, 20) }.percentage, 0.01f)
    }

    @Test
    fun `output always sums to 100`() {
        val smoother = ColorSmoother(alpha = 0.3f)
        smoother.smooth(listOf(red, green))

        val result = smoother.smooth(
            listOf(ColorResult(RgbColor(200, 20, 20), 55f), ColorResult(RgbColor(20, 200, 20), 25f)),
        )

        assertEquals(100.0, result.sumOf { it.percentage.toDouble() }, 0.01)
    }
}
