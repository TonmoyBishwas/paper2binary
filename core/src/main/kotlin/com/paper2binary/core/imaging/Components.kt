package com.paper2binary.core.imaging

/** Statistics of one connected blob of ink. */
class Component(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val area: Int,
    val centerX: Double,
    val centerY: Double,
) {
    val width get() = maxX - minX + 1
    val height get() = maxY - minY + 1
    val fill get() = area.toDouble() / (width * height)
}

object Components {
    /**
     * Two-pass connected component labelling (8-connected) using union-find. Only statistics are
     * returned because the detector never needs per-pixel labels afterwards.
     */
    fun find(mask: BinaryMask, minArea: Int = 4): List<Component> {
        val w = mask.width
        val h = mask.height
        val labels = IntArray(w * h)
        var parent = IntArray(4096)
        var next = 1

        fun root(start: Int): Int {
            var r = start
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (mask.data[row + x].toInt() == 0) continue
                // Already-visited neighbours: left, and the three pixels in the row above.
                val left = if (x > 0) labels[row + x - 1] else 0
                val upLeft = if (x > 0 && y > 0) labels[row - w + x - 1] else 0
                val up = if (y > 0) labels[row - w + x] else 0
                val upRight = if (x < w - 1 && y > 0) labels[row - w + x + 1] else 0

                var label = 0
                for (n in intArrayOf(left, upLeft, up, upRight)) {
                    if (n == 0) continue
                    if (label == 0) label = n else if (n != label) union(label, n)
                }
                if (label == 0) {
                    if (next == parent.size) parent = parent.copyOf(parent.size * 2)
                    parent[next] = next
                    label = next++
                }
                labels[row + x] = label
            }
        }

        // Second pass: gather statistics per resolved root.
        val minX = IntArray(next) { Int.MAX_VALUE }
        val minY = IntArray(next) { Int.MAX_VALUE }
        val maxX = IntArray(next) { -1 }
        val maxY = IntArray(next) { -1 }
        val area = IntArray(next)
        val sumX = LongArray(next)
        val sumY = LongArray(next)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val raw = labels[row + x]
                if (raw == 0) continue
                val r = root(raw)
                if (x < minX[r]) minX[r] = x
                if (x > maxX[r]) maxX[r] = x
                if (y < minY[r]) minY[r] = y
                if (y > maxY[r]) maxY[r] = y
                area[r]++
                sumX[r] += x.toLong()
                sumY[r] += y.toLong()
            }
        }

        val out = ArrayList<Component>()
        for (i in 1 until next) {
            if (area[i] < minArea) continue
            out += Component(
                minX[i], minY[i], maxX[i], maxY[i], area[i],
                sumX[i].toDouble() / area[i], sumY[i].toDouble() / area[i],
            )
        }
        return out
    }
}
