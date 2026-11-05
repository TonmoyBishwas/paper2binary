package com.paper2binary.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paper2binary.app.scan.AnswerCorrections
import com.paper2binary.app.scan.SheetImageWithMarks
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.Survey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** All saved sheets for a survey, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsScreen(surveyId: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val repository = LocalContext.current.repository
    val survey by repository.observeSurvey(surveyId).collectAsState(initial = null)
    val responses by repository.observeResponses(surveyId).collectAsState(initial = emptyList())
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scanned sheets") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        val s = survey ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (responses.isEmpty()) {
                item { Text("No sheets scanned yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(responses, key = { _, r -> r.id }) { index, response ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(response.id) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Sheet ${responses.size - index}  ·  ${time.format(Date(response.scannedAt))}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            summary(s, repository.decodeAnswers(response)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private fun summary(survey: Survey, answers: List<List<Int>>): String =
    survey.questions.indices.joinToString("   ") { q ->
        val answer = answers.getOrElse(q) { emptyList() }
        val text = if (answer.isEmpty()) "-" else answer.joinToString("+") { Survey.optionLetter(it) }
        "Q${q + 1} $text"
    }

/** Checks or corrects one saved sheet against its stored photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDetailScreen(surveyId: String, responseId: String, onBack: () -> Unit) {
    val repository = LocalContext.current.repository
    val scope = rememberCoroutineScope()
    val survey by repository.observeSurvey(surveyId).collectAsState(initial = null)
    val answers = remember { mutableStateListOf<List<Int>>() }
    var image by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(responseId) {
        repository.getResponse(responseId)?.let { r ->
            answers.clear()
            answers.addAll(repository.decodeAnswers(r))
            image = r.sheetImage
        }
    }
    val bitmap by produceState<Bitmap?>(null, image) {
        val name = image ?: return@produceState
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(repository.imageFile(surveyId, name).path) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sheet") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete this sheet")
                    }
                },
            )
        },
    ) { padding ->
        val s = survey ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (image != null) {
                SheetImageWithMarks(bitmap, SheetLayout.compute(s.toSpec()), answers.toList())
            } else {
                Text(
                    "Imported from another phone, so no photo is available here.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            AnswerCorrections(
                survey = s,
                answers = answers.toList(),
                statuses = null,
                onChange = { q, value -> if (q < answers.size) answers[q] = value },
            )
            Button(
                onClick = {
                    scope.launch {
                        repository.updateAnswers(responseId, answers.toList())
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save changes") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this sheet?") },
            text = { Text("Its answers will no longer count in the results.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.deleteResponse(responseId)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
