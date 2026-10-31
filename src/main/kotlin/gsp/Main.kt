package gsp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

object Version {
    const val NAME = "0.1"
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Gsheet_Plus ${Version.NAME}",
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
    ) {
        MaterialTheme { App() }
    }
}
