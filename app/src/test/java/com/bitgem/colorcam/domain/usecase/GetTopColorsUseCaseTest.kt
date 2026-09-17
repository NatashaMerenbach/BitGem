package com.bitgem.colorcam.domain.usecase

import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.FrameData
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.domain.repository.ColorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A hand-written fake instead of a Mockito mock: `ColorRepository.analyzeColors` is a
 * *suspend* function, whose JVM signature carries an extra `Continuation` parameter that
 * Mockito's matcher/`when` DSL cannot express cleanly from Kotlin. The fake is both stricter
 * (it records the exact frame it was handed) and less brittle than a mock with a
 * `Continuation`-shaped hole in its argument list.
 *
 * Mockito is used where the interface is not suspending — see `ObserveTopColorsUseCaseTest`.
 */
private class FakeColorRepository(
    private val results: List<ColorResult>,
) : ColorRepository {
    var lastFrame: FrameData? = null
    var analyzeCallCount = 0

    override suspend fun analyzeColors(frame: FrameData): List<ColorResult> {
        lastFrame = frame
        analyzeCallCount++
        return results
    }

    override fun observeTopColors(): Flow<List<ColorResult>> = flowOf(results)

    override fun observeAnalysisErrors(): Flow<Throwable> = emptyFlow()
}

class GetTopColorsUseCaseTest {

    private val frame = FrameData(2, 2, IntArray(4) { 0xFF112233.toInt() })

    private val sixColors = (1..6).map { index ->
        ColorResult(RgbColor(index * 10, index * 20, index * 30), 100f / 6f)
    }

    @Test
    fun `delegates to the repository with the exact frame`() = runTest {
        val repository = FakeColorRepository(sixColors)
        val useCase = GetTopColorsUseCase(repository)

        useCase(frame)

        assertEquals(1, repository.analyzeCallCount)
        assertEquals(frame, repository.lastFrame)
    }

    @Test
    fun `returns at most five colours by default`() = runTest {
        val repository = FakeColorRepository(sixColors)

        val result = GetTopColorsUseCase(repository)(frame)

        assertEquals(5, result.size)
        assertEquals(sixColors.take(5), result)
    }

    @Test
    fun `honours an explicit limit`() = runTest {
        val repository = FakeColorRepository(sixColors)

        val result = GetTopColorsUseCase(repository)(frame, limit = 2)

        assertEquals(2, result.size)
    }

    @Test
    fun `propagates an empty result`() = runTest {
        val repository = FakeColorRepository(emptyList())

        assertEquals(emptyList<ColorResult>(), GetTopColorsUseCase(repository)(frame))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive limit`() = runTest {
        GetTopColorsUseCase(FakeColorRepository(emptyList()))(frame, limit = 0)
    }
}
