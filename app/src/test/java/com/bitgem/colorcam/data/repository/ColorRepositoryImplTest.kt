package com.bitgem.colorcam.data.repository

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.bitgem.colorcam.data.camera.ElapsedTimeSource
import com.bitgem.colorcam.data.camera.ImageProxyFrameMapper
import com.bitgem.colorcam.domain.analysis.AnalysisConfig
import com.bitgem.colorcam.domain.analysis.ColorSmoother
import com.bitgem.colorcam.domain.analysis.KMeansColorQuantizer
import com.bitgem.colorcam.domain.analysis.Yuv420Converter
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.model.RgbColor
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
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

    private val config = AnalysisConfig(
        samplingStep = 1,
        bitsPerChannel = 5,
        // clusterCount must be >= topColorCount; the config's own invariant check enforces it.
        clusterCount = 6,
        mergeDistance = 24.0,
        topColorCount = 5,
        minFrameIntervalMillis = 100L,
    )

    private var now = 1_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val repository = ColorRepositoryImpl(
        mapper = ImageProxyFrameMapper(),
        converter = Yuv420Converter(),
        quantizer = KMeansColorQuantizer(config),
        smoother = ColorSmoother(alpha = 1f),
        config = config,
        elapsedTime = ElapsedTimeSource { now },
        analysisDispatcher = dispatcher,
    )

    @After
    fun tearDown() {
        executor.shutdown()
    }

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
    fun `frames inside the throttle window are dropped and still closed`() {
        val first = halfRedHalfBlue()
        val second = halfRedHalfBlue()

        repository.analyze(first)
        repository.analyze(second)

        assertEquals(1L, repository.analysedFrameCount.get())
        assertEquals(1L, repository.skippedFrameCount.get())
        verify(second).close()
        // Crucially, the dropped frame is closed too: leaking ImageProxies stalls the camera.
        verify(first).close()
    }

    @Test
    fun `frames after the throttle window are analysed again`() {
        repository.analyze(halfRedHalfBlue())
        now += config.minFrameIntervalMillis + 1

        repository.analyze(halfRedHalfBlue())

        assertEquals(2L, repository.analysedFrameCount.get())
    }

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
    fun `the pull API analyses a supplied frame`() = runTest {
        val width = 8
        // Two rows of green, two rows of red.
        val pixels = IntArray(width * 4) { index ->
            if (index < width * 2) RgbColor(10, 200, 10).argb else RgbColor(200, 10, 10).argb
        }

        val colors = repository.analyzeColors(FrameData(width, 4, pixels))

        assertEquals(2, colors.size)
        // Order between two equal-percentage colours is an implementation detail; the content
        // and the shares are not.
        assertEquals(
            setOf(RgbColor(200, 10, 10), RgbColor(10, 200, 10)),
            colors.map { it.rgb }.toSet(),
        )
        assertEquals(50f, colors[0].percentage, 0.5f)
        assertEquals(50f, colors[1].percentage, 0.5f)
    }

    @Test
    fun `the colour flow starts empty and reflects the latest frame`() = runTest {
        assertEquals(emptyList<Any>(), repository.observeTopColors().first())

        repository.analyze(halfRedHalfBlue())

        assertEquals(2, repository.observeTopColors().first().size)
    }
}
