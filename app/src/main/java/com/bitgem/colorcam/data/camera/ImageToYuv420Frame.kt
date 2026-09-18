package com.bitgem.colorcam.data.camera

import androidx.camera.core.ImageProxy
import com.bitgem.colorcam.domain.model.Yuv420Frame
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a CameraX [ImageProxy] onto the domain's [Yuv420Frame].
 *
 * This is the only class that knows about `ImageProxy`, and it does nothing but copy the
 * three plane references and their strides across — no pixel maths, no allocation. That
 * boundary is what allows the interesting part (the YUV→RGB conversion and the clustering)
 * to be unit-tested on a plain JVM.
 *
 * **Lifetime rule:** the buffers inside an `ImageProxy` are only valid until
 * `ImageProxy.close()`. Nothing here copies them, so the caller must finish converting
 * *before* closing the proxy — see `ColorRepositoryImpl.analyze`.
 */
@Singleton
class ImageToYuv420Frame @Inject constructor() {

    fun map(image: ImageProxy): Yuv420Frame {
        require(image.format == ImageProxyFormat.YUV_420_888) {
            "ImageProxyFrameMapper only understands YUV_420_888 but got format ${image.format}"
        }
        val planes = image.planes
        require(planes.size >= 3) { //throw IllegalArgumentException
            "A YUV_420_888 image must expose 3 planes but exposed ${planes.size}"
        }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        return Yuv420Frame(
            width = image.width,
            height = image.height,
            y = yPlane.buffer,
            u = uPlane.buffer,
            v = vPlane.buffer,
            yRowStride = yPlane.rowStride,
            uvRowStride = uPlane.rowStride,
            uvPixelStride = uPlane.pixelStride,
            yPixelStride = yPlane.pixelStride,
            timestampNanos = image.imageInfo.timestamp,
        )
    }
}

/**
 * `ImageFormat.YUV_420_888` without importing `android.graphics.ImageFormat`, so that this
 * file stays testable against a mocked `ImageProxy` on a plain JVM.
 */
internal object ImageProxyFormat {
    const val YUV_420_888 = 35
}
