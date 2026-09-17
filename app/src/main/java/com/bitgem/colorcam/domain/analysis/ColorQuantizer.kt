package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData

/**
 * Reduces a frame to its most common colours.
 *
 * Implementations must be side-effect free from the caller's point of view, but they are
 * allowed to keep reusable scratch state internally (see [KMeansColorQuantizer]) — hence the
 * requirement that a single instance is only ever used from one thread at a time.
 */
interface ColorQuantizer {
    /**
     * @return up to [topColorCount] colours, sorted by descending occurrence, whose
     * percentages sum to 100 (allowing for float rounding).
     */
    fun quantize(frame: FrameData, topColorCount: Int): List<ColorResult>
}
