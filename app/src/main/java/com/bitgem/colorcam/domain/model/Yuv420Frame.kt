package com.bitgem.colorcam.domain.model

import java.nio.ByteBuffer

/**
 * A YUV_420_888 frame as delivered by the camera pipeline, expressed with plain
 * `java.nio.ByteBuffer`s so that the conversion to RGB stays pure JVM code that can be
 * unit-tested without a device or emulator.
 *
 * Strides are *not* optional detail: camera planes are very often padded
 * (`rowStride > width`, `uvPixelStride == 2`). Reading them as if they were tightly packed
 * is the classic source of skewed, color-shifted output.
 *
 * [y], [u] and [v] are absolute-indexed buffers whose index 0 is the first byte of the
 * plane.
 */
class Yuv420Frame(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val yRowStride: Int,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val yPixelStride: Int = 1,
    val timestampNanos: Long = 0L,
) {
    init {
        require(width > 0 && height > 0) { "Frame size must be positive but was ${width}x$height" } //throw IllegalArgumentException
        require(yRowStride > 0 && uvRowStride > 0) { "Row strides must be positive" }
        require(uvPixelStride >= 1 && yPixelStride >= 1) { "Pixel strides must be at least 1" }
    }
}
