package com.paper2binary.core

import com.paper2binary.core.analysis.CsvExporter
import com.paper2binary.core.analysis.ResponseRecord
import com.paper2binary.core.analysis.SurveyBundle
import com.paper2binary.core.analysis.SurveyStats
import com.paper2binary.core.model.Question
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.model.Survey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AnalysisTest {
    private val survey = Survey(
        id = "K7M2QX",
        title = "Canteen",
        questions = listOf(
            Question("Food quality?", listOf("Good", "Bad")),
            Question("Visit, how often?", listOf("Daily", "Weekly", "Never")),
        ),
    )
    private val responses = listOf(
        ResponseRecord("a", 0, listOf(listOf(0), listOf(0))),
        ResponseRecord("b", 0, listOf(listOf(0), listOf(1))),
        ResponseRecord("c", 0, listOf(listOf(1), listOf(1))),
        ResponseRecord("d", 0, listOf(emptyList(), listOf(2)), commentText = "too, \"salty\""),
    )

    @Test
    fun `percentages are of those who answered, with blanks counted apart`() {
        val stats = SurveyStats.compute(survey, responses)
        assertEquals(3, stats[0].answered)
        assertEquals(1, stats[0].blank)
        assertEquals(2, stats[0].options[0].count)
        assertEquals(2.0 / 3, stats[0].options[0].share, 1e-9)
        assertEquals(0, stats[1].blank)
    }

    @Test
    fun `no responses does not divide by zero`() {
        val stats = SurveyStats.compute(survey, emptyList())
        assertEquals(0.0, stats[0].options[0].share, 0.0)
    }

    @Test
    fun `cross tab only counts sheets answering both questions`() {
        val table = SurveyStats.crossTab(survey, responses, 0, 1)
        assertEquals(listOf(1, 1, 0), table.counts[0])
        assertEquals(listOf(0, 1, 0), table.counts[1])
        assertEquals(3, table.total)
        assertEquals(listOf(2, 1), table.rowTotals)
        assertEquals(listOf(1, 2, 0), table.columnTotals)
    }

    @Test
    fun `responses csv uses wording, escapes text and has a byte order mark`() {
        val csv = CsvExporter.responses(survey, responses, TimeZone.getTimeZone("UTC"))
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").trim().split("\r\n")
        assertEquals(5, lines.size)
        assertEquals("response_id,scanned_at,Q1: Food quality?,\"Q2: Visit, how often?\",comment,comment_image,checked_by_person", lines[0])
        assertEquals("a,1970-01-01 00:00:00,Good,Daily,,,no", lines[1])
        assertEquals("d,1970-01-01 00:00:00,,Never,\"too, \"\"salty\"\"\",,no", lines[4])
    }

    @Test
    fun `formula-like text is neutralised but negative numbers are not`() {
        assertEquals("'=SUM(A1)", CsvExporter.escape("=SUM(A1)"))
        assertEquals("-5", CsvExporter.escape("-5"))
    }

    @Test
    fun `summary lists every option and the blanks`() {
        val summary = CsvExporter.summary(survey, responses)
        assertTrue(summary.contains("Q1: Food quality?,Good,2,66.7"))
        assertTrue(summary.contains("Q1: Food quality?,(left blank),1,"))
    }

    @Test
    fun `bundle round trips including non-latin text`() {
        val bengali = survey.copy(title = "ক্যান্টিন")
        val bundle = SurveyBundle(survey = bengali, responses = responses)
        assertEquals(bundle, SurveyBundle.fromJson(bundle.toJson()))
    }

    @Test
    fun `placeholder survey can be built from just the QR code`() {
        val spec = SheetSpec.decode("P2B1:K7M2QX:3.2M:C")!!
        val survey = Survey.placeholderFrom(spec)
        assertEquals(listOf("A", "B", "C"), survey.questions[0].options)
        assertTrue(survey.questions[1].multiSelect)
        assertTrue(survey.placeholder)
        assertEquals(spec, survey.toSpec())
    }
}
