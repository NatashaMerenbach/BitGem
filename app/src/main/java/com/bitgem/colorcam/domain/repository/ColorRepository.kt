package com.bitgem.colorcam.domain.repository

import com.bitgem.colorcam.domain.model.ColorResult
import kotlinx.coroutines.flow.Flow

/**
 * The domain's view of "where colors come from".
 *
 *  - [observeTopColors] — the live pipeline used by the camera screen. The implementation is
 *    fed frames by the camera analyser and pushes the newest result to every collector.
 *  - [observeAnalysisErrors] — the failure channel of that pipeline.
 *
 * There is deliberately no pull/one-shot entry point: the app only ever analyses the live
 * camera stream, and a second entry point would have to duplicate the pipeline's locking and
 * dispatcher rules for a caller that does not exist. Adding one back (for an imported photo, a
 * share target, a widget) is a small, well-defined change: a `suspend fun` that runs
 * [com.bitgem.colorcam.domain.analysis.ColorQuantizer.quantize] on the analysis dispatcher under
 * the same lock.
 */
interface ColorRepository {

    /**
     * Hot flow of the most recent analysis result (conflated: a slow collector skips
     * intermediate values instead of being back-pressured into slowing the camera down).
     * Emits an empty list until the first frame has been analysed.
     */
    fun observeTopColors(): Flow<List<ColorResult>>

    /**
     * Failures encountered while analysing frames — a single malformed frame must not kill
     * the pipeline, so they are reported here instead of being thrown at the camera.
     */
    fun observeAnalysisErrors(): Flow<Throwable>
}
