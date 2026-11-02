package gsp

const val ID_HEADER = "_gsp_id"
val PREVIEW_HEADER = Regex(" preview( \\d+)?$")

/** Everything read from a sheet tab in one go. Row 0 of the lists is the header row. */
class SheetRead(
    /** Raw values: String, Number or Boolean (null for empty cells). */
    val values: List<List<Any?>>,
    /** The same cells as typed (formulas start with "="). */
    val formulas: List<List<String>>,
    val dateColumns: Set<Int>,
    val validations: Map<Int, SheetValidation>,
    /** Size of the tab's grid. */
    val gridRows: Int,
    val gridColumns: Int,
    /** Columns formatted as times in the Sheet. */
    val timeColumns: Set<Int> = emptySet(),
)

/** Changes to make in the sheet. Row and column numbers are 0-based; row 0 is the header. */
class WriteOps(
    val cells: MutableMap<Pair<Int, Int>, String> = HashMap(),
    val deleteRows: MutableList<Int> = ArrayList(),
    val deleteColumns: MutableList<Int> = ArrayList(),
    val hideColumns: MutableList<Int> = ArrayList(),
    /** Column moves, applied last, in order: (from, destinationIndex) as in the Sheets API moveDimension. */
    val moveColumns: MutableList<Pair<Int, Int>> = ArrayList(),
    /** column -> number format to set: "DATE", "TIME", or "" to clear it */
    val formats: MutableMap<Int, String> = HashMap(),
    /** column -> validation to set (null removes it) */
    val validations: MutableMap<Int, SheetValidation?> = HashMap(),
    var needRows: Int = 0,
    var needColumns: Int = 0,
) {
    fun isEmpty() = cells.isEmpty() && deleteRows.isEmpty() && deleteColumns.isEmpty() && hideColumns.isEmpty() && moveColumns.isEmpty() && formats.isEmpty() && validations.isEmpty()
}

interface SheetsGateway {
    suspend fun read(spreadsheetId: String, tabTitle: String): SheetRead
    suspend fun apply(spreadsheetId: String, sheetId: Int, tabTitle: String, ops: WriteOps)
}

/** Turns a number stored by an older text/number column into a date or time text. */
fun dateTimeText(v: String, type: ColumnType): String {
    val n = v.toDoubleOrNull() ?: return v
    return SheetImport.convert(n, type).ifEmpty { v }
}

class SyncResult(val pulled: Int, val pushed: Int, val conflicts: Int, val added: Int, val removed: Int, val columnMoves: Int = 0, val orderFromSheet: Boolean = false) {
    fun message(): String = "Synced: $pulled from the Sheet, $pushed sent" +
        (if (conflicts > 0) ", $conflicts conflict(s) (see Sync history)" else "") +
        (if (columnMoves > 0) ", column order sent to the Sheet ($columnMoves move(s))" else "") +
        (if (orderFromSheet) ", column order taken from the Sheet" else "")
}

/** Text to write into a sheet cell so Google keeps it as typed (no number, date or formula guessing for text columns). */
fun sheetText(type: ColumnType, value: String): String {
    if (value.isEmpty()) return ""
    val textual = type == ColumnType.TEXT || type == ColumnType.PHONE || type == ColumnType.ADDRESS ||
        type == ColumnType.LIST || type.isAttachment
    if (!textual) return value
    val looksTyped = value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("'") ||
        parseNumber(value) != null || value.equals("true", true) || value.equals("false", true) ||
        runCatching { java.time.LocalDate.parse(value) }.isSuccess || Regex("\\d{1,2}[:/.-]\\d{1,2}([:/.-]\\d{2,4})?").matches(value)
    return if (looksTyped) "'$value" else value
}

/** Two-way merge between a table and its linked Sheet. The app wins conflicts. */
class SyncEngine(private val ws: Workspace, private val gateway: SheetsGateway) {

    private class PendingCell(val rowId: Long, val columnId: Long, val expected: String, val value: String)
    private class NewRowPlan(val uuid: String, val sheetRow: Int, val values: Map<Long, String>)

