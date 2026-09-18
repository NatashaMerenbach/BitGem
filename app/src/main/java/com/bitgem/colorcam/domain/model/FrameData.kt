package com.bitgem.colorcam.domain.model

/**
 * Domain-level pixel buffer: one ARGB_8888 `Int` per pixel, row-major and tightly packed.
 *
 * Deliberately a plain class rather than a `data class`: `IntArray` uses reference equality,
 * so a generated `equals`/`hashCode` would be misleading.
 *
 * The analyser reuses a scratch buffer across frames, so a `FrameData` produced by
 * [com.bitgem.colorcam.domain.analysis.Yuv420Converter.convertIntoFrameData] is only valid for the
 * duration of the current analysis pass.
 */
class FrameData(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width >= 0 && height >= 0) { "Frame size must not be negative but was ${width}x$height" }
        require(pixels.size >= width * height) {
            "Pixel buffer too small: ${pixels.size} values for a ${width}x$height frame"
        }
    }

    val pixelCount: Int get() = width * height

    val isEmpty: Boolean get() = pixelCount == 0

    fun pixelAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "Pixel ($x, $y) is outside ${width}x$height" }
        return pixels[y * width + x]
    }
}
