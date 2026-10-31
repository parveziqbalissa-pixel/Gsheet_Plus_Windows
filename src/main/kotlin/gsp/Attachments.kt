package gsp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.imageio.ImageIO
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

/** Small pictures for the attachment list: the local file if it is on this PC, otherwise Google's thumbnail. */
object Thumbs {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, ImageBitmap>()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()

    private fun scaled(image: BufferedImage): BufferedImage {
        val max = 400
        if (image.width <= max) return image
        val h = (image.height * (max.toDouble() / image.width)).toInt().coerceAtLeast(1)
        val out = BufferedImage(max, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, max, h, null)
        g.dispose()
        return out
    }

    fun load(a: Attachment): ImageBitmap? {
        cache[a.id]?.let { return it }
        val image: BufferedImage? = runCatching {
            val local = a.localPath?.let { File(it) }?.takeIf { it.exists() }
            if (local != null) {
                ImageIO.read(local)
            } else {
                a.driveId?.let { id ->
                    val request = HttpRequest.newBuilder(URI("https://drive.google.com/thumbnail?id=$id&sz=w400")).build()
                    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    if (response.statusCode() == 200) ImageIO.read(ByteArrayInputStream(response.body())) else null
                }
            }
        }.getOrNull()
        val bitmap = image?.let { scaled(it).toComposeImageBitmap() } ?: return null
        cache[a.id] = bitmap
        return bitmap
    }
}

@Composable
private fun Thumbnail(a: Attachment, onClick: () -> Unit) {
    val isImage = a.mime.startsWith("image/")
    val bitmap by produceState<ImageBitmap?>(null, a.id) {
        if (isImage) value = withContext(Dispatchers.IO) { Thumbs.load(a) }
    }
    Box(
        Modifier.size(84.dp).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp))
        } else {
            Text(
                if (!isImage) a.name.substringAfterLast('.', "file").uppercase().take(5) else "no preview",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
            Column(Modifier.width(520.dp)) {
                if (items.isEmpty()) Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(items, key = { it.id }) { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Thumbnail(a) { open(a) }
                            Column(Modifier.weight(1f)) {
                                Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (a.url == null) "On this PC, uploads on next sync" else "In Google Drive",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { open(a) }) { Text("Open") }
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
