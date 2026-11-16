package gsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Only one copy of the app may run (two copies would overwrite each other's data). A second copy - started by the
 * Windows "Send to" menu, for example - hands its files to the first one over a local socket and exits.
 */
object SingleInstance {
    private const val PORT = 47653
    private var server: ServerSocket? = null

    /** True when this is the only copy and should start; false when another copy is running and was given [files]. */
    fun claim(files: List<String>): Boolean {
        val loopback = InetAddress.getLoopbackAddress()
        try {
            server = ServerSocket(PORT, 8, loopback)
            return true
        } catch (e: IOException) {
            return try {
                Socket(loopback, PORT).use { socket ->
                    val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    files.forEach { out.write(it); out.write("\n") }
                    out.flush()
                }
                false
            } catch (e2: IOException) {
                true
            }
        }
    }

    /** Calls [onFiles] (with a possibly empty list) each time another copy was started. */
    fun listen(onFiles: (List<String>) -> Unit) {
        val socket = server ?: return
        thread(isDaemon = true, name = "single-instance") {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use {
                        val lines = it.getInputStream().bufferedReader(Charsets.UTF_8).readLines().filter { l -> l.isNotBlank() }
                        onFiles(lines)
                    }
                }
            }
        }
    }
}

/** Puts a "Gsheet_Plus" shortcut into the Windows "Send to" menu. Returns a message for the user. */
fun installSendToShortcut(): String {
    val exe = ProcessHandle.current().info().command().orElse(null) ?: return "Could not find the program file."
    if (!exe.endsWith(".exe", ignoreCase = true) || exe.endsWith("java.exe", true) || exe.endsWith("javaw.exe", true)) {
        return "Run the installed Gsheet_Plus program (not from a build tool) to add it to the Send to menu."
    }
    val appData = System.getenv("APPDATA") ?: return "Could not find the Send to folder."
    val link = File(appData, "Microsoft\\Windows\\SendTo\\Gsheet_Plus.lnk")
    val script = "\$s=(New-Object -ComObject WScript.Shell).CreateShortcut('${link.absolutePath.replace("'", "''")}');" +
        "\$s.TargetPath='${exe.replace("'", "''")}';\$s.WorkingDirectory='${File(exe).parent.replace("'", "''")}';\$s.Save()"
    return runCatching {
        val p = ProcessBuilder("powershell", "-NoProfile", "-Command", script).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        if (p.waitFor() == 0 && link.exists()) "Added to the Send to menu: right-click a file, Send to, Gsheet_Plus."
        else "Could not create the Send to shortcut."
    }.getOrElse { "Could not create the Send to shortcut." }
}

/** Asks where files sent from Explorer ("Send to") should go, then attaches them to a new row. */
@Composable
fun SendToDialog(c: Controller) {
    val files: List<Path> = c.incoming.value
    if (files.isEmpty()) return
    val tables = remember(files) { c.ws.summaries().sortedByDescending { it.updatedAt } }
    var tableId by remember(files) { mutableStateOf(tables.firstOrNull()?.id) }
    var colId by remember(files) { mutableStateOf<Long?>(null) }
    var tableMenu by remember { mutableStateOf(false) }
    var colMenu by remember { mutableStateOf(false) }
    val grid = remember(tableId) { tableId?.let { c.ws.grid(it) } }
    val columns = remember(grid, files) {
        grid?.columns.orEmpty().filter { col -> files.all { attachmentFits(mimeFromName(it.fileName.toString()), col) } }
    }
    val chosen = columns.firstOrNull { it.id == colId } ?: columns.firstOrNull()
    AlertDialog(
        onDismissRequest = { c.incoming.value = emptyList() },
        title = { Text(if (files.size == 1) "Add \"${files[0].fileName}\"" else "Add ${files.size} files") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tables.isEmpty()) {
                    Text("Create a table first.")
                } else {
                    Text("The files go into a new row of the table you pick.")
                    Box {
                        TextButton(onClick = { tableMenu = true }) { Text("Table: ${grid?.name ?: "choose"}  ▾") }
                        DropdownMenu(expanded = tableMenu, onDismissRequest = { tableMenu = false }) {
                            tables.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { tableMenu = false; tableId = t.id; colId = null }) }
                        }
                    }
                    if (columns.isEmpty()) {
                        Text("This table has no File, Picture or Audio column that fits these files. Add one first.")
                    } else {
                        Box {
                            TextButton(onClick = { colMenu = true }) { Text("Column: ${chosen?.name}  ▾") }
                            DropdownMenu(expanded = colMenu, onDismissRequest = { colMenu = false }) {
                                columns.forEach { col -> DropdownMenuItem(text = { Text(col.name) }, onClick = { colMenu = false; colId = col.id }) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = tableId != null && chosen != null,
                onClick = {
                    val tid = tableId ?: return@TextButton
                    val col = chosen ?: return@TextButton
                    val rowId = c.ws.addRow(tid)
                    var added = 0
                    if (rowId != null) {
                        for (p in files) {
                            if (c.edit(tid) { c.ws.addAttachmentFile(tid, rowId, col.id, p, mimeFromName(p.fileName.toString())) }) added++
                        }
                    }
                    c.scheduleSync(tid)
                    c.say(if (added > 0) "Added $added ${if (added == 1) "file" else "files"} to ${grid?.name}" else "Could not add the files")
                    c.incoming.value = emptyList()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { c.incoming.value = emptyList() }) { Text("Cancel") } },
    )
}
