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
 *
 * **Sampling is part of the conversion** (see [convertIntoFrameData]): the converter walks a
 * regular grid, every `step`-th pixel in x and y, and only does the maths for those. A grid
 * rather than random sampling because it is deterministic (the same frame yields the same
 * samples, which the stability tests rely on), allocation-free and cache-friendly; the known
 * weakness is aliasing on high-frequency patterns whose pitch is close to `step` — a 4-pixel
 * stripe can be over- or under-represented. A jittered/low-discrepancy grid would remove that
 * at the cost of determinism (see PROCESS.md, "what I'd improve").
 */
class Yuv420Converter {

    /** Converts the whole frame. Convenient for callers and tests that want every pixel. */
    fun convert(frame: Yuv420Frame, step: Int = 1): FrameData {
        val pixels = IntArray(sampledPixelCount(frame.width, frame.height, step))
        return convertIntoFrameData(frame, pixels, step)
    }

    /**
     * Converts the sampled grid of [frame] into [output]: every [step]-th pixel in x *and* y,
     * tightly packed row-major.
     *
     * Sampling happens *here* rather than after the conversion, because the YUV maths is the
     * most expensive per-pixel work in the pipeline (two multiplies, a chained add each and
     * three clamps per pixel). Decoding only the pixels that will actually be binned makes the
     * saving real at both ends: at `step = 4` a 640x480 frame converts 19,200 pixels instead of
     * 307,200, and the ARGB scratch buffer drops from ~1.2 MB to ~77 KB — small enough to stay
     * in cache while the histogram pass reads it.
     *
     * The returned [FrameData] describes the *sampled grid*: its width and height are the number
     * of sampled columns and rows, so its pixels are a packed image of the samples and no
     * downstream stage has to know about the stride. Chroma indexing is unaffected — the
     * `(row / 2) * uvRowStride + (col / 2) * uvPixelStride` mapping is still correct for a
     * strided row and column, since luma rows/columns are only visited every [step].
     *
     * The result *aliases* [output]; the analyser reuses one scratch array per analyser
     * instance to avoid allocating ~1.2 MB of garbage per frame at 10 frames/s.
     */
    fun convertIntoFrameData(frame: Yuv420Frame, output: IntArray, step: Int = 1): FrameData {
        require(step >= 1) { "step must be at least 1 but was $step" }

        val width = frame.width
        val height = frame.height
        val columns = ceilDiv(width, step)
        val rows = ceilDiv(height, step)
        require(output.size >= columns * rows) {
            "Output buffer too small: ${output.size} values, at least ${columns * rows} required " +
                "for a ${width}x$height frame at step $step"
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
        var row = 0
        while (row < height) {
            val yRowStart = row * yRowStride
            val uvRowStart = (row shr 1) * uvRowStride
            var col = 0
            while (col < width) {
                val yIndex = yRowStart + col * yPixelStride
                val chromaIndex = uvRowStart + (col shr 1) * uvPixelStride

                // Defensive bounds handling: some vendor HALs hand out buffers that are a
                // few bytes shorter than the stride arithmetic implies. Neutral chroma keeps
                // the output greyscale instead of throwing mid-frame.
                val yValue = if (yIndex < yLimit) yPlane.get(yIndex).toInt() and 0xFF else 16
                val uValue = if (chromaIndex < uLimit) uPlane.get(chromaIndex).toInt() and 0xFF else 128
                val vValue = if (chromaIndex < vLimit) vPlane.get(chromaIndex).toInt() and 0xFF else 128

                output[index++] = yuvToArgb(yValue, uValue, vValue)
                col += step
            }
            row += step
        }
        return FrameData(columns, rows, output)
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

    companion object {
        /**
         * How many pixels [convertIntoFrameData] writes for this frame size and step — i.e. the
         * size the caller's scratch buffer has to be. Callers need it *before* converting (the
         * buffer must be sized up front), which is why it lives here rather than being returned.
         */
        fun sampledPixelCount(width: Int, height: Int, step: Int): Int {
            require(step >= 1) { "step must be at least 1 but was $step" }
            if (width <= 0 || height <= 0) return 0
            return ceilDiv(width, step) * ceilDiv(height, step)
        }

        private fun ceilDiv(value: Int, step: Int): Int = if (value <= 0) 0 else (value + step - 1) / step
    }
}
