package com.paper2binary.core.analysis

import com.paper2binary.core.model.Survey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes results as CSV that opens correctly in Excel and Google Sheets, in any language. */
object CsvExporter {
    /** A byte-order mark is what makes Excel read the file as UTF-8 rather than mangling it. */
    private const val BOM = "﻿"
    private const val NEWLINE = "\r\n"

    /** One row per sheet, with the ticked option's wording in each question's column. */
    fun responses(survey: Survey, responses: List<ResponseRecord>, timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { this.timeZone = timeZone }
        val out = StringBuilder(BOM)

        val header = buildList {
            add("response_id")
            add("scanned_at")
            survey.questions.forEachIndexed { i, q -> add("Q${i + 1}: ${q.text}") }
            if (survey.hasComment) {
                add("comment")
                add("comment_image")
            }
            add("checked_by_person")
        }
        out.appendRow(header)

        responses.forEach { r ->
            val row = buildList {
                add(r.id)
                add(format.format(Date(r.scannedAtMillis)))
                survey.questions.forEachIndexed { q, question ->
                    val answer = r.answers.getOrElse(q) { emptyList() }
                    add(answer.sorted().joinToString("; ") { question.options.getOrElse(it) { "?" } })
                }
                if (survey.hasComment) {
                    add(r.commentText.orEmpty())
                    add(r.commentImageName.orEmpty())
                }
                add(if (r.reviewed) "yes" else "no")
            }
            out.appendRow(row)
        }
        return out.toString()
    }

    /** Counts and percentages per option, ready to be turned into charts in a spreadsheet. */
    fun summary(survey: Survey, responses: List<ResponseRecord>): String {
        val out = StringBuilder(BOM)
        out.appendRow(listOf("question", "option", "count", "percent_of_answered"))
        SurveyStats.compute(survey, responses).forEachIndexed { q, stats ->
            val label = "Q${q + 1}: ${stats.question.text}"
            stats.options.forEach { option ->
                val percent = String.format(Locale.ROOT, "%.1f", option.share * 100)
                out.appendRow(listOf(label, option.label, option.count.toString(), percent))
            }
            out.appendRow(listOf(label, "(left blank)", stats.blank.toString(), ""))
        }
        return out.toString()
    }

    private fun StringBuilder.appendRow(cells: List<String>) {
        append(cells.joinToString(",") { escape(it) })
        append(NEWLINE)
    }

    internal fun escape(raw: String): String {
        // Text beginning with these characters would be run as a formula by spreadsheet programs.
        val startsLikeFormula = raw.isNotEmpty() && raw[0] in "=+-@" && raw.toDoubleOrNull() == null
        val safe = if (startsLikeFormula) "'$raw" else raw
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
