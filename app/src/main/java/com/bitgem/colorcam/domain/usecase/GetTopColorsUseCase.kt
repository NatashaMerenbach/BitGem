package com.bitgem.colorcam.domain.usecase

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.repository.ColorRepository
import javax.inject.Inject

/**
 * One-shot analysis of a caller-supplied frame: "what are the N most common colours in this
 * picture?".
 *
 * The use case owns no algorithm itself — the binning/clustering lives in
 * `domain/analysis` and is reached through [ColorRepository] — which keeps it a two-line
 * orchestrator that is trivially testable with a mocked repository.
 */
class GetTopColorsUseCase @Inject constructor(
    private val repository: ColorRepository,
) {
    suspend operator fun invoke(frame: FrameData, limit: Int = DEFAULT_LIMIT): List<ColorResult> {
        require(limit > 0) { "limit must be positive but was $limit" }
        return repository.analyzeColors(frame).take(limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 5
    }
}
