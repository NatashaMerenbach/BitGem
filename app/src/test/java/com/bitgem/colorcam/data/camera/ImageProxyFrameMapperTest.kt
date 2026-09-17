package com.bitgem.colorcam.data.camera

import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.bitgem.colorcam.domain.analysis.Yuv420Converter
import com.bitgem.colorcam.domain.model.RgbColor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * CameraX's `ImageProxy`/`PlaneProxy` are interfaces, so the mapping can be verified on a
 * plain JVM with Mockito — no camera, no device, no Robolectric.
 */
class ImageProxyFrameMapperTest {

    private val mapper = ImageProxyFrameMapper()
    private val converter = Yuv420Converter()

    private fun plane(buffer: ByteArray, rowStride: Int, pixelStride: Int): ImageProxy.PlaneProxy {
        val proxy = mock(ImageProxy.PlaneProxy::class.java)
        `when`(proxy.buffer).thenReturn(ByteBuffer.wrap(buffer))
        `when`(proxy.rowStride).thenReturn(rowStride)
        `when`(proxy.pixelStride).thenReturn(pixelStride)
        return proxy
    }

    private fun image(
        width: Int,
        height: Int,
        planes: Array<ImageProxy.PlaneProxy>,
        format: Int = ImageProxyFormat.YUV_420_888,
        timestamp: Long = 42L,
    ): ImageProxy {
        val info = mock(ImageInfo::class.java)
        `when`(info.timestamp).thenReturn(timestamp)

        val image = mock(ImageProxy::class.java)
        `when`(image.width).thenReturn(width)
        `when`(image.height).thenReturn(height)
        `when`(image.format).thenReturn(format)
        `when`(image.planes).thenReturn(planes)
        `when`(image.imageInfo).thenReturn(info)
        return image
    }

    private fun redYuvImage(width: Int, height: Int, yRowStride: Int = width): ImageProxy {
        val y = ByteArray(yRowStride * height) { 76.toByte() }
        val u = ByteArray((width / 2) * (height / 2)) { 84.toByte() }
        val v = ByteArray((width / 2) * (height / 2)) { 255.toByte() }
        return image(
            width = width,
            height = height,
            planes = arrayOf(
                plane(y, yRowStride, 1),
                plane(u, width / 2, 1),
                plane(v, width / 2, 1),
            ),
        )
    }

    @Test
    fun `maps dimensions, strides, buffers and timestamp`() {
        val image = redYuvImage(width = 8, height = 4, yRowStride = 12)

        val frame = mapper.map(image)

        assertEquals(8, frame.width)
        assertEquals(4, frame.height)
        assertEquals(12, frame.yRowStride)
        assertEquals(4, frame.uvRowStride)
        assertEquals(1, frame.uvPixelStride)
        assertEquals(42L, frame.timestampNanos)
        // The padded Y plane is rowStride * height bytes long, not width * height.
        assertEquals(12 * 4, frame.y.limit())
    }

    /** End-to-end across the boundary: mocked ImageProxy in, real colours out. */
    @Test
    fun `a mocked red frame converts to red`() {
        val image = redYuvImage(width = 8, height = 4)

        val pixel = RgbColor.fromArgb(converter.convert(mapper.map(image)).pixelAt(3, 2))

        // BT.601 red (Y=76, U=84, V=255) lands on 254 rather than 255: the 1.402 coefficient
        // cannot quite reach full scale from there.
        assertEquals(RgbColor(254, 0, 0), pixel)
    }

    @Test
    fun `rejects a format it cannot understand`() {
        val image = redYuvImage(width = 8, height = 4)
        `when`(image.format).thenReturn(0x1) // RGBA_8888

        assertThrows(IllegalArgumentException::class.java) { mapper.map(image) }
    }

    @Test
    fun `rejects an image without three planes`() {
        val image = image(8, 4, planes = arrayOf(plane(ByteArray(4), 8, 1)))

        assertThrows(IllegalArgumentException::class.java) { mapper.map(image) }
    }
}
