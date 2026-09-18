package com.bitgem.colorcam.data.repository

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.bitgem.colorcam.data.camera.ElapsedTimeSource
import com.bitgem.colorcam.data.camera.ImageToYuv420Frame
import com.bitgem.colorcam.domain.analysis.AnalysisConfig
import com.bitgem.colorcam.domain.analysis.ColorSmoother
import com.bitgem.colorcam.domain.analysis.ColorQuantizer
import com.bitgem.colorcam.domain.analysis.Yuv420Converter
import com.bitgem.colorcam.domain.model.RgbColor
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * Drives the repository with mocked `ImageProxy`s and a fake clock. No camera, no device.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ColorRepositoryImplTest {


    private var now = 1_000L

    private val repository = ColorRepositoryImpl(
        imageToYuv420Frame = ImageToYuv420Frame(),
        yuv420Converter = Yuv420Converter(),
        colorQuantizer = ColorQuantizer(),
        colorSmoother = ColorSmoother(alpha = 1f),
        analysisConfig = AnalysisConfig(),
        elapsedTime = ElapsedTimeSource { now },
    )

    private fun yuvImage(
        width: Int,
        height: Int,
        yBandRows: Int,
        yTop: Int,
        uTop: Int,
        vTop: Int,
        yBottom: Int,
        uBottom: Int,
        vBottom: Int,
    ): ImageProxy {
        val y = ByteArray(width * height)
        val u = ByteArray((width / 2) * (height / 2))
        val v = ByteArray((width / 2) * (height / 2))

        for (row in 0 until height) {
            val value = if (row < yBandRows) yTop else yBottom
            for (col in 0 until width) y[row * width + col] = value.toByte()
        }
        for (row in 0 until height / 2) {
            val uValue = if (row * 2 < yBandRows) uTop else uBottom
            val vValue = if (row * 2 < yBandRows) vTop else vBottom
            for (col in 0 until width / 2) {
                u[row * (width / 2) + col] = uValue.toByte()
                v[row * (width / 2) + col] = vValue.toByte()
            }
        }

        val planes = arrayOf(
            plane(y, width, 1),
            plane(u, width / 2, 1),
            plane(v, width / 2, 1),
        )
        val info = mock(ImageInfo::class.java)
        `when`(info.timestamp).thenReturn(5L)
        val image = mock(ImageProxy::class.java)
        `when`(image.width).thenReturn(width)
        `when`(image.height).thenReturn(height)
        `when`(image.format).thenReturn(35)
        `when`(image.planes).thenReturn(planes)
        `when`(image.imageInfo).thenReturn(info)
        return image
    }

    private fun plane(buffer: ByteArray, rowStride: Int, pixelStride: Int): ImageProxy.PlaneProxy {
        val proxy = mock(ImageProxy.PlaneProxy::class.java)
        `when`(proxy.buffer).thenReturn(ByteBuffer.wrap(buffer))
        `when`(proxy.rowStride).thenReturn(rowStride)
        `when`(proxy.pixelStride).thenReturn(pixelStride)
        return proxy
    }

    /** Half red (top), half blue (bottom) — the expected answer is 50/50. */
    private fun halfRedHalfBlue(): ImageProxy = yuvImage(
        width = 8,
        height = 8,
        yBandRows = 4,
        yTop = 76, uTop = 84, vTop = 255,
        yBottom = 29, uBottom = 255, vBottom = 107,
    )

    @Test
    fun `analyzing a frame publishes the colour breakdown`() = runTest {
        val image = halfRedHalfBlue()

        repository.analyze(image)

        val colors = repository.observeTopColors().first()
        assertEquals(2, colors.size)
        assertEquals(50f, colors[0].percentage, 0.5f)
        assertEquals(50f, colors[1].percentage, 0.5f)
        assertTrue("expected a red and a blue but got $colors", colors.any { it.rgb.r > 200 && it.rgb.b < 20 })
        assertTrue("expected a red and a blue but got $colors", colors.any { it.rgb.b > 200 && it.rgb.r < 20 })
        verify(image).close()
    }

    @Test
    fun `frames inside the throttle window are dropped and still closed`() = runTest {
        val first = halfRedHalfBlue()
        // A frame nobody could mistake for the first one: if it were analysed, the breakdown
        // would become a single 100% white swatch.
        val second = whiteFrame()

        repository.analyze(first)
        repository.analyze(second)

        // The published breakdown still describes the first frame, so the second one really was
        // dropped rather than merely being cheap to analyse.
        val colors = repository.observeTopColors().first()
        assertEquals(2, colors.size)
        assertTrue(
            "expected the first frame's red and blue but got $colors",
            colors.any { it.rgb.r > 200 && it.rgb.b < 20 },
        )
        assertTrue(
            "expected the first frame's red and blue but got $colors",
            colors.any { it.rgb.b > 200 && it.rgb.r < 20 },
        )

        // Crucially, the dropped frame is closed too: leaking ImageProxies stalls the camera.
        verify(second).close()
        verify(first).close()
    }

    @Test
    fun `frames after the throttle window are analysed again`() = runTest {
        repository.analyze(halfRedHalfBlue())
        now += AnalysisConfig().minFrameIntervalMillis + 1

        repository.analyze(whiteFrame())

        // Now the second frame *is* the breakdown: the throttle released it.
        val colors = repository.observeTopColors().first()
        assertEquals(1, colors.size)
        assertEquals(RgbColor.White, colors[0].rgb)
        assertEquals(100f, colors[0].percentage, 0.5f)
    }

    /** BT.601 white (Y=255, U=V=128) — one uniform colour, so a single 100% swatch. */
    private fun whiteFrame(): ImageProxy = yuvImage(
        width = 8,
        height = 8,
        yBandRows = 8,
        yTop = 255, uTop = 128, vTop = 128,
        yBottom = 255, uBottom = 128, vBottom = 128,
    )

    @Test
    fun `a failing frame is reported instead of crashing the pipeline`() = runTest {
        val brokenImage = mock(ImageProxy::class.java)
        `when`(brokenImage.format).thenReturn(35)
        `when`(brokenImage.planes).thenReturn(emptyArray())

        val errors = mutableListOf<Throwable>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeAnalysisErrors().collect { errors += it }
        }

        repository.analyze(brokenImage)

        assertEquals(1, errors.size)
        verify(brokenImage).close()
    }

    @Test
    fun `the colour flow starts empty and reflects the latest frame`() = runTest {
        assertEquals(emptyList<Any>(), repository.observeTopColors().first())

        repository.analyze(halfRedHalfBlue())

        assertEquals(2, repository.observeTopColors().first().size)
    }
}
