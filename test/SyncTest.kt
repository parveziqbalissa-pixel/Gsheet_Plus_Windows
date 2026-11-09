import gsp.*
import kotlin.coroutines.*

fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

/** An in-memory Google Sheet. */
class FakeSheet : SheetsGateway {
    var grid = ArrayList<ArrayList<Any?>>()
    var validations = HashMap<Int, SheetValidation>()
    var hidden = HashSet<Int>()
    var formats = HashMap<Int, String>()
    var applied = 0
    override suspend fun read(spreadsheetId: String, tabTitle: String): SheetRead {
        val width = grid.maxOfOrNull { it.size } ?: 0
        val values = grid.map { row -> List(width) { row.getOrNull(it) } }
        val formulas = grid.map { row -> List(width) { c -> row.getOrNull(c)?.let { if (it is String && it.startsWith("=")) it else it.text() }.orEmpty() } }
        return SheetRead(values, formulas, formats.filterValues { it == "DATE" }.keys, validations, grid.size + 5, width, formats.filterValues { it == "TIME" }.keys, formats.filterValues { it in setOf("FILE", "AUDIO", "PICTURE", "SIGNATURE") })
    }
    override suspend fun apply(spreadsheetId: String, sheetId: Int, tabTitle: String, ops: WriteOps) {
        applied++
        while (grid.size < ops.needRows) grid.add(ArrayList())
        for ((rc, text) in ops.cells) {
            val (r, c) = rc
            val row = grid[r]
            while (row.size <= c) row.add(null)
            row[c] = if (text.startsWith("'")) text.substring(1) else text.toDoubleOrNull() ?: if (text == "true") true else if (text == "false") false else text.ifEmpty { null }
        }
        hidden += ops.hideColumns
        for ((c, k) in ops.formats) if (k.isEmpty()) formats.remove(c) else formats[c] = k
        for ((c, v) in ops.validations) if (v == null) validations.remove(c) else validations[c] = v
        for (r in ops.deleteRows.sortedDescending()) grid.removeAt(r)
        for (c in ops.deleteColumns.sortedDescending()) for (row in grid) if (c < row.size) row.removeAt(c)
        for ((from, dest) in ops.moveColumns) { val f = formats.toMap(); formats.clear(); for ((k, v) in f) { val i = (0..maxOf(k, from, dest) + 1).toMutableList(); val x = i.removeAt(from); i.add(if (from < dest) dest - 1 else dest, x); formats[i.indexOf(k)] = v } }
        for ((from, dest) in ops.moveColumns) for (row in grid) {
            while (row.size <= maxOf(from, dest)) row.add(null)
            val v = row.removeAt(from); row.add(if (from < dest) dest - 1 else dest, v)
        }
    }
    fun cell(r: Int, c: Int): Any? = grid.getOrNull(r)?.getOrNull(c)
    fun set(r: Int, c: Int, v: Any?) { while (grid.size <= r) grid.add(ArrayList()); val row = grid[r]; while (row.size <= c) row.add(null); row[c] = v }
}

var failures = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) failures++ }

