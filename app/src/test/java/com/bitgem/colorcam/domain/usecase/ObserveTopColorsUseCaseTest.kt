package com.bitgem.colorcam.domain.usecase

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.domain.repository.ColorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
    fun `emits the repository colours truncated to the limit`() = runTest {
        `when`(repository.observeTopColors()).thenReturn(flowOf(colors))

        val emitted = mutableListOf<List<ColorResult>>()
        ObserveTopColorsUseCase(repository)().collect { emitted += it }

        assertEquals(1, emitted.size)
        assertEquals(5, emitted.first().size)
        assertEquals(colors.take(5), emitted.first())
        verify(repository).observeTopColors()
    }

    @Test
    fun `an empty repository flow emits nothing`() = runTest {
        `when`(repository.observeTopColors()).thenReturn(emptyFlow())

        val emitted = mutableListOf<List<ColorResult>>()
        ObserveTopColorsUseCase(repository)().collect { emitted += it }

        assertEquals(0, emitted.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive limit`() {
        ObserveTopColorsUseCase(repository)(limit = -1)
    }
}
