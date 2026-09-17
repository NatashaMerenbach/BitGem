package com.bitgem.colorcam.data.repository

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bitgem.colorcam.data.camera.ElapsedTimeSource
import com.bitgem.colorcam.data.camera.ImageProxyFrameMapper
import com.bitgem.colorcam.data.di.AnalysisDispatcher
import com.bitgem.colorcam.domain.analysis.AnalysisConfig
import com.bitgem.colorcam.domain.analysis.ColorQuantizer
import com.bitgem.colorcam.domain.analysis.ColorSmoother
import com.bitgem.colorcam.domain.analysis.Yuv420Converter
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.repository.ColorRepository
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The only implementation of [ColorRepository]: CameraX's `ImageAnalysis.Analyzer` *and* the
 * data-layer wrapper around the clustering pipeline.
 *
 * Threading model — the whole design hinges on this:
 *  - [analyze] is invoked by CameraX on the single-threaded [AnalysisExecutor] that was
 *    passed to `ImageAnalysis.setAnalyzer`. It **never** touches the main thread.
 *  - The analysis pipeline (conversion, binning, k-means, smoothing) keeps reusable scratch
 *    state (histogram arrays, pixel buffer, smoother history), so it must not run twice
 *    concurrently. Every entry point takes [analysisLock], which makes the class correct even
 *    if a caller supplies a multi-threaded executor, and costs nothing in practice because
 *    there is never contention on the camera path.
 *  - [analyzeColors] (the pull API) hops onto the same analysis dispatcher and takes the same
 *    lock, so the use-case path cannot corrupt the camera path's buffers.
 *
 * Back-pressure is handled in three layers: `STRATEGY_KEEP_ONLY_LATEST` in the
 * `ImageAnalysis` use case (CameraX drops frames while we are busy), the time-based throttle
 * below, and a conflated `StateFlow` for the UI.
 */
@Singleton
class ColorRepositoryImpl @Inject constructor(
    private val mapper: ImageProxyFrameMapper,
    private val converter: Yuv420Converter,
    private val quantizer: ColorQuantizer,
    private val smoother: ColorSmoother,
    private val config: AnalysisConfig,
    private val elapsedTime: ElapsedTimeSource,
    @AnalysisDispatcher private val analysisDispatcher: CoroutineDispatcher,
) : ColorRepository, ImageAnalysis.Analyzer {

    private val topColors = MutableStateFlow<List<ColorResult>>(emptyList())
    private val analysisErrors = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Guards every use of the shared scratch buffers. */
    private val analysisLock = Any()

    /** Reused between frames: a 640x480 ARGB frame is 1.2 MB, and we run 10 of them per second. */
    private var scratchPixels = IntArray(0)

    private var lastAnalysisMillis = Long.MIN_VALUE

    /** Diagnostics for the write-up / manual verification; not part of the domain contract. */
    val analysedFrameCount = AtomicLong(0)
    val skippedFrameCount = AtomicLong(0)

    override fun observeTopColors(): Flow<List<ColorResult>> = topColors.asStateFlow()

    override fun observeAnalysisErrors(): Flow<Throwable> = analysisErrors.asSharedFlow()

    override suspend fun analyzeColors(frame: FrameData): List<ColorResult> =
        withContext(analysisDispatcher) {
            synchronized(analysisLock) {
                // No temporal smoothing on the pull path: a one-shot analysis must answer for
                // the frame it was given, not for a blend with whatever the camera last saw.
                quantizer.quantize(frame, config.topColorCount)
            }
        }

    override fun analyze(image: ImageProxy) {
        if (!shouldAnalyseNow()) {
            skippedFrameCount.incrementAndGet()
            image.close()
            return
        }

        try {
            synchronized(analysisLock) {
                // The conversion HAS to happen while the proxy is open: closing it invalidates
                // the plane buffers (and the underlying camera buffer is recycled).
                val yuvFrame = mapper.map(image)
                val pixels = scratchBufferFor(yuvFrame.width, yuvFrame.height)
                val frame = converter.convertInto(yuvFrame, pixels)

                val quantized = quantizer.quantize(frame, config.topColorCount)
                topColors.value = smoother.smooth(quantized)
                analysedFrameCount.incrementAndGet()
            }
        } catch (error: Throwable) {
            // A single malformed frame must never take down the camera pipeline; report and
            // keep consuming frames.
            analysisErrors.tryEmit(error)
        } finally {
            // Always close, on every path — otherwise the ImageAnalysis pipeline stalls after
            // `maxImages` frames and the preview freezes too.
            image.close()
        }
    }

    /** Time-based throttle: `minFrameIntervalMillis` between analyses. Always true at start-up. */
    private fun shouldAnalyseNow(): Boolean {
        val now = elapsedTime.nowMillis()
        val last = lastAnalysisMillis
        if (last != Long.MIN_VALUE && now - last < config.minFrameIntervalMillis) return false
        lastAnalysisMillis = now
        return true
    }

    /** Grows the scratch buffer on demand; called under [analysisLock]. */
    private fun scratchBufferFor(width: Int, height: Int): IntArray {
        val required = width * height
        if (scratchPixels.size < required) scratchPixels = IntArray(required)
        return scratchPixels
    }
}
