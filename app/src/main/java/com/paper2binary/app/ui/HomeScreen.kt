package com.paper2binary.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paper2binary.app.data.SurveySummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewSurvey: () -> Unit,
    onOpenSurvey: (String) -> Unit,
    onScanAny: () -> Unit,
) {
    val context = LocalContext.current
    val repository = context.repository
    val surveys by repository.observeSummaries().collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = try {
                val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val result = repository.importBundle(text)
                "Imported \"${result.survey.title}\" with ${result.addedResponses} new responses"
            } catch (_: Exception) {
                "That file is not a Paper2Binary survey"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paper2Binary") },
                actions = {
                    IconButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import a survey file")
                    }
                    IconButton(onClick = onScanAny) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan a sheet from any survey")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSurvey,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New survey") },
            )
        },
    ) { padding ->
        val list = surveys
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyHome(Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.survey.id }) { summary ->
                    SurveyCard(summary) { onOpenSurvey(summary.survey.id) }
                }
            }
        }
    }
}

@Composable
private fun SurveyCard(summary: SurveySummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(summary.survey.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${summary.survey.questions.size} questions  ·  ${summary.responseCount} sheets scanned",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.NoteAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("No surveys yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create a survey, print its sheet and photocopy it as many times as you need. " +
                    "Then scan the filled-in papers with this phone.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
