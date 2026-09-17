package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.Yuv420Frame
import java.nio.ByteBuffer

/**
 * Builds YUV_420_888 test frames.
 *
 * The colour constants are the standard BT.601 test vectors (the same values a camera
 * produces for a saturated red/green/blue/white/black target under neutral illumination):
 *
 * | colour | Y  | U   | V   |
 * |--------|----|-----|-----|
 * | red    | 76 | 84  | 255 |
 * | green  | 150| 44  | 21  |
 * | blue   | 29 | 255 | 107 |
 * | white  | 255| 128 | 128 |
 * | black  | 0  | 128 | 128 |
 */
object YuvTestFrames {

    const val RED_Y = 76
    const val RED_U = 84
    const val RED_V = 255

    const val GREEN_Y = 150
    const val GREEN_U = 44
    const val GREEN_V = 21

    const val BLUE_Y = 29
    const val BLUE_U = 255
    const val BLUE_V = 107

    const val WHITE_Y = 255
    const val BLACK_Y = 0

    fun flat(
        width: Int,
        height: Int,
        y: Int,
        u: Int,
        v: Int,
        yRowStride: Int = width,
        uvRowStride: Int = width / 2,
        uvPixelStride: Int = 1,
    ): Yuv420Frame {
        val yPlane = ByteArray(yRowStride * (height - 1) + width)
        val uvHeight = height / 2
        val uvCount = uvPixelStride * (uvRowStride * (uvHeight - 1) + width / 2)
        val uPlane = ByteArray(uvCount)
        val vPlane = ByteArray(uvCount)

        for (row in 0 until height) {
            for (col in 0 until width) {
                yPlane[row * yRowStride + col] = y.toByte()
            }
        }
        for (row in 0 until uvHeight) {
            for (col in 0 until width / 2) {
                val index = row * uvRowStride + col * uvPixelStride
                uPlane[index] = u.toByte()
                vPlane[index] = v.toByte()
            }
        }
        return frame(width, height, yPlane, uPlane, vPlane, yRowStride, uvRowStride, uvPixelStride)
    }

    fun frame(
        width: Int,
        height: Int,
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int = width,
        uvRowStride: Int = width / 2,
        uvPixelStride: Int = 1,
    ): Yuv420Frame = Yuv420Frame(
        width = width,
        height = height,
        y = ByteBuffer.wrap(yPlane),
        u = ByteBuffer.wrap(uPlane),
        v = ByteBuffer.wrap(vPlane),
        yRowStride = yRowStride,
        uvRowStride = uvRowStride,
        uvPixelStride = uvPixelStride,
    )
}
