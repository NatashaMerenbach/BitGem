package com.bitgem.colorcam.domain.usecases

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.repository.ColorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * The colors of whatever the camera is currently looking at — what the camera screen's
 * ViewModel collects.
 *
 * There is deliberately no `limit` parameter: the pipeline caps its own output at
 * [com.bitgem.colorcam.domain.analysis.AnalysisConfig.topColorCount], so the number of colors
 * lives in exactly one place and cannot drift between the algorithm and the UI.
 */
class ObserveTopColorsUseCase @Inject constructor(
    private val repository: ColorRepository,
) {
    operator fun invoke(): Flow<List<ColorResult>> = repository.observeTopColors()
}