fun main() {
    val dir = java.nio.file.Files.createTempDirectory("gsp")
    val ws = Workspace(dir)
    val sheet = FakeSheet()
    sheet.grid = arrayListOf(
        arrayListOf("Name", "Qty", "Done", "Notes"),
        arrayListOf("Apple", 3L, true, "red"),
        arrayListOf("Pear", 5L, false, null),
    )
    val engine = SyncEngine(ws, FakeGateway(sheet))
    val id = ws.createLinkedTable("T", Link("S", 0, "Tab", "u"))
    var res = runSync { engine.sync(id) }
    var g = ws.grid(id)!!
    check("import 4 columns", g.columns.map { it.name } == listOf("Name", "Qty", "Done", "Notes"))
    check("types", g.columns.map { it.type } == listOf(ColumnType.TEXT, ColumnType.NUMBER, ColumnType.CHECKBOX, ColumnType.TEXT))
    check("import 2 rows", g.rows.size == 2)
    check("values", g.rows[0].values.values.toSet() == setOf("Apple", "3", "true", "red"))
    check("ids written to sheet", sheet.cell(1, 4) is String && sheet.cell(2, 4) is String && 4 in sheet.hidden)

    // app edit goes to the sheet
    val qty = g.columns[1].id
    ws.setCell(id, g.rows[0].id, qty, "10")
    res = runSync { engine.sync(id) }
    check("app edit pushed", sheet.cell(1, 1) == 10.0 && res.pushed == 1)

    // sheet edit comes to the app
    sheet.set(2, 0, "Plum")
    res = runSync { engine.sync(id) }
    g = ws.grid(id)!!
    check("sheet edit pulled", g.rows[1].values[g.columns[0].id] == "Plum")

    // conflict: app wins
    ws.setCell(id, g.rows[0].id, g.columns[0].id, "Apple2")
    sheet.set(1, 0, "AppleSheet")
    res = runSync { engine.sync(id) }
    check("conflict app wins", sheet.cell(1, 0) == "Apple2" && res.conflicts == 1 && ws.conflicts(id).first().lost == "AppleSheet")

    // new row in app is appended
    val rid = ws.addRow(id)!!
    ws.setCell(id, rid, g.columns[0].id, "Fig")
    res = runSync { engine.sync(id) }
    check("new app row appended", sheet.grid.size == 4 && sheet.cell(3, 0) == "Fig" && sheet.cell(3, 4) is String)

    // new row in sheet comes in
    sheet.grid.add(arrayListOf("Kiwi", 1L, null, "green"))
    res = runSync { engine.sync(id) }
    g = ws.grid(id)!!
    check("new sheet row pulled", g.rows.size == 4 && g.rows.any { it.values[g.columns[0].id] == "Kiwi" } && sheet.cell(4, 4) is String)

    // delete in app removes from sheet (even after the Undo window)
    val snap = ws.deleteRow(id, g.rows[0].id)
    res = runSync { engine.sync(id) }
    check("app delete removes sheet row", sheet.grid.size == 4 && sheet.grid.none { it.getOrNull(0) == "Apple2" })
    g = ws.grid(id)!!
    check("3 rows left", g.rows.size == 3)

    // delete in sheet removes from app
    sheet.grid.removeAt(1)
    res = runSync { engine.sync(id) }
    g = ws.grid(id)!!
    check("sheet delete removes app row", g.rows.size == 2 && res.removed == 1)

    // sheet-only column discovered; app column pushed; app rename pushed
    sheet.set(0, 5, "Extra"); sheet.set(1, 5, "x"); sheet.set(2, 5, "y")
    ws.addColumn(id, "Price", ColumnType.NUMBER)
    ws.updateColumn(id, g.columns[3].id, "Remarks", ColumnType.TEXT, "", "", "", emptyList(), "")
    res = runSync { engine.sync(id) }
    g = ws.grid(id)!!
    check("discovered column", g.columns.any { it.name == "Extra" })
    check("app column header pushed", (sheet.grid[0].map { it.text() }).contains("Price"))
    check("rename pushed", sheet.cell(0, 3) == "Remarks")

    // delete column in app deletes it in sheet
    ws.deleteColumn(id, g.columns.first { it.name == "Extra" }.id)
    res = runSync { engine.sync(id) }
    check("column delete pushed", !sheet.grid[0].map { it.text() }.contains("Extra"))

    // header renamed in the Sheet (e.g. by the Android app) -> same column, new name
    run {
        val before = ws.grid(id)!!
        val qtyId = before.columns.first { it.name == "Qty" }.id
        val hc = sheet.grid[0].indexOfFirst { it.text() == "Qty" }
        sheet.set(0, hc, "Quantity")
        runSync { engine.sync(id) }
        val after = ws.grid(id)!!
        check("sheet rename keeps column", after.columns.any { it.id == qtyId && it.name == "Quantity" })
        check("sheet rename adds no duplicate", after.columns.size == before.columns.size)
        check("sheet rename keeps data", after.rows.any { it.values[qtyId] != null })
    }
    // columns moved in the Sheet -> app order follows
    run {
        val names = { sheet.grid[0].map { it.text() } }
        val a = names().indexOf("Name"); val b = names().indexOf("Quantity")
        for (row in sheet.grid) { val t = row.getOrNull(a); row[a] = row.getOrNull(b); row[b] = t }
        runSync { engine.sync(id) }
        val order = ws.grid(id)!!.columns.map { it.name }
        check("sheet move reorders app", order.indexOf("Quantity") < order.indexOf("Name"))
        check("sheet move keeps values", ws.grid(id)!!.rows.any { r -> r.values.values.contains("Kiwi") })
        // move back so the later checks see the original order
        val a2 = names().indexOf("Name"); val b2 = names().indexOf("Quantity")
        for (row in sheet.grid) { val t = row.getOrNull(a2); row[a2] = row.getOrNull(b2); row[b2] = t }
        runSync { engine.sync(id) }
        check("sheet move back", ws.grid(id)!!.columns.map { it.name }.indexOf("Name") == 0)
    }

    // table synced by an older version (no remembered positions): the Sheet's order is taken once
    run {
        val names = { sheet.grid[0].map { it.text() } }
        val a = names().indexOf("Name"); val b = names().indexOf("Quantity")
        for (row in sheet.grid) { val t = row.getOrNull(a); row[a] = row.getOrNull(b); row[b] = t }
        ws.withTable(id) { t -> t.columns.forEach { it.sheetIndex = null } }
        runSync { engine.sync(id) }
        val order = ws.grid(id)!!.columns.map { it.name }
        check("legacy table takes sheet order", order.indexOf("Quantity") < order.indexOf("Name"))
        for (row in sheet.grid) { val t = row.getOrNull(a); row[a] = row.getOrNull(b); row[b] = t }
        runSync { engine.sync(id) }
    }
    // column moved in the app -> moved in the Sheet
    run {
        val before = ws.grid(id)!!
        val firstId = before.columns[0].id
        ws.moveColumn(id, firstId, 1)
        runSync { engine.sync(id) }
        val h = sheet.grid[0].map { it.text() }
        val moved = ws.grid(id)!!.columns.map { it.name }
        check("app move order kept", moved.indexOf(before.columns[0].name) == 1)
        check("app move reaches sheet", h.indexOf(before.columns[0].name) > h.indexOf(before.columns[1].name))
        check("app move keeps data", sheet.grid.any { it.contains("Kiwi") } && ws.grid(id)!!.rows.any { r -> r.values.values.contains("Kiwi") })
        // a second sync changes nothing
        val snap = sheet.grid.map { it.toList() }
        runSync { engine.sync(id) }
        check("move is stable", sheet.grid.map { it.toList() } == snap && ws.grid(id)!!.columns.map { it.name } == moved)
        // and move it back
        ws.moveColumn(id, firstId, -1)
        runSync { engine.sync(id) }
        val h2 = sheet.grid[0].map { it.text() }
        check("move back", h2.indexOf(before.columns[0].name) < h2.indexOf(before.columns[1].name))
    }
    // date/time type follows the Sheet's format, both ways
    run {
        val g0 = ws.grid(id)!!
        val col = g0.columns.first { it.name == "Remarks" }
        val ci = sheet.grid[0].indexOfFirst { it.text() == "Remarks" }
        sheet.set(1, ci, 46328.0)                       // a date typed in the Sheet, still read as text here
        runSync { engine.sync(id) }
        check("legacy text holds the number", ws.grid(id)!!.rows.any { it.values[col.id] == "46328" })
        sheet.formats[ci] = "DATE"                      // the phone changed the column to Date
        runSync { engine.sync(id) }
        val gd = ws.grid(id)!!
        check("sheet date format -> app Date", gd.columns.first { it.name == "Remarks" }.type == ColumnType.DATE)
         println(sheet.grid.map { it.getOrNull(sheet.grid[0].indexOfFirst { h -> h.text() == "Remarks" }) })
        check("old number text becomes a date", gd.rows.any { r -> r.values[col.id] == "2026-11-02" })
        // a later edit of the date on the phone (new serial in the Sheet) reaches the app
        sheet.set(1, ci, 46330.0)
        runSync { engine.sync(id) }
        check("date edited in Sheet reaches app", ws.grid(id)!!.rows.any { r -> r.values[col.id] == "2026-11-04" })
        // and an edit in the app reaches the Sheet
        ws.setCell(id, ws.grid(id)!!.rows[0].id, col.id, "2026-12-25")
        runSync { engine.sync(id) }
        check("date edited in app reaches Sheet", sheet.cell(1, ci).toString().let { it == "2026-12-25" || it == "46751.0" })
        // app changes a column to Time -> Sheet gets the format
        val qc = gd.columns.first { it.name == "Quantity" }
        ws.updateColumn(id, qc.id, "Quantity", ColumnType.TIME, "", "", "", emptyList(), "")
        runSync { engine.sync(id) }
        check("app Time -> sheet format", sheet.formats[sheet.grid[0].indexOfFirst { it.text() == "Quantity" }] == "TIME")
        // and back to Number clears it
        ws.updateColumn(id, qc.id, "Quantity", ColumnType.NUMBER, "", "", "", emptyList(), "")
        runSync { engine.sync(id) }
        check("app Number clears sheet format", sheet.formats[sheet.grid[0].indexOfFirst { it.text() == "Quantity" }] == null)
        check("type stays Number", ws.grid(id)!!.columns.first { it.name == "Quantity" }.type == ColumnType.NUMBER)
        // restore the column so later checks are unaffected
        ws.updateColumn(id, col.id, "Remarks", ColumnType.TEXT, "", "", "", emptyList(), "")
        sheet.formats.clear(); runSync { engine.sync(id) }
    }
    // file column type follows the Sheet's header note, both ways
    run {
        val g1 = ws.grid(id)!!
        val nameCol = g1.columns.first { it.name == "Name" }
        val ci = sheet.grid[0].indexOfFirst { it.text() == "Name" }
        sheet.formats[ci] = "FILE"
        runSync { engine.sync(id) }
        check("sheet file note -> app File", ws.grid(id)!!.columns.first { it.name == "Name" }.type == ColumnType.FILE)
        ws.updateColumn(id, nameCol.id, "Name", ColumnType.TEXT, "", "", "", emptyList(), "")
        runSync { engine.sync(id) }
        check("app Text clears the note", sheet.formats[ci] == null && ws.grid(id)!!.columns.first { it.name == "Name" }.type == ColumnType.TEXT)
        ws.updateColumn(id, nameCol.id, "Name", ColumnType.AUDIO, "", "", "", emptyList(), "")
        runSync { engine.sync(id) }
        check("app Audio writes the note", sheet.formats[ci] == "AUDIO")
        ws.updateColumn(id, nameCol.id, "Name", ColumnType.TEXT, "", "", "", emptyList(), "")
        runSync { engine.sync(id) }
    }
    // text that looks like a number stays text
    val c0 = ws.grid(id)!!.columns[0].id
    ws.setCell(id, ws.grid(id)!!.rows[0].id, c0, "0300123")
    runSync { engine.sync(id) }
    check("leading zero kept", sheet.grid.any { it.getOrNull(0) == "0300123" })

    // undo restore
    val snap2 = ws.deleteRow(id, ws.grid(id)!!.rows[0].id)!!
    ws.restoreRow(id, snap2)
    runSync { engine.sync(id) }
    check("restored row back in sheet", sheet.grid.size == 3)

    // persistence
    ws.flush()
    val ws2 = Workspace(dir)
    check("reload", ws2.grid(id)!!.rows.size == ws.grid(id)!!.rows.size && ws2.link(id)?.spreadsheetId == "S")

    // clipboard
    val grid = ws.grid(id)!!
    val clip = buildClip(grid.rows.take(2), grid.columns.take(2), emptyList(), false)
    val r2 = ws.pasteCells(id, clip, grid.rows.map { it.id }, grid.columns.map { it.id })
    check("paste block", r2.pasted == 4)
    val r3 = ws.pasteAsRows(id, clip)
    check("paste rows", r3.rowsAdded == 2 && ws.grid(id)!!.rows.size == grid.rows.size + 2)
    check("text parse", parseClipText("a\tb\nc\td").cols == 2)
    check("date text detected", SheetImport.looksLikeDate("2026-11-03") && SheetImport.looksLikeDate("11/3/2026") && SheetImport.looksLikeDate("3 Nov 2026") && SheetImport.looksLikeDate("Nov 3, 2026"))
    check("not a date", !SheetImport.looksLikeDate("46329") && !SheetImport.looksLikeDate("hello") && !SheetImport.looksLikeDate("1.5"))
    check("time text detected", SheetImport.looksLikeTime("08:49") && SheetImport.looksLikeTime("8:49 AM") && !SheetImport.looksLikeTime("2026-11-03"))
    println(if (failures == 0) "ALL OK" else "$failures FAILED")
}

class FakeGateway(val s: FakeSheet) : SheetsGateway by s
