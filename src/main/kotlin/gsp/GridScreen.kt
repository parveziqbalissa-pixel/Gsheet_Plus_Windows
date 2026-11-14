package gsp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import java.awt.Desktop
import java.net.URI
import kotlin.math.roundToInt

/** A rectangle of selected cells: one corner is where the mouse went down, the other follows the mouse. */
private class Sel(val anchorRow: Long, val anchorCol: Long, val endRow: Long, val endCol: Long)

private val CellHeight = 32.dp
private val RowNumberWidth = 52.dp

private fun Modifier.pin(pinned: Boolean, scroll: androidx.compose.foundation.ScrollState): Modifier =
    if (pinned) this.offset { IntOffset(scroll.value, 0) }.zIndex(1f) else this

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun GridScreen(c: Controller, tableId: Long, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        val stop = c.ws.changes.listen { tick = it }
        onDispose { stop() }
    }
    val grid = remember(tick) { c.ws.grid(tableId) }
    if (grid == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val link = remember(tick) { c.ws.link(tableId) }
    val attachments = remember(tick) { c.ws.attachments(tableId) }
    val rules = remember(tick) { c.ws.rules(tableId) }
    val savedViews = remember(tick) { c.ws.views(tableId) }
    var mode by remember(tableId) { mutableStateOf("table") }
    var activeView by remember(tableId) { mutableStateOf<String?>(null) }
    var rowPage by remember { mutableStateOf<Long?>(null) }
    var chartOpen by remember { mutableStateOf(false) }
    var rulesOpen by remember { mutableStateOf(false) }
    var saveViewOpen by remember { mutableStateOf(false) }
    var viewsMenu by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(ViewOptions()) }
    val shown = remember(grid, view) { applyView(grid, view) }
    val hiddenColumns = remember(tick) { c.ws.hiddenColumns(tableId) }
    val columns = remember(grid.columns, hiddenColumns) { grid.columns.filter { it.id !in hiddenColumns }.sortedBy { if (it.pinned) 0 else 1 } }
    var manageColumns by remember { mutableStateOf(false) }
    val groupColumn = view.groupBy?.let { id -> columns.firstOrNull { it.id == id } }
    val groups = remember(shown, groupColumn) {
        groupColumn?.let { gc -> shown.rows.groupBy { it.values[gc.id].orEmpty().ifEmpty { "(empty)" } }.entries.toList() }
    }
    val ordered = remember(shown, groups) { groups?.flatMap { it.value } ?: shown.rows }

    var sel by remember { mutableStateOf<Sel?>(null) }
    var editing by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var editStart by remember { mutableStateOf<String?>(null) }
    var cellMenu by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var renamingTable by remember { mutableStateOf(false) }
    var addingColumn by remember { mutableStateOf(false) }
    var editingColumn by remember { mutableStateOf<GridColumn?>(null) }
    var deletingColumn by remember { mutableStateOf<GridColumn?>(null) }
    var filteringColumn by remember { mutableStateOf<GridColumn?>(null) }
    var styleColumn by remember { mutableStateOf<GridColumn?>(null) }
    var colorRow by remember { mutableStateOf<Long?>(null) }
    var attachTarget by remember { mutableStateOf<Pair<Long, GridColumn>?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var widthColumn by remember { mutableStateOf<GridColumn?>(null) }
    var mediaPaste by remember { mutableStateOf<Triple<GridColumn, Long, PastedMedia>?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    val liveWidths = remember { mutableStateMapOf<Long, Int>() }
    val liveHeights = remember { mutableStateMapOf<Long, Int>() }
    val aggregates = remember { mutableStateMapOf<Long, Aggregate>() }
    val hs = rememberScrollState()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var clip by remember { mutableStateOf(CellClipboard.current.value) }
    DisposableEffect(Unit) {
        val stop = CellClipboard.current.listen { clip = it }
        onDispose { stop() }
    }

    LaunchedEffect(link != null) {
        if (link != null) {
            c.syncNow(tableId, announce = false)
            while (true) {
                val seconds = c.syncSeconds.value
                kotlinx.coroutines.delay(if (seconds > 0) seconds * 1000L else 2000L)
                if (c.syncSeconds.value > 0) c.syncNow(tableId, announce = false)
            }
        }
    }

    fun widthOf(col: GridColumn): Int = liveWidths[col.id] ?: col.width

    fun block(): Pair<List<GridRow>, List<GridColumn>>? {
        val s = sel ?: return null
        val r1 = ordered.indexOfFirst { it.id == s.anchorRow }
        val r2 = ordered.indexOfFirst { it.id == s.endRow }
        val c1 = columns.indexOfFirst { it.id == s.anchorCol }
        val c2 = columns.indexOfFirst { it.id == s.endCol }
        if (r1 < 0 || r2 < 0 || c1 < 0 || c2 < 0) return null
        return ordered.subList(minOf(r1, r2), maxOf(r1, r2) + 1) to columns.subList(minOf(c1, c2), maxOf(c1, c2) + 1)
    }

    fun isSelected(rowId: Long, colId: Long): Boolean {
        val s = sel ?: return false
        val r1 = ordered.indexOfFirst { it.id == s.anchorRow }
        val r2 = ordered.indexOfFirst { it.id == s.endRow }
        val c1 = columns.indexOfFirst { it.id == s.anchorCol }
        val c2 = columns.indexOfFirst { it.id == s.endCol }
        val r = ordered.indexOfFirst { it.id == rowId }
        val cc = columns.indexOfFirst { it.id == colId }
        return r in minOf(r1, r2)..maxOf(r1, r2) && cc in minOf(c1, c2)..maxOf(c1, c2)
    }

    fun copySelection() {
        val (rows, cols) = block() ?: return
        CellClipboard.set(buildClip(rows, cols, attachments, wholeRows = false))
        c.say(if (rows.size == 1 && cols.size == 1) "Copied cell" else "Copied ${rows.size * cols.size} cells")
    }

    fun pasteMedia(col: GridColumn, rowId: Long, media: PastedMedia) {
        var added = 0
        var skipped = 0
        for (p in media.paths) {
            val mime = mimeFromName(p.fileName.toString())
            if (!attachmentFits(mime, col)) { skipped++; continue }
            if (c.edit(tableId) { c.ws.addAttachmentFile(tableId, rowId, col.id, p, mime) }) added++ else skipped++
        }
        media.cleanUp()
        c.say(if (added > 0) "Pasted $added ${if (added == 1) "item" else "items"}" + (if (skipped > 0) ", skipped $skipped" else "") else "Nothing fits this column")
    }

    fun pasteAtSelection() {
        val active = sel
        if (active != null) {
            val media = SystemClipboard.readMedia()
            if (media != null) {
                val col = columns.firstOrNull { it.id == active.endCol }
                if (col == null) { media.cleanUp(); return }
                if (col.locked) { media.cleanUp(); c.say("This column cannot be edited."); return }
                if (col.type.isAttachment) pasteMedia(col, active.endRow, media) else mediaPaste = Triple(col, active.endRow, media)
                return
            }
        }
        val (rows, cols) = block() ?: return
        val copied = CellClipboard.resolve() ?: return
        val r0 = ordered.indexOfFirst { it.id == rows.first().id }
        val c0 = columns.indexOfFirst { it.id == cols.first().id }
        val single = copied.rows == 1 && copied.cols == 1
        val rowIds = if (single) rows.map { it.id } else ordered.drop(r0).map { it.id }
        val colIds = if (single) cols.map { it.id } else columns.drop(c0).map { it.id }
        val result = c.edit(tableId) { c.ws.pasteCells(tableId, copied, rowIds, colIds) }
        c.say(result.message())
    }

    fun clearSelection() {
        val (rows, cols) = block() ?: return
        val n = c.edit(tableId) { c.ws.clearCells(tableId, rows.map { it.id }, cols.map { it.id }) }
        c.say("Cleared $n ${if (n == 1) "cell" else "cells"}")
    }

    fun startEdit(rowId: Long, colId: Long, text: String? = null) {
        val col = columns.firstOrNull { it.id == colId } ?: return
        if (col.locked) return
        when {
            col.type.isAttachment -> attachTarget = rowId to col
            col.type == ColumnType.LIST || col.type == ColumnType.CHECKBOX -> cellMenu = rowId to colId
            else -> { editing = rowId to colId; editStart = text }
        }
    }

    fun move(dRow: Int, dCol: Int, extend: Boolean) {
        val s = sel ?: return
        val r = ordered.indexOfFirst { it.id == s.endRow }
        val cc = columns.indexOfFirst { it.id == s.endCol }
        if (r < 0 || cc < 0) return
        val nr = (r + dRow).coerceIn(0, ordered.lastIndex)
        val nc = (cc + dCol).coerceIn(0, columns.lastIndex)
        val rowId = ordered[nr].id
        val colId = columns[nc].id
        sel = if (extend) Sel(s.anchorRow, s.anchorCol, rowId, colId) else Sel(rowId, colId, rowId, colId)
    }

    fun commit(rowId: Long, colId: Long, raw: String) {
        val ok = c.edit(tableId) { c.ws.setCell(tableId, rowId, colId, raw) }
        if (!ok) c.say("That value does not fit this column.")
        editing = null
        editStart = null
        focus.requestFocus()
    }

    val numbered = remember(ordered) { ordered.withIndex().associate { it.value.id to it.index + 1 } }
    val rowContent: @Composable (GridRow) -> Unit = { row ->
        DataRow(
            pictureHeight = c.pictureRows.value,
            number = numbered[row.id] ?: 0, row = row, columns = columns, widthOf = ::widthOf, hs = hs,
            isSelected = ::isSelected, editing = editing, editStart = editStart, attachments = attachments,
            cellMenu = cellMenu,
            onPress = { colId, shift ->
                val s = sel
                sel = if (shift && s != null) Sel(s.anchorRow, s.anchorCol, row.id, colId) else Sel(row.id, colId, row.id, colId)
                if (editing != null && editing != (row.id to colId)) editing = null
                focus.requestFocus()
            },
            onDrag = { colId -> sel = sel?.let { Sel(it.anchorRow, it.anchorCol, row.id, colId) } },
            onRightClick = { colId ->
                if (!isSelected(row.id, colId)) sel = Sel(row.id, colId, row.id, colId)
                cellMenu = row.id to colId
            },
            onDouble = { colId -> startEdit(row.id, colId) },
            onCommit = { colId, raw -> commit(row.id, colId, raw) },
            onCancelEdit = { editing = null; editStart = null; focus.requestFocus() },
            onCloseMenu = { cellMenu = null },
            onMenuCopy = { copySelection() },
            onMenuPaste = { pasteAtSelection() },
            onMenuClear = { clearSelection() },
            onMenuEdit = { colId -> startEdit(row.id, colId) },
            onRowSelect = { if (columns.isNotEmpty()) sel = Sel(row.id, columns.first().id, row.id, columns.last().id); focus.requestFocus() },
            onOpenRow = { rowPage = row.id },
            paint = paintRow(row, columns, rules),
            onCopyRow = {
                CellClipboard.set(buildClip(listOf(row), columns, attachments, wholeRows = true))
                c.say("Copied row")
            },
            onPasteRows = {
                val copied = CellClipboard.resolve()
                if (copied != null) c.say(c.edit(tableId) { c.ws.pasteAsRows(tableId, copied) }.message())
            },
            onRowColor = { colorRow = row.id },
            onDeleteRow = { c.deleteRow(tableId, row.id) },
            onOpenAttachments = { col -> attachTarget = row.id to col },
            sheetLink = link?.url,
            rowHeightDp = liveHeights[row.id] ?: row.height,
            onRowHeight = { h -> liveHeights[row.id] = h },
            onRowHeightEnd = { h -> liveHeights[row.id] = h; c.ws.setRowHeight(tableId, row.id, h) },
            onRowHeightReset = { liveHeights.remove(row.id); c.ws.setRowHeight(tableId, row.id, null) },
            onToggleList = { colId, value -> commit(row.id, colId, value) },
        )
    }

    fun dropFiles(files: List<java.io.File>) {
        val active = sel
        val usable = files.filter { it.isFile }
        if (usable.isEmpty()) return
        if (active == null || mode != "table") { c.say("Click a cell first, then drop the files on the window."); return }
        val col = columns.firstOrNull { it.id == active.endCol } ?: return
        val media = PastedMedia(usable.map { it.toPath() }, false)
        if (col.locked) { c.say("This column cannot be edited."); return }
        if (col.type.isAttachment) pasteMedia(col, active.endRow, media) else mediaPaste = Triple(col, active.endRow, media)
    }

    fun pictureUrl(row: GridRow, col: GridColumn): String? {
        val first = attachmentLinks(row.values[col.id].orEmpty()).firstOrNull() ?: return null
        val a = attachments.firstOrNull { it.rowId == row.id && it.columnId == col.id && it.name == first } ?: return null
        a.localPath?.let { p -> java.io.File(p).takeIf { it.exists() }?.let { return it.toURI().toString() } }
        return a.driveId?.let { "https://drive.google.com/thumbnail?id=$it&sz=w320" }
    }

    fun openReport() {
        val title = grid.name + (activeView?.let { " - $it" } ?: "")
        c.openPage(title, reportHtml(title, c.reportHeader.value, columns, ordered, ::pictureUrl))
        if (c.reportHeader.value.business.isBlank()) c.say("Tip: add your business name and logo in Settings > Report")
    }

    fun openMap() {
        val address = columns.firstOrNull { it.type == ColumnType.ADDRESS }
        if (address == null) { c.say("Add an Address column to see rows on a map."); return }
        val title = titleColumn(columns)
        val places = ordered.mapNotNull { r ->
            val a = r.values[address.id].orEmpty().trim()
            if (a.isEmpty()) null else (title?.let { r.values[it.id] }?.takeIf { it.isNotBlank() } ?: "Row") to a
        }
        if (places.isEmpty()) { c.say("No addresses in the rows shown."); return }
        c.openPage(grid.name + " map", mapHtml(grid.name, places))
    }

    // Files dragged from Explorer onto the window go into the selected cell.
    val dropNow by rememberUpdatedState<(List<java.io.File>) -> Unit>({ files -> dropFiles(files) })
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transfer = event.awtTransferable
                if (!transfer.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = runCatching {
                    transfer.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                }.getOrNull().orEmpty()
                if (files.isEmpty()) return false
                dropNow(files)
                return true
            }
        }
    }
    val dropModifier = Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)

    Column(Modifier.fillMaxSize().then(dropModifier)) {
        // ----- top bar -----
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onBack) { Text("‹ Tables") }
            Text(grid.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = view.search, onValueChange = { view = view.copy(search = it) },
                placeholder = { Text("Search all columns") }, singleLine = true, modifier = Modifier.width(240.dp),
            )
            TextButton(onClick = { c.edit(tableId) { c.ws.addRow(tableId) } }) { Text("+ Row") }
            TextButton(onClick = { addingColumn = true }) { Text("+ Column") }
            if (link != null) {
                TextButton(onClick = { c.syncNow(tableId) }, enabled = !c.syncing.value) { Text("Sync") }
            }
            Box {
                TextButton(onClick = { moreMenu = true }) { Text("More ▾") }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text("Manage columns") }, onClick = { moreMenu = false; manageColumns = true })
                    DropdownMenuItem(text = { Text("Rename table") }, onClick = { moreMenu = false; renamingTable = true })
                    DropdownMenuItem(text = { Text("Export CSV") }, onClick = { moreMenu = false; Export.csv(c, grid, shown) })
                    DropdownMenuItem(text = { Text("Export Excel (.xlsx)") }, onClick = { moreMenu = false; Export.xlsx(c, grid, shown) })
                    if (view.groupBy != null) {
                        DropdownMenuItem(text = { Text("Stop grouping") }, onClick = { moreMenu = false; view = view.copy(groupBy = null) })
                    }
                    if (view.filters.isNotEmpty() || view.search.isNotEmpty() || view.sort != null) {
                        DropdownMenuItem(text = { Text("Clear search, filters and sort") }, onClick = { moreMenu = false; view = ViewOptions(groupBy = view.groupBy, collapsed = view.collapsed) })
                    }
                    if (link == null) {
                        DropdownMenuItem(
                            text = { Text("Create Google Sheet from table") },
                            onClick = { moreMenu = false; Linking.createSheet(c, tableId) },
                        )
                    } else {
                        DropdownMenuItem(text = { Text("Sync history") }, onClick = { moreMenu = false; historyOpen = true })
                        DropdownMenuItem(
                            text = { Text("Open in Google Sheets") },
                            onClick = { moreMenu = false; runCatching { Desktop.getDesktop().browse(URI(link.url)) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Show last row in Google Sheets") },
                            onClick = {
                                moreMenu = false
                                val lr = Regex("Sheet's last row: (\\d+)").find(c.status.value)?.groupValues?.get(1)
                                runCatching { Desktop.getDesktop().browse(URI(link.url + (if (lr != null) "&range=A$lr" else ""))) }
                            },
                        )
                    }
                }
            }
        }
        if (c.syncing.value) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (link != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    if (c.signedIn.value) c.status.value.ifEmpty { "Linked to Google Sheet" } else "Not signed in - changes are kept here until you sign in",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
        if (view.filters.isNotEmpty() || view.search.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Showing ${shown.rows.size} of ${grid.rows.size} rows", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { view = ViewOptions(sort = view.sort, groupBy = view.groupBy, collapsed = view.collapsed) }) { Text("Clear") }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("table" to "Table", "cards" to "Cards", "calendar" to "Calendar").forEach { (m, label) ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) })
            }
            Box {
                TextButton(onClick = { viewsMenu = true }) { Text("View: " + (activeView ?: "All rows") + "  ▾") }
                DropdownMenu(expanded = viewsMenu, onDismissRequest = { viewsMenu = false }) {
                    DropdownMenuItem(text = { Text("All rows") }, onClick = { viewsMenu = false; activeView = null; view = ViewOptions() })
                    savedViews.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.name) },
                            onClick = { viewsMenu = false; activeView = v.name; view = v.options },
                            trailingIcon = {
                                TextButton(onClick = {
                                    viewsMenu = false
                                    c.ws.deleteView(tableId, v.name)
                                    if (activeView == v.name) activeView = null
                                }) { Text("✕") }
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Save current search, filters and sort as a view...") }, onClick = { viewsMenu = false; saveViewOpen = true })
                }
            }
            TextButton(onClick = { chartOpen = true }) { Text("Chart") }
            TextButton(onClick = { rulesOpen = true }) { Text("Colour rules" + if (rules.isNotEmpty()) " (${rules.size})" else "") }
            TextButton(onClick = { openReport() }) { Text("Report / PDF") }
            if (columns.any { it.type == ColumnType.ADDRESS }) TextButton(onClick = { openMap() }) { Text("Map") }
        }

        if (mode == "cards") {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CardsView(ordered, columns, attachments, rules) { rowPage = it }
            }
        } else if (mode == "calendar") {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CalendarView(ordered, columns, rules, onOpen = { rowPage = it }, onAdd = { date, col ->
                    val id = c.edit(tableId) { c.ws.addRow(tableId) }
                    if (id != null) {
                        c.ws.setCell(tableId, id, col.id, date.toString())
                        rowPage = id
                    }
                })
            }
        } else {
        // ----- table -----
        val totalWidth = (RowNumberWidth.value + columns.sumOf { widthOf(it) } + 40).dp
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || editing != null) return@onPreviewKeyEvent false
                    val current = sel
                    when {
                        e.isCtrlPressed && e.key == Key.C -> { copySelection(); true }
                        e.isCtrlPressed && e.key == Key.V -> { pasteAtSelection(); true }
                        e.isCtrlPressed && e.key == Key.X -> { copySelection(); clearSelection(); true }
                        e.key == Key.Delete || e.key == Key.Backspace -> { clearSelection(); true }
                        e.key == Key.DirectionDown -> { move(1, 0, false); true }
                        e.key == Key.DirectionUp -> { move(-1, 0, false); true }
                        e.key == Key.DirectionLeft -> { move(0, -1, false); true }
                        e.key == Key.DirectionRight || e.key == Key.Tab -> { move(0, 1, false); true }
                        (e.key == Key.Enter || e.key == Key.F2) && current != null -> { startEdit(current.endRow, current.endCol); true }
                        e.key == Key.Escape -> { sel = null; true }
                        current != null && !e.isCtrlPressed && !e.isAltPressed && e.utf16CodePoint >= 32 && isTypedCharacter(e.utf16CodePoint) && e.key != Key.Spacebar -> {
                            startEdit(current.endRow, current.endCol, String(Character.toChars(e.utf16CodePoint))); true
                        }
                        else -> false
                    }
                },
        ) {
            Column(Modifier.fillMaxSize().horizontalScroll(hs)) {
                Row {
                    Box(Modifier.pin(true, hs).width(RowNumberWidth).height(CellHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("#") }
                    columns.forEach { col ->
                        Box(Modifier.pin(col.pinned, hs)) {
                            HeaderCell(
                                col = col, width = widthOf(col),
                                sortDirection = view.sort?.takeIf { it.columnId == col.id }?.direction,
                                filtered = view.filters.containsKey(col.id), grouped = view.groupBy == col.id,
                                onSort = { d -> view = view.copy(sort = d?.let { SortSpec(col.id, it) }) },
                                onFilter = { filteringColumn = col },
                                onGroup = { view = view.copy(groupBy = if (view.groupBy == col.id) null else col.id) },
                                onPin = { c.edit(tableId) { c.ws.setColumnPinned(tableId, col.id, !col.pinned) } },
                                onMove = { d -> c.edit(tableId) { c.ws.moveColumn(tableId, col.id, d) } },
                                onStyle = { styleColumn = col },
                                onSettings = { editingColumn = col },
                                onWidth = { widthColumn = col },
                                onAutoFit = {
                                    val w = autoWidth(col, shown.rows)
                                    liveWidths[col.id] = w
                                    c.ws.setColumnWidth(tableId, col.id, w)
                                },
                                onDelete = { deletingColumn = col },
                                onSelect = {
                                    if (ordered.isNotEmpty()) sel = Sel(ordered.first().id, col.id, ordered.last().id, col.id)
                                    focus.requestFocus()
                                },
                                onResize = { w -> liveWidths[col.id] = w },
                                onResizeEnd = { w -> liveWidths[col.id] = w; c.ws.setColumnWidth(tableId, col.id, w) },
                            )
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).width(totalWidth), state = listState) {
                    if (shown.rows.isEmpty()) {
                        item { Text(if (grid.rows.isEmpty()) "No rows yet. Click \"+ Row\"." else "No rows match.", modifier = Modifier.padding(16.dp)) }
                    }
                    if (groups == null) {
                        items(ordered, key = { it.id }) { row -> rowContent(row) }
                    } else {
                        groups.forEach { (title, groupRows) ->
                            val collapsed = title in view.collapsed
                            item(key = "group:$title") {
                                Box(
                                    Modifier.width(totalWidth).height(34.dp).background(MaterialTheme.colorScheme.secondaryContainer)
                                        .combinedClickable(onClick = {
                                            view = view.copy(collapsed = if (collapsed) view.collapsed - title else view.collapsed + title)
                                        }),
                                ) {
                                    Text(
                                        (if (collapsed) "▸ " else "▾ ") + title + "  (${groupRows.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.offset { IntOffset(hs.value, 0) }.align(Alignment.CenterStart).padding(horizontal = 12.dp),
                                    )
                                }
                            }
                            if (!collapsed) {
                                items(groupRows, key = { it.id }) { row -> rowContent(row) }
                            }
                        }
                    }
                }
                if (columns.any { it.isNumeric }) {
                    Row {
                        Box(Modifier.pin(true, hs).width(RowNumberWidth).height(CellHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("Σ") }
                        columns.forEach { col ->
                            Box(Modifier.pin(col.pinned, hs)) {
                                val agg = aggregates[col.id] ?: Aggregate.SUM
                                Column(
                                    Modifier.width(widthOf(col).dp).height(CellHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .combinedClickable(enabled = col.isNumeric, onClick = {
                                            val all = Aggregate.entries
                                            aggregates[col.id] = all[(all.indexOf(agg) + 1) % all.size]
                                        })
                                        .padding(horizontal = 8.dp),
                                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.End,
                                ) {
                                    if (col.isNumeric) {
                                        Text(agg.label, style = MaterialTheme.typography.labelSmall)
                                        Text(aggregateValue(col, shown.rows, agg), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 14.dp),
                style = LocalScrollbarStyle.current.copy(thickness = 12.dp),
            )
            HorizontalScrollbar(
                rememberScrollbarAdapter(hs),
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 14.dp),
                style = LocalScrollbarStyle.current.copy(thickness = 12.dp),
            )
        }

        }

        // ----- bottom bar -----
        val b = block()
        if (b != null && (b.first.size > 1 || b.second.size > 1)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.first.size * b.second.size} cells selected (${b.first.size} x ${b.second.size})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copySelection() }) { Text("Copy") }
                    TextButton(onClick = { pasteAtSelection() }) { Text("Paste") }
                    TextButton(onClick = { clearSelection() }) { Text("Clear") }
                    TextButton(onClick = { sel = null }) { Text("Done") }
                }
            }
        }
        clip?.let { copied ->
            val what = when {
                copied.wholeRows -> if (copied.rows == 1) "Row" else "${copied.rows} rows"
                copied.rows == 1 && copied.cols == 1 -> "Cell"
                else -> "${copied.rows} x ${copied.cols} cells"
            }
            val preview = copied.toText().replace('\n', ' ').replace('\t', ' ').let { if (it.length > 60) it.take(60) + "..." else it }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Copied $what: ${preview.ifEmpty { "(empty)" }}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { CellClipboard.clear() }) { Text("Cancel") }
                }
            }
        }
    }

    // ----- dialogs -----
    if (renamingTable) NameDialog("Rename table", "Table name", "Save", grid.name, { renamingTable = false; c.ws.renameTable(tableId, it) }, { renamingTable = false })
    if (addingColumn) {
        ColumnDialog(null, { col ->
            addingColumn = false
            c.edit(tableId) { c.ws.addColumn(tableId, col.name, col.type, col.prefix, col.suffix, col.defaultValue, col.listItems, col.formula) }
        }, { addingColumn = false })
    }
    editingColumn?.let { col ->
        ColumnDialog(col, { n ->
            editingColumn = null
            c.edit(tableId) { c.ws.updateColumn(tableId, col.id, n.name, n.type, n.prefix, n.suffix, n.defaultValue, n.listItems, n.formula) }
        }, { editingColumn = null })
    }
    deletingColumn?.let { col ->
        ConfirmDialog("Delete column?", "Column \"${col.name}\" and all of its data will be permanently deleted.", "Delete", {
            deletingColumn = null
            c.edit(tableId) { c.ws.deleteColumn(tableId, col.id) }
        }, { deletingColumn = null })
    }
    filteringColumn?.let { col ->
        FilterDialog(
            col, view.filters[col.id],
            onApply = { spec -> filteringColumn = null; view = view.copy(filters = view.filters + (col.id to spec)) },
            onRemove = { filteringColumn = null; view = view.copy(filters = view.filters - col.id) },
            onDismiss = { filteringColumn = null },
        )
    }
    styleColumn?.let { col ->
        ColumnStyleDialog(col, { bg, text, bold, italic ->
            styleColumn = null
            c.ws.setColumnStyle(tableId, col.id, bg, text, bold, italic)
        }, { styleColumn = null })
    }
    colorRow?.let { id ->
        RowColorDialog(grid.rows.firstOrNull { it.id == id }?.color, { color -> colorRow = null; c.ws.setRowColor(tableId, id, color) }, { colorRow = null })
    }
    attachTarget?.let { (rowId, col) ->
        AttachmentDialog(c, tableId, rowId, col, attachments.filter { it.rowId == rowId && it.columnId == col.id }, { attachTarget = null })
    }
    mediaPaste?.let { (col, rowId, media) ->
        val target = if (media.allImages) ColumnType.PICTURE else ColumnType.FILE
        val what = if (media.allImages) "picture" else "file"
        ConfirmDialog(
            "Change column?",
            "\"${col.name}\" is a ${col.type.label} column. Change it to ${target.label} and paste ${media.paths.size} $what(s) here? Text already in the column stays as names.",
            "Change and paste",
            {
                mediaPaste = null
                c.edit(tableId) { c.ws.updateColumn(tableId, col.id, col.name, target, col.prefix, col.suffix, col.defaultValue, col.listItems, col.formula) }
                pasteMedia(col.copy(type = target), rowId, media)
            },
            { mediaPaste = null; media.cleanUp() },
        )
    }
    widthColumn?.let { col ->
        NameDialog("Column width", "Width (60 to 800)", "Save", (liveWidths[col.id] ?: col.width).toString(), { text ->
            widthColumn = null
            text.trim().toIntOrNull()?.coerceIn(60, 800)?.let { w ->
                liveWidths[col.id] = w
                c.ws.setColumnWidth(tableId, col.id, w)
            }
        }, { widthColumn = null })
    }
    if (manageColumns) {
        ManageColumnsDialog(
            c, tableId, grid.columns, hiddenColumns,
            onEdit = { editingColumn = it }, onDelete = { deletingColumn = it }, onAdd = { addingColumn = true },
            onDismiss = { manageColumns = false },
        )
    }
    rowPage?.let { id ->
        RowPageDialog(c, tableId, id, onAttachments = { col -> attachTarget = id to col }, onDismiss = { rowPage = null })
    }
    if (chartOpen) ChartDialog(c, shown.rows, columns) { chartOpen = false }
    if (rulesOpen) RulesDialog(c, tableId, columns) { rulesOpen = false }
    if (saveViewOpen) {
        NameDialog("Save view", "View name (e.g. Unpaid)", "Save", activeView.orEmpty(), { name ->
            saveViewOpen = false
            if (name.isNotBlank()) {
                c.ws.saveView(tableId, name, view)
                activeView = name.trim()
                c.say("View \"${name.trim()}\" saved")
            }
        }, { saveViewOpen = false })
    }
    if (historyOpen) {
        HistoryDialog(
            c.ws.conflicts(tableId),
            onRestore = { conflict ->
                val col = grid.columns.firstOrNull { it.name == conflict.columnName }
                val row = grid.rows.firstOrNull { it.values[col?.id ?: -1] == conflict.kept }
                if (col != null && row != null) c.edit(tableId) { c.ws.setCell(tableId, row.id, col.id, conflict.lost) }
                historyOpen = false
            },
            onClear = { c.ws.clearConflicts(tableId); historyOpen = false },
            onDismiss = { historyOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeaderCell(
    col: GridColumn, width: Int, sortDirection: SortDirection?, filtered: Boolean, grouped: Boolean,
    onSort: (SortDirection?) -> Unit, onFilter: () -> Unit, onGroup: () -> Unit, onPin: () -> Unit, onMove: (Int) -> Unit,
    onStyle: () -> Unit, onSettings: () -> Unit, onWidth: () -> Unit, onAutoFit: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit,
    onResize: (Int) -> Unit, onResizeEnd: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    var dragWidth by remember { mutableStateOf(0f) }
    val currentWidth by rememberUpdatedState(width)
    val resize by rememberUpdatedState(onResize)
    val resizeEnd by rememberUpdatedState(onResizeEnd)
    val subtitle = buildString {
        append(col.type.label)
        if (col.readOnly && col.type != ColumnType.CALC) append(" · Formula")
        if (col.pinned) append(" · Pinned")
        if (grouped) append(" · Grouped")
        if (sortDirection != null) append(if (sortDirection == SortDirection.ASC) " ▲" else " ▼")
        if (filtered) append(" · filtered")
    }
    Box(
        Modifier.width(width.dp).height(CellHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = { menu = true }),
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 12.dp)) {
            Text(col.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragWidth = currentWidth.toFloat() },
                        onDragEnd = { resizeEnd(dragWidth.roundToInt()) },
                        onDragCancel = { resizeEnd(dragWidth.roundToInt()) },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            dragWidth = (dragWidth + delta / density).coerceIn(60f, 800f)
                            resize(dragWidth.roundToInt())
                        },
                    )
                },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Sort ascending") }, onClick = { menu = false; onSort(SortDirection.ASC) })
            DropdownMenuItem(text = { Text("Sort descending") }, onClick = { menu = false; onSort(SortDirection.DESC) })
            if (sortDirection != null) DropdownMenuItem(text = { Text("Clear sort") }, onClick = { menu = false; onSort(null) })
            DropdownMenuItem(text = { Text(if (filtered) "Edit filter" else "Filter") }, onClick = { menu = false; onFilter() })
            DropdownMenuItem(text = { Text(if (grouped) "Stop grouping" else "Group by this column") }, onClick = { menu = false; onGroup() })
            DropdownMenuItem(text = { Text(if (col.pinned) "Unpin column" else "Pin column") }, onClick = { menu = false; onPin() })
            DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; onMove(-1) })
            DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; onMove(1) })
            DropdownMenuItem(text = { Text("Select column") }, onClick = { menu = false; onSelect() })
            DropdownMenuItem(text = { Text("Column width...") }, onClick = { menu = false; onWidth() })
            DropdownMenuItem(text = { Text("Fit width to content") }, onClick = { menu = false; onAutoFit() })
            DropdownMenuItem(text = { Text("Colors and font") }, onClick = { menu = false; onStyle() })
            DropdownMenuItem(text = { Text("Column settings / rename") }, onClick = { menu = false; onSettings() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun DataRow(
    pictureHeight: Int,
    number: Int, row: GridRow, columns: List<GridColumn>, widthOf: (GridColumn) -> Int, hs: androidx.compose.foundation.ScrollState,
    isSelected: (Long, Long) -> Boolean, editing: Pair<Long, Long>?, editStart: String?, attachments: List<Attachment>,
    cellMenu: Pair<Long, Long>?,
    onPress: (Long, Boolean) -> Unit, onDrag: (Long) -> Unit, onRightClick: (Long) -> Unit, onDouble: (Long) -> Unit,
    onCommit: (Long, String) -> Unit, onCancelEdit: () -> Unit, onCloseMenu: () -> Unit,
    onMenuCopy: () -> Unit, onMenuPaste: () -> Unit, onMenuClear: () -> Unit, onMenuEdit: (Long) -> Unit,
    onRowSelect: () -> Unit, onOpenRow: () -> Unit, paint: RulePaint, onCopyRow: () -> Unit, onPasteRows: () -> Unit, onRowColor: () -> Unit, onDeleteRow: () -> Unit,
    onOpenAttachments: (GridColumn) -> Unit, onToggleList: (Long, String) -> Unit,
    sheetLink: String? = null,
    rowHeightDp: Int? = null, onRowHeight: (Int) -> Unit = {}, onRowHeightEnd: (Int) -> Unit = {}, onRowHeightReset: () -> Unit = {},
) {
    var rowMenu by remember { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface
    // Rows that hold pictures are taller and show the pictures, like the previews in Google Sheets.
    val hasPictures = pictureHeight > 0 && columns.any { (it.type == ColumnType.PICTURE || it.type == ColumnType.SIGNATURE) && attachmentLinks(row.values[it.id].orEmpty()).isNotEmpty() }
    val rowHeight = rowHeightDp?.dp ?: if (hasPictures) pictureHeight.dp else CellHeight
    val tall = rowHeightDp != null && rowHeightDp > 48
    var dragHeight by remember { mutableStateOf(0f) }
    val currentH by rememberUpdatedState(rowHeightDp ?: (if (hasPictures) pictureHeight else 32))
    val heightChange by rememberUpdatedState(onRowHeight)
    val heightEnd by rememberUpdatedState(onRowHeightEnd)
    Row {
        Box(
            Modifier.pin(true, hs).width(RowNumberWidth).height(rowHeight).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .background(row.color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = { onRowSelect(); rowMenu = true }),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = if (row.color != null) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(8.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragHeight = currentH.toFloat() },
                            onDragEnd = { heightEnd(dragHeight.roundToInt()) },
                            onDragCancel = { heightEnd(dragHeight.roundToInt()) },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                dragHeight = (dragHeight + delta / density).coerceIn(24f, 600f)
                                heightChange(dragHeight.roundToInt())
                            },
                        )
                    },
            )
            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                if (rowHeightDp != null) DropdownMenuItem(text = { Text("Default row height") }, onClick = { rowMenu = false; onRowHeightReset() })
                if (row.sheetRow != null) {
                    DropdownMenuItem(text = { Text("This is row ${row.sheetRow} in the Google Sheet") }, enabled = false, onClick = {})
                    if (sheetLink != null) {
                        DropdownMenuItem(
                            text = { Text("Show this row in Google Sheets") },
                            onClick = { rowMenu = false; runCatching { Desktop.getDesktop().browse(URI(sheetLink + "&range=A" + row.sheetRow)) } },
                        )
                    }
                }
                DropdownMenuItem(text = { Text("Open row page") }, onClick = { rowMenu = false; onOpenRow() })
                DropdownMenuItem(text = { Text("Copy row") }, onClick = { rowMenu = false; onCopyRow() })
                DropdownMenuItem(text = { Text("Paste as new row") }, enabled = CellClipboard.resolve() != null, onClick = { rowMenu = false; onPasteRows() })
                DropdownMenuItem(text = { Text("Row colour") }, onClick = { rowMenu = false; onRowColor() })
                DropdownMenuItem(text = { Text("Delete row") }, onClick = { rowMenu = false; onDeleteRow() })
            }
        }
        columns.forEach { col ->
            val value = row.values[col.id].orEmpty()
            val selected = isSelected(row.id, col.id)
            val tint = paint.cells[col.id] ?: paint.rowColor ?: col.bgColor ?: row.color
            val background = when {
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                tint != null -> Color(tint)
                col.pinned -> surface
                else -> Color.Transparent
            }
            val textColor = col.textColor?.let { Color(it) } ?: if (tint != null) Color.Black else MaterialTheme.colorScheme.onSurface
            val style = MaterialTheme.typography.bodyMedium.copy(
                color = textColor,
                fontWeight = if (col.bold || col.id in paint.bold) FontWeight.Bold else null,
                fontStyle = if (col.italic) FontStyle.Italic else null,
                textAlign = if (col.isNumeric) TextAlign.End else TextAlign.Start,
            )
            val isEditing = editing == (row.id to col.id)
            val target = if (isEditing) null else linkOf(col, value)
            Box(
                Modifier.pin(col.pinned, hs).width(widthOf(col).dp).height(rowHeight)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).background(background)
                    .onPointerEvent(PointerEventType.Press) { event ->
                        if (event.buttons.isSecondaryPressed) onRightClick(col.id)
                        else if (event.buttons.isPrimaryPressed) {
                            if (target != null && event.keyboardModifiers.isCtrlPressed) openLink(target)
                            else onPress(col.id, event.keyboardModifiers.isShiftPressed)
                        }
                    }
                    .onPointerEvent(PointerEventType.Enter) { event ->
                        if (event.buttons.isPrimaryPressed) onDrag(col.id)
                    }
                    .combinedClickable(onClick = {}, onDoubleClick = { onDouble(col.id) })
                    .padding(horizontal = 8.dp),
                contentAlignment = if (col.isNumeric) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                when {
                    isEditing -> EditField(
                        initial = editStart ?: col.toEditText(value), style = style, replaceAll = editStart == null,
                        onCommit = { onCommit(col.id, it) }, onCancel = onCancelEdit,
                    )
                    col.type == ColumnType.CHECKBOX && !col.locked ->
                        Checkbox(checked = value == "true", onCheckedChange = { onToggleList(col.id, if (it) "true" else "false") })
                    col.type == ColumnType.RATING -> Stars(value, !col.locked) { v -> onToggleList(col.id, v) }
                    (col.type == ColumnType.PICTURE || col.type == ColumnType.SIGNATURE) && hasPictures && attachmentLinks(value).isNotEmpty() -> {
                        val names = attachmentLinks(value)
                        val inCell = names.mapNotNull { n -> attachments.firstOrNull { it.rowId == row.id && it.columnId == col.id && it.name == n } }
                        val thumb = (pictureHeight - 8).coerceAtLeast(24)
                        val fit = ((widthOf(col) - 16) / (thumb + 4)).coerceAtLeast(1)
                        val shownThumbs = inCell.take(if (inCell.size > fit) (fit - 1).coerceAtLeast(1) else fit)
                        Row(
                            Modifier.fillMaxSize().combinedClickable(onClick = { onOpenAttachments(col) }),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            shownThumbs.forEach { AttachmentThumb(it, thumb.dp, picture = true) }
                            if (names.size > shownThumbs.size) {
                                Text("+${names.size - shownThumbs.size}", style = style.copy(color = MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                    col.type.isAttachment -> {
                        val names = attachmentLinks(value)
                        val pending = attachments.count { it.rowId == row.id && it.columnId == col.id && it.url == null }
                        Text(
                            when {
                                names.isEmpty() -> "+ Add"
                                names.size == 1 -> names[0]
                                else -> "${names.size} ${if (col.type == ColumnType.PICTURE) "pictures" else if (col.type == ColumnType.AUDIO) "recordings" else "files"}"
                            } + if (pending > 0) " (on this PC)" else "",
                            style = style.copy(color = if (names.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.combinedClickable(onClick = { onOpenAttachments(col) }),
                        )
                    }
                    else -> Text(
                        text = if (value.isEmpty() || !col.isNumeric) (if (tall) value else value.replace('\n', ' ')) else col.prefix + value + col.suffix,
                        style = if (target != null) style.copy(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                        else style.copy(color = if (col.locked) textColor.copy(alpha = 0.75f) else textColor),
                        maxLines = if (tall) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (cellMenu == (row.id to col.id)) {
                    DropdownMenu(expanded = true, onDismissRequest = onCloseMenu) {
                        if (col.type == ColumnType.LIST && !col.locked) {
                            col.listItems.forEach { item ->
                                DropdownMenuItem(text = { Text(item) }, onClick = { onCloseMenu(); onToggleList(col.id, item) })
                            }
                            if (col.listItems.isEmpty()) DropdownMenuItem(text = { Text("No items - see Column settings") }, onClick = onCloseMenu)
                        } else {
                            if (target != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isPhoneLink(target)) "Call (open phone app)" else if (target.startsWith("mailto:")) "Send email" else "Open link  (Ctrl+click)") },
                                    onClick = { onCloseMenu(); openLink(target) },
                                )
                            }
                            DropdownMenuItem(text = { Text("Copy  (Ctrl+C)") }, onClick = { onCloseMenu(); onMenuCopy() })
                            DropdownMenuItem(text = { Text("Paste  (Ctrl+V)") }, enabled = !col.locked && canPaste(), onClick = { onCloseMenu(); onMenuPaste() })
                            if (!col.locked && !col.type.isAttachment) {
                                DropdownMenuItem(text = { Text("Edit  (Enter)") }, onClick = { onCloseMenu(); onMenuEdit(col.id) })
                                DropdownMenuItem(text = { Text("Clear  (Delete)") }, onClick = { onCloseMenu(); onMenuClear() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The text to edit: numbers without prefix and suffix, text without its stored prefix and suffix. */
private fun GridColumn.toEditText(value: String): String = withoutAffixes(value)

@Composable
private fun EditField(initial: String, style: TextStyle, replaceAll: Boolean, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val requester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        requester.requestFocus()
        if (replaceAll) text = text.copy(selection = TextRange(0, initial.length))
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth()
            .focusRequester(requester)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter, Key.Tab -> { if (!done) { done = true; onCommit(text.text) }; true }
                    Key.Escape -> { if (!done) { done = true; onCancel() }; true }
                    else -> false
                }
            }
            .onFocusChanged { focus ->
                if (focus.isFocused) hadFocus = true
                else if (hadFocus && !done) { done = true; onCommit(text.text) }
            },
    )
}

/** True for a real character; false for the placeholder codes that keys like Shift, F5 or Delete report. */
internal fun isTypedCharacter(codePoint: Int): Boolean =
    codePoint != 0xFFFF && codePoint != 127 && !Character.isISOControl(codePoint) &&
        Character.isDefined(codePoint) && codePoint !in 0xE000..0xF8FF
