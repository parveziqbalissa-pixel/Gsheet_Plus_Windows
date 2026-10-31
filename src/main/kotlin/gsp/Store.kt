package gsp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Col(
    val id: Long,
    var name: String,
    var type: ColumnType,
    var width: Int = 160,
    var prefix: String = "",
    var suffix: String = "",
    var defaultValue: String = "",
    var listItems: List<String> = emptyList(),
    var formula: String = "",
    var readOnly: Boolean = false,
    var bgColor: Int? = null,
    var textColor: Int? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var pinned: Boolean = false,
    /** The header text this column has in the linked Sheet (null until it has been synced). */
    var sheetName: String? = null,
    /** The validation last pushed to the Sheet for this column. */
    var sig: String? = null,
) {
    fun toGrid() = GridColumn(
        id, name, type, width, prefix, suffix, defaultValue, listItems, formula,
        readOnly, bgColor, textColor, bold, italic, pinned,
    )
}

class Row(
    val id: Long,
    var uuid: String = UUID.randomUUID().toString(),
    val values: MutableMap<Long, String> = HashMap(),
    var color: Int? = null,
    /** The values as of the last sync (null for a row the Sheet has never seen). */
    var base: MutableMap<Long, String>? = null,
    var sourceRow: Int? = null,
)

class Attachment(
    val id: Long,
    var rowId: Long,
    var columnId: Long,
    var name: String,
    var mime: String,
    var localPath: String?,
    var driveId: String? = null,
    var url: String? = null,
    var sheetSynced: Boolean = false,
)

class Link(
    val spreadsheetId: String,
    val sheetId: Int,
    val tabTitle: String,
    val url: String,
    var lastSyncedAt: Long? = null,
)

class Conflict(val id: Long, val columnName: String, val kept: String, val lost: String, val at: Long)

class TableData(val id: Long, var name: String) {
    var pinned = false
    var updatedAt = System.currentTimeMillis()
    val columns = ArrayList<Col>()
    val rows = ArrayList<Row>()
    val attachments = ArrayList<Attachment>()
    var link: Link? = null
    val deletedRowUuids = ArrayList<String>()
    val deletedColumnNames = ArrayList<String>()
    val conflicts = ArrayList<Conflict>()
    var nextId = 1L
    fun newId(): Long = nextId++

    /** What the screen shows: formulas calculated, values copied. */
    fun toGrid(): GridData {
        val cols = columns.map { it.toGrid() }
        val gridRows = rows.map { row ->
            val values = HashMap(row.values)
            applyCalculatedColumns(cols, values)
            GridRow(row.id, values, row.color)
        }
        return GridData(id, name, cols, gridRows)
    }
}

class RowSnapshot(val row: Row, val attachments: List<Attachment>)

class PasteResult(val pasted: Int, val skipped: Int, val rowsAdded: Int = 0, val unmatchedColumns: Int = 0) {
    fun message(): String = buildString {
        if (rowsAdded > 0) append(if (rowsAdded == 1) "Added 1 row" else "Added $rowsAdded rows")
        else append("Pasted $pasted ${if (pasted == 1) "cell" else "cells"}")
        if (skipped > 0) append(", skipped $skipped that did not fit")
        if (unmatchedColumns > 0) append(", $unmatchedColumns column${if (unmatchedColumns == 1) "" else "s"} not in this table")
    }
}

