package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor

/**
 * Temporal smoothing between frames.
 *
 * A pure per-frame analyser re-derives everything from scratch, so sensor noise and the
 * camera's auto-exposure hunting make the numbers (and occasionally the ordering) twitch by
 * several points every frame. This class turns the sequence of independent per-frame results
 * into a stable signal by matching each colour of the new frame to the closest colour of the
 * previous one and applying an exponential moving average to both the colour and the
 * percentage.
 *
 * `alpha = 1` disables smoothing (pass the frame straight through).
 *
 * Alternatives considered and rejected:
 *  - *Median over a rolling window of N frames*: better spike rejection, but needs a
 *    per-colour history and can lag by up to N frames (visible "sticky" percentages).
 *  - *Keeping unmatched previous colours alive for N frames with a decay*: removes the
 *    flicker of a colour that oscillates in and out of the top 5, but produces ghost cards
 *    that no longer exist in the scene, which is worse than a slightly jumpy list.
 *
 * Stateful by design; confine to one thread.
 */
class ColorSmoother(
    private val alpha: Float = 0.35f,
    private val matchDistance: Double = 32.0,
) {
    init {
        require(alpha in 0f..1f) { "alpha must be in 0..1 but was $alpha" }
        require(matchDistance >= 0.0) { "matchDistance must not be negative but was $matchDistance" }
    }

    private var previous: List<ColorResult> = emptyList()

    fun smooth(current: List<ColorResult>): List<ColorResult> {
        if (current.isEmpty()) {
            previous = emptyList()
            return emptyList()
        }
        if (alpha >= 1f) {
            previous = normalize(current)
            return previous
        }

        val smoothed = current.map { now ->
            val before = closestPrevious(now.rgb)
            if (before == null) {
                now
            } else {
                ColorResult(
                    rgb = ColorMath.lerp(before.rgb, now.rgb, alpha),
                    percentage = before.percentage + alpha * (now.percentage - before.percentage),
                )
            }
        }

        previous = normalize(smoothed)
        return previous
    }

    fun reset() {
        previous = emptyList()
    }

    private fun closestPrevious(color: RgbColor): ColorResult? {
        var best: ColorResult? = null
        var bestDistance = Double.MAX_VALUE
        for (candidate in previous) {
            val distance = ColorMath.distance(candidate.rgb, color)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best?.takeIf { bestDistance <= matchDistance }
    }

    /** Rescales to exactly 100% and re-sorts, so the panel always sums up. */
    private fun normalize(results: List<ColorResult>): List<ColorResult> {
        var total = 0f
        for (result in results) total += result.percentage
        val scaled = if (total <= 0f) {
            results
        } else {
            results.map { ColorResult(it.rgb, it.percentage / total * 100f) }
        }
        return scaled.sortedByDescending { it.percentage }
    }
}
