package com.paper2binary.core.omr

import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.layout.RectMm
import com.paper2binary.core.layout.SheetLayout

/** Outcome of reading one question, used to decide whether a person has to look at it. */
enum class ReadingStatus {
    /** One clear answer, or for multi-select questions any number of clear ticks. */
    OK,

    /** Nothing written in any box. A legitimate outcome: respondents may skip a question. */
    BLANK,

    /** Faint or very small marks that could be either a tick or dirt. */
    UNSURE,

    /** Several boxes ticked on a question that allows just one. */
    MULTIPLE,
}

data class QuestionReading(
    /** Share of each box's interior covered by ink, in option order. */
    val fills: List<Double>,
    /** Option indexes taken as the answer; may be a best guess if status is not OK. */
    val selected: List<Int>,
    val status: ReadingStatus,
) {
    val needsReview get() = status == ReadingStatus.UNSURE || status == ReadingStatus.MULTIPLE
}

/**
 * Decides which boxes were ticked on a flat, perspective-corrected sheet image.
 *
 * Works in three steps per box: nudge the expected position onto the printed border to absorb
 * curled paper; find the local paper brightness to absorb shadows; then measure how much of the
 * box interior is covered by ink. Printed text cannot affect the measurement, because the
 * interior of every box is blank on every copy of the form.
 */
class MarkReader(private val tuning: Tuning = Tuning()) {

    data class Tuning(
        /** Ink coverage from which a box counts as ticked. */
        val markedFill: Double = 0.045,
        /** Coverage below which a box is certainly empty; in between is flagged for a person. */
        val emptyFill: Double = 0.018,
        /** A faint mark next to a tick this many times stronger is ignored as a stray mark. */
        val strayRatio: Double = 5.0,
        /** A pixel is ink when darker than this fraction of the local paper brightness. */
        val inkLevel: Double = 0.72,
        val minInkContrast: Int = 25,
        /** Distance kept from the printed border when measuring a box, in mm. */
        val interiorInsetMm: Double = 0.85,
        /** How far a printed box may be displaced from its expected position, in mm. */
        val searchMm: Double = 1.8,
    )

    /** @param sheet flat image of the sheet at [pxPerMm] pixels per millimetre */
    fun read(sheet: GrayImage, layout: SheetLayout, pxPerMm: Double): List<QuestionReading> {
        val offsets = alignOffsets(sheet, layout, pxPerMm)
        var boxIndex = 0
        return layout.questions.mapIndexed { qi, question ->
            val fills = question.options.map { option ->
                val (dx, dy) = offsets[boxIndex++]
                measureFill(sheet, option.box, dx, dy, pxPerMm)
            }
            classify(fills, layout.spec.questions[qi].multiSelect)
        }
    }

    /** Applies the tick / empty / unsure rules to the fills of one question. */
    fun classify(fills: List<Double>, multiSelect: Boolean): QuestionReading {
        val marked = fills.indices.filter { fills[it] >= tuning.markedFill }
        val faint = fills.indices.filter { fills[it] >= tuning.emptyFill && fills[it] < tuning.markedFill }
        val strongest = fills.maxOrNull() ?: 0.0
        // A pen tick landing next to a much fainter speck: the speck can be safely ignored.
        val realFaint = faint.filter { fills[it] * tuning.strayRatio >= strongest || marked.isEmpty() }

        return when {
            multiSelect && realFaint.isEmpty() ->
                QuestionReading(fills, marked, if (marked.isEmpty()) ReadingStatus.BLANK else ReadingStatus.OK)

            multiSelect -> QuestionReading(fills, marked, ReadingStatus.UNSURE)

            marked.size >= 2 -> QuestionReading(fills, marked, ReadingStatus.MULTIPLE)

            marked.size == 1 && realFaint.isEmpty() ->
                QuestionReading(fills, marked, ReadingStatus.OK)

            marked.size == 1 -> QuestionReading(fills, marked, ReadingStatus.UNSURE)

            realFaint.isNotEmpty() -> {
                // Offer the strongest faint mark as a suggestion for the reviewer to confirm.
                val best = realFaint.maxByOrNull { fills[it] }!!
                QuestionReading(fills, listOf(best), ReadingStatus.UNSURE)
            }

            else -> QuestionReading(fills, emptyList(), ReadingStatus.BLANK)
        }
    }

    /**
     * Finds how far each printed box has moved from its ideal position. Boxes on the same row are
     * bent by the same amount, so the row median is used; a heavy tick could otherwise fool the
     * border search of a single box.
     */
    private fun alignOffsets(
        sheet: GrayImage,
        layout: SheetLayout,
        pxPerMm: Double,
    ): List<Pair<Int, Int>> {
        val boxes = layout.allBoxes
        val raw = boxes.map { bestBorderOffset(sheet, it, pxPerMm) }
        val rows = boxes.indices.groupBy { boxes[it].top }
        val result = MutableList(boxes.size) { 0 to 0 }
        rows.values.forEach { indexes ->
            val dx = indexes.map { raw[it].first }.sorted()[indexes.size / 2]
            val dy = indexes.map { raw[it].second }.sorted()[indexes.size / 2]
            indexes.forEach { result[it] = dx to dy }
        }
        return result
    }

