package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness of the clustering pipeline is verified with synthetic images whose color
 * composition is known exactly (the "known-color test images" of the write-up), so the
 * percentages can be asserted numerically rather than eyeballed.
 */
class ColorQuantizerTest {

    /**
     * Written down explicitly because the tunables are a constructor parameter: passing the
     * config is what makes "the tests can vary them" true, and a quantizer that built its own
     * would silently ignore this one.
     */
    private val config = AnalysisConfig()
    private val quantizer = ColorQuantizer(config)

    private val red = RgbColor(220, 30, 20)
    private val green = RgbColor(30, 200, 60)
    private val blue = RgbColor(40, 60, 210)
    private val yellow = RgbColor(240, 210, 40)
    private val purple = RgbColor(140, 40, 200)
    private val cyan = RgbColor(40, 200, 200)

    /** Builds a frame made of horizontal bands: (rows, color). */
    private fun frameOf(width: Int, height: Int, bands: List<Pair<Int, RgbColor>>, noise: Int = 0): FrameData {
        val pixels = IntArray(width * height)
        var row = 0
        var seed = 12345
        for ((rows, color) in bands) {
            repeat(rows) {
                for (col in 0 until width) {
                    if (noise == 0) {
                        pixels[row * width + col] = color.argb
                    } else {
                        // Deterministic pseudo-noise: a small LCG so the test is reproducible.
                        seed = seed * 1103515245 + 12345
                        val jitter = (seed ushr 16) % (2 * noise + 1) - noise
                        seed = seed * 1103515245 + 12345
                        val jitter2 = (seed ushr 16) % (2 * noise + 1) - noise
                        pixels[row * width + col] = RgbColor.of(
                            color.r + jitter,
                            color.g + jitter2,
                            color.b - jitter,
                        ).argb
                    }
                }
                row++
            }
        }
        return FrameData(width, height, pixels)
    }

    @Test
    fun `known 60-30-10 image yields those percentages and colors`() {
        // 40 rows: 24 red (60%), 12 green (30%), 4 blue (10%).
        val frame = frameOf(
            width = 40,
            height = 40,
            bands = listOf(24 to red, 12 to green, 4 to blue),
        )

        val results = quantizer.quantize(frame, config.topColorCount)

        assertEquals(3, results.size)
        assertColorNear(red, results[0].rgb)
        assertEquals(60f, results[0].percentage, 1.5f)
        assertColorNear(green, results[1].rgb)
        assertEquals(30f, results[1].percentage, 1.5f)
        assertColorNear(blue, results[2].rgb)
        assertEquals(10f, results[2].percentage, 1.5f)
    }

    @Test
    fun `percentages sum to 100 and are sorted descending`() {
        val frame = frameOf(30, 30, listOf(10 to red, 8 to green, 7 to blue, 5 to yellow))

        val results = quantizer.quantize(frame, config.topColorCount)

        assertTrue("expected several colors but got $results", results.size >= 3)
        val total = results.sumOf { it.percentage.toDouble() }
        assertEquals(100.0, total, 0.05)
        for (index in 1 until results.size) {
            assertTrue(
                "results must be sorted descending but were $results",
                results[index - 1].percentage >= results[index].percentage,
            )
        }
    }

    @Test
    fun `sensor noise does not change the dominant color or its share`() {
        val clean = frameOf(40, 40, listOf(24 to red, 12 to green, 4 to blue))
        val noisy = frameOf(40, 40, listOf(24 to red, 12 to green, 4 to blue), noise = 4)

        val cleanResults = quantizer.quantize(clean, config.topColorCount)
        val noisyResults = quantizer.quantize(noisy, config.topColorCount)

        assertEquals(cleanResults.size, noisyResults.size)
        assertColorNear(red, noisyResults[0].rgb, tolerance = 6)
        assertEquals(60f, noisyResults[0].percentage, 3f)
        assertColorNear(green, noisyResults[1].rgb, tolerance = 6)
        assertEquals(30f, noisyResults[1].percentage, 3f)
    }

