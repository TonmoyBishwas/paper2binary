package com.paper2binary.core.layout

import com.paper2binary.core.model.QuestionSpec
import com.paper2binary.core.model.SheetSpec
import kotlin.math.ceil
import kotlin.math.min

/** Axis-aligned rectangle in millimetres, measured from the top-left corner of the sheet. */
data class RectMm(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun inset(by: Double) = RectMm(left + by, top + by, right - by, bottom - by)
}

data class PointMm(val x: Double, val y: Double)

/** A printed tick box and the space for its label. */
data class OptionCell(val box: RectMm, val label: RectMm)

data class QuestionCell(val text: RectMm, val options: List<OptionCell>)

/**
 * The exact position of everything printed on a half-A4 sheet.
 *
 * The layout is a pure function of [SheetSpec], which is what makes the QR code sufficient:
 * the printer (PDF renderer) and the reader (scanner) both call [compute] and are guaranteed
 * to agree on where each box is, without storing any coordinates.
 *
 * Do not change these numbers for an existing format tag. Any change must bump
 * [SheetSpec.TAG], otherwise already-printed sheets would be read at the wrong positions.
 */
class SheetLayout private constructor(
    val spec: SheetSpec,
    val questions: List<QuestionCell>,
    val comment: RectMm?,
    val commentLabel: RectMm?,
    /** False when the questions need more height than the sheet has. */
    val fits: Boolean,
) {
    /** Every box in reading order, for code that does not care about question boundaries. */
    val allBoxes: List<RectMm> get() = questions.flatMap { q -> q.options.map { it.box } }

    companion object {
        /** Half of an A4 sheet (i.e. A5). */
        const val SHEET_WIDTH = 148.0
        const val SHEET_HEIGHT = 210.0

        const val MARKER_SIZE = 8.0
        const val MARKER_RING = 1.5
        const val MARKER_DOT = 3.0

        /** Distance from the sheet edge to a marker centre; survives uneven cutting of A4. */
        private const val MARKER_INSET = 9.0

        /** Marker centres: top-left, top-right, bottom-right, bottom-left. */
        val MARKER_CENTERS = listOf(
            PointMm(MARKER_INSET, MARKER_INSET),
            PointMm(SHEET_WIDTH - MARKER_INSET, MARKER_INSET),
            PointMm(SHEET_WIDTH - MARKER_INSET, SHEET_HEIGHT - MARKER_INSET),
            PointMm(MARKER_INSET, SHEET_HEIGHT - MARKER_INSET),
        )

        /** QR code including its quiet zone, tucked in beside the top-right marker. */
        val QR_RECT = RectMm(112.0, 4.5, 134.0, 26.5)

        /** Title and instructions, between the top-left marker and the QR code. */
        val HEADER_RECT = RectMm(15.0, 4.5, 110.0, 26.5)

        const val BOX_SIZE = 5.5
        private const val CONTENT_LEFT = 8.0
        private const val CONTENT_RIGHT = 140.0
        private const val QUESTIONS_TOP = 28.0
        private const val BOTTOM_LIMIT = 195.0
        private const val COMMENT_HEIGHT = 27.0
        private const val COMMENT_LABEL_HEIGHT = 5.0

        private const val MIN_TEXT_HEIGHT = 8.0
        private const val MAX_TEXT_HEIGHT = 11.0
        private const val MIN_ROW_HEIGHT = 7.5
        private const val MAX_ROW_HEIGHT = 10.0
        private const val QUESTION_GAP = 1.5
        private const val LABEL_GAP = 1.5

        /** Options per printed row; longer lists wrap so labels keep a readable width. */
        private fun rowsFor(count: Int): Int = ceil(count / 5.0).toInt()

        fun compute(spec: SheetSpec): SheetLayout {
            val commentTop = BOTTOM_LIMIT - COMMENT_HEIGHT
            val comment = if (spec.hasComment) {
                RectMm(CONTENT_LEFT, commentTop, CONTENT_RIGHT, BOTTOM_LIMIT)
            } else null
            val commentLabel = if (spec.hasComment) {
                RectMm(CONTENT_LEFT, commentTop - COMMENT_LABEL_HEIGHT, CONTENT_RIGHT, commentTop)
            } else null

            val questionsBottom =
                if (spec.hasComment) commentTop - COMMENT_LABEL_HEIGHT - 1.0 else BOTTOM_LIMIT
            val available = questionsBottom - QUESTIONS_TOP

            val n = spec.questions.size
            val totalRows = spec.questions.sumOf { rowsFor(it.optionCount) }
            val gaps = QUESTION_GAP * (n - 1).coerceAtLeast(0)
            val minimum = n * MIN_TEXT_HEIGHT + totalRows * MIN_ROW_HEIGHT + gaps

            // Spare height is shared out evenly, up to a comfortable maximum.
            val slack = (available - minimum).coerceAtLeast(0.0)
            val units = n + totalRows
            val extra = if (units > 0) slack / units else 0.0
            val textHeight = min(MIN_TEXT_HEIGHT + extra, MAX_TEXT_HEIGHT)
            val rowHeight = min(MIN_ROW_HEIGHT + extra, MAX_ROW_HEIGHT)

            var y = QUESTIONS_TOP
            val cells = spec.questions.map { q ->
                val cell = layoutQuestion(q, y, textHeight, rowHeight)
                y += textHeight + rowsFor(q.optionCount) * rowHeight + QUESTION_GAP
                cell
            }
            return SheetLayout(spec, cells, comment, commentLabel, minimum <= available)
        }

        private fun layoutQuestion(
            q: QuestionSpec,
            top: Double,
            textHeight: Double,
            rowHeight: Double,
        ): QuestionCell {
            val text = RectMm(CONTENT_LEFT, top, CONTENT_RIGHT, top + textHeight)
            val rows = rowsFor(q.optionCount)
            val columns = ceil(q.optionCount.toDouble() / rows).toInt()
            val cellWidth = (CONTENT_RIGHT - CONTENT_LEFT) / columns
            val options = List(q.optionCount) { index ->
                val row = index / columns
                val column = index % columns
                val cellLeft = CONTENT_LEFT + column * cellWidth
                val rowTop = text.bottom + row * rowHeight
                val boxTop = rowTop + (rowHeight - BOX_SIZE) / 2
                OptionCell(
                    box = RectMm(cellLeft, boxTop, cellLeft + BOX_SIZE, boxTop + BOX_SIZE),
                    label = RectMm(
                        cellLeft + BOX_SIZE + LABEL_GAP,
                        rowTop,
                        cellLeft + cellWidth - LABEL_GAP,
                        rowTop + rowHeight,
                    ),
                )
            }
            return QuestionCell(text, options)
        }
    }
}
