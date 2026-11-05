package com.paper2binary.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.paper2binary.app.scan.ScannerScreen
import com.paper2binary.app.ui.CommentsScreen
import com.paper2binary.app.ui.EditorScreen
import com.paper2binary.app.ui.HomeScreen
import com.paper2binary.app.ui.P2BTheme
import com.paper2binary.app.ui.PrintScreen
import com.paper2binary.app.ui.ResultsScreen
import com.paper2binary.app.ui.SheetDetailScreen
import com.paper2binary.app.ui.SheetsScreen
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
                onScanAny = { nav.navigate("scan?survey=") },
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
        composable(
            "scan?survey={survey}",
            listOf(navArgument("survey") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ScannerScreen(
                surveyId = entry.arguments?.getString("survey")?.takeIf { it.isNotEmpty() },
                onBack = { nav.popBackStack() },
            )
        }
        composable("results/{id}") { entry ->
            ResultsScreen(surveyId = entry.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
        }
        composable("comments/{id}") { entry ->
            CommentsScreen(surveyId = entry.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
        }
        composable("sheets/{id}") { entry ->
            val id = entry.arguments!!.getString("id")!!
            SheetsScreen(
                surveyId = id,
                onOpen = { nav.navigate("sheet/$id/$it") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("sheet/{id}/{response}") { entry ->
            SheetDetailScreen(
                surveyId = entry.arguments!!.getString("id")!!,
                responseId = entry.arguments!!.getString("response")!!,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
