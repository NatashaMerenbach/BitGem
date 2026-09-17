package com.bitgem.colorcam.domain.usecase

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.repository.ColorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Live-stream variant of [GetTopColorsUseCase]: the colours of whatever the camera is
 * currently looking at. This is what the camera screen's ViewModel collects.
 */
class ObserveTopColorsUseCase @Inject constructor(
    private val repository: ColorRepository,
) {
    operator fun invoke(limit: Int = GetTopColorsUseCase.DEFAULT_LIMIT): Flow<List<ColorResult>> {
        require(limit > 0) { "limit must be positive but was $limit" }
        return repository.observeTopColors().map { colors -> colors.take(limit) }
    }
}
