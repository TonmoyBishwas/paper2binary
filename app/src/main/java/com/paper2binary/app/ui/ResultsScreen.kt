package com.paper2binary.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paper2binary.core.analysis.CrossTab
import com.paper2binary.core.analysis.QuestionStats
import com.paper2binary.core.analysis.ResponseRecord
import com.paper2binary.core.analysis.SurveyStats
import com.paper2binary.core.model.Survey
import kotlin.math.roundToInt

/** Charts of the answers, plus a cross-tab for spotting links between two questions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(surveyId: String, onBack: () -> Unit) {
    val repository = LocalContext.current.repository
    val survey by repository.observeSurvey(surveyId).collectAsState(initial = null)
    // Re-read whenever responses change, so results stay right after corrections.
    val changes by repository.observeResponses(surveyId).collectAsState(initial = emptyList())
    val records by produceState(emptyList<ResponseRecord>(), changes) {
        value = repository.records(surveyId)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Results") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        val s = survey ?: return@Scaffold
        val stats = remember(s, records) { SurveyStats.compute(s, records) }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("${records.size} sheets", style = MaterialTheme.typography.headlineSmall)
                if (records.isEmpty()) {
                    Text(
                        "Nothing to show yet. Results appear here as sheets are scanned.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (records.isNotEmpty()) {
                itemsIndexed(stats) { index, question -> QuestionChart(index, question) }
                if (s.questions.size >= 2) {
                    item { CrossTabCard(s, records) }
                }
            }
        }
    }
}

/**
 * Horizontal bars in one colour. Each row has its count and percentage in text, so no legend or
 * axis is needed, and wording of any length stays readable above its bar.
 */
@Composable
private fun QuestionChart(index: Int, stats: QuestionStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${index + 1}. ${stats.question.text}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val largest = stats.options.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
            stats.options.forEach { option ->
                val percent = (option.share * 100).roundToInt()
                Column(
                    Modifier.semantics {
                        contentDescription = "${option.label}: ${option.count} answers, $percent percent"
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${option.count}  ·  $percent%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Bar(option.count.toFloat() / largest)
                }
            }
            Text(
                "${stats.answered} answered, ${stats.blank} left blank. Percentages are of those who answered." +
                    if (stats.question.multiSelect) " Several ticks allowed, so they can add up to over 100%." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    val color = ChartColors.current
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .padding(top = 2.dp),
    ) {
        drawRect(track, size = Size(size.width, size.height))
        if (fraction > 0f) {
            // Square at the baseline, rounded only at the data end.
            val width = (size.width * fraction).coerceAtLeast(3.dp.toPx())
            val radius = 3.dp.toPx()
            drawRoundRect(color, size = Size(width, size.height), cornerRadius = CornerRadius(radius, radius))
            drawRect(color, size = Size((width - radius).coerceAtLeast(0f), size.height), topLeft = Offset.Zero)
        }
    }
}

@Composable
private fun CrossTabCard(survey: Survey, records: List<ResponseRecord>) {
    var rowQuestion by remember { mutableIntStateOf(0) }
    var columnQuestion by remember { mutableIntStateOf(1) }
    val table = remember(survey, records, rowQuestion, columnQuestion) {
        SurveyStats.crossTab(survey, records, rowQuestion, columnQuestion)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Compare two questions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Do people who answered one way also tend to answer another way the same? " +
                    "Darker cells hold more sheets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionPicker("Rows", survey, rowQuestion, Modifier.weight(1f)) { rowQuestion = it }
                QuestionPicker("Columns", survey, columnQuestion, Modifier.weight(1f)) { columnQuestion = it }
            }
            if (rowQuestion == columnQuestion) {
                Text("Choose two different questions.", style = MaterialTheme.typography.bodySmall)
            } else {
                CrossTabTable(table)
            }
        }
    }
}

@Composable
private fun QuestionPicker(
    label: String,
    survey: Survey,
    selected: Int,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("$label: Q${selected + 1}", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            survey.questions.forEachIndexed { i, q ->
                DropdownMenuItem(
                    text = { Text("Q${i + 1}. ${q.text}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

@Composable
private fun CrossTabTable(table: CrossTab) {
    val largest = table.counts.maxOfOrNull { row -> row.maxOrNull() ?: 0 }?.coerceAtLeast(1) ?: 1
    val heat = ChartColors.current
    val onHeat = androidx.compose.ui.graphics.Color.White
    val ink = MaterialTheme.colorScheme.onSurface
    val labelWidth = 104.dp
    val cellWidth = 72.dp
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Row {
                Box(Modifier.width(labelWidth))
                table.columnLabels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(cellWidth)
                            .padding(2.dp),
                    )
                }
                HeaderTotal(cellWidth)
            }
            table.counts.forEachIndexed { r, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        table.rowLabels[r],
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(labelWidth)
                            .padding(end = 4.dp),
                    )
                    row.forEach { count ->
                        val strength = count.toFloat() / largest
                        // Sequential single-hue shading; the number keeps it readable regardless.
                        Box(
                            Modifier
                                .width(cellWidth)
                                .height(36.dp)
                                .padding(1.dp)
                                .background(heat.copy(alpha = 0.06f + 0.84f * strength))
                                .border(0.5.dp, ink.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                count.toString(),
                                color = if (strength > 0.55f) onHeat else ink,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    TotalCell(table.rowTotals[r].toString(), cellWidth)
                }
            }
            Row {
                Text(
                    "Total",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .width(labelWidth)
                        .padding(top = 6.dp),
                )
                table.columnTotals.forEach { TotalCell(it.toString(), cellWidth) }
                TotalCell(table.total.toString(), cellWidth)
            }
        }
    }
}

@Composable
private fun HeaderTotal(width: androidx.compose.ui.unit.Dp) {
    Text(
        "Total",
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(width)
            .padding(2.dp),
    )
}

@Composable
private fun TotalCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(width)
            .padding(top = 6.dp),
    )
}
