package com.bitgem.colorcam.domain.usecases

import com.bitgem.colorcam.domain.repository.ColorRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveErrorsUseCase@Inject constructor(
    private val repository: ColorRepository,
) {
    operator fun invoke() : Flow<Throwable> {
        // Implementation for observing errors
       return repository.observeAnalysisErrors()
    }
}