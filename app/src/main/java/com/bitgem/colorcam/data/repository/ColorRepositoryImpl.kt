package com.bitgem.colorcam.data.repository

import android.util.Log
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

    /** Set by the first failure of a burst, cleared by the next frame that succeeds. */
    private var loggedFailure = false

    override fun observeTopColors(): Flow<List<ColorResult>> = topColors.asStateFlow()

    override fun observeAnalysisErrors(): Flow<Throwable> = analysisErrors.asSharedFlow()

    /** override from ImageAnalysis.Analyzer */
    override fun analyze(image: ImageProxy) {
        if (!shouldAnalyseNow()) {
            image.close()
            return
        }

        // Kept for the failure log: on a device, the useful part of a broken frame is its
        // geometry (a vendor HAL can hand out strides the arithmetic did not expect), and by the
        // time the catch runs the proxy may already be closed.
        var frameWidth = image.width
        var frameHeight = image.height
        var geometry = "unknown strides"

        try {
            synchronized(analysisLock) {
                // The conversion HAS to happen while the proxy is open: closing it invalidates
                // the plane buffers (and the underlying camera buffer is recycled).
                val yuvFrame = imageToYuv420Frame.convertToYUV420Frame(image)
                frameWidth = yuvFrame.width
                frameHeight = yuvFrame.height
                geometry = "yRowStride=${yuvFrame.yRowStride} yPixelStride=${yuvFrame.yPixelStride} " +
                    "uvRowStride=${yuvFrame.uvRowStride} uvPixelStride=${yuvFrame.uvPixelStride}"
                // Sample while converting: the YUV maths is the most expensive per-pixel work in
                // the pipeline, so decoding all 307,200 pixels of a 640x480 frame to then bin
                // 19,200 of them wastes the largest cost in the loop (and the ARGB buffer).
                val step = analysisConfig.samplingStep
                val pixels = bufferToRequiredSize(
                    Yuv420Converter.sampledPixelCount(yuvFrame.width, yuvFrame.height, step),
                )
                val frame = yuv420Converter.convertIntoFrameData(yuvFrame, pixels, step)

                val colorResults = colorQuantizer.quantize(frame, analysisConfig.topColorCount)
                topColors.value = colorSmoother.smooth(colorResults)
                // A frame made it through, so the next failure is a new problem, not a repeat.
                loggedFailure = false
            }
        } catch (error: Throwable) {
            // A single malformed frame must never take down the camera pipeline; report and
            // keep consuming frames.
            logFailure(error, frameWidth, frameHeight, geometry)
            analysisErrors.tryEmit(error)
        } finally {
            // Always close, on every path — otherwise the ImageAnalysis pipeline stalls after
            // `maxImages` frames and the preview freezes too.
            image.close()
        }
    }

    /**
     * Puts the first failure of a burst in logcat with the frame geometry it choked on.
     *
     * Logging every failure would be useless: a stride bug or a HAL edge case throws on *every*
     * frame, so at ~10 analyses/s the trace would be buried by its own repeats within seconds.
     * The first failure of a burst gets the full stack trace, later ones are suppressed, and a
     * successful frame re-arms it — which is also exactly the pair a bug report needs: what broke,
     * and the geometry it broke on.
     *
     * The UI still shows only `error_analysis_failed`: a user should not be reading strides.
     */
    private fun logFailure(error: Throwable, width: Int, height: Int, geometry: String) {
        if (loggedFailure) return
        loggedFailure = true
        Log.e(LOG_TAG, "Frame analysis failed on ${width}x$height ($geometry)", error)
    }

    /** Time-based throttle: `minFrameIntervalMillis` between analyses. Always true at start-up. */
    private fun shouldAnalyseNow(): Boolean {
        val now = elapsedTime.nowMillis()
        val last = lastAnalysisMillis
        if (last != Long.MIN_VALUE && now - last < analysisConfig.minFrameIntervalMillis) return false
        lastAnalysisMillis = now
        return true
    }

    /**
     * Grows the buffer on demand. At `samplingStep = 4` a 640x480 analysis frame needs 19,200
     * ints (~77 KB) rather than 307,200 (~1.2 MB), and the array is reused across frames.
     */
    private fun bufferToRequiredSize(required: Int): IntArray {
        if (pixels.size < required) pixels = IntArray(required)
        return pixels
    }
}

/** Filterable with `adb logcat -s ColorCam.Analysis` (see README, "Debugging on a device"). */
private const val LOG_TAG = "ColorCam.Analysis"
