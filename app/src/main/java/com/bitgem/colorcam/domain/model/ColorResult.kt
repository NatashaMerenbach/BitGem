package com.bitgem.colorcam.domain.model

/**
 * One entry of the color breakdown: a color plus the share of the analysed frame
 * it occupies, expressed as a percentage of the pixels that were sampled (0..100).
 */
data class ColorResult(
    val rgb: RgbColor,
    val percentage: Float,
) {
    init {
        require(percentage.isFinite() && percentage >= 0f) {
            "Percentage must be a finite, non-negative value but was $percentage"
        }
    }

    val argb: Int get() = rgb.argb
}
