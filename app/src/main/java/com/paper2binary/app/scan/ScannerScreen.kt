package com.paper2binary.app.scan

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paper2binary.app.ui.StatusColors
import com.paper2binary.app.util.ImageConversions.toBitmap
import com.paper2binary.core.pipeline.ScanGate
import com.paper2binary.core.vision.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.max

@Composable
fun ScannerScreen(surveyId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    val application = context.applicationContext as Application
    val model: ScannerViewModel = viewModel(key = "scanner-$surveyId") {
        ScannerViewModel(application, surveyId)
    }
    val state by model.state.collectAsStateWithLifecycle()

    val pending = state.queue.firstOrNull()
    if (pending != null) {
        BackHandler { model.discardPending() }
        ReviewContent(pending, remaining = state.queue.size - 1, model)
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            CameraPreview(model)
            SheetOutline(state)
        } else {
            Text(
                "The camera is needed to scan sheets. You can also pick existing photos below.",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }
        ScannerControls(state, model, onBack, hasPermission)
    }
}

/** Binds preview, analysis and capture to the screen's lifecycle. */
@Composable
private fun CameraPreview(model: ScannerViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO),
            )
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio).build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ratio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val capture = ImageCapture.Builder()
            .setResolutionSelector(ratio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy: ImageProxy ->
            try {
                model.onPreviewFrame(FrameConverter.convert(proxy), System.currentTimeMillis())
            } finally {
                proxy.close()
            }
        }
        model.captureRequest = {
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = model.onCaptured(image)
                    override fun onError(exception: ImageCaptureException) = model.onCaptureFailed()
                },
            )
        }
        model.onSheetSaved = {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            preview.surfaceProvider = previewView.surfaceProvider
            provider.unbindAll()
            val camera: Camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                capture,
            )
            torchControl.value = camera
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            analysis.clearAnalyzer()
            model.captureRequest = null
            model.onSheetSaved = null
            torchControl.value = null
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
            tone?.release()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** The bound camera, shared with the torch button in the controls. */
private val torchControl = mutableStateOf<Camera?>(null)

/** Draws the detected sheet over the preview, coloured by what the scanner is doing. */
@Composable
private fun SheetOutline(state: ScannerState) {
    val corners = state.corners ?: return
    if (state.frameWidth == 0) return
    val color = when (state.phase) {
        ScanGate.Phase.CAPTURE, ScanGate.Phase.BUSY, ScanGate.Phase.REMOVE_SHEET -> StatusColors.Good
        ScanGate.Phase.TOO_FAR -> StatusColors.Problem
        else -> StatusColors.Attention
    }
    Canvas(Modifier.fillMaxSize()) {
        // Same scaling rule as the preview's FILL_CENTER, so the outline sits on the paper.
        val scale = max(size.width / state.frameWidth, size.height / state.frameHeight)
        val dx = (size.width - state.frameWidth * scale) / 2
        val dy = (size.height - state.frameHeight * scale) / 2
        fun map(p: Point) = Offset(p.x.toFloat() * scale + dx, p.y.toFloat() * scale + dy)
        val path = Path().apply {
            val first = map(corners[0])
            moveTo(first.x, first.y)
            corners.drop(1).forEach { p ->
                val o = map(p)
                lineTo(o.x, o.y)
            }
            close()
        }
        drawPath(path, color, style = Stroke(width = 4.dp.toPx()))
    }
}

private fun instructionFor(phase: ScanGate.Phase): String = when (phase) {
    ScanGate.Phase.SEARCHING -> "Point at a sheet so all four corner squares are visible"
    ScanGate.Phase.TOO_FAR -> "Move closer"
    ScanGate.Phase.HOLD_STILL -> "Hold still..."
    ScanGate.Phase.CAPTURE, ScanGate.Phase.BUSY -> "Reading..."
    ScanGate.Phase.REMOVE_SHEET -> "Done. Put down the next sheet"
}

@Composable
private fun ScannerControls(state: ScannerState, model: ScannerViewModel, onBack: () -> Unit, cameraOn: Boolean) {
    var torchOn by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        model.importPhotos(uris)
    }
    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(if (notice.kind == ScanNotice.Kind.SAVED) 2800 else 4500)
            model.dismissNotice()
        }
    }

    val iconColors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, colors = iconColors) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.survey?.title ?: "Any survey",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    "${state.sessionCount} saved this session",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                colors = iconColors,
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Read sheets from existing photos")
            }
            IconButton(
                enabled = cameraOn,
                onClick = {
                    torchOn = !torchOn
                    torchControl.value?.cameraControl?.enableTorch(torchOn)
                },
                colors = iconColors,
            ) {
                Icon(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (torchOn) "Turn light off" else "Turn light on",
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.photosRemaining > 0) {
                Surface(shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Reading photos, ${state.photosRemaining} left")
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (notice != null) {
                val background = if (notice.kind == ScanNotice.Kind.SAVED) StatusColors.Good else StatusColors.Attention
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(background, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(notice.text, color = Color.White, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    notice.undoResponseId?.let { id ->
                        TextButton(onClick = { model.undo(id) }) { Text("Undo", color = Color.White) }
                    }
                }
            }
            Text(instructionFor(state.phase), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Save clear sheets automatically",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.autoSave, onCheckedChange = model::setAutoSave)
            }
        }
    }
}

/** Lets a person check one read sheet, compared with the original on the paper. */
@Composable
private fun ReviewContent(pending: PendingSheet, remaining: Int, model: ScannerViewModel) {
    val scan = pending.scan
    val answers = remember(pending) { mutableStateListOf(*scan.readings.map { it.selected }.toTypedArray()) }
    val bitmap by produceState<Bitmap?>(null, pending) {
        value = withContext(Dispatchers.Default) { scan.sheet.toBitmap() }
    }
    val duplicate = pending.duplicate

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .fillMaxSize(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (remaining > 0) "Check this sheet ($remaining more after this)" else "Check this sheet",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (duplicate != null) {
                    Banner(
                        "Possible duplicate. The handwriting matches a sheet saved at " +
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(duplicate.scannedAt)) +
                            ". Discard unless it really is a different paper.",
                        StatusColors.Problem,
                    )
                }
                if (scan.isBlank) {
                    Banner("This sheet looks empty: no ticks and no comment.", StatusColors.Attention)
                }
                // Questions needing a decision come first, so no scrolling is needed in the
                // usual case; the sheet and the rest of the answers follow for comparison.
                val flagged = scan.readings.indices.filter { scan.readings[it].needsReview }
                flagged.forEach { q ->
                    QuestionCorrector(
                        index = q,
                        question = pending.survey.questions[q],
                        selected = answers[q],
                        status = scan.readings[q].status,
                        onChange = { answers[q] = it },
                    )
                }
                SheetImageWithMarks(bitmap, scan.layout, answers.toList())
                scan.readings.indices.filter { it !in flagged }.forEach { q ->
                    QuestionCorrector(
                        index = q,
                        question = pending.survey.questions[q],
                        selected = answers[q],
                        status = scan.readings[q].status,
                        onChange = { answers[q] = it },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = model::discardPending, modifier = Modifier.weight(1f)) { Text("Discard") }
                Button(
                    onClick = { model.confirmPending(answers.toList()) },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}
