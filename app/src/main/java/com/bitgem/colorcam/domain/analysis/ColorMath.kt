package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.RgbColor
import kotlin.math.sqrt

/**
 * Colour-space helpers used by the clustering pipeline. All of them are pure functions,
 * which is what makes the algorithm testable in isolation.
 *
 * Distance is plain Euclidean distance in (gamma-encoded) sRGB. That is *not* perceptually
 * uniform, but it is cheap, monotonic, and — unlike CIE76 ΔE in Lab — needs no additional
 * transform in the hot loop. See PROCESS.md for the trade-off discussion.
 */
object ColorMath {

    fun distanceSquared(a: RgbColor, b: RgbColor): Double {
        val dr = (a.r - b.r).toDouble()
        val dg = (a.g - b.g).toDouble()
        val db = (a.b - b.b).toDouble()
        return dr * dr + dg * dg + db * db
    }

    fun distance(a: RgbColor, b: RgbColor): Double = sqrt(distanceSquared(a, b))

    fun distance(a: RgbColor, r: Double, g: Double, b: Double): Double {
        val dr = a.r - r
        val dg = a.g - g
        val db = a.b - b
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** Linear interpolation; `t = 0` returns [from], `t = 1` returns [to]. */
    fun lerp(from: RgbColor, to: RgbColor, t: Float): RgbColor {
        val clamped = t.coerceIn(0f, 1f)
        return RgbColor.of(
            from.r + (to.r - from.r) * clamped,
            from.g + (to.g - from.g) * clamped,
            from.b + (to.b - from.b) * clamped,
        )
    }
}
