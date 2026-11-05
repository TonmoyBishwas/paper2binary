package com.paper2binary.core.imaging

import kotlin.math.max

/** Boolean-per-pixel mask stored as bytes: 1 = ink, 0 = background. */
class BinaryMask(val width: Int, val height: Int, val data: ByteArray)

object Binarizer {
    /**
     * Adaptive threshold (Bradley-Roth): a pixel is ink when it is clearly darker than the mean
     * of its neighbourhood. This tolerates the shadow of a phone and uneven room lighting, which
     * would defeat any single global threshold.
     *
     * @param relative how far below the local mean a pixel must be, as a fraction of that mean
     * @param minContrast absolute difference required, so that dark but flat areas such as a
     *                    table surface do not turn into noise
     */
    fun adaptive(
        image: GrayImage,
        windowSize: Int = max(image.width, image.height) / 8,
        relative: Double = 0.18,
        minContrast: Int = 18,
    ): BinaryMask {
        val w = image.width
        val h = image.height
        val half = (windowSize / 2).coerceAtLeast(4)

        // Integral image with one extra row and column so lookups need no edge handling.
        val stride = w + 1
        val integral = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            val src = y * w
            val above = y * stride
            val here = (y + 1) * stride
            for (x in 0 until w) {
                rowSum += image.pixels[src + x].toInt() and 0xFF
                integral[here + x + 1] = integral[above + x + 1] + rowSum
            }
        }

        val out = ByteArray(w * h)
        val keep = 1.0 - relative
        for (y in 0 until h) {
            val y0 = (y - half).coerceAtLeast(0)
            val y1 = (y + half + 1).coerceAtMost(h)
            val top = y0 * stride
            val bottom = y1 * stride
            val src = y * w
            for (x in 0 until w) {
                val x0 = (x - half).coerceAtLeast(0)
                val x1 = (x + half + 1).coerceAtMost(w)
                val sum = integral[bottom + x1] - integral[bottom + x0] -
                    integral[top + x1] + integral[top + x0]
                val count = (x1 - x0) * (y1 - y0)
                val value = image.pixels[src + x].toInt() and 0xFF
                val mean = sum.toDouble() / count
                if (value < mean * keep && mean - value >= minContrast) out[src + x] = 1
            }
        }
        return BinaryMask(w, h, out)
    }
}