/** All tables of this PC, kept in memory and saved as JSON files (one per table). */
class Workspace(val dir: Path) {
    private val lock = Any()
    private val tableList = ArrayList<TableData>()
    private val dirty = HashSet<Long>()
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "saver").apply { isDaemon = true } }
    private var nextTableId = 1L

    val filesDir: Path = dir.resolve("files")
    val changes = Signal(0L)

    // `changes` counts up whenever anything changes; screens listen to it.

    init {
        Files.createDirectories(dir)
        Files.createDirectories(filesDir)
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().matches(Regex("table_\\d+\\.json")) }.forEach { path ->
                runCatching { TableJson.read(Files.readString(path)) }.getOrNull()?.let { tableList += it }
            }
        }
        nextTableId = (tableList.maxOfOrNull { it.id } ?: 0L) + 1
        Runtime.getRuntime().addShutdownHook(Thread { flush() })
    }

    // ----- reading -----

    class Summary(val id: Long, val name: String, val updatedAt: Long, val rows: Int, val columns: Int, val linked: Boolean, val pinned: Boolean)

    fun summaries(): List<Summary> = synchronized(lock) {
        tableList.map { Summary(it.id, it.name, it.updatedAt, it.rows.size, it.columns.size, it.link != null, it.pinned) }
            .sortedWith(compareByDescending<Summary> { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun grid(tableId: Long): GridData? = synchronized(lock) { find(tableId)?.toGrid() }

    fun link(tableId: Long): Link? = synchronized(lock) { find(tableId)?.link }

    fun attachments(tableId: Long): List<Attachment> = synchronized(lock) { find(tableId)?.attachments?.toList().orEmpty() }

    /** Runs [block] on the table while holding the lock (used by the sync). */
    fun <T> withTable(tableId: Long, block: (TableData) -> T): T? = synchronized(lock) {
        val table = find(tableId) ?: return null
        val result = block(table)
        touch(table)
        result
    }

    private fun find(tableId: Long): TableData? = tableList.firstOrNull { it.id == tableId }

    private fun touch(table: TableData, stamp: Boolean = true) {
        if (stamp) table.updatedAt = System.currentTimeMillis()
        dirty += table.id
        changes.value = changes.value + 1
        saver.schedule({ flush() }, 400, TimeUnit.MILLISECONDS)
    }

    fun flush() {
        val toWrite = synchronized(lock) {
            val tables = dirty.mapNotNull { find(it) }.map { it.id to TableJson.write(it) }
            dirty.clear()
            tables
        }
        for ((id, json) in toWrite) {
            val target = dir.resolve("table_$id.json")
            val temp = dir.resolve("table_$id.json.tmp")
            runCatching {
                Files.writeString(temp, json)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    // ----- tables -----

    fun createTable(name: String, withStarter: Boolean = true): Long = synchronized(lock) {
        val table = TableData(nextTableId++, name.trim().ifEmpty { "New table" })
        if (withStarter) {
            table.columns += Col(table.newId(), "Name", ColumnType.TEXT)
            table.columns += Col(table.newId(), "Notes", ColumnType.TEXT)
            repeat(3) { table.rows += Row(table.newId()) }
        }
        tableList += table
        touch(table)
        table.id
    }

    fun renameTable(tableId: Long, name: String) = synchronized(lock) {
        find(tableId)?.let { it.name = name.trim().ifEmpty { it.name }; touch(it) }
    }

    fun setPinned(tableId: Long, pinned: Boolean) = synchronized(lock) {
        find(tableId)?.let { it.pinned = pinned; touch(it, stamp = false) }
    }

    fun deleteTable(tableId: Long) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        tableList.remove(table)
        dirty.remove(tableId)
        runCatching { Files.deleteIfExists(dir.resolve("table_$tableId.json")) }
        table.attachments.mapNotNull { it.localPath }.forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
        changes.value = changes.value + 1
    }

    /** Copies a table. A copy is never linked to a Google Sheet; attachment files are duplicated. */
    fun copyTable(sourceId: Long, structureOnly: Boolean): Long? = synchronized(lock) {
        val source = find(sourceId) ?: return null
        val copy = TableData(nextTableId++, source.name + if (structureOnly) " (structure)" else " (copy)")
        val columnMap = HashMap<Long, Long>()
        for (c in source.columns) {
            val id = copy.newId()
            columnMap[c.id] = id
            copy.columns += Col(
                id, c.name, c.type, c.width, c.prefix, c.suffix, c.defaultValue, c.listItems, c.formula,
                false, c.bgColor, c.textColor, c.bold, c.italic, c.pinned,
            )
        }
        if (structureOnly) {
            copy.rows += Row(copy.newId())
        } else {
            val rowMap = HashMap<Long, Long>()
            for (r in source.rows) {
                val row = Row(copy.newId(), color = r.color)
                rowMap[r.id] = row.id
                for ((cid, v) in r.values) columnMap[cid]?.let { row.values[it] = v }
                copy.rows += row
            }
            for (a in source.attachments) {
                val newRow = rowMap[a.rowId] ?: continue
                val newCol = columnMap[a.columnId] ?: continue
                val path = a.localPath?.let(::copyFile)
                copy.attachments += Attachment(copy.newId(), newRow, newCol, a.name, a.mime, path, a.driveId, a.url, false)
            }
        }
        tableList += copy
        touch(copy)
        copy.id
    }

    private fun copyFile(path: String): String? {
        val source = Path.of(path)
        if (!Files.exists(source)) return null
        val ext = source.fileName.toString().substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val target = filesDir.resolve(UUID.randomUUID().toString() + ext)
        return runCatching { Files.copy(source, target); target.toString() }.getOrNull()
    }

    // ----- columns -----

    fun addColumn(
        tableId: Long,
        name: String,
        type: ColumnType,
        prefix: String = "",
        suffix: String = "",
        defaultValue: String = "",
        listItems: List<String> = emptyList(),
        formula: String = "",
    ): Long? = synchronized(lock) {
        val table = find(tableId) ?: return null
        val col = Col(
            table.newId(), uniqueName(table, name.trim().ifEmpty { "Column ${table.columns.size + 1}" }), type,
            prefix = prefix, suffix = suffix, defaultValue = defaultValue,
            listItems = listItems, formula = if (type == ColumnType.CALC) formula else "",
        )
        table.columns += col
        touch(table)
        col.id
    }

    private fun uniqueName(table: TableData, wanted: String, except: Long? = null): String {
        var name = wanted
        var n = 2
        while (table.columns.any { it.id != except && it.name.equals(name, ignoreCase = true) }) name = "$wanted ($n)".also { n++ }
        return name
    }

    fun updateColumn(
        tableId: Long,
        columnId: Long,
        name: String,
        type: ColumnType,
        prefix: String,
        suffix: String,
        defaultValue: String,
        listItems: List<String>,
        formula: String,
    ) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        val col = table.columns.firstOrNull { it.id == columnId } ?: return@synchronized
        col.name = uniqueName(table, name.trim().ifEmpty { col.name }, columnId)
        if (col.type != type) {
            col.type = type
            if (type == ColumnType.CALC) {
                table.rows.forEach { it.values.remove(columnId) }
            }
        }
        col.prefix = prefix
        col.suffix = suffix
        col.defaultValue = defaultValue
        col.listItems = listItems
        col.formula = if (type == ColumnType.CALC) formula else ""
        touch(table)
    }

    fun setColumnStyle(tableId: Long, columnId: Long, bg: Int?, text: Int?, bold: Boolean, italic: Boolean) =
        editColumn(tableId, columnId) { it.bgColor = bg; it.textColor = text; it.bold = bold; it.italic = italic }

    fun setColumnPinned(tableId: Long, columnId: Long, pinned: Boolean) = editColumn(tableId, columnId) { it.pinned = pinned }

    fun setColumnWidth(tableId: Long, columnId: Long, width: Int) =
        editColumn(tableId, columnId, stamp = false) { it.width = width.coerceIn(60, 800) }

    private fun editColumn(tableId: Long, columnId: Long, stamp: Boolean = true, change: (Col) -> Unit) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        table.columns.firstOrNull { it.id == columnId }?.let { change(it); touch(table, stamp) }
    }

    fun moveColumn(tableId: Long, columnId: Long, delta: Int) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        val i = table.columns.indexOfFirst { it.id == columnId }
        val j = i + delta
        if (i < 0 || j !in table.columns.indices) return@synchronized
        val col = table.columns.removeAt(i)
        table.columns.add(j, col)
        touch(table)
    }

    fun deleteColumn(tableId: Long, columnId: Long) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        val col = table.columns.firstOrNull { it.id == columnId } ?: return@synchronized
        table.columns.remove(col)
        table.rows.forEach { it.values.remove(columnId); it.base?.remove(columnId) }
        val gone = table.attachments.filter { it.columnId == columnId }
        table.attachments.removeAll(gone)
        gone.mapNotNull { it.localPath }.forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
        // A column that exists in the linked Sheet must be deleted there too, or it would come back.
        val sheetName = col.sheetName
        if (sheetName != null && table.link != null) table.deletedColumnNames += sheetName
        touch(table)
    }

    // ----- rows and cells -----

    fun addRow(tableId: Long): Long? = synchronized(lock) {
        val table = find(tableId) ?: return null
        val row = Row(table.newId())
        for (c in table.columns) {
            if (c.type.isAttachment || c.type == ColumnType.CALC) continue
            val initial = c.toGrid().initialValue()
            if (initial.isNotEmpty()) row.values[c.id] = initial
        }
        table.rows += row
        touch(table)
        row.id
    }

    fun deleteRow(tableId: Long, rowId: Long): RowSnapshot? = synchronized(lock) {
        val table = find(tableId) ?: return null
        val row = table.rows.firstOrNull { it.id == rowId } ?: return null
        table.rows.remove(row)
        val files = table.attachments.filter { it.rowId == rowId }
        table.attachments.removeAll(files)
        if (table.link != null && row.sourceRow != null) table.deletedRowUuids += row.uuid
        touch(table)
        RowSnapshot(row, files)
    }

    /** Undo of [deleteRow]. */
    fun restoreRow(tableId: Long, snapshot: RowSnapshot) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        table.deletedRowUuids.remove(snapshot.row.uuid)
        snapshot.row.base = null
        snapshot.row.sourceRow = null
        table.rows += snapshot.row
        table.attachments += snapshot.attachments
        touch(table)
    }

    /** Removes the files of a deleted row for good (the undo time is over). */
    fun discardSnapshot(snapshot: RowSnapshot) {
        snapshot.attachments.mapNotNull { it.localPath }.forEach { runCatching { Files.deleteIfExists(Path.of(it)) } }
    }

    fun setRowColor(tableId: Long, rowId: Long, color: Int?) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        table.rows.firstOrNull { it.id == rowId }?.let { it.color = color; touch(table, stamp = false) }
    }

    /** Saves one cell. Returns false for a formula or locked column, or a number that is not a number. */
    fun setCell(tableId: Long, rowId: Long, columnId: Long, raw: String): Boolean = synchronized(lock) {
        val table = find(tableId) ?: return false
        val col = table.columns.firstOrNull { it.id == columnId } ?: return false
        val row = table.rows.firstOrNull { it.id == rowId } ?: return false
        if (!writeCell(table, row, col, col.toGrid().withAffixes(raw))) return false
        touch(table)
        true
    }

    private fun writeCell(table: TableData, row: Row, col: Col, value: String): Boolean {
        if (col.type == ColumnType.CALC || col.readOnly) return false
        val text = value.trim()
        when {
            text.isEmpty() -> row.values.remove(col.id)
            col.type == ColumnType.NUMBER -> row.values[col.id] = formatNumber(parseNumber(text) ?: return false)
            else -> row.values[col.id] = text
        }
        return true
    }

    // ----- copy and paste -----

    /** Pastes a copied block starting at the first of [rowIds] and [columnIds]; one cell is repeated over all targets. */
    fun pasteCells(tableId: Long, clip: Clip, rowIds: List<Long>, columnIds: List<Long>): PasteResult = synchronized(lock) {
        val table = find(tableId) ?: return PasteResult(0, 0)
        if (clip.rows == 0 || rowIds.isEmpty() || columnIds.isEmpty()) return PasteResult(0, 0)
        val fillAll = clip.rows == 1 && clip.cols == 1
        val rowCount = if (fillAll) rowIds.size else minOf(clip.rows, rowIds.size)
        val colCount = if (fillAll) columnIds.size else minOf(clip.cols, columnIds.size)
        var pasted = 0
        var skipped = if (fillAll) 0 else clip.rows * clip.cols - rowCount * colCount
        for (r in 0 until rowCount) {
            val row = table.rows.firstOrNull { it.id == rowIds[r] } ?: continue
            for (c in 0 until colCount) {
                val col = table.columns.firstOrNull { it.id == columnIds[c] } ?: continue
                val source = if (fillAll) clip.cells[0][0] else clip.cells[r][c]
                if (pasteOne(table, source, row, col)) pasted++ else skipped++
            }
        }
        touch(table)
        PasteResult(pasted, skipped)
    }

    /** One new row per copied row; columns are matched by name (or by position for text from another app). */
    fun pasteAsRows(tableId: Long, clip: Clip): PasteResult = synchronized(lock) {
        val table = find(tableId) ?: return PasteResult(0, 0)
        if (clip.rows == 0) return PasteResult(0, 0)
        val editable = table.columns.filter { !it.type.isAttachment || true }.filter { !(it.readOnly || it.type == ColumnType.CALC) }
        val byName = clip.columnNames.isNotEmpty()
        val sourceIndex: Map<Long, Int> = if (byName) {
            val names = clip.columnNames.map { it.trim().lowercase() }
            editable.mapNotNull { c -> names.indexOf(c.name.trim().lowercase()).takeIf { it >= 0 }?.let { c.id to it } }.toMap()
        } else {
            editable.withIndex().filter { it.index < clip.cols }.associate { it.value.id to it.index }
        }
        var pasted = 0
        var skipped = 0
        for (sourceRow in clip.cells) {
            val row = Row(table.newId())
            for (c in table.columns) {
                if (c.type.isAttachment || c.type == ColumnType.CALC) continue
                val initial = c.toGrid().initialValue()
                if (initial.isNotEmpty()) row.values[c.id] = initial
            }
            table.rows += row
            for (c in editable) {
                val index = sourceIndex[c.id] ?: continue
                if (pasteOne(table, sourceRow[index], row, c)) pasted++ else skipped++
            }
        }
        touch(table)
        PasteResult(pasted, skipped, rowsAdded = clip.rows, unmatchedColumns = if (byName) clip.columnNames.size - sourceIndex.size else 0)
    }

    private fun pasteOne(table: TableData, cell: ClipCell, row: Row, col: Col): Boolean {
        val grid = col.toGrid()
        if (grid.locked) return false
        if (col.type.isAttachment) {
            var added = 0
            for (a in cell.attachments) {
                if (!attachmentFits(a.mime, grid)) continue
                val path = a.localPath?.let(::copyFile) ?: continue
                var name = a.name
                val lines = attachmentLinks(row.values[col.id].orEmpty())
                var n = 2
                while (name in lines) name = "${a.name} ($n)".also { n++ }
                table.attachments += Attachment(table.newId(), row.id, col.id, name, a.mime, path)
                row.values[col.id] = (lines + name).joinToString("\n")
                added++
            }
            return added > 0
        }
        val converted = convertForColumn(cell, grid) ?: return false
        return writeCell(table, row, col, grid.withAffixes(converted))
    }

    /** Empties cells that can be emptied. Returns how many. */
    fun clearCells(tableId: Long, rowIds: List<Long>, columnIds: List<Long>): Int = synchronized(lock) {
        val table = find(tableId) ?: return 0
        var cleared = 0
        for (rid in rowIds) {
            val row = table.rows.firstOrNull { it.id == rid } ?: continue
            for (cid in columnIds) {
                val col = table.columns.firstOrNull { it.id == cid } ?: continue
                if (col.readOnly || col.type == ColumnType.CALC || col.type.isAttachment) continue
                row.values.remove(cid)
                cleared++
            }
        }
        touch(table)
        cleared
    }

    fun removeAttachment(tableId: Long, attachmentId: Long) = synchronized(lock) {
        val table = find(tableId) ?: return@synchronized
        val a = table.attachments.firstOrNull { it.id == attachmentId } ?: return@synchronized
        table.attachments.remove(a)
        a.localPath?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
        table.rows.firstOrNull { it.id == a.rowId }?.let { row ->
            val lines = attachmentLinks(row.values[a.columnId].orEmpty()).filter { it != a.name }
            if (lines.isEmpty()) row.values.remove(a.columnId) else row.values[a.columnId] = lines.joinToString("\n")
        }
        touch(table)
    }

    /** Adds a file from this PC to a Picture, File or Audio cell. */
    fun addAttachmentFile(tableId: Long, rowId: Long, columnId: Long, source: Path, mime: String): Boolean = synchronized(lock) {
        val table = find(tableId) ?: return false
        val row = table.rows.firstOrNull { it.id == rowId } ?: return false
        val col = table.columns.firstOrNull { it.id == columnId } ?: return false
        if (!col.type.isAttachment || col.readOnly) return false
        val ext = source.fileName.toString().substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val target = filesDir.resolve(UUID.randomUUID().toString() + ext)
        runCatching { Files.copy(source, target) }.getOrElse { return false }
        val label = source.fileName.toString()
        val lines = attachmentLinks(row.values[columnId].orEmpty())
        var name = label
        var n = 2
        while (name in lines) name = "$label ($n)".also { n++ }
        table.attachments += Attachment(table.newId(), rowId, columnId, name, mime, target.toString())
        row.values[columnId] = (lines + name).joinToString("\n")
        touch(table)
        true
    }

    fun addConflict(table: TableData, columnName: String, kept: String, lost: String) {
        table.conflicts += Conflict(table.newId(), columnName, kept, lost, System.currentTimeMillis())
        while (table.conflicts.size > 200) table.conflicts.removeAt(0)
    }

    fun clearConflicts(tableId: Long) = synchronized(lock) {
        find(tableId)?.let { it.conflicts.clear(); touch(it, stamp = false) }
    }

    fun conflicts(tableId: Long): List<Conflict> = synchronized(lock) { find(tableId)?.conflicts?.toList().orEmpty() }

    fun setLink(tableId: Long, link: Link?) = synchronized(lock) {
        find(tableId)?.let { it.link = link; touch(it, stamp = false) }
    }

    /** Creates a table from an opened Sheet (the sync fills it). */
    fun createLinkedTable(name: String, link: Link): Long = synchronized(lock) {
        val table = TableData(nextTableId++, name.trim().ifEmpty { "Sheet" })
        table.link = link
        tableList += table
        touch(table)
        table.id
    }

    fun linkedTableIds(): List<Long> = synchronized(lock) { tableList.filter { it.link != null }.map { it.id } }

    fun tableForSheet(spreadsheetId: String, sheetId: Int): Long? = synchronized(lock) {
        tableList.firstOrNull { it.link?.spreadsheetId == spreadsheetId && it.link?.sheetId == sheetId }?.id
    }
}
