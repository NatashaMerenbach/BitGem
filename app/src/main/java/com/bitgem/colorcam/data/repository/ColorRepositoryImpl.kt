package com.bitgem.colorcam.data.repository

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.bitgem.colorcam.data.camera.ElapsedTimeSource
import com.bitgem.colorcam.data.camera.ImageToYuv420Frame
import com.bitgem.colorcam.domain.analysis.AnalysisConfig
import com.bitgem.colorcam.domain.analysis.ColorQuantizer
import com.bitgem.colorcam.domain.analysis.ColorSmoother
import com.bitgem.colorcam.domain.analysis.Yuv420Converter
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.repository.ColorRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only implementation of [ColorRepository]: CameraX's `ImageAnalysis.Analyzer` *and* the
 * data-layer wrapper around the clustering pipeline.
 *
 * Threading model — the whole design hinges on this:
 *  - [analyze] is invoked by CameraX on the single-threaded executor that was passed to
 *    `ImageAnalysis.setAnalyzer`. It **never** touches the main thread.
 *  - The analysis pipeline (conversion, binning, k-means, smoothing) keeps reusable scratch
 *    state (histogram arrays, pixel buffer, smoother history), so it must not run twice
 *    concurrently. [analyze] therefore takes [analysisLock], which makes the class correct even
 *    if a caller supplies a multi-threaded executor; on the camera path there is never
 *    contention, so the lock is free.
 *
 * Back-pressure is handled in three layers: `STRATEGY_KEEP_ONLY_LATEST` in the
 * `ImageAnalysis` use case (CameraX drops frames while we are busy), the time-based throttle
 * below, and a conflated `StateFlow` for the UI.
 */
@Singleton
class ColorRepositoryImpl @Inject constructor(
    private val imageToYuv420Frame: ImageToYuv420Frame,
    private val yuv420Converter: Yuv420Converter,
    private val colorQuantizer: ColorQuantizer,
    private val colorSmoother: ColorSmoother,
    private val analysisConfig: AnalysisConfig,
    private val elapsedTime: ElapsedTimeSource,
) : ColorRepository, ImageAnalysis.Analyzer {

    private val topColors = MutableStateFlow<List<ColorResult>>(emptyList())
    private val analysisErrors = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Guards every use of the shared scratch buffers. */
    private val analysisLock = Any()

    /** Reused between frames: a 640x480 ARGB frame is 1.2 MB, and we run 10 of them per second. */
    private var pixels = IntArray(0)

    private var lastAnalysisMillis = Long.MIN_VALUE

    override fun observeTopColors(): Flow<List<ColorResult>> = topColors.asStateFlow()

    override fun observeAnalysisErrors(): Flow<Throwable> = analysisErrors.asSharedFlow()

    /** override from ImageAnalysis.Analyzer */
    override fun analyze(image: ImageProxy) {
        if (!shouldAnalyseNow()) {
            image.close()
            return
        }

        try {
            synchronized(analysisLock) {
                // The conversion HAS to happen while the proxy is open: closing it invalidates
                // the plane buffers (and the underlying camera buffer is recycled).
                val yuvFrame = imageToYuv420Frame.convertToYUV420Frame(image)
                val pixels : IntArray = bufferToRequiredSize(yuvFrame.width, yuvFrame.height)
                val frame : FrameData = yuv420Converter.convertIntoFrameData(yuvFrame, pixels)

                val colorResults = colorQuantizer.quantize(frame, analysisConfig.topColorCount)
                topColors.value = colorSmoother.smooth(colorResults)
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
        if (last != Long.MIN_VALUE && now - last < analysisConfig.minFrameIntervalMillis) return false
        lastAnalysisMillis = now
        return true
    }

    /** Grows the buffer on demand. */
    private fun bufferToRequiredSize(width: Int, height: Int): IntArray {
        val required = width * height
        if (pixels.size < required) pixels = IntArray(required)
        return pixels
    }
}
