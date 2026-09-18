package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.FrameData

/**
 * Regular-grid subsampling of a frame.
 *
 * Why subsample at all: the histogram/k-means cost is linear in the number of pixels fed to
 * it, and neighbouring camera pixels are almost perfectly correlated (sensor noise aside),
 * so a dense scan buys accuracy nobody can see. With `step = 4`, a 640x480 analysis frame
 * drops from 307,200 to 19,200 samples — a 16x reduction in the hot loop — while the
 * measured percentage error on synthetic flat-colour images stays below ~1.5 points.
 *
 * Why a grid instead of random sampling: it is deterministic (same frame ⇒ same sample set,
 * which the stability tests rely on), allocation-free, and trivially cache-friendly.
 * The known weakness is aliasing on high-frequency patterns (a 4-pixel pitch stripe pattern
 * can be under- or over-represented); a random/Poisson-disc jitter would remove it at the
 * cost of determinism. See PROCESS.md ("what I'd improve").
 */
class PixelSampler {

    /** Number of samples [sample] will produce for a frame of this size. */
    fun sampleCount(width: Int, height: Int, step: Int): Int {
        require(step >= 1) { "step must be at least 1 but was $step" }
        if (width <= 0 || height <= 0) return 0
        val columns = (width + step - 1) / step
        val rows = (height + step - 1) / step
        return columns * rows
    }

    /** Samples into a fresh array. Convenient for tests and one-off callers. */
    fun sample(frame: FrameData, step: Int): IntArray {
        val required = sampleCount(frame.width, frame.height, step)
        return IntArray(required).also { sampleInto(frame, step, it) }
    }

    /**
     * Samples into [sampleBufferOut], which must hold at least [sampleCount] values.
     *
     * @return the number of ARGB values written.
     */
    fun sampleInto(frame: FrameData, step: Int, sampleBufferOut: IntArray): Int {
        val required = sampleCount(frame.width, frame.height, step)
        require(sampleBufferOut.size >= required) {
            "Sample buffer too small: ${sampleBufferOut.size} values, at least $required required"
        }
        if (required == 0) return 0

        val width = frame.width
        val height = frame.height
        val pixels = frame.pixels
        var count = 0
        var currentHeight = 0
        while (currentHeight < height) {
            val rowOffset = currentHeight * width
            var currentWidth = 0
            while (currentWidth < width) {
                sampleBufferOut[count++] = pixels[rowOffset + currentWidth]
                currentWidth += step
            }
            currentHeight += step
        }
        return count
    }
}
