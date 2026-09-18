package com.bitgem.colorcam.domain.analysis

import com.bitgem.colorcam.domain.model.FrameData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelSamplerTest {

    private val sampler = PixelSampler()

    private fun frame(width: Int, height: Int): FrameData =
        FrameData(width, height, IntArray(width * height) { it })

    @Test
    fun `step 1 keeps every pixel`() {
        val frame = frame(5, 4)

        val samples = sampler.sample(frame, 1)

        assertEquals(20, samples.size)
        assertEquals(frame.pixels.toList(), samples.toList())
    }

    @Test
    fun `step 4 keeps every 16th pixel`() {
        val frame = frame(8, 8)

        val samples = sampler.sample(frame, 4)

        // rows 0 and 4, columns 0 and 4 of an 8x8 frame.
        assertEquals(4, samples.size)
        assertEquals(listOf(0, 4, 32, 36), samples.toList())
    }

    @Test
    fun `sample count matches what sampleInto writes for odd sizes`() {
        val frame = frame(7, 5)
        val expected = sampler.sampleCount(7, 5, 3)
        val buffer = IntArray(expected)

        val written = sampler.sampleInto(frame, 3, buffer)

        assertEquals(expected, written)
    }

    @Test
    fun `an oversized buffer is legal and only the required prefix is written`() {
        val frame = frame(7, 5)
        val required = sampler.sampleCount(7, 5, 3)
        // Poisoned tail: a reused buffer must not leak stale samples into the answer.
        val buffer = IntArray(required + 8) { -1 }

        val written = sampler.sampleInto(frame, 3, buffer)

        assertEquals(required, written)
        assertTrue(
            "the tail must stay untouched but was ${buffer.drop(required)}",
            buffer.drop(required).all { it == -1 },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a buffer smaller than the sample count is rejected`() {
        val tooSmall = IntArray(sampler.sampleCount(7, 5, 3) - 1)

        sampler.sampleInto(frame(7, 5), 3, tooSmall)
    }

    @Test
    fun `empty frame yields no samples`() {
        val empty = FrameData(0, 0, IntArray(0))

        assertEquals(0, sampler.sampleCount(0, 0, 4))
        assertTrue(sampler.sample(empty, 4).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `step below 1 is rejected`() {
        sampler.sampleCount(10, 10, 0)
    }
}
