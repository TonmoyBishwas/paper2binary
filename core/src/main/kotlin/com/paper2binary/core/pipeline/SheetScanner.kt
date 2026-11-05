package com.paper2binary.core.pipeline

import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.layout.RectMm
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.omr.MarkReader
import com.paper2binary.core.omr.QuestionReading
import com.paper2binary.core.vision.Homography
import com.paper2binary.core.vision.MarkerDetector
import com.paper2binary.core.vision.PageQuad
import com.paper2binary.core.vision.Point
import com.paper2binary.core.vision.QrCodec
import kotlin.math.max

/** A successfully read sheet. */
class SheetScan(
    val spec: SheetSpec,
    val layout: SheetLayout,
    /** Flat, upright image of the sheet at [PX_PER_MM]. */
    val sheet: GrayImage,
    val readings: List<QuestionReading>,
    /** Flat image of the comment box, or null when nothing was written in it. */
    val commentImage: GrayImage?,
) {
    val needsReview: Boolean get() = readings.any { it.needsReview }

    /** True when not one box was ticked and no comment written, usually a mistaken scan. */
    val isBlank: Boolean get() = commentImage == null && readings.all { it.selected.isEmpty() }

    companion object {
        const val PX_PER_MM = 10.0
    }
}

sealed interface ScanOutcome {
    data class Success(val scan: SheetScan) : ScanOutcome

    /** The four corner markers could not be found. */
    data object NoSheet : ScanOutcome

    /** Corners were found, but neither the QR code nor the expected layout could be matched. */
    data object UnreadableCode : ScanOutcome
}

/**
 * The complete pipeline from a photo to answers:
 *
 * locate corners -> find orientation from the QR code -> flatten -> read boxes -> crop comment
 */
class SheetScanner(
    private val markReader: MarkReader = MarkReader(),
    /** Minimum ink coverage for the comment box to count as filled in. */
    private val commentInkThreshold: Double = 0.0018,
) {

    /** Fast check for live preview frames: only finds the corners. */
    fun locate(frame: GrayImage): PageQuad? = MarkerDetector.locatePage(frame)

    /**
     * @param photo the highest resolution image available
     * @param expected spec of the current survey, used as a fallback if the QR code is damaged
     */
    fun scan(photo: GrayImage, expected: SheetSpec? = null): ScanOutcome {
        // Corners are found on a reduced copy (fast and plenty accurate), then scaled back.
        val small = photo.downscaledTo(DETECTION_SIDE)
        val quad = MarkerDetector.locatePage(small) ?: return ScanOutcome.NoSheet
        val scale = max(photo.width, photo.height).toDouble() / max(small.width, small.height)
        val corners = quad.corners.map { Point(it.x * scale, it.y * scale) }

        val oriented = orient(photo, corners, expected) ?: return ScanOutcome.UnreadableCode
        val layout = SheetLayout.compute(oriented.spec)
        if (!layout.fits) return ScanOutcome.UnreadableCode

        val pxPerMm = SheetScan.PX_PER_MM
        val sheet = oriented.transform.warp(
            photo,
            (SheetLayout.SHEET_WIDTH * pxPerMm).toInt(),
            (SheetLayout.SHEET_HEIGHT * pxPerMm).toInt(),
            pxPerMm,
        )
        val readings = markReader.read(sheet, layout, pxPerMm)
        val commentImage = layout.comment?.let { area ->
            val ink = markReader.inkCoverage(sheet, area, pxPerMm)
            if (ink >= commentInkThreshold) crop(sheet, area, pxPerMm) else null
        }
        return ScanOutcome.Success(SheetScan(oriented.spec, layout, sheet, readings, commentImage))
    }

    private class Oriented(val spec: SheetSpec, val transform: Homography)

    /**
     * The sheet may lie in any of four orientations. The QR code sits in exactly one corner, so
     * the only assignment of corners under which it can be read in place is the correct one.
     */
    private fun orient(photo: GrayImage, corners: List<Point>, expected: SheetSpec?): Oriented? {
        val transforms = (0 until 4).mapNotNull { rotation ->
            val photoPoints = List(4) { corners[(it + rotation) % 4] }
            Homography.fromPoints(MARKERS_MM, photoPoints)
        }

        val qr = SheetLayout.QR_RECT
        val pixels = (qr.width * QR_PX_PER_MM).toInt()
        for (transform in transforms) {
            val crop = transform.warp(photo, pixels, pixels, QR_PX_PER_MM, qr.left, qr.top)
            val spec = QrCodec.decode(crop)?.let { SheetSpec.decode(it) } ?: continue
            return Oriented(spec, transform)
        }
        for (transform in transforms) {
            val text = QrCodec.decodeAtKnownPosition(qr, QrCodec.QUIET_MODULES) { x, y ->
                val p = transform.apply(x, y)
                photo.sample(p.x, p.y)
            } ?: continue
            val spec = SheetSpec.decode(text) ?: continue
            return Oriented(spec, transform)
        }

        // QR unreadable: fall back on where the printed boxes of the expected layout line up.
        if (expected == null) return null
        val layout = SheetLayout.compute(expected)
        val scored = transforms.map { it to boxBorderContrast(photo, it, layout) }
        val (best, score) = scored.maxByOrNull { it.second } ?: return null
        val runnerUp = scored.filter { it.first !== best }.maxOf { it.second }
        return if (score > MIN_BORDER_CONTRAST && score > runnerUp * 1.3) Oriented(expected, best) else null
    }

    /** How much darker the expected box outlines are than the paper just inside them. */
    private fun boxBorderContrast(photo: GrayImage, t: Homography, layout: SheetLayout): Double {
        var total = 0.0
        val boxes = layout.allBoxes
        boxes.forEach { box ->
            var edge = 0.0
            var inner = 0.0
            val samples = 12
            for (i in 0 until samples) {
                val along = box.left + box.width * (i + 0.5) / samples
                val p1 = t.apply(along, box.top)
                val p2 = t.apply(along, box.top + 1.5)
                edge += photo.sample(p1.x, p1.y)
                inner += photo.sample(p2.x, p2.y)
            }
            total += (inner - edge) / samples
        }
        return total / boxes.size
    }

    private fun crop(sheet: GrayImage, area: RectMm, pxPerMm: Double): GrayImage = sheet.crop(
        (area.left * pxPerMm).toInt(),
        (area.top * pxPerMm).toInt(),
        (area.right * pxPerMm).toInt(),
        (area.bottom * pxPerMm).toInt(),
    )

    private companion object {
        const val DETECTION_SIDE = 1600
        const val QR_PX_PER_MM = 12.0
        const val MIN_BORDER_CONTRAST = 12.0
        val MARKERS_MM = SheetLayout.MARKER_CENTERS.map { Point(it.x, it.y) }
    }
}
