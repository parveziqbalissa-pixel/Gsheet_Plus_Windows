package gsp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

object Version {
    const val NAME = "0.3.2"
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Gsheet_Plus ${Version.NAME}",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        val scope = rememberCoroutineScope()
        val controller = remember { Controller(scope) }
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { App(controller) }
                    val toast = controller.toast.value
                    if (toast != null) {
                        LaunchedEffect(toast) {
                            delay(toast.seconds * 1000L)
                            if (controller.toast.value === toast) controller.toast.value = null
                        }
                        Surface(color = MaterialTheme.colorScheme.inverseSurface) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(toast.text, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.weight(1f))
                                if (toast.actionLabel != null && toast.action != null) {
                                    TextButton(onClick = { toast.action.invoke(); controller.toast.value = null }) { Text(toast.actionLabel) }
                                }
                                TextButton(onClick = { controller.toast.value = null }) { Text("OK") }
                            }
                        }
                    }
                }
            }
        }
    }
}
