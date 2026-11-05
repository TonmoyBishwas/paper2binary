package com.paper2binary.app.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.paper2binary.app.P2BApplication
import com.paper2binary.app.data.SurveyRepository

val Context.repository: SurveyRepository
    get() = (applicationContext as P2BApplication).repository

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** "1 sheet", "3 sheets": counts appear all over the app and one-sheet surveys are common early on. */
fun counted(count: Int, singular: String): String = if (count == 1) "1 $singular" else "$count ${singular}s"
