package com.paper2binary.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paper2binary.app.pdf.PrintLayout
import com.paper2binary.app.pdf.SheetPdfRenderer
import com.paper2binary.app.util.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows the master sheet and sends it to a printer, another app or a file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(surveyId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = context.repository
    val survey by repository.observeSurvey(surveyId).collectAsState(initial = null)
    var mode by remember { mutableStateOf(PrintLayout.TWO_ON_A4) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(survey, mode) {
        val s = survey ?: return@LaunchedEffect
        val suffix = if (mode == PrintLayout.TWO_ON_A4) "A4" else "A5"
        val file = repository.exportFile("${s.id}_sheet_$suffix.pdf")
        preview = withContext(Dispatchers.Default) {
            SheetPdfRenderer.render(s, mode, file)
            renderPreview(file)
        }
        pdf = file
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val source = pdf
        if (uri != null && source != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                }
                Toast.makeText(context, "PDF saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Print sheet") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == PrintLayout.TWO_ON_A4,
                    onClick = { mode = PrintLayout.TWO_ON_A4 },
                    label = { Text("2 sheets on A4") },
                )
                FilterChip(
                    selected = mode == PrintLayout.SINGLE_A5,
                    onClick = { mode = PrintLayout.SINGLE_A5 },
                    label = { Text("Single A5") },
                )
            }

            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Preview of the printable sheet",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                )
            }

            Text(
                "Print this one page, then print or photocopy it as many times as you need. " +
                    "Every copy is identical. Use black and white, and do not cut off the four corner squares.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                enabled = pdf != null,
                onClick = {
                    val media = if (mode == PrintLayout.TWO_ON_A4) {
                        PrintAttributes.MediaSize.ISO_A4.asLandscape()
                    } else {
                        PrintAttributes.MediaSize.ISO_A5
                    }
                    Sharing.printPdf(context, pdf!!, "Survey sheet", media)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Print, contentDescription = null)
                Text("  Print")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = pdf != null,
                    onClick = { Sharing.shareFile(context, pdf!!, "application/pdf", "Send the sheet") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("  Share")
                }
                OutlinedButton(
                    enabled = pdf != null,
                    onClick = { saver.launch(pdf!!.name) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text("  Save PDF")
                }
            }
        }
    }
}

private fun renderPreview(file: File): Bitmap {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val scale = 1400f / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt(),
                    (page.height * scale).toInt(),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}
