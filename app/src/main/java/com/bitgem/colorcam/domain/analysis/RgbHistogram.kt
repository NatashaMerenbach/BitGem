package com.bitgem.colorcam.domain.analysis

/**
 * Coarse RGB histogram — the first stage of the clustering pipeline.
 *
 * Binning first, clustering second, is the key performance decision of this project:
 * k-means over ~19,200 *pixels* would need 19,200 × k distance computations per iteration;
 * k-means over the ~300-2,000 *non-empty bins* needs a fraction of that, while producing
 * the same answer (each bin carries its population as a weight, and its mean color —
 * not its center — is used as the point, so the binning does not shift the resulting
 * colors).
 *
 * The arrays are allocated once and reused forever. Instead of clearing 32,768 slots per
 * frame (≈4 × 32k writes of pure overhead), each slot carries an `epoch` stamp: a slot whose
 * stamp differs from the current epoch is treated as empty and lazily re-initialized.
 * `usedBins` records only the slots touched this epoch, so the clustering stage iterates over
 * exactly the non-empty bins.
 *
 * **Not thread-safe** on purpose: it is confined to a single analysis thread and its scratch
 * arrays are reused. Callers must serialize access (the repository does).
 */
internal class RgbHistogram(val bitsPerChannel: Int) {

    private val shift = 8 - bitsPerChannel
    private val channelLevels = 1 shl bitsPerChannel

    val binCount: Int = 1 shl (3 * bitsPerChannel)

    private val counts = IntArray(binCount)
    private val sumR = LongArray(binCount)
    private val sumG = LongArray(binCount)
    private val sumB = LongArray(binCount)
    private val epochOf = IntArray(binCount)
    private val usedBins = IntArray(binCount)

    private var epoch = 0
    private var usedCount = 0

    /** Number of distinct non-empty bins in the current epoch. */
    val populatedBinCount: Int get() = usedCount

    fun binAt(index: Int): Int {
        require(index in 0 until usedCount) { "Bin index $index out of range 0..${usedCount - 1}" }
        return usedBins[index]
    }

    fun weightOf(bin: Int): Int = counts[bin]

    fun meanR(bin: Int): Double = sumR[bin].toDouble() / counts[bin]

    fun meanG(bin: Int): Double = sumG[bin].toDouble() / counts[bin]

    fun meanB(bin: Int): Double = sumB[bin].toDouble() / counts[bin]

    /** Starts a new frame and logically empties the histogram (O(1)). */
    fun begin() {
        if (epoch == Int.MAX_VALUE) {
            epochOf.fill(0)
            epoch = 0
        }
        epoch++
        usedCount = 0
    }

    /** Adds one ARGB pixel to the histogram. */
    fun add(argb: Int) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val bin = binOf(r, g, b)

        if (epochOf[bin] != epoch) {
            epochOf[bin] = epoch
            counts[bin] = 0
            sumR[bin] = 0L
            sumG[bin] = 0L
            sumB[bin] = 0L
            usedBins[usedCount++] = bin
        }
        counts[bin]++
        sumR[bin] += r.toLong()
        sumG[bin] += g.toLong()
        sumB[bin] += b.toLong()
    }

    private fun binOf(r: Int, g: Int, b: Int): Int =
        ((r shr shift) * channelLevels + (g shr shift)) * channelLevels + (b shr shift)
}