    suspend fun sync(tableId: Long): SyncResult {
        val link = ws.link(tableId) ?: error("This table is not linked to a Google Sheet.")
        val read = gateway.read(link.spreadsheetId, link.tabTitle)

        val ops = WriteOps()
        val pending = ArrayList<PendingCell>()
        val baseUpdates = HashMap<Long, MutableMap<Long, String>>()
        val newRows = ArrayList<NewRowPlan>()
        val deleteLocal = ArrayList<Long>()
        val rowSheetIndex = HashMap<Long, Int>()      // app row id -> its 0-based sheet row (before deletes)
        val appendedRowIds = ArrayList<Pair<Long, Int>>()
        val conflictsFound = ArrayList<Triple<String, String, String>>()
        val tombstonesAtPlan = HashSet<String>()
        val columnTombstonesAtPlan = HashSet<String>()
        var orderFromSheet = false
        var pushed = 0
        var pulled = 0

        val planned = ws.withTable(tableId) { table ->
            val values = read.values
            val header = values.firstOrNull().orEmpty().map { it.text().trim() }
            val width = maxOf(read.gridColumns, header.size, values.maxOfOrNull { it.size } ?: 0)
            fun rawAt(r: Int, c: Int): Any? = values.getOrNull(r)?.getOrNull(c)
            fun formulaAt(r: Int, c: Int): String = read.formulas.getOrNull(r)?.getOrNull(c).orEmpty()

            // ----- columns -----
            var idCol = header.indexOf(ID_HEADER)
            var nextColumn = width
            if (idCol < 0) {
                idCol = nextColumn++
                ops.cells[0 to idCol] = ID_HEADER
                ops.hideColumns += idCol
            }
            val used = HashSet<Int>()
            used += idCol
            val sheetIndex = HashMap<Long, Int>()
            val removeCols = ArrayList<Col>()
            fun usable(i: Int) = i !in used && header[i].isNotEmpty() && header[i] != ID_HEADER && !PREVIEW_HEADER.containsMatchIn(header[i])
            val positionBefore = HashMap<Long, Int?>()
            for (col in table.columns) positionBefore[col.id] = col.sheetIndex
            // Columns synced by an older version have no remembered position yet: take the Sheet's order once.
            val legacyOrder = table.columns.any { it.sheetName != null && it.sheetIndex == null }
            val unresolved = ArrayList<Col>()
            // Pass 1: columns whose header text is still where we left it, or has moved elsewhere unchanged.
            for (col in table.columns) {
                val known = col.sheetName
                val wanted = known ?: col.name
                val here = col.sheetIndex?.takeIf { it in header.indices && it !in used && header[it] == wanted }
                var idx = here ?: header.indices.firstOrNull { it !in used && header[it] == wanted }
                if (idx == null && known != null) idx = header.indices.firstOrNull { it !in used && header[it] == col.name }
                if (idx != null) {
                    used += idx
                    sheetIndex[col.id] = idx
                    if (known != null && header[idx] == known && col.name != known) {
                        ops.cells[0 to idx] = col.name   // renamed in the app
                    }
                    col.sheetName = col.name
                } else unresolved += col
            }
            // Pass 2: the old header text is gone. A header at the column's old position is a rename made in the Sheet.
            for (col in unresolved) {
                val old = col.sheetIndex
                if (col.sheetName != null && old != null && old in header.indices && usable(old)) {
                    used += old
                    sheetIndex[col.id] = old
                    col.name = header[old]          // the Sheet's new name wins
                    col.sheetName = header[old]
                } else if (col.sheetName != null) {
                    removeCols += col          // deleted in the Sheet
                } else {
                    val at = nextColumn++
                    used += at
                    sheetIndex[col.id] = at
                    ops.cells[0 to at] = col.name
                    col.sheetName = col.name
                }
            }
            for (col in removeCols) {
                table.columns.remove(col)
                table.rows.forEach { it.values.remove(col.id); it.base?.remove(col.id) }
                table.attachments.removeAll { it.columnId == col.id }
            }
            // Columns that exist only in the Sheet.
            val tombstoned = table.deletedColumnNames.toSet()
            val strangers = header.indices.filter { i ->
                i !in used && header[i].isNotEmpty() && !PREVIEW_HEADER.containsMatchIn(header[i])
            }
            val toCreate = ArrayList<Int>()
            for (i in strangers) {
                if (header[i] in tombstoned) ops.deleteColumns += i else toCreate += i
            }
            if (toCreate.isNotEmpty()) {
                val formulaColumns = toCreate.filter { c ->
                    (1 until read.formulas.size).any { r -> formulaAt(r, c).startsWith("=") }
                }.toSet()
                val plan = SheetImport.plan(values, read.dateColumns, read.validations, formulaColumns)
                for (i in toCreate) {
                    val p = plan.columns.getOrNull(i) ?: continue
                    // A column with a "<name> preview" column next to it holds pictures.
                    val kind = if (p.type == ColumnType.TEXT && header.contains(previewHeader(p.name))) ColumnType.PICTURE else p.type
                    val col = Col(
                        table.newId(), p.name, kind, readOnly = p.readOnly, listItems = p.listItems,
                        sheetName = header[i],
                        sig = columnSig(kind, p.listItems),
                    )
                    table.columns += col
                    sheetIndex[col.id] = i
                    used += i
                }
            }
            // Remember each column's Sheet position; when the Sheet's order changed, the app's order follows it.
            // Column order. A move made in the app is sent to the Sheet; a move made in the Sheet is taken over.
            val mapped = table.columns.filter { positionBefore[it.id] != null && sheetIndex[it.id] != null }
            val appMoved = !legacyOrder && toCreate.isEmpty() &&
                mapped.map { it.id } != mapped.sortedBy { positionBefore[it.id] }.map { it.id }
            val deletedAt = ops.deleteColumns.toSet()
            fun after(i: Int) = i - deletedAt.count { it < i }
            if (appMoved) {
                val size = maxOf(width, nextColumn, (sheetIndex.values.maxOrNull() ?: -1) + 1)
                val current = (0 until size).filter { it !in deletedAt }.toMutableList()
                val wanted = mapped.map { sheetIndex.getValue(it.id) }
                val mappedTokens = wanted.toSet()
                for (k in wanted.indices) {
                    val slots = current.withIndex().filter { it.value in mappedTokens }.map { it.index }
                    val target = slots[k]
                    val from = current.indexOf(wanted[k])
                    if (from < 0 || from == target) continue
                    val moved = current.removeAt(from)
                    current.add(target, moved)
                    ops.moveColumns += from to (if (from < target) target + 1 else target)
                }
                for (c in table.columns) c.sheetIndex = sheetIndex[c.id]?.let { current.indexOf(it) }?.takeIf { it >= 0 }
            } else {
                val movedInSheet = legacyOrder || toCreate.isNotEmpty() || table.columns.any { c ->
                    val before = positionBefore[c.id]
                    before != null && sheetIndex[c.id] != null && sheetIndex[c.id] != before
                }
                for (c in table.columns) c.sheetIndex = sheetIndex[c.id]?.let { after(it) }
                if (movedInSheet) {
                    val before = table.columns.map { it.id }
                    table.columns.sortWith(compareBy { it.sheetIndex ?: Int.MAX_VALUE })
                    if (table.columns.map { it.id } != before) orderFromSheet = true
                }
            }
            // Date and time columns: the Sheet's number format and the app's column type follow each other.
            for (col in table.columns) {
                val idx = sheetIndex[col.id] ?: continue
                if (col.readOnly || col.type == ColumnType.CALC || col.type.isAttachment) continue
                val sheetKind = when (idx) { in read.dateColumns -> "DATE"; in read.timeColumns -> "TIME"; else -> "" }
                val appKind = when (col.type) { ColumnType.DATE -> "DATE"; ColumnType.TIME -> "TIME"; else -> "" }
                if (col.fmt != sheetKind) {
                    // First look at this column, or the Sheet's format changed: the Sheet decides.
                    if (sheetKind != "" && (col.type == ColumnType.TEXT || col.type == ColumnType.NUMBER || col.type == ColumnType.DATE || col.type == ColumnType.TIME)) {
                        val newType = if (sheetKind == "DATE") ColumnType.DATE else ColumnType.TIME
                        if (col.type != newType) {
                            for (row in table.rows) {
                                row.values[col.id]?.let { v -> row.values[col.id] = dateTimeText(v, newType) }
                                row.base?.get(col.id)?.let { v -> row.base!![col.id] = dateTimeText(v, newType) }
                            }
                            col.type = newType
                        }
                        col.fmt = sheetKind
                    } else if (sheetKind == "" && col.fmt == null && appKind != "") {
                        ops.formats[idx] = appKind      // first sync of a date/time column made in the app
                        col.fmt = appKind
                    } else col.fmt = sheetKind
                } else if (appKind != sheetKind) {
                    ops.formats[idx] = appKind          // the type was changed in the app
                    col.fmt = appKind
                }
            }
            // Validation for LIST and CHECKBOX columns the app owns.
            for (col in table.columns) {
                val idx = sheetIndex[col.id] ?: continue
                val sig = columnSig(col.type, col.listItems)
                if (sig == col.sig) continue
                val known = read.validations[idx]
                val sheetSig = known?.let { if (it.checkbox) "CHECKBOX" else "LIST:" + it.items.joinToString("|") }
                if (sig != sheetSig) {
                    ops.validations[idx] = when (col.type) {
                        ColumnType.CHECKBOX -> SheetValidation(true, emptyList())
                        ColumnType.LIST -> SheetValidation(false, col.listItems)
                        else -> null
                    }
                }
                col.sig = sig
            }
            tombstonesAtPlan += table.deletedRowUuids
            columnTombstonesAtPlan += table.deletedColumnNames

            // ----- rows -----
            val appByUuid = table.rows.associateBy { it.uuid }
            val matched = HashSet<Long>()
            var lastUsedRow = 0
            val rowCount = values.size
            for (r in 1 until rowCount) {
                val cells = values[r]
                if (cells.all { it == null || (it is String && it.isBlank()) }) continue
                lastUsedRow = r
            }
            // The first free row for rows added from the app.
            var appendAt = maxOf(lastUsedRow + 1, 1)

            for (r in 1 until rowCount) {
                val cells = values[r]
                if (cells.all { it == null || (it is String && it.isBlank()) }) continue
                val id = rawAt(r, idCol).text().trim()
                if (id in table.deletedRowUuids) {
                    ops.deleteRows += r
                    continue
                }
                val row = if (id.isNotEmpty()) appByUuid[id] else null
                if (row == null) {
                    // A row that exists only in the Sheet.
                    val uuid = id.ifEmpty { java.util.UUID.randomUUID().toString() }
                    if (id.isEmpty()) ops.cells[r to idCol] = uuid
                    val rowValues = HashMap<Long, String>()
                    for (col in table.columns) {
                        val idx = sheetIndex[col.id] ?: continue
                        if (col.type == ColumnType.CALC) continue
                        val v = normalizeCell(col.type, SheetImport.convert(rawAt(r, idx), col.type))
                        if (v.isNotEmpty()) rowValues[col.id] = v
                    }
                    newRows += NewRowPlan(uuid, r, rowValues)
                    pulled += rowValues.size
                    continue
                }
                matched += row.id
                rowSheetIndex[row.id] = r
                val base = row.base
                val baseUpd = baseUpdates.getOrPut(row.id) { HashMap() }
                for (col in table.columns) {
                    val idx = sheetIndex[col.id] ?: continue
                    val type = col.type
                    val sheetVal = normalizeCell(type, SheetImport.convert(rawAt(r, idx), type))
                    val appVal = normalizeCell(type, row.values[col.id].orEmpty())
                    val baseVal = normalizeCell(type, base?.get(col.id).orEmpty())
                    val isFormulaCell = formulaAt(r, idx).startsWith("=")
                    when {
                        type == ColumnType.CALC -> {
                            // Push-only: the app's calculated value goes to the Sheet.
                            val calc = table.toGrid().rows.firstOrNull { it.id == row.id }?.values?.get(col.id).orEmpty()
                            if (!isFormulaCell && normalizeCell(type, calc) != sheetVal) {
                                ops.cells[r to idx] = calc
                                pushed++
                            }
                            baseUpd[col.id] = calc
                        }
                        col.readOnly || isFormulaCell -> {
                            if (sheetVal != appVal) {
                                pending += PendingCell(row.id, col.id, row.values[col.id].orEmpty(), sheetVal)
                                pulled++
                            }
                            baseUpd[col.id] = sheetVal
                        }
                        appVal == sheetVal -> baseUpd[col.id] = appVal
                        appVal == baseVal -> {
                            pending += PendingCell(row.id, col.id, row.values[col.id].orEmpty(), sheetVal)
                            baseUpd[col.id] = sheetVal
                            pulled++
                        }
                        sheetVal == baseVal -> {
                            ops.cells[r to idx] = sheetText(type, appVal)
                            baseUpd[col.id] = appVal
                            pushed++
                        }
                        else -> {
                            // Changed on both sides: the app wins, and the Sheet's value is kept in the history.
                            ops.cells[r to idx] = sheetText(type, appVal)
                            baseUpd[col.id] = appVal
                            conflictsFound += Triple(col.name, appVal, sheetVal)
                            pushed++
                        }
                    }
                }
            }

            // App rows the Sheet does not have.
            for (row in table.rows) {
                if (row.id in matched) continue
                val everSynced = row.base != null && row.sourceRow != null
                if (everSynced) {
                    val changed = table.columns.any { c ->
                        c.type != ColumnType.CALC &&
                            normalizeCell(c.type, row.values[c.id].orEmpty()) != normalizeCell(c.type, row.base?.get(c.id).orEmpty())
                    }
                    if (!changed) {
                        deleteLocal += row.id     // deleted in the Sheet
                        continue
                    }
                }
                val at = appendAt++
                appendedRowIds += row.id to at
                rowSheetIndex[row.id] = at
                ops.cells[at to idCol] = row.uuid
                val baseUpd = baseUpdates.getOrPut(row.id) { HashMap() }
                val grid = table.toGrid().rows.firstOrNull { it.id == row.id }
                for (col in table.columns) {
                    val idx = sheetIndex[col.id] ?: continue
                    val v = if (col.type == ColumnType.CALC) grid?.values?.get(col.id).orEmpty() else normalizeCell(col.type, row.values[col.id].orEmpty())
                    if (v.isNotEmpty()) ops.cells[at to idx] = sheetText(col.type, v)
                    baseUpd[col.id] = v
                }
                pushed++
            }
            true
        }
        if (planned == null) error("The table no longer exists.")

        // Header and grid size needs.
        val maxCol = (ops.cells.keys.maxOfOrNull { it.second } ?: -1).coerceAtLeast(ops.validations.keys.maxOrNull() ?: -1)
        ops.needColumns = maxCol + 1
        ops.needRows = (ops.cells.keys.maxOfOrNull { it.first } ?: -1) + 1

        if (!ops.isEmpty()) gateway.apply(link.spreadsheetId, link.sheetId, link.tabTitle, ops)

        // ----- apply to the table -----
        val deletedRowsSorted = ops.deleteRows.sorted()
        fun shift(row: Int): Int = row - deletedRowsSorted.count { it < row }
        ws.withTable(tableId) { table ->
            val byId = table.rows.associateBy { it.id }
            for (p in pending) {
                val row = byId[p.rowId] ?: continue
                if (row.values[p.columnId].orEmpty() != p.expected) continue    // edited while syncing: next sync decides
                if (p.value.isEmpty()) row.values.remove(p.columnId) else row.values[p.columnId] = p.value
            }
            for ((rowId, upd) in baseUpdates) {
                val row = byId[rowId] ?: continue
                val base = row.base ?: HashMap<Long, String>().also { row.base = it }
                for ((c, v) in upd) if (v.isEmpty()) base.remove(c) else base[c] = v
            }
            for ((rowId, idx) in rowSheetIndex) byId[rowId]?.sourceRow = shift(idx) + 1
            for (n in newRows) {
                val row = Row(table.newId(), uuid = n.uuid, sourceRow = shift(n.sheetRow) + 1)
                row.values.putAll(n.values)
                row.base = HashMap(n.values)
                table.rows += row
            }
            table.rows.removeAll { it.id in deleteLocal }
            table.attachments.removeAll { it.rowId in deleteLocal }
            for ((name, kept, lost) in conflictsFound) ws.addConflict(table, name, kept, lost)
            table.deletedRowUuids.removeAll(tombstonesAtPlan)
            table.deletedColumnNames.removeAll(columnTombstonesAtPlan)
            // Rows follow the Sheet's order; rows only in the app stay at the end.
            val order = table.rows.withIndex().sortedBy { (i, r) -> (r.sourceRow ?: Int.MAX_VALUE).toLong() * 100000 + i }
            val sorted = order.map { it.value }
            table.rows.clear()
            table.rows.addAll(sorted)
            table.link?.lastSyncedAt = System.currentTimeMillis()
        }
        return SyncResult(pulled, pushed, conflictsFound.size, newRows.size, deleteLocal.size, ops.moveColumns.size, orderFromSheet)
    }
}
