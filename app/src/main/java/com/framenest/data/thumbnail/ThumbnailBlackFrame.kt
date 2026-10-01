package com.framenest.data.thumbnail

/**
 * Detect near-black frames so generation can try the next candidate timestamp.
 *
 * Operates on packed ARGB samples (no Android Bitmap dependency) for unit tests.
 */
object ThumbnailBlackFrame {
    /** Mean luminance below this (0–255) counts as black. */
    const val LUMINANCE_THRESHOLD: Double = 12.0

    /**
     * Fraction of sampled pixels that must be "dark" to call the frame black.
     * Avoids rejecting night scenes that still have bright highlights.
     */
    const val DARK_PIXEL_RATIO: Double = 0.96

    /** Per-pixel luminance below this is "dark". */
    const val DARK_PIXEL_LUMINANCE: Double = 18.0

    /**
     * @param argb packed 0xAARRGGBB samples (any order / stride)
     * @return true when the frame is considered unusable black
     */
    fun isBlackFrame(argb: IntArray): Boolean {
        if (argb.isEmpty()) return true
        var sumY = 0.0
        var dark = 0
        for (pixel in argb) {
            val y = luminance(pixel)
            sumY += y
            if (y < DARK_PIXEL_LUMINANCE) dark++
        }
        val mean = sumY / argb.size
        val darkRatio = dark.toDouble() / argb.size
        return mean < LUMINANCE_THRESHOLD && darkRatio >= DARK_PIXEL_RATIO
    }

    /**
     * Nearly uniform fields (solid color, gray fade) do not show what the video is.
     * Text on a background still has luminance spread, so title cards are kept.
     */
    const val LOW_INFORMATION_LUMINANCE_STDDEV: Double = 6.0

    fun isLowInformation(argb: IntArray): Boolean {
        if (argb.isEmpty()) return true
        var sumY = 0.0
        val luma = DoubleArray(argb.size)
        for (index in argb.indices) {
            val y = luminance(argb[index])
            luma[index] = y
            sumY += y
        }
        val mean = sumY / argb.size
        var variance = 0.0
        for (y in luma) {
            val delta = y - mean
            variance += delta * delta
        }
        val stddev = kotlin.math.sqrt(variance / argb.size)
        return stddev < LOW_INFORMATION_LUMINANCE_STDDEV
    }

    /** Rec. 601 luma from packed ARGB. */
    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** Sample a grid from a full width×height ARGB buffer (row-major). */
    fun sampleGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        samplesPerSide: Int = 8,
    ): IntArray {
        require(width > 0 && height > 0) { "invalid size" }
        require(pixels.size.toLong() >= width.toLong() * height.toLong()) { "buffer too small" }
        return sampleGrid(width, height, samplesPerSide) { x, y -> pixels[y * width + x] }
    }

    /**
     * Sample a fixed-size grid without first copying the complete frame.
     *
     * A 4K ARGB frame contains more than eight million pixels, so allocating a full
     * [IntArray] just to inspect 64 points briefly adds roughly 32 MiB per candidate.
     * Callers backed by a bitmap or another random-access image should use this overload.
     */
    inline fun sampleGrid(
        width: Int,
        height: Int,
        samplesPerSide: Int = 8,
        pixelAt: (x: Int, y: Int) -> Int,
    ): IntArray {
        require(width > 0 && height > 0) { "invalid size" }
        val n = samplesPerSide.coerceAtLeast(1)
        val out = IntArray(n * n)
        var i = 0
        for (gy in 0 until n) {
            val y = ((gy + 0.5) * height / n).toInt().coerceIn(0, height - 1)
            for (gx in 0 until n) {
                val x = ((gx + 0.5) * width / n).toInt().coerceIn(0, width - 1)
                out[i++] = pixelAt(x, y)
            }
        }
        return out
    }
}
