package com.bitgem.colorcam.domain.model

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An immutable color with 8-bit components.
 *
 * Every instance is guaranteed to hold legal component values: [of] clamps, and the `init`
 * guard fails loudly if a computed value ever escapes the legal range — a silent
 * out-of-range centroid would otherwise show up much later as a nonsense swatch.
 */
data class RgbColor(val r: Int, val g: Int, val b: Int) {

    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) {
            "RGB components must be in 0..255 but were ($r, $g, $b). Use RgbColor.of(...) to clamp computed values."
        }
    }

    /** Packs the color into the ARGB layout Compose/Android expect (`0xFFRRGGBB`). */
    val argb: Int
        get() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * WCAG 2.x relative luminance, 0.0 (black) .. 1.0 (white).
     *
     * The 0.2126/0.7152/0.0722 weights are the Y row of the sRGB → CIE XYZ matrix (derivable
     * from the sRGB primaries and the D65 white point), and they appear verbatim in the WCAG
     * 2.x definition of relative luminance. They are color-space-specific: Rec.601
     * (0.2989/0.5866/0.1144) and Rec.2020 (0.2627/0.6780/0.0593) use the same derivation with
     * different primaries, so copying one set onto another color space is a real bug, not a
     * stylistic difference — the maths still runs and quietly produces wrong contrast decisions.
     *
     * Note: WCAG's own text gives the linearization knee as 0.03928; this uses 0.04045, the
     * sRGB/IEC 61966-2-1 value the spec is derived from. The two agree to ~1e-5 in the resulting
     * luminance, so no contrast decision can turn on the difference.
     */
    val relativeLuminance: Double
        get() = 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)

    /**
     * WCAG contrast ratio between this color and [rgbColor], 1.0 .. 21.0.
     *
     * The `+ 0.05` on both luminances is WCAG's veiling-glare term, modeling ambient light
     * reflecting off a display that is never perfectly black. It is structural, not cosmetic:
     * without it, black-on-black is 0/0 and ratios explode as luminance approaches zero, so
     * color pairs a user genuinely cannot tell apart would score as passing. It is also where
     * the 21:1 maximum comes from: `(1.0 + 0.05) / (0.0 + 0.05) = 21`.
     *
     * [contrastingTextColor] reduces to comparing [relativeLuminance] against a single crossover,
     * `sqrt(1.05 * 0.05) - 0.05 ≈ 0.1791`: black wins above it, white below. That crossover is
     * inherited from WCAG 2.x's normative 0.05 constant (an artifact of CRT-era veiling-glare
     * measurement, which WCAG 3's APCA replaces with a different model entirely).
     */
    fun contrastRatioWith(rgbColor: RgbColor): Double {
        val firstLuminance = relativeLuminance
        val secondLuminance = rgbColor.relativeLuminance
        val lighter = maxOf(firstLuminance, secondLuminance)
        val darker = minOf(firstLuminance, secondLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Picks black or white for text drawn on top of this color, choosing whichever
     * yields the higher WCAG contrast ratio (ties go to black).
     *
     * This is intentionally a pure function of the color so that the "auto-contrast"
     * rule of the swatch cards can be unit-tested without any UI machinery.
     */
    fun contrastingTextColor(): RgbColor =
        if (contrastRatioWith(White) > contrastRatioWith(Black)) White else Black

    private fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    companion object {
        val Black = RgbColor(0, 0, 0)
        val White = RgbColor(255, 255, 255)

        /** Creates a color from components of any sign/range, clamping into 0..255. */
        fun of(r: Int, g: Int, b: Int): RgbColor =
            RgbColor(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

        /** Creates a color from rounded floating point components (k-means centroids). */
        fun of(r: Double, g: Double, b: Double): RgbColor =
            of(roundToIntSafe(r), roundToIntSafe(g), roundToIntSafe(b))

        /** [Double] overload for interpolated components. */
        fun of(r: Float, g: Float, b: Float): RgbColor =
            of(r.toDouble(), g.toDouble(), b.toDouble())

        /** Reverses [argb]; the alpha byte is ignored. */
        fun fromArgb(argb: Int): RgbColor =
            RgbColor((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

private fun roundToIntSafe(value: Double): Int =
    if (value.isNaN()) 0 else value.roundToInt()
