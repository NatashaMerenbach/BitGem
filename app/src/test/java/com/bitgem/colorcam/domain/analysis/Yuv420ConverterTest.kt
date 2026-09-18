package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the hand-written YUV_420_888 → RGB conversion against the BT.601 reference
 * vectors, and against the two plane layouts that actually differ on real devices
 * (planar I420 with `pixelStride = 1` and semi-planar NV12/NV21 with `pixelStride = 2`),
 * plus row padding.
 */
class Yuv420ConverterTest {

    private val converter = Yuv420Converter()

    @Test
    fun `white frame converts to white`() {
        val frame = YuvTestFrames.flat(8, 8, YuvTestFrames.WHITE_Y, 128, 128)

        val converted = converter.convert(frame)

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals(0xFFFFFFFF.toInt(), converted.pixelAt(x, y))
            }
        }
    }

    @Test
    fun `black frame converts to black`() {
        val frame = YuvTestFrames.flat(8, 8, YuvTestFrames.BLACK_Y, 128, 128)

        val converted = converter.convert(frame)

        assertEquals(0xFF000000.toInt(), converted.pixelAt(4, 4))
    }

    @Test
    fun `red frame converts to red within rounding tolerance`() {
        val frame = YuvTestFrames.flat(4, 4, YuvTestFrames.RED_Y, YuvTestFrames.RED_U, YuvTestFrames.RED_V)

        val pixel = RgbColor.fromArgb(converter.convert(frame).pixelAt(1, 1))

        // Expected (254, 0, 0): the 1.402 coefficient cannot reach 255 from Y=76.
        assertTrue("expected a saturated red but was $pixel", pixel.r >= 250)
        assertTrue("expected almost no green but was $pixel", pixel.g <= 3)
        assertTrue("expected almost no blue but was $pixel", pixel.b <= 3)
    }

    @Test
    fun `green and blue frames convert correctly`() {
        val green = RgbColor.fromArgb(
            converter.convert(YuvTestFrames.flat(4, 4, YuvTestFrames.GREEN_Y, YuvTestFrames.GREEN_U, YuvTestFrames.GREEN_V))
                .pixelAt(0, 0),
        )
        val blue = RgbColor.fromArgb(
            converter.convert(YuvTestFrames.flat(4, 4, YuvTestFrames.BLUE_Y, YuvTestFrames.BLUE_U, YuvTestFrames.BLUE_V))
                .pixelAt(0, 0),
        )

        assertTrue("expected green but was $green", green.g >= 250 && green.r <= 3 && green.b <= 5)
        assertTrue("expected blue but was $blue", blue.b >= 250 && blue.r <= 3 && blue.g <= 3)
    }

    /**
     * The classic bug: treating a padded Y plane as if `rowStride == width`. With a stride
     * of width + 4 the naive implementation reads four bytes into the next row per row,
     * which shifts the image diagonally and mixes colours.
     */
    @Test
    fun `row padding is honoured`() {
        val width = 6
        val height = 4
        val yRowStride = width + 4
        val frame = YuvTestFrames.flat(
            width = width,
            height = height,
            y = YuvTestFrames.RED_Y,
            u = YuvTestFrames.RED_U,
            v = YuvTestFrames.RED_V,
            yRowStride = yRowStride,
            uvRowStride = width,
        )

        val converted = converter.convert(frame)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = RgbColor.fromArgb(converted.pixelAt(x, y))
                assertTrue("pixel ($x, $y) should still be red but was $pixel", pixel.r >= 250)
            }
        }
    }

    /**
     * Semi-planar layout: U and V live in the same buffer with `pixelStride = 2`, so the U
     * plane's buffer holds U at even and V at odd indices.
     */
    @Test
    fun `interleaved chroma with pixel stride 2 is honoured`() {
        val width = 4
        val height = 4
        val uvRowStride = width
        val yPlane = ByteArray(width * height) { YuvTestFrames.RED_Y.toByte() }
        val uPlane = ByteArray(uvRowStride * (height / 2))
        val vPlane = ByteArray(uvRowStride * (height / 2))
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                // Pixel stride 2 => sample n sits at offset n * 2.
                uPlane[row * uvRowStride + col * 2] = YuvTestFrames.RED_U.toByte()
                vPlane[row * uvRowStride + col * 2] = YuvTestFrames.RED_V.toByte()
            }
        }
        val frame = YuvTestFrames.frame(
            width = width,
            height = height,
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yRowStride = width,
            uvRowStride = uvRowStride,
            uvPixelStride = 2,
        )

        val pixel = RgbColor.fromArgb(converter.convert(frame).pixelAt(2, 2))

        assertTrue("expected red from interleaved chroma but was $pixel", pixel.r >= 250)
    }

    /** A truncated plane must not throw; the affected pixels fall back to neutral chroma. */
    @Test
    fun `truncated luma plane degrades to grey instead of throwing`() {
        val frame = YuvTestFrames.frame(
            width = 8,
            height = 4,
            yPlane = ByteArray(8), // far too short for 8x4 with stride 8
            uPlane = ByteArray(4),
            vPlane = ByteArray(4),
        )

        val converted = converter.convert(frame)

        assertEquals(8 * 4, converted.pixelCount)
    }

    @Test
    fun `convertInto writes into the supplied buffer and reports the right size`() {
        val frame = YuvTestFrames.flat(4, 2, YuvTestFrames.WHITE_Y, 128, 128)
        val scratch = IntArray(16)

        val converted = converter.convertIntoFrameData(frame, scratch)

        assertEquals(4, converted.width)
        assertEquals(2, converted.height)
        assertEquals(0xFFFFFFFF.toInt(), scratch[0])
    }

    @Test
    fun `sampling converts every 4th pixel in both axes, tightly packed`() {
        // Luma carries the pixel's own index and chroma is neutral, so each converted value
        // identifies the position it came from (with neutral chroma, R = G = B = Y).
        val width = 8
        val height = 8
        val luma = ByteArray(width * height) { (it and 0xFF).toByte() }
        val neutral = ByteArray((width / 2) * (height / 2)) { 128.toByte() }
        val frame = YuvTestFrames.frame(width, height, luma, neutral, neutral)

        val converted = converter.convert(frame, step = 4)

        // ceil(8/4) x ceil(8/4) samples, packed row-major with no gaps: the pixels of the
        // returned FrameData *are* the sample grid, so nothing downstream walks a stride.
        assertEquals(2, converted.width)
        assertEquals(2, converted.height)
        assertEquals(4, converted.pixels.size)
        assertEquals(listOf(0, 4, 32, 36), converted.pixels.map { it shr 8 and 0xFF })
    }

    @Test
    fun `the production sampling step keeps the documented 1 in 16 ratio`() {
        // samplingStep is the pipeline's cost knob and its effect is quadratic: step 4 converts
        // 1/16 of a 640x480 analysis frame (19,200 of 307,200 pixels). PROCESS.md, the README
        // data-flow diagram and the converter's KDoc all describe that ratio, so it is pinned
        // here — quietly moving the default to 1 multiplies the YUV work per frame by 16 on a
        // background thread that runs up to 10 times a second.
        val step = AnalysisConfig().samplingStep

        assertEquals(19_200, Yuv420Converter.sampledPixelCount(640, 480, step))
    }

    @Test
    fun `an oversized buffer is legal and only the sampled prefix is written`() {
        val frame = YuvTestFrames.flat(8, 8, YuvTestFrames.WHITE_Y, 128, 128)
        val buffer = IntArray(Yuv420Converter.sampledPixelCount(8, 8, 4) + 8) { -1 }

        val converted = converter.convertIntoFrameData(frame, buffer, step = 4)

        assertEquals(4, converted.pixelCount)
        assertTrue(
            "the tail must stay untouched but was ${buffer.drop(4)}",
            buffer.drop(4).all { it == -1 },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a buffer smaller than the sampled count is rejected`() {
        val frame = YuvTestFrames.flat(8, 8, YuvTestFrames.WHITE_Y, 128, 128)
        val tooSmall = IntArray(Yuv420Converter.sampledPixelCount(8, 8, 4) - 1)

        converter.convertIntoFrameData(frame, tooSmall, step = 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `step below 1 is rejected`() {
        converter.convert(YuvTestFrames.flat(4, 4, YuvTestFrames.WHITE_Y, 128, 128), step = 0)
    }
}
