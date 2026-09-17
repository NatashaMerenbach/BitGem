package com.bitgem.colorcam.domain.repository

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import kotlinx.coroutines.flow.Flow

/**
 * The domain's view of "where colours come from".
 *
 * Two entry points, because the app needs both:
 *  - [observeTopColors] — the live pipeline used by the camera screen. The implementation
 *    is fed frames by the camera analyser and pushes the newest result to every collector.
 *  - [analyzeColors] — a pull-based, one-shot analysis of a frame supplied by the caller,
 *    which is what makes the pipeline usable from tests and from any future
 *    non-camera source (an imported photo, a widget, a share target).
 *  - [observeAnalysisErrors] — the failure channel of the live pipeline.
 */
interface ColorRepository {

    /**
     * Hot flow of the most recent analysis result (conflated: a slow collector skips
     * intermediate values instead of being back-pressured into slowing the camera down).
     * Emits an empty list until the first frame has been analysed.
     */
    fun observeTopColors(): Flow<List<ColorResult>>

    /** Analyses one frame on a background dispatcher and returns the top colours. */
    suspend fun analyzeColors(frame: FrameData): List<ColorResult>

    /**
     * Failures encountered while analysing frames — a single malformed frame must not kill
     * the pipeline, so they are reported here instead of being thrown at the camera.
     */
    fun observeAnalysisErrors(): Flow<Throwable>
}
