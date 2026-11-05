package com.paper2binary.app.scan

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paper2binary.app.ui.StatusColors
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.Question
import com.paper2binary.core.model.Survey
import com.paper2binary.core.omr.ReadingStatus

/**
 * The sheet image with a coloured outline on every box the app considers ticked, so a person can
 * compare it with the paper at a glance. Pinch to zoom in on a questionable mark.
 */
@Composable
fun SheetImageWithMarks(
    bitmap: Bitmap?,
    layout: SheetLayout,
    answers: List<List<Int>>,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio((SheetLayout.SHEET_WIDTH / SheetLayout.SHEET_HEIGHT).toFloat())
            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                    pan = if (zoom == 1f) Offset.Zero else pan + gesturePan
                }
            }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Photo of the scanned sheet",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / SheetLayout.SHEET_WIDTH.toFloat()
            layout.questions.forEachIndexed { q, cell ->
                answers.getOrElse(q) { emptyList() }.forEach { option ->
                    val box = cell.options.getOrNull(option)?.box ?: return@forEach
                    drawRect(
                        color = StatusColors.Good,
                        topLeft = Offset((box.left * scale).toFloat() - 3f, (box.top * scale).toFloat() - 3f),
                        size = Size((box.width * scale).toFloat() + 6f, (box.height * scale).toFloat() + 6f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** Option chips for correcting one question. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionCorrector(
    index: Int,
    question: Question,
    selected: List<Int>,
    status: ReadingStatus?,
    onChange: (List<Int>) -> Unit,
) {
    val flagged = status == ReadingStatus.UNSURE || status == ReadingStatus.MULTIPLE
    val flagText = when (status) {
        ReadingStatus.UNSURE -> "Faint mark. Please check."
        ReadingStatus.MULTIPLE -> "Several boxes ticked. Choose the answer to keep."
        else -> null
    }
    Card(
        colors = if (flagged) {
            CardDefaults.cardColors(containerColor = StatusColors.Attention.copy(alpha = 0.16f))
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "${index + 1}. ${question.text}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (flagText != null) {
                Text(flagText, style = MaterialTheme.typography.bodySmall, color = StatusColors.Attention)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                question.options.forEachIndexed { option, label ->
                    val isOn = option in selected
                    FilterChip(
                        selected = isOn,
                        label = { Text(label) },
                        // A tick shape as well as colour, so the choice never relies on colour alone.
                        leadingIcon = if (isOn) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            val next = when {
                                // Keeping several boxes on is valid on a multi-select question,
                                // and also lets a person fix one of several on a single-choice one.
                                question.multiSelect -> if (isOn) selected - option else selected + option
                                isOn -> emptyList()
                                else -> listOf(option)
                            }
                            onChange(next.sorted())
                        },
                    )
                }
            }
        }
    }
}

/** Questions of a survey with their correction chips, sharing the review and edit screens. */
@Composable
fun AnswerCorrections(
    survey: Survey,
    answers: List<List<Int>>,
    statuses: List<ReadingStatus>?,
    onChange: (Int, List<Int>) -> Unit,
) {
    survey.questions.forEachIndexed { q, question ->
        QuestionCorrector(
            index = q,
            question = question,
            selected = answers.getOrElse(q) { emptyList() },
            status = statuses?.getOrNull(q),
            onChange = { onChange(q, it) },
        )
    }
}