    @Test
    fun `identical input produces identical output every time`() {
        val frame = frameOf(40, 40, listOf(24 to red, 12 to green, 4 to blue))

        val first = quantizer.quantize(frame, config.topColorCount)
        val second = quantizer.quantize(frame, config.topColorCount)

        assertEquals(first, second)
    }

    @Test
    fun `frame to frame stability - a small scene change keeps the same colors in the same order`() {
        val frameA = frameOf(40, 40, listOf(24 to red, 12 to green, 4 to blue))
        // Same scene, slightly different exposure/lighting: every color shifted by ~3 units.
        val frameB = frameOf(40, 40, listOf(24 to red, 12 to green, 4 to blue), noise = 3)

        val resultsA = quantizer.quantize(frameA, config.topColorCount)
        val resultsB = quantizer.quantize(frameB, config.topColorCount)

        assertEquals(
            resultsA.map { it.rgb.r > it.rgb.b },
            resultsB.map { it.rgb.r > it.rgb.b },
        )
        assertEquals(resultsA.size, resultsB.size)
        for (index in resultsA.indices) {
            val distance = ColorMath.distance(resultsA[index].rgb, resultsB[index].rgb)
            assertTrue(
                "color $index moved by $distance between frames; expected < 12 (${resultsA[index]} -> ${resultsB[index]})",
                distance < 12.0,
            )
        }
    }

    @Test
    fun `single color frame reports 100 percent`() {
        val frame = frameOf(16, 16, listOf(16 to red))

        val results = quantizer.quantize(frame, config.topColorCount)

        assertEquals(1, results.size)
        assertEquals(100f, results[0].percentage, 0.01f)
        assertColorNear(red, results[0].rgb)
    }

    @Test
    fun `nearly identical colors are merged into one entry`() {
        val frame = frameOf(20, 20, listOf(10 to RgbColor(200, 10, 10), 10 to RgbColor(210, 15, 8)))

        val results = quantizer.quantize(frame, config.topColorCount)

        assertEquals("colors 11 units apart must merge, got $results", 1, results.size)
        assertEquals(100f, results[0].percentage, 0.01f)
    }

    @Test
    fun `the injected config, not a built-in default, decides what merges`() {
        // The same frame the merge test uses: two colors ~11 units apart.
        val frame = frameOf(20, 20, listOf(10 to RgbColor(200, 10, 10), 10 to RgbColor(210, 15, 8)))

        val merging = ColorQuantizer(AnalysisConfig(mergeDistance = 24.0))
            .quantize(frame, config.topColorCount)
        assertEquals("mergeDistance = 24 must fold them together, got $merging", 1, merging.size)

        // Same frame, same algorithm, different injected config. A quantizer that quietly built
        // its own AnalysisConfig would return 1 here as well — which is exactly the bug this
        // pins down.
        val separating = ColorQuantizer(AnalysisConfig(mergeDistance = 0.0))
            .quantize(frame, config.topColorCount)
        assertEquals("mergeDistance = 0 must keep them apart, got $separating", 2, separating.size)
    }

    @Test
    fun `never returns more than the requested number of colors`() {
        val frame = frameOf(
            width = 30,
            height = 60,
            bands = listOf(
                20 to red, 16 to green, 12 to blue, 6 to yellow, 4 to purple, 2 to cyan,
            ),
        )

        val results = quantizer.quantize(frame, topColorCount = 5)

        assertEquals(5, results.size)
        assertColorNear(red, results.first().rgb)
    }

    @Test
    fun `empty frame yields no colors`() {
        val empty = FrameData(0, 0, IntArray(0))

        assertTrue(quantizer.quantize(empty, config.topColorCount).isEmpty())
    }

    @Test
    fun `black frame is reported as black rather than dropped`() {
        val frame = frameOf(16, 16, listOf(16 to RgbColor.Black))

        val results = quantizer.quantize(frame, config.topColorCount)

        assertEquals(listOf(ColorResult(RgbColor.Black, 100f)), results)
    }

    private fun assertColorNear(expected: RgbColor, actual: RgbColor, tolerance: Int = 3) {
        val distance = ColorMath.distance(expected, actual)
        assertTrue(
            "expected $expected within $tolerance but was $actual (distance $distance)",
            distance <= tolerance.toDouble(),
        )
    }
}