    private fun bestBorderOffset(sheet: GrayImage, box: RectMm, pxPerMm: Double): Pair<Int, Int> {
        val reach = (tuning.searchMm * pxPerMm).toInt()
        var best = 0 to 0
        var bestScore = -1L
        // Coarse scan, then a fine scan around the winner.
        for (dy in -reach..reach step 2) for (dx in -reach..reach step 2) {
            val score = borderDarkness(sheet, box, dx, dy, pxPerMm)
            if (score > bestScore) {
                bestScore = score
                best = dx to dy
            }
        }
        val (cx, cy) = best
        for (dy in cy - 1..cy + 1) for (dx in cx - 1..cx + 1) {
            val score = borderDarkness(sheet, box, dx, dy, pxPerMm)
            if (score > bestScore) {
                bestScore = score
                best = dx to dy
            }
        }
        return best
    }

    private fun borderDarkness(sheet: GrayImage, box: RectMm, dx: Int, dy: Int, pxPerMm: Double): Long {
        val l = (box.left * pxPerMm).toInt() + dx
        val r = (box.right * pxPerMm).toInt() + dx
        val t = (box.top * pxPerMm).toInt() + dy
        val b = (box.bottom * pxPerMm).toInt() + dy
        if (l < 0 || t < 0 || r >= sheet.width || b >= sheet.height) return -1
        var sum = 0L
        for (x in l..r) sum += (255 - sheet[x, t]) + (255 - sheet[x, b])
        for (y in t + 1 until b) sum += (255 - sheet[l, y]) + (255 - sheet[r, y])
        return sum
    }

    private fun measureFill(sheet: GrayImage, box: RectMm, dx: Int, dy: Int, pxPerMm: Double): Double {
        val inner = box.inset(tuning.interiorInsetMm)
        val left = (inner.left * pxPerMm).toInt() + dx
        val right = (inner.right * pxPerMm).toInt() + dx
        val top = (inner.top * pxPerMm).toInt() + dy
        val bottom = (inner.bottom * pxPerMm).toInt() + dy

        val paper = paperBrightness(sheet, box, dx, dy, pxPerMm)
        val ink = inkMap(sheet, left, top, right, bottom, paper)
        return countSolidInk(ink, right - left, bottom - top).toDouble() / ((right - left) * (bottom - top))
    }

    /** Local paper level: a high percentile, so ink and printed lines nearby do not lower it. */
    private fun paperBrightness(sheet: GrayImage, box: RectMm, dx: Int, dy: Int, pxPerMm: Double): Int {
        val pad = (1.5 * pxPerMm).toInt()
        val l = ((box.left * pxPerMm).toInt() + dx - pad).coerceAtLeast(0)
        val r = ((box.right * pxPerMm).toInt() + dx + pad).coerceAtMost(sheet.width)
        val t = ((box.top * pxPerMm).toInt() + dy - pad).coerceAtLeast(0)
        val b = ((box.bottom * pxPerMm).toInt() + dy + pad).coerceAtMost(sheet.height)
        val histogram = IntArray(256)
        for (y in t until b) for (x in l until r) histogram[sheet[x, y]]++
        val target = ((r - l) * (b - t)) * 0.8
        var seen = 0
        for (v in 0..255) {
            seen += histogram[v]
            if (seen >= target) return v
        }
        return 255
    }

    private fun inkMap(sheet: GrayImage, l: Int, t: Int, r: Int, b: Int, paper: Int): BooleanArray {
        val w = r - l
        val out = BooleanArray(w * (b - t))
        val level = paper * tuning.inkLevel
        for (y in t until b) for (x in l until r) {
            val v = sheet[x.coerceIn(0, sheet.width - 1), y.coerceIn(0, sheet.height - 1)]
            out[(y - t) * w + (x - l)] = v < level && paper - v >= tuning.minInkContrast
        }
        return out
    }

    /** Counts ink pixels that have ink next to them, which removes photocopier speckle. */
    private fun countSolidInk(ink: BooleanArray, w: Int, h: Int): Int {
        var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!ink[y * w + x]) continue
            var neighbours = 0
            if (x > 0 && ink[y * w + x - 1]) neighbours++
            if (x < w - 1 && ink[y * w + x + 1]) neighbours++
            if (y > 0 && ink[(y - 1) * w + x]) neighbours++
            if (y < h - 1 && ink[(y + 1) * w + x]) neighbours++
            if (neighbours >= 2) count++
        }
        return count
    }

    /** Ink coverage of a free-writing area, used to tell whether a comment was written at all. */
    fun inkCoverage(sheet: GrayImage, area: RectMm, pxPerMm: Double): Double {
        val inner = area.inset(1.5)
        val left = (inner.left * pxPerMm).toInt()
        val right = (inner.right * pxPerMm).toInt()
        val top = (inner.top * pxPerMm).toInt()
        val bottom = (inner.bottom * pxPerMm).toInt()
        val paper = paperBrightness(sheet, inner, 0, 0, pxPerMm)
        val ink = inkMap(sheet, left, top, right, bottom, paper)
        return countSolidInk(ink, right - left, bottom - top).toDouble() / ((right - left) * (bottom - top))
    }
}
