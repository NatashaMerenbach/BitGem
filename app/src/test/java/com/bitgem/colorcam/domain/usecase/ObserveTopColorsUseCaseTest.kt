package com.bitgem.colorcam.domain.usecase

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.domain.repository.ColorRepository
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * Mockito on the non-suspending part of the repository contract.
 */
class ObserveTopColorsUseCaseTest {

    private val repository: ColorRepository = mock(ColorRepository::class.java)

    private val colors = (1..7).map { index ->
        ColorResult(RgbColor(index, index, index), 10f)
    }

    @Test
    fun `forwards the repository colours unchanged`() = runTest {
        `when`(repository.observeTopColors()).thenReturn(flowOf(colors))

        val emitted = mutableListOf<List<ColorResult>>()
        ObserveTopColorsUseCase(repository)().collect { emitted += it }

        // No truncation happens here: the pipeline already caps the list at
        // AnalysisConfig.topColorCount, which is the single owner of that number.
        assertEquals(listOf(colors), emitted)
        verify(repository).observeTopColors()
    }

    @Test
    fun `an empty repository flow emits nothing`() = runTest {
        `when`(repository.observeTopColors()).thenReturn(emptyFlow())

        val emitted = mutableListOf<List<ColorResult>>()
        ObserveTopColorsUseCase(repository)().collect { emitted += it }

        assertEquals(0, emitted.size)
    }
}
