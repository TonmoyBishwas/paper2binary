package com.paper2binary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.Question
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.model.Survey
import kotlinx.coroutines.launch

/** Editable copy of a question. */
private class QuestionDraft(text: String, options: List<String>, multiSelect: Boolean) {
    var text by mutableStateOf(text)
    val options: SnapshotStateList<String> = mutableStateListOf<String>().apply { addAll(options) }
    var multiSelect by mutableStateOf(multiSelect)
}

private data class OptionPreset(val name: String, val options: List<String>)

private val PRESETS = listOf(
    OptionPreset("Yes / No", listOf("Yes", "No")),
    OptionPreset("Agree scale", listOf("Strongly agree", "Agree", "Neutral", "Disagree", "Strongly disagree")),
    OptionPreset("Rating", listOf("Excellent", "Good", "Average", "Poor")),
    OptionPreset("Numbers 1-5", listOf("1", "2", "3", "4", "5")),
)

/**
 * Creates or edits a survey. Once sheets have been scanned, only the wording may change: adding
 * or removing options would move the boxes, and papers already printed could no longer be read.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(surveyId: String?, onDone: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = context.repository
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(surveyId == null) }
    var title by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf(Survey.DEFAULT_INSTRUCTIONS) }
    var hasComment by remember { mutableStateOf(true) }
    var commentPrompt by remember { mutableStateOf(Survey.DEFAULT_COMMENT_PROMPT) }
    val questions = remember { mutableStateListOf<QuestionDraft>() }
    val responseCount by (surveyId?.let { repository.observeResponseCount(it) } ?: kotlinx.coroutines.flow.flowOf(0))
        .collectAsState(initial = 0)
    val structureLocked = responseCount > 0

    LaunchedEffect(surveyId) {
        if (surveyId != null) {
            repository.getSurvey(surveyId)?.let { s ->
                title = s.title
                instructions = s.instructions
                hasComment = s.hasComment
                commentPrompt = s.commentPrompt
                questions.clear()
                questions.addAll(s.questions.map { QuestionDraft(it.text, it.options, it.multiSelect) })
            }
            loaded = true
        } else if (questions.isEmpty()) {
            questions.add(QuestionDraft("", PRESETS[2].options, false))
        }
    }

    var confirmLeave by remember { mutableStateOf(false) }
    val hasContent = title.isNotBlank() || questions.any { it.text.isNotBlank() }
    val askBeforeLeaving = {
        if (hasContent) confirmLeave = true else onBack()
    }
    BackHandler(onBack = askBeforeLeaving)
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave without saving?") },
            text = { Text("Changes made here will be lost.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") }
            },
        )
    }

    fun buildSurvey(id: String) = Survey(
        id = id,
        title = title.trim(),
        instructions = instructions.trim(),
        questions = questions.map { d ->
            Question(d.text.trim(), d.options.map { it.trim() }, d.multiSelect)
        },
        hasComment = hasComment,
        commentPrompt = commentPrompt.trim(),
    )

    val fits = questions.isNotEmpty() && SheetLayout.compute(buildSurvey(surveyId ?: "AAAAAA").toSpec()).fits
    val problem = when {
        title.isBlank() -> "Give the survey a title"
        questions.isEmpty() -> "Add at least one question"
        questions.any { it.text.isBlank() } -> "Every question needs some text"
        questions.any { it.options.size < 2 } -> "Every question needs at least two options"
        questions.any { d -> d.options.any { it.isBlank() } } -> "Fill in or remove empty options"
        !fits -> "Too much for half an A4 page. Remove a question or some options"
        else -> null
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (surveyId == null) "New survey" else "Edit survey") },
                navigationIcon = { BackButton(askBeforeLeaving) },
                actions = {
                    TextButton(
                        enabled = problem == null,
                        onClick = {
                            scope.launch {
                                val id = surveyId ?: SheetSpec.newSurveyId()
                                repository.saveSurvey(buildSurvey(id))
                                onDone(id)
                            }
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Survey title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Instructions printed at the top") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (structureLocked) {
                item {
                    Text(
                        "$responseCount sheets are already scanned, so only the wording can be changed. " +
                            "Adding or removing options would no longer match the printed papers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Attention,
                    )
                }
            }
            itemsIndexed(questions) { index, draft ->
                QuestionEditor(
                    index = index,
                    draft = draft,
                    locked = structureLocked,
                    onRemove = { questions.removeAt(index) },
                )
            }
            item {
                if (!structureLocked) {
                    OutlinedButton(
                        onClick = { questions.add(QuestionDraft("", PRESETS[2].options, false)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add question")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Comment box at the bottom", modifier = Modifier.weight(1f))
                            Switch(
                                checked = hasComment,
                                enabled = !structureLocked,
                                onCheckedChange = { hasComment = it },
                            )
                        }
                        if (hasComment) {
                            OutlinedTextField(
                                value = commentPrompt,
                                onValueChange = { commentPrompt = it },
                                label = { Text("Comment prompt") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    problem ?: "This survey fits on half an A4 page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem == null) StatusColors.Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionEditor(index: Int, draft: QuestionDraft, locked: Boolean, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Question ${index + 1}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!locked) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove question ${index + 1}")
                    }
                }
            }
            OutlinedTextField(
                value = draft.text,
                onValueChange = { draft.text = it },
                label = { Text("Question text") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!locked) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRESETS.forEach { preset ->
                        AssistChip(
                            onClick = {
                                draft.options.clear()
                                draft.options.addAll(preset.options)
                            },
                            label = { Text(preset.name) },
                        )
                    }
                }
            }
            draft.options.forEachIndexed { optionIndex, option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = option,
                        onValueChange = { draft.options[optionIndex] = it },
                        label = { Text("Option ${optionIndex + 1}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (!locked && draft.options.size > 2) {
                        IconButton(onClick = { draft.options.removeAt(optionIndex) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove option ${optionIndex + 1}")
                        }
                    }
                }
            }
            if (!locked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (draft.options.size < 10) {
                        TextButton(onClick = { draft.options.add("") }) { Text("Add option") }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Allow several ticks", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = draft.multiSelect, onCheckedChange = { draft.multiSelect = it })
                }
            }
        }
    }
}
