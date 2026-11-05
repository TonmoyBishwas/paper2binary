package com.paper2binary.core.analysis

import com.paper2binary.core.model.Question
import com.paper2binary.core.model.Survey
import kotlinx.serialization.Serializable

/**
 * One saved sheet, independent of how it is stored. Answers hold the ticked option indexes per
 * question, and an empty list means the question was left blank.
 */
@Serializable
data class ResponseRecord(
    val id: String,
    val scannedAtMillis: Long,
    val answers: List<List<Int>>,
    val commentText: String? = null,
    val commentImageName: String? = null,
    /** True when a person checked or corrected this sheet rather than it passing automatically. */
    val reviewed: Boolean = false,
)

data class OptionCount(val label: String, val count: Int, /** Share of those who answered, 0..1. */ val share: Double)

data class QuestionStats(
    val question: Question,
    /** Sheets with at least one box ticked for this question. */
    val answered: Int,
    val blank: Int,
    val options: List<OptionCount>,
)

/** A table of how answers to one question break down by the answers to another. */
data class CrossTab(
    val rowLabels: List<String>,
    val columnLabels: List<String>,
    /** counts[row][column] */
    val counts: List<List<Int>>,
) {
    val rowTotals get() = counts.map { it.sum() }
    val columnTotals get() = columnLabels.indices.map { c -> counts.sumOf { it[c] } }
    val total get() = counts.sumOf { it.sum() }
}

object SurveyStats {

    fun compute(survey: Survey, responses: List<ResponseRecord>): List<QuestionStats> =
        survey.questions.mapIndexed { q, question ->
            val answers = responses.map { it.answers.getOrElse(q) { emptyList() } }
            val answered = answers.count { it.isNotEmpty() }
            val counts = IntArray(question.options.size)
            answers.forEach { a -> a.forEach { if (it in counts.indices) counts[it]++ } }
            QuestionStats(
                question = question,
                answered = answered,
                blank = responses.size - answered,
                options = question.options.mapIndexed { o, label ->
                    OptionCount(label, counts[o], if (answered > 0) counts[o].toDouble() / answered else 0.0)
                },
            )
        }

    /** Only sheets that answered both questions count. Multi-select answers count once per tick. */
    fun crossTab(survey: Survey, responses: List<ResponseRecord>, rowQuestion: Int, columnQuestion: Int): CrossTab {
        val rows = survey.questions[rowQuestion].options
        val columns = survey.questions[columnQuestion].options
        val counts = List(rows.size) { IntArray(columns.size) }
        responses.forEach { response ->
            val rowAnswers = response.answers.getOrElse(rowQuestion) { emptyList() }
            val columnAnswers = response.answers.getOrElse(columnQuestion) { emptyList() }
            rowAnswers.forEach { r ->
                columnAnswers.forEach { c ->
                    if (r in rows.indices && c in columns.indices) counts[r][c]++
                }
            }
        }
        return CrossTab(rows, columns, counts.map { it.toList() })
    }
}
