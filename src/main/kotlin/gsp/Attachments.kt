package gsp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser

fun mimeOf(file: File): String = java.net.URLConnection.guessContentTypeFromName(file.name) ?: when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

/** Lists the files in one Picture, File or Audio cell; lets the user add several at once or remove them. */
@Composable
fun AttachmentDialog(c: Controller, tableId: Long, rowId: Long, column: GridColumn, items: List<Attachment>, onDismiss: () -> Unit) {
    fun open(a: Attachment) {
        val target: URI? = a.url?.let { runCatching { URI(it) }.getOrNull() } ?: a.localPath?.let { File(it).toURI() }
        if (target != null) runCatching { Desktop.getDesktop().browse(target) }
    }

    fun add() {
        val chooser = JFileChooser()
        chooser.isMultiSelectionEnabled = true
        chooser.dialogTitle = "Choose ${if (column.type == ColumnType.PICTURE) "pictures" else if (column.type == ColumnType.AUDIO) "recordings" else "files"}"
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        var added = 0
        var skipped = 0
        for (f in chooser.selectedFiles) {
            val mime = mimeOf(f)
            if (!attachmentFits(mime, column)) { skipped++; continue }
            if (c.edit(tableId) { c.ws.addAttachmentFile(tableId, rowId, column.id, f.toPath(), mime) }) added++ else skipped++
        }
        c.say("Added $added" + if (skipped > 0) ", skipped $skipped (wrong kind of file)" else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(column.name) },
        text = {
            Column(Modifier.width(460.dp)) {
                if (items.isEmpty()) Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(items, key = { it.id }) { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { open(a) }, modifier = Modifier.weight(1f)) {
                                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { c.edit(tableId) { c.ws.removeAttachment(tableId, a.id) } }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { add() }) { Text("Add from this PC...") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
