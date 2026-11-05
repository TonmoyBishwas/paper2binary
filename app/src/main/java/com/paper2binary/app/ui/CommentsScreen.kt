package com.paper2binary.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paper2binary.app.data.ResponseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The handwritten comments, each photo next to its text. Automatic recognition only provides a
 * draft, so this is where a person turns the comments into text that can be trusted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(surveyId: String, onBack: () -> Unit) {
    val repository = LocalContext.current.repository
    val all by repository.observeResponses(surveyId).collectAsState(initial = emptyList())
    val withComments = all.filter { it.commentImage != null || !it.commentText.isNullOrBlank() }
    var onlyPending by remember { mutableStateOf(true) }
    val shown = if (onlyPending) withComments.filter { !it.commentConfirmed } else withComments
    val doneCount = withComments.count { it.commentConfirmed }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("Comments") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("$doneCount of " + counted(withComments.size, "comment") + " typed up", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = onlyPending, onClick = { onlyPending = true }, label = { Text("To do") })
                    FilterChip(selected = !onlyPending, onClick = { onlyPending = false }, label = { Text("All") })
                }
                if (shown.isEmpty()) {
                    Text(
                        if (withComments.isEmpty()) "No sheets with comments yet." else "All comments are done.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(shown, key = { it.id }) { response ->
                CommentCard(
                    response = response,
                    image = response.commentImage?.let { repository.imageFile(surveyId, it) },
                )
            }
        }
    }
}

@Composable
private fun CommentCard(response: ResponseEntity, image: File?) {
    val repository = LocalContext.current.repository
    val scope = rememberCoroutineScope()
    var text by remember(response.id, response.commentText) { mutableStateOf(response.commentText.orEmpty()) }
    val bitmap by produceState<Bitmap?>(null, image) {
        value = image?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path) } }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Handwritten comment",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White),
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (response.commentConfirmed) "Comment" else "Type or correct the comment") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (response.commentConfirmed) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusColors.Good)
                    Text(" Confirmed", style = MaterialTheme.typography.bodySmall)
                } else if (!response.commentText.isNullOrBlank()) {
                    Text(
                        "Automatic draft. Please check it against the photo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Attention,
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = text.isNotBlank() || response.commentText != null,
                    onClick = {
                        scope.launch { repository.updateComment(response.id, text.trim(), confirmed = true) }
                    },
                ) { Text("Confirm") }
            }
        }
    }
}
