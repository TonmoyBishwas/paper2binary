package com.paper2binary.core.vision

import com.paper2binary.core.imaging.Binarizer
import com.paper2binary.core.imaging.Component
import com.paper2binary.core.imaging.Components
import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.layout.SheetLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A corner marker found in an image; the centre comes from the dot, which is the most exact. */
data class Marker(val center: Point, val size: Double)

/** Four corner markers in clockwise order. The starting corner is not yet known. */
data class PageQuad(val corners: List<Point>, val markerSize: Double) {
    /** Longest side, handy for "move closer" hints and stability checks. */
    val longestSide: Double
        get() = (0 until 4).maxOf { distance(corners[it], corners[(it + 1) % 4]) }
}

fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

/**
 * Finds the four printed corner markers.
 *
 * A marker is a thick square ring with a dot exactly in its centre, so it is recognised by its
 * topology (blob inside a blob) rather than by template matching. That stays reliable under
 * rotation, perspective and blur, and a ticked checkbox does not qualify because its border is
 * far thinner and its tick is neither solid nor centred.
 */
object MarkerDetector {

    fun detectMarkers(image: GrayImage): List<Marker> {
        val mask = Binarizer.adaptive(image)
        val components = Components.find(mask, minArea = 4)

        val rings = components.filter { c ->
            c.area >= 30 && c.width >= 8 && c.height >= 8 &&
                max(c.width, c.height) <= 2.5 * min(c.width, c.height) &&
                c.fill in 0.22..0.85
        }
        val dots = components.filter { it.fill >= 0.45 && it.width >= 2 && it.height >= 2 }

        val markers = ArrayList<Marker>()
        for (ring in rings) {
            val size = (ring.width + ring.height) / 2.0
            val dot = dots.firstOrNull { d -> isCentredDot(ring, d, size) } ?: continue
            markers += Marker(Point(dot.centerX, dot.centerY), size)
        }
        return markers
    }

    private fun isCentredDot(ring: Component, dot: Component, ringSize: Double): Boolean {
        if (dot === ring) return false
        if (dot.minX <= ring.minX || dot.maxX >= ring.maxX) return false
        if (dot.minY <= ring.minY || dot.maxY >= ring.maxY) return false
        val offCentre = hypot(dot.centerX - ring.centerX, dot.centerY - ring.centerY)
        if (offCentre > 0.15 * ringSize) return false
        val areaRatio = dot.area.toDouble() / ring.area
        if (areaRatio !in 0.08..0.6) return false
        val sizeRatio = (dot.width + dot.height) / 2.0 / ringSize
        return sizeRatio in 0.2..0.6
    }

    /**
     * Picks the four markers that form the sheet, or null when no plausible sheet is visible.
     *
     * The three finder patterns of the QR code look just like markers, only smaller. The largest
     * valid quadrilateral whose markers are of a similar size excludes them.
     */
    fun locatePage(image: GrayImage): PageQuad? {
        val candidates = detectMarkers(image).sortedByDescending { it.size }.take(12)
        if (candidates.size < 4) return null

        var best: PageQuad? = null
        var bestArea = 0.0
        val n = candidates.size
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val group = listOf(candidates[a], candidates[b], candidates[c], candidates[d])
            val quad = validQuad(group) ?: continue
            val area = quadArea(quad.corners)
            if (area > bestArea) {
                bestArea = area
                best = quad
            }
        }
        return best
    }

    private val EXPECTED_MARKER_TO_SIDE: Double =
        SheetLayout.MARKER_SIZE / (SheetLayout.SHEET_WIDTH - 18.0)

    private fun validQuad(group: List<Marker>): PageQuad? {
        val sizes = group.map { it.size }
        if (sizes.max() > 2.2 * sizes.min()) return null

        // Clockwise on screen, because image y points downwards.
        val cx = group.sumOf { it.center.x } / 4
        val cy = group.sumOf { it.center.y } / 4
        val corners = group.map { it.center }.sortedBy { atan2(it.y - cy, it.x - cx) }
        if (!isConvex(corners)) return null

        val sides = (0 until 4).map { distance(corners[it], corners[(it + 1) % 4]) }
        // Opposite sides shrink under perspective but only within limits for a hand-held phone.
        if (max(sides[0], sides[2]) > 2.0 * min(sides[0], sides[2])) return null
        if (max(sides[1], sides[3]) > 2.0 * min(sides[1], sides[3])) return null

        // The sheet is 130 x 192 mm between marker centres.
        val along = (sides[0] + sides[2]) / 2
        val across = (sides[1] + sides[3]) / 2
        val aspect = max(along, across) / min(along, across)
        if (aspect !in 1.05..2.2) return null

        val shortSide = min(along, across)
        val markerToSide = sizes.average() / shortSide
        if (markerToSide !in 0.45 * EXPECTED_MARKER_TO_SIDE..1.9 * EXPECTED_MARKER_TO_SIDE) return null

        return PageQuad(corners, sizes.average())
    }

    private fun isConvex(p: List<Point>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun quadArea(p: List<Point>): Double {
        var sum = 0.0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
    }
}
