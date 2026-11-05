package com.paper2binary.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paper2binary.app.util.Sharing
import kotlinx.coroutines.launch

/** The hub for one survey: print, scan, then look at results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    surveyId: String,
    onEdit: () -> Unit,
    onPrint: () -> Unit,
    onScan: () -> Unit,
    onResults: () -> Unit,
    onComments: () -> Unit,
    onSheets: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = context.repository
    val survey by repository.observeSurvey(surveyId).collectAsState(initial = null)
    val count by repository.observeResponseCount(surveyId).collectAsState(initial = 0)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun shareBundle(includeResponses: Boolean) {
        scope.launch {
            val file = repository.exportBundle(surveyId, includeResponses)
            Sharing.shareFile(context, file, "application/json", "Send to another phone")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(survey?.title.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit survey")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Share or delete")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Send survey to another phone") },
                            onClick = { menuOpen = false; shareBundle(false) },
                        )
                        DropdownMenuItem(
                            text = { Text("Send survey with all responses") },
                            onClick = { menuOpen = false; shareBundle(true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete survey") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(counted(count, "sheet") + " scanned", style = MaterialTheme.typography.headlineSmall)
            if (survey?.placeholder == true) {
                Text(
                    "This survey came from a sheet's QR code, so its wording is unknown. " +
                        "Tap edit to enter it, or import the survey file from the phone that created it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Attention,
                )
            }
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DocumentScanner, contentDescription = null)
                Text("  Scan sheets")
            }
            ActionCard(Icons.Default.Print, "Print sheet", "Master copy to print or photocopy", onPrint)
            ActionCard(Icons.Default.BarChart, "Results", "Charts and cross-tabs", onResults)
            if (survey?.hasComment == true) {
                ActionCard(Icons.Default.TextSnippet, "Comments", "Read and type up handwritten comments", onComments)
            }
            ActionCard(Icons.Default.ListAlt, "Scanned sheets", "Check or correct individual sheets", onSheets)
            ActionCard(Icons.Default.TableChart, "Export to Excel", "CSV tables and comment images in a ZIP") {
                scope.launch {
                    try {
                        val file = repository.exportZip(surveyId)
                        Sharing.shareFile(context, file, "application/zip", "Export results")
                    } catch (e: Exception) {
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this survey?") },
            text = { Text("This also deletes the results of " + counted(count, "scanned sheet") + " from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.deleteSurvey(surveyId)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier
                    .padding(start = 14.dp)
                    .width(0.dp)
                    .weight(1f),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
