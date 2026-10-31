package gsp

import java.io.File

/** A cell whose text gets a hyperlink for each attachment. [runs] mark where each line starts and its link (null = no link). */
class LinkRun(val start: Int, val uri: String?)
class LinkCell(val row: Int, val col: Int, val text: String, val runs: List<LinkRun>)

/** Drive upload and the extra Sheet writes needed for attachments. */
interface AttachmentGateway {
    /** Uploads to the Gsheet_Plus folder in Drive; returns the Drive id and the link. */
    suspend fun upload(file: File, name: String, mime: String, shareByLink: Boolean): Pair<String, String>

    /** For each (0-based row, column) in [columns]: the lines of the cell that carry a hyperlink, as (text, uri). */
    suspend fun links(spreadsheetId: String, tabTitle: String, columns: List<Int>): Map<Pair<Int, Int>, List<Pair<String, String>>>

    /** The header row (row 1) of the tab. */
    suspend fun header(spreadsheetId: String, tabTitle: String): List<String>

    /**
     * Writes [links] (hyperlinked cells), [cells] (preview formulas, "" clears) and [headers] (new column titles).
     * Rows and columns are 0-based; row 0 is the header.
     */
    suspend fun write(spreadsheetId: String, sheetId: Int, tabTitle: String, links: List<LinkCell>, cells: Map<Pair<Int, Int>, String>, headers: Map<Int, String>)
}

const val MAX_PREVIEWS = 10

fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "bmp" -> "image/bmp"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

