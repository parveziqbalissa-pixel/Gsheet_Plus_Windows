package gsp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.window.Tray
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Path

object Version {
    const val NAME = "0.24.0"
}

fun main(args: Array<String>) {
    val startFiles = args.filter { File(it).isFile }
    if (!SingleInstance.claim(startFiles)) return
    runApp(startFiles)
}

private fun runApp(startFiles: List<String>) = application {
    val appScope = rememberCoroutineScope()
    val controller = remember { Controller(appScope) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (startFiles.isNotEmpty()) controller.incoming.value = startFiles.map { Path.of(it) }
        SingleInstance.listen { files ->
            if (files.isNotEmpty()) controller.incoming.value = files.map { Path.of(it) }
            visible = true
        }
    }
    Tray(
        icon = painterResource("icon.png"),
        tooltip = "Gsheet_Plus",
        onAction = { visible = true },
        menu = {
            Item("Quick add row", onClick = { controller.quickAdd.value = true })
            Item("Add to Windows \"Send to\" menu", onClick = { controller.say(installSendToShortcut()); visible = true })
            Item("Open Gsheet_Plus", onClick = { visible = true })
            Item("Exit", onClick = ::exitApplication)
        },
    )
    if (controller.quickAdd.value) {
        Window(
            onCloseRequest = { controller.quickAdd.value = false },
            title = "Quick add - Gsheet_Plus",
            icon = painterResource("icon.png"),
            alwaysOnTop = true,
            state = rememberWindowState(width = 440.dp, height = 600.dp),
        ) {
            MaterialTheme(colorScheme = if (controller.dark.value) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (controller.locked.value) LockScreen(controller) else QuickAddContent(controller) { controller.quickAdd.value = false }
                }
            }
        }
    }
    Window(
        visible = visible,
        onCloseRequest = ::exitApplication,
        title = "Gsheet_Plus ${Version.NAME}",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        LaunchedEffect(Unit) {
            if (controller.restoredAtStart) controller.say("Backup restored")
            if (controller.dailyBackup.value) controller.backupNow(quiet = true)
        }
        MaterialTheme(colorScheme = if (controller.dark.value) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (controller.locked.value) LockScreen(controller) else App(controller)
                        if (!controller.locked.value) SendToDialog(controller)
                    }
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
