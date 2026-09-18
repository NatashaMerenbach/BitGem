package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.model.RgbColor
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Weighted k-means (Lloyd's algorithm) with a k-means++ seeding pass, run over an
 * [RgbHistogram] of the sampled pixels instead of over the pixels themselves.
 *
 * Pipeline: **sample → bin → k-means over non-empty bins → merge near-identical
 * clusters → sort → top N**.
 *
 * Design notes that matter:
 *  - **No ready-made library.** Everything here is hand-rolled: the binning, the seeding,
 *    the Lloyd iterations, the empty-cluster recovery and the merging.
 *  - **Deterministic.** The seed is fixed ([AnalysisConfig.seed]) and points are visited in
 *    histogram order, so the same frame always yields the same answer. Randomised seeding
 *    was rejected precisely because it makes both the tests and the on-screen ordering
 *    unreproducible.
 *  - **Empty clusters are recovered, not ignored.** A centroid that ends up owning no points
 *    is reseeded onto the worst-represented point. Skipping this is the classic bug that
 *    silently reduces `k` and produces two identical swatches.
 *  - **Merging after clustering.** k-means happily splits one dominant colour into two
 *    neighbours; merging by colour distance is what turns "8 clusters" into "5 colours a
 *    human would name", and its greedy weighted-mean merge keeps the percentages exact.
 *  - **k > topN on purpose** ([AnalysisConfig.clusterCount] = 8 for 5 displayed colours) so
 *    that merging cannot starve the panel.
 *
 * Scratch arrays are allocated once and reused (see PROCESS.md on allocation); the instance
 * must therefore be confined to a single thread by its owner.
 */
class ColorQuantizer() {
    private val config: AnalysisConfig = AnalysisConfig()
    private val sampler: PixelSampler = PixelSampler()
    private val histogram = RgbHistogram(config.bitsPerChannel)

    // Points = populated histogram bins, in ARGB-independent arrays (no boxing, no allocation).
    private val pointR = DoubleArray(config.binCount)
    private val pointG = DoubleArray(config.binCount)
    private val pointB = DoubleArray(config.binCount)
    private val pointW = IntArray(config.binCount)

    private val centroidR = DoubleArray(config.clusterCount)
    private val centroidG = DoubleArray(config.clusterCount)
    private val centroidB = DoubleArray(config.clusterCount)

    private val assignment = IntArray(config.binCount)
    private val clusterWeight = DoubleArray(config.clusterCount)
    private val clusterSumR = DoubleArray(config.clusterCount)
    private val clusterSumG = DoubleArray(config.clusterCount)
    private val clusterSumB = DoubleArray(config.clusterCount)
    private var sampleBuffer = IntArray(0)

    fun quantize(frame: FrameData, topColorCount: Int): List<ColorResult> {
        if (frame.isEmpty) return emptyList()

        val step = config.samplingStep
        val required = sampler.sampleCount(frame.width, frame.height, step)
        if (required == 0) return emptyList()
        // Grow on demand rather than reallocating: a 640x480 frame needs 19200 samples (~77 KB)
        // and we analyse ~10 frames per second, so a fresh array per frame is pure garbage.
        // Keeping a buffer larger than `required` is fine — sampleInto returns how many values
        // it wrote, and only that prefix is read below.
        if (sampleBuffer.size < required) sampleBuffer = IntArray(required)
        val sampleCount = sampler.sampleInto(frame, step, sampleBuffer)
        if (sampleCount == 0) return emptyList()

        histogram.begin()
        for (i in 0 until sampleCount) {
            histogram.add(sampleBuffer[i])
        }

        val pointCount = loadPoints()
        if (pointCount == 0) return emptyList()

        val clusterCount = min(config.clusterCount, pointCount)
        seedCentroids(pointCount, clusterCount)
        runLloyd(pointCount, clusterCount)

        val clusters = collectClusters(clusterCount)
        return merge(clusters)
            .sortedByDescending { it.percentage }
            .take(topColorCount)
    }

    /** Copies the populated bins into the flat point arrays. */
    private fun loadPoints(): Int {
        val populated = histogram.populatedBinCount
        for (i in 0 until populated) {
            val bin = histogram.binAt(i)
            pointR[i] = histogram.meanR(bin)
            pointG[i] = histogram.meanG(bin)
            pointB[i] = histogram.meanB(bin)
            pointW[i] = histogram.weightOf(bin)
        }
        return populated
    }

    /**
     * k-means++ seeding, weighted by bin population: the first centre is the most populous
     * bin, every following centre is drawn with probability proportional to
     * `weight × distance²` to the closest existing centre.
     */
    private fun seedCentroids(pointCount: Int, clusterCount: Int) {
        val random = Random(config.seed)

        var mostPopulous = 0
        var bestWeight = -1
        for (i in 0 until pointCount) {
            if (pointW[i] > bestWeight) {
                bestWeight = pointW[i]
                mostPopulous = i
            }
        }
        centroidR[0] = pointR[mostPopulous]
        centroidG[0] = pointG[mostPopulous]
        centroidB[0] = pointB[mostPopulous]

        val minDistanceSquared = DoubleArray(pointCount)
        for (i in 0 until pointCount) {
            minDistanceSquared[i] = distanceSquared(i, centroidR[0], centroidG[0], centroidB[0])
        }

        for (c in 1 until clusterCount) {
            var total = 0.0
            for (i in 0 until pointCount) total += pointW[i] * minDistanceSquared[i]

            val chosen: Int
            if (total <= 0.0) {
                // Every remaining point coincides with an existing centre: fall back to the
                // most populous still-distant point (or give up and duplicate centre 0).
                var best = -1
                var weight = -1
                for (i in 0 until pointCount) {
                    if (minDistanceSquared[i] > 0.0 && pointW[i] > weight) {
                        weight = pointW[i]
                        best = i
                    }
                }
                chosen = if (best >= 0) best else mostPopulous
            } else {
                var target = random.nextDouble() * total
                var index = pointCount - 1
                for (i in 0 until pointCount) {
                    target -= pointW[i] * minDistanceSquared[i]
                    if (target <= 0.0) {
                        index = i
                        break
                    }
                }
                chosen = index
            }

            centroidR[c] = pointR[chosen]
            centroidG[c] = pointG[chosen]
            centroidB[c] = pointB[chosen]
            for (i in 0 until pointCount) {
                val d = distanceSquared(i, centroidR[c], centroidG[c], centroidB[c])
                if (d < minDistanceSquared[i]) minDistanceSquared[i] = d
            }
        }
    }

    /** Lloyd iterations: assign → recompute → stop when nothing moves. */
    private fun runLloyd(pointCount: Int, clusterCount: Int) {
        var iteration = 0
        while (iteration < config.maxIterations) {
            assignPoints(pointCount, clusterCount)

            var maxShift = 0.0
            for (c in 0 until clusterCount) {
                if (clusterWeight[c] > 0.0) {
                    val weight = clusterWeight[c]
                    val newR = clusterSumR[c] / weight
                    val newG = clusterSumG[c] / weight
                    val newB = clusterSumB[c] / weight
                    val dr = newR - centroidR[c]
                    val dg = newG - centroidG[c]
                    val db = newB - centroidB[c]
                    maxShift = maxOf(maxShift, sqrt(dr * dr + dg * dg + db * db))
                    centroidR[c] = newR
                    centroidG[c] = newG
                    centroidB[c] = newB
                } else {
                    val farthest = farthestPoint(pointCount, clusterCount)
                    if (farthest >= 0) {
                        centroidR[c] = pointR[farthest]
                        centroidG[c] = pointG[farthest]
                        centroidB[c] = pointB[farthest]
                    }
                    // Force at least one more iteration: the cluster just moved.
                    maxShift = maxOf(maxShift, config.convergenceEpsilon * 2.0)
                }
            }

            iteration++
            if (maxShift <= config.convergenceEpsilon) break
        }
        // Final assignment so that the reported weights match the reported centroids.
        assignPoints(pointCount, clusterCount)
    }

    private fun assignPoints(pointCount: Int, k: Int) {
        for (c in 0 until k) {
            clusterWeight[c] = 0.0
            clusterSumR[c] = 0.0
            clusterSumG[c] = 0.0
            clusterSumB[c] = 0.0
        }
        for (i in 0 until pointCount) {
            var best = 0
            var bestDistance = Double.MAX_VALUE
            for (c in 0 until k) {
                val d = distanceSquared(i, centroidR[c], centroidG[c], centroidB[c])
                if (d < bestDistance) {
                    bestDistance = d
                    best = c
                }
            }
            assignment[i] = best
            val weight = pointW[i].toDouble()
            clusterWeight[best] += weight
            clusterSumR[best] += weight * pointR[i]
            clusterSumG[best] += weight * pointG[i]
            clusterSumB[best] += weight * pointB[i]
        }
    }

    /** Index of the point that is currently the worst represented, or -1 when there is none. */
    private fun farthestPoint(pointCount: Int, k: Int): Int {
        var farthest = -1
        var farthestDistance = -1.0
        for (i in 0 until pointCount) {
            val c = assignment[i].coerceIn(0, k - 1)
            val d = distanceSquared(i, centroidR[c], centroidG[c], centroidB[c])
            if (d > farthestDistance) {
                farthestDistance = d
                farthest = i
            }
        }
        return farthest
    }

    private fun collectClusters(clusterCount: Int): List<ClusterDraft> {
        val clusters = ArrayList<ClusterDraft>(clusterCount)
        for (current in 0 until clusterCount) {
            val weight = clusterWeight[current]
            if (weight <= 0.0) continue
            clusters += ClusterDraft(
                r = clusterSumR[current] / weight,
                g = clusterSumG[current] / weight,
                b = clusterSumB[current] / weight,
                weight = weight,
            )
        }
        return clusters
    }

    /**
     * Greedy agglomeration: walking the clusters from the heaviest down, absorb any cluster
     * whose colour is within [AnalysisConfig.mergeDistance] of an already accepted one. The
     * weighted-mean absorb keeps the total weight (and therefore the percentages) intact.
     */
    private fun merge(clusters: List<ClusterDraft>): List<ColorResult> {
        val resultClusterDrafts = ArrayList<ClusterDraft>(clusters.size)
        for (cluster in clusters.sortedByDescending { it.weight }) {
            val clusterDraft = resultClusterDrafts.firstOrNull {
                ColorMath.distance(it.color, cluster.color) <= config.mergeDistance
            }
            if (clusterDraft == null) {
                resultClusterDrafts += cluster
            } else {
                clusterDraft.accumulateWeight(cluster)
            }
        }

        var total = 0.0
        for (cluster in resultClusterDrafts) {
            total += cluster.weight
        }
        if (total <= 0.0) return emptyList()

        return resultClusterDrafts.map { cluster ->
            ColorResult(
                rgb = cluster.color,
                percentage = (cluster.weight / total * 100.0).toFloat(),
            )
        }
    }

    private fun distanceSquared(point: Int, r: Double, g: Double, b: Double): Double {
        val dr = pointR[point] - r
        val dg = pointG[point] - g
        val db = pointB[point] - b
        return dr * dr + dg * dg + db * db
    }

    private class ClusterDraft(var r: Double, var g: Double, var b: Double, var weight: Double) {
        val color: RgbColor get() = RgbColor.of(r, g, b)

        fun accumulateWeight(clusterDraft: ClusterDraft) {
            val total = weight + clusterDraft.weight
            if (total <= 0.0) return
            r = (r * weight + clusterDraft.r * clusterDraft.weight) / total
            g = (g * weight + clusterDraft.g * clusterDraft.weight) / total
            b = (b * weight + clusterDraft.b * clusterDraft.weight) / total
            weight = total
        }
    }
}