fun driveIdOf(url: String): String? =
    Regex("/d/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: Regex("[?&]id=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

fun previewHeader(columnName: String, number: Int = 1): String =
    columnName.trim() + " preview" + if (number > 1) " $number" else ""

fun thumbnailFormula(driveId: String): String = "=IMAGE(\"https://drive.google.com/thumbnail?id=$driveId&sz=w320\")"

/**
 * Runs after a normal sync: uploads new pictures and files to Drive, then puts hyperlinks into the cells and
 * thumbnails ("X preview", "X preview 2", ...) next to picture columns. Preview columns are added at the end of the
 * Sheet, or reused when they already exist (for example made by the phone app).
 */
class AttachmentSync(private val ws: Workspace, private val gateway: AttachmentGateway) {

    /** Returns how many files were uploaded. */
    suspend fun run(tableId: Long): Int {
        val link = ws.link(tableId) ?: return 0
        var uploaded = 0
        var firstError: Exception? = null

        pullLinks(tableId, link)

        val toUpload = ws.withTable(tableId) { t ->
            t.attachments.filter { it.driveId == null && it.localPath != null && t.columns.any { c -> c.id == it.columnId } }
                .map { Triple(it.id, it.name, it.mime) to it.localPath!! }
        }.orEmpty()
        for ((meta, path) in toUpload) {
            try {
                val (driveId, url) = gateway.upload(File(path), meta.second, meta.third, meta.third.startsWith("image/"))
                ws.withTable(tableId) { t ->
                    t.attachments.firstOrNull { it.id == meta.first }?.let { it.driveId = driveId; it.url = url; it.sheetSynced = false }
                }
                uploaded++
            } catch (e: NotSignedIn) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }

        decorate(tableId, link)
        firstError?.let { throw it }
        return uploaded
    }


    /** Reads the hyperlinks in the Sheet's picture, file and audio cells (made here or on the phone) as attachments. */
    private suspend fun pullLinks(tableId: Long, link: Link) {
        val header = gateway.header(link.spreadsheetId, link.tabTitle).map { it.trim() }
        val columns = ws.withTable(tableId) { t ->
            t.columns.filter { it.type.isAttachment }.mapNotNull { c ->
                val idx = header.indexOf(c.sheetName ?: c.name)
                if (idx >= 0) c.id to idx else null
            }
        }.orEmpty()
        if (columns.isEmpty()) return
        val found = gateway.links(link.spreadsheetId, link.tabTitle, columns.map { it.second })
        val byIndex = columns.associate { it.second to it.first }
        ws.withTable(tableId) { t ->
            val rowBySheet = t.rows.filter { it.sourceRow != null }.associateBy { it.sourceRow!! - 1 }
            for ((pos, entries) in found) {
                val row = rowBySheet[pos.first] ?: continue
                val columnId = byIndex[pos.second] ?: continue
                val have = t.attachments.filter { it.rowId == row.id && it.columnId == columnId }
                val lines = attachmentLinks(row.values[columnId].orEmpty())
                for ((text, uri) in entries) {
                    val name = text.trim()
                    if (name.isEmpty() || have.any { it.name == name }) continue
                    t.attachments += Attachment(t.newId(), row.id, columnId, name, mimeFromName(name), null, driveIdOf(uri), uri, true)
                   
                }
                // Links that vanished from the cell (removed on the phone or in the Sheet).
                for (a in have) {
                    if (a.localPath == null && a.name !in lines) { t.attachments.remove(a); }
                }
            }
        }
    }

    private suspend fun decorate(tableId: Long, link: Link) {
        val dirty = ws.withTable(tableId) { t ->
            val keys = LinkedHashSet<Pair<Long, Long>>()
            for (a in t.attachments) if (a.url != null && !a.sheetSynced) keys += a.rowId to a.columnId
            keys += t.staleCells
            keys.filter { (r, c) -> t.rows.any { it.id == r && it.sourceRow != null } && t.columns.any { it.id == c } }
        }.orEmpty()
        if (dirty.isEmpty()) return

        val header = gateway.header(link.spreadsheetId, link.tabTitle).map { it.trim() }
        var nextColumn = header.size
        val newHeaders = HashMap<Int, String>()
        val links = ArrayList<LinkCell>()
        val cells = HashMap<Pair<Int, Int>, String>()
        val handled = ArrayList<Pair<Long, Long>>()

        ws.withTable(tableId) { t ->
            fun previewColumn(name: String, k: Int): Int {
                val title = previewHeader(name, k)
                val at = header.indexOf(title)
                if (at >= 0) return at
                newHeaders.entries.firstOrNull { it.value == title }?.let { return it.key }
                val idx = nextColumn++
                newHeaders[idx] = title
                return idx
            }
            for ((rowId, columnId) in dirty) {
                val row = t.rows.firstOrNull { it.id == rowId } ?: continue
                val col = t.columns.firstOrNull { it.id == columnId } ?: continue
                val sheetRow = (row.sourceRow ?: continue) - 1
                val colIndex = header.indexOf(col.sheetName ?: col.name)
                if (colIndex < 0) continue
                val text = row.values[columnId].orEmpty()
                val inCell = t.attachments.filter { it.rowId == rowId && it.columnId == columnId }
                val lines = text.split("\n")
                val runs = ArrayList<LinkRun>()
                var offset = 0
                var linked = false
                for (line in lines) {
                    val match = inCell.firstOrNull { it.name == line.trim() }
                    if (match?.url != null) linked = true
                    runs += LinkRun(offset, match?.url)
                    offset += line.length + 1
                }
                if (linked && text.isNotEmpty()) links += LinkCell(sheetRow, colIndex, text, runs)
                if (col.type == ColumnType.PICTURE) {
                    val images = attachmentLinks(text).mapNotNull { name ->
                        inCell.firstOrNull { it.name == name && it.mime.startsWith("image/") && it.driveId != null }
                    }.take(MAX_PREVIEWS)
                    var existing = 0
                    while (existing < MAX_PREVIEWS && header.contains(previewHeader(col.name, existing + 1))) existing++
                    val count = maxOf(images.size, existing)
                    for (k in 1..count) {
                        val at = previewColumn(col.name, k)
                        cells[sheetRow to at] = images.getOrNull(k - 1)?.let { thumbnailFormula(it.driveId.orEmpty()) } ?: ""
                    }
                }
                handled += rowId to columnId
            }
        }
        if (links.isEmpty() && cells.isEmpty() && newHeaders.isEmpty()) {
            markDone(tableId, handled)
            return
        }
        gateway.write(link.spreadsheetId, link.sheetId, link.tabTitle, links, cells, newHeaders)
        markDone(tableId, handled)
    }

    private fun markDone(tableId: Long, handled: List<Pair<Long, Long>>) {
        val done = handled.toSet()
        ws.withTable(tableId) { t ->
            for (a in t.attachments) if (a.url != null && (a.rowId to a.columnId) in done) a.sheetSynced = true
            t.staleCells.removeAll(done)
        }
    }
}
