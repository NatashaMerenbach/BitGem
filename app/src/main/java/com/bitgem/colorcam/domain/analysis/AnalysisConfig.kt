package com.bitgem.colorcam.domain.analysis

/**
 * Tunables for the whole analysis pipeline, in one place and injectable so that both the
 * app and the tests can vary them (tests mostly lower [samplingStep] to keep frames tiny).
 */
data class AnalysisConfig(
    /**
     * Colour resolution of the histogram: `2^bitsPerChannel` levels per channel, i.e.
     * `2^(3*bits)` bins. 5 bits → 32 levels → 32,768 bins → ~1.2 MB of reusable scratch
     * space. Higher values separate near-identical colours better but cost memory and make
     * the histogram sparse; 5 bits is the sweet spot found by experiment (see PROCESS.md).
     */
    val bitsPerChannel: Int = 5,

    /**
     * Every `samplingStep`-th pixel in x *and* y is analysed, so the sample count is
     * `1 / samplingStep²` of the frame. 4 → 1/16 of the pixels. Rationale in PROCESS.md;
     * briefly: a 640x480 analysis stream is 307k pixels, the clustering cost is dominated
     * by the number of *bins*, and 16x fewer pixels is 16x less binning work with a
     * percentage error that is far below the perceptible threshold.
     */
    val samplingStep: Int = 4,

    /**
     * k of k-means. Deliberately larger than the number of displayed colours: the merge
     * step can collapse two clusters into one, so a little headroom prevents the panel
     * from dropping to 3-4 cards while still costing almost nothing (k-means work grows
     * with the number of *bins*, not with k).
     */
    val clusterCount: Int = 8,

    /** Upper bound on Lloyd iterations; the loop normally converges in 4-6. */
    val maxIterations: Int = 12,

    /** Stop as soon as no centroid moves more than this many RGB units. */
    val convergenceEpsilon: Double = 0.5,

    /**
     * Two clusters closer than this (Euclidean RGB distance) are merged into one.
     * ~24 units is roughly the point below which two swatches look like "the same colour"
     * side by side on a phone screen.
     */
    val mergeDistance: Double = 24.0,

    /** How many colours the panel shows. */
    val topColorCount: Int = 5,

    /** EMA factor for temporal smoothing: `new = old + alpha * (current - old)`. */
    val temporalAlpha: Float = 0.35f,

    /** A colour only inherits the previous frame's smoothing if it is this close to it. */
    val temporalMatchDistance: Double = 32.0,

    /**
     * Throttle: at most one analysis per this many milliseconds (10/s). The camera happily
     * delivers 30 fps; analysing all of them wastes battery for no visible benefit
     * (the panel is far below the flicker-fusion limit anyway) and makes the numbers
     * jitter more.
     */
    val minFrameIntervalMillis: Long = 100L,

    /** Fixed seed for k-means++ so that identical input yields identical output. */
    val seed: Long = 0xB16B00B5L,
) {
    init {
        require(bitsPerChannel in 2..6) { "bitsPerChannel must be in 2..6 but was $bitsPerChannel" }
        require(samplingStep >= 1) { "samplingStep must be at least 1 but was $samplingStep" }
        require(clusterCount >= 1) { "clusterCount must be at least 1 but was $clusterCount" }
        require(maxIterations >= 1) { "maxIterations must be at least 1 but was $maxIterations" }
        require(topColorCount in 1..clusterCount) {
            "topColorCount must be in 1..clusterCount but was $topColorCount / $clusterCount"
        }
        require(temporalAlpha in 0f..1f) { "temporalAlpha must be in 0..1 but was $temporalAlpha" }
        require(minFrameIntervalMillis >= 0) { "minFrameIntervalMillis must not be negative" }
    }

    /** Number of histogram bins implied by [bitsPerChannel]. */
    val binCount: Int get() = 1 shl (3 * bitsPerChannel)
}
