package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.model.Yuv420Frame

/**
 * Hand-written YUV_420_888 → ARGB converter (BT.601 / JFIF coefficients, full range).
 *
 * "Hand-written" is a hard requirement for this project, but it also happens to be the
 * right call: the alternative (`RenderScript`'s `ScriptIntrinsicYuvToRGB`) is deprecated and
 * needs a 2-4 MB runtime initialisation, and `ImageAnalysis`'s RGBA_8888 output format
 * costs an extra full-frame copy inside CameraX on many devices.
 *
 * Two things make this non-trivial and are handled explicitly:
 *  1. **Row padding** — `rowStride` is frequently larger than `width` (128-byte alignment
 *     in the camera HAL), so the plane cannot be walked linearly.
 *  2. **Chroma layout** — `uvPixelStride` is 1 for planar I420 and 2 for semi-planar
 *     NV12/NV21. Both are covered by the same expression: `(row/2) * uvRowStride +
 *     (col/2) * uvPixelStride`, reading U and V from their own (offset) buffers.
 *
 * The formula (`R = Y + 1.402*V'`, `G = Y - 0.344136*U' - 0.714136*V'`, `B = Y + 1.772*U'`,
 * with `U' = U-128`, `V' = V-128`) assumes full-range video levels, which is what Android
 * camera devices output for YUV_420_888. Limited-range (16..235) content would need a
 * scale/offset; mismatch shows up as a slight loss of contrast in near-black/white areas,
 * not as a colour error.
 */
class Yuv420Converter {

    /** Converts into a freshly allocated buffer. */
    fun convert(frame: Yuv420Frame): FrameData {
        val pixels = IntArray(frame.width * frame.height)
        return convertInto(frame, pixels)
    }

    /**
     * Converts into [output] (must hold at least `width * height` pixels).
     *
     * The result *aliases* [output]; the analyser reuses one scratch array per analyser
     * instance to avoid allocating ~1.2 MB of garbage per frame at 10 frames/s.
     */
    fun convertInto(frame: Yuv420Frame, output: IntArray): FrameData {
        val width = frame.width
        val height = frame.height
        require(output.size >= width * height) {
            "Output buffer too small: ${output.size} values for a ${width}x$height frame"
        }

        val yPlane = frame.y
        val uPlane = frame.u
        val vPlane = frame.v
        val yLimit = yPlane.limit()
        val uLimit = uPlane.limit()
        val vLimit = vPlane.limit()
        val yPixelStride = frame.yPixelStride
        val uvPixelStride = frame.uvPixelStride
        val yRowStride = frame.yRowStride
        val uvRowStride = frame.uvRowStride

        var index = 0
        for (row in 0 until height) {
            val yRowStart = row * yRowStride
            val uvRowStart = (row shr 1) * uvRowStride
            for (col in 0 until width) {
                val yIndex = yRowStart + col * yPixelStride
                val chromaIndex = uvRowStart + (col shr 1) * uvPixelStride

                // Defensive bounds handling: some vendor HALs hand out buffers that are a
                // few bytes shorter than the stride arithmetic implies. Neutral chroma keeps
                // the output greyscale instead of throwing mid-frame.
                val yValue = if (yIndex < yLimit) yPlane.get(yIndex).toInt() and 0xFF else 16
                val uValue = if (chromaIndex < uLimit) uPlane.get(chromaIndex).toInt() and 0xFF else 128
                val vValue = if (chromaIndex < vLimit) vPlane.get(chromaIndex).toInt() and 0xFF else 128

                output[index++] = yuvToArgb(yValue, uValue, vValue)
            }
        }
        return FrameData(width, height, output)
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val uPrime = u - 128.0
        val vPrime = v - 128.0
        val yf = y.toDouble()

        val r = yf + 1.402 * vPrime
        val g = yf - 0.344136 * uPrime - 0.714136 * vPrime
        val b = yf + 1.772 * uPrime

        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun channel(value: Double): Int =
        when {
            value <= 0.0 -> 0
            value >= 255.0 -> 255
            else -> (value + 0.5).toInt()
        }
}
