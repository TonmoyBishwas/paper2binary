package com.paper2binary.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.paper2binary.app.ui.EditorScreen
import com.paper2binary.app.ui.HomeScreen
import com.paper2binary.app.ui.P2BTheme
import com.paper2binary.app.ui.PrintScreen
import com.paper2binary.app.ui.SurveyScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            P2BTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNewSurvey = { nav.navigate("editor") },
                onOpenSurvey = { nav.navigate("survey/$it") },
                onScanAny = { nav.navigate("scan") },
            )
        }
        composable("editor") {
            EditorScreen(
                surveyId = null,
                onDone = { id ->
                    nav.navigate("survey/$id") { popUpTo("home") }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("editor/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            EditorScreen(
                surveyId = entry.arguments!!.getString("id"),
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable("survey/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments!!.getString("id")!!
            SurveyScreen(
                surveyId = id,
                onEdit = { nav.navigate("editor/$id") },
                onPrint = { nav.navigate("print/$id") },
                onScan = { nav.navigate("scan?survey=$id") },
                onResults = { nav.navigate("results/$id") },
                onComments = { nav.navigate("comments/$id") },
                onSheets = { nav.navigate("sheets/$id") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("print/{id}", listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            PrintScreen(surveyId = entry.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
        }
        // Filled in by the following features.
        composable(
            "scan?survey={survey}",
            listOf(navArgument("survey") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { Text("Scanner") }
        composable("results/{id}") { Text("Results") }
        composable("comments/{id}") { Text("Comments") }
        composable("sheets/{id}") { Text("Sheets") }
    }
}
