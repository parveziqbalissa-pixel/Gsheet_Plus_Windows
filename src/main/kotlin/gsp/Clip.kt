package gsp

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.time.LocalDate
import java.time.LocalTime

/** One copied cell: its type, its text, and (for pictures, files, recordings) the attachments. */
class ClipCell(val type: ColumnType, val value: String, val attachments: List<Attachment> = emptyList())

/** Copied cells: a block of rows x columns. [wholeRows] is true when whole rows were copied. */
class Clip(val columnNames: List<String>, val cells: List<List<ClipCell>>, val wholeRows: Boolean) {
    val rows: Int get() = cells.size
    val cols: Int get() = cells.firstOrNull()?.size ?: 0

    /** The text put on the PC's clipboard (tab and line separated, like a spreadsheet). */
    fun toText(): String {
        if (rows == 1 && cols == 1) return cells[0][0].value
        return cells.joinToString("\n") { row -> row.joinToString("\t") { it.value.replace('\t', ' ').replace('\n', ' ') } }
    }
}

/** Text from another program: tabs make columns and lines make rows; text without tabs is one cell. */
fun parseClipText(text: String): Clip {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
    if ('\t' !in normalized) return Clip(emptyList(), listOf(listOf(ClipCell(ColumnType.TEXT, normalized))), false)
    val lines = normalized.split('\n').map { it.split('\t') }
    val width = lines.maxOf { it.size }
    return Clip(emptyList(), lines.map { line -> List(width) { i -> ClipCell(ColumnType.TEXT, line.getOrNull(i).orEmpty()) } }, false)
}

/** The text to store in [column] for a pasted [cell], or null when it does not fit that column. */
fun convertForColumn(cell: ClipCell, column: GridColumn): String? {
    if (column.locked) return null
    val value = cell.value.trim()
    return when (column.type) {
        ColumnType.TEXT, ColumnType.PHONE, ColumnType.ADDRESS -> value
        ColumnType.NUMBER -> if (value.isEmpty()) "" else parseNumber(value)?.let(::formatNumber)
        ColumnType.DATE -> if (value.isEmpty()) "" else value.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        ColumnType.TIME -> if (value.isEmpty()) "" else value.takeIf { runCatching { LocalTime.parse(it) }.isSuccess }
        ColumnType.CHECKBOX -> value.lowercase().takeIf { it.isEmpty() || it == "true" || it == "false" }
        ColumnType.LIST -> value.takeIf { it.isEmpty() || it in column.listItems }
        ColumnType.RATING -> if (value.isEmpty()) "" else parseNumber(value)?.takeIf { it in 0.0..5.0 }?.let { formatNumber(Math.round(it).toDouble()) }
        ColumnType.PICTURE, ColumnType.FILE, ColumnType.AUDIO, ColumnType.CALC, ColumnType.SIGNATURE -> null
    }
}

/** Whether a copied attachment of [mime] can go into [column]. */
fun attachmentFits(mime: String, column: GridColumn): Boolean = !column.locked && when (column.type) {
    ColumnType.PICTURE, ColumnType.SIGNATURE -> mime.startsWith("image/")
    ColumnType.AUDIO -> mime.startsWith("audio/")
    ColumnType.FILE -> true
    else -> false
}

/** Whether [cell] can be pasted into [column]. */
fun cellFits(cell: ClipCell, column: GridColumn): Boolean =
    if (column.type.isAttachment) cell.attachments.any { attachmentFits(it.mime, column) } else convertForColumn(cell, column) != null

/** Copies the cells of [rows] x [columns]; [attachments] are the table's attachments. */
fun buildClip(rows: List<GridRow>, columns: List<GridColumn>, attachments: List<Attachment>, wholeRows: Boolean): Clip {
    val byCell = attachments.groupBy { it.rowId to it.columnId }
    return Clip(
        columnNames = columns.map { it.name },
        cells = rows.map { row ->
            columns.map { c ->
                ClipCell(
                    c.type,
                    c.withoutAffixes(row.values[c.id].orEmpty()),
                    if (c.type.isAttachment) byCell[row.id to c.id].orEmpty() else emptyList(),
                )
            }
        },
        wholeRows = wholeRows,
    )
}

/** What was copied in the app, mirrored as text on the PC's clipboard. */
object CellClipboard {
    private var internal: Clip? = null
    val current = Signal<Clip?>(null)

    fun set(clip: Clip) {
        internal = clip
        current.value = clip
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(clip.toText()), null) }
    }

    fun clear() {
        internal = null
        current.value = null
    }

    private fun systemText(): String? = runCatching {
        val board = Toolkit.getDefaultToolkit().systemClipboard
        if (board.isDataFlavorAvailable(DataFlavor.stringFlavor)) board.getData(DataFlavor.stringFlavor) as? String else null
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Our own copy if the PC clipboard still matches it; otherwise whatever text is on the clipboard. */
    fun resolve(): Clip? {
        val system = systemText()
        val own = internal
        return when {
            own != null && (system == null || system == own.toText()) -> own
            system != null -> parseClipText(system)
            else -> own
        }
    }
}

/** Pictures or files taken from the PC's clipboard. [temporary] files are deleted after they have been added. */
class PastedMedia(val paths: List<java.nio.file.Path>, val temporary: Boolean) {
    val allImages: Boolean get() = paths.all { mimeFromName(it.fileName.toString()).startsWith("image/") }
    fun cleanUp() {
        if (temporary) paths.forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
    }
}

object SystemClipboard {
    private fun board() = Toolkit.getDefaultToolkit().systemClipboard

    private fun hasText(): Boolean = runCatching { board().isDataFlavorAvailable(DataFlavor.stringFlavor) }.getOrDefault(false)

    /** Cheap check (looks at the kinds of data only). Text wins over an image, because copying cells from Excel also offers a picture. */
    fun hasMedia(): Boolean = runCatching {
        board().isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ||
            (board().isDataFlavorAvailable(DataFlavor.imageFlavor) && !hasText())
    }.getOrDefault(false)

    /** Files copied in Explorer, or a picture (screenshot, image copied from a browser) saved to a temporary PNG. */
    fun readMedia(): PastedMedia? = runCatching {
        val b = board()
        if (b.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = (b.getData(DataFlavor.javaFileListFlavor) as? List<java.io.File>).orEmpty().filter { it.isFile }
            if (files.isNotEmpty()) return@runCatching PastedMedia(files.map { it.toPath() }, false)
        }
        if (b.isDataFlavorAvailable(DataFlavor.imageFlavor) && !hasText()) {
            val image = b.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return@runCatching null
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return@runCatching null
            val buffered = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = buffered.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, width, height)
            g.drawImage(image, 0, 0, null)
            g.dispose()
            val dir = java.nio.file.Files.createTempDirectory("gsp-paste")
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
            val file = dir.resolve("Pasted image $stamp.png")
            javax.imageio.ImageIO.write(buffered, "png", file.toFile())
            return@runCatching PastedMedia(listOf(file), true)
        }
        null
    }.getOrNull()
}

/** Whether Paste can do something: cells copied in the app, text, or a picture or files from the PC. */
fun canPaste(): Boolean = CellClipboard.resolve() != null || SystemClipboard.hasMedia()
