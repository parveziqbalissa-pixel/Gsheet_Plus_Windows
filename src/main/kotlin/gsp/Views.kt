package gsp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** A column picker shown as a button with a menu. */
@Composable
fun ColumnPicker(label: String, columns: List<GridColumn>, selected: GridColumn?, allowNone: String? = null, onPick: (GridColumn?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: ${selected?.name ?: allowNone ?: "choose"}  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone != null) DropdownMenuItem(text = { Text(allowNone) }, onClick = { open = false; onPick(null) })
            columns.forEach { col -> DropdownMenuItem(text = { Text(col.name + "  (" + col.type.label + ")") }, onClick = { open = false; onPick(col) }) }
        }
    }
}

/** The column whose text names a row (first text-like column). */
fun titleColumn(columns: List<GridColumn>): GridColumn? =
    columns.firstOrNull { it.type == ColumnType.TEXT } ?: columns.firstOrNull { !it.type.isAttachment }

fun displayValue(col: GridColumn, value: String): String = when {
    value.isEmpty() -> ""
    col.type == ColumnType.CHECKBOX -> if (value == "true") "✔" else "✘"
    col.type == ColumnType.RATING -> "★".repeat((parseNumber(value) ?: 0.0).toInt().coerceIn(0, 5))
    col.type.isAttachment -> attachmentLinks(value).let { if (it.size == 1) it[0] else "${it.size} files" }
    col.isNumeric -> col.prefix + value + col.suffix
    else -> value.replace('\n', ' ')
}

// ===================== cards =====================

@Composable
fun CardsView(rows: List<GridRow>, columns: List<GridColumn>, attachments: List<Attachment>, rules: List<ColorRule>, onOpen: (Long) -> Unit) {
    val title = titleColumn(columns)
    val picture = columns.firstOrNull { it.type == ColumnType.PICTURE }
    val details = columns.filter { it.id != title?.id && it.id != picture?.id }.take(5)
    if (rows.isEmpty()) {
        Text("No rows to show.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(250.dp), modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(rows, key = { it.id }) { row ->
            val paint = paintRow(row, columns, rules)
            val tint = paint.rowColor ?: row.color
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tint?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(6.dp).fillMaxWidth().clickable { onOpen(row.id) },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (picture != null) {
                        val first = attachmentLinks(row.values[picture.id].orEmpty()).firstOrNull()
                        val a = first?.let { n -> attachments.firstOrNull { it.rowId == row.id && it.columnId == picture.id && it.name == n } }
                        if (a != null) AttachmentThumb(a, 140.dp, picture = true)
                    }
                    val textColor = if (tint != null) Color.Black else MaterialTheme.colorScheme.onSurface
                    Text(
                        title?.let { row.values[it.id] }?.takeIf { it.isNotBlank() } ?: "(no title)",
                        style = MaterialTheme.typography.titleMedium, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    details.forEach { col ->
                        val v = displayValue(col, row.values[col.id].orEmpty())
                        if (v.isNotEmpty()) {
                            Text("${col.name}: $v", style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ===================== calendar =====================

@Composable
fun CalendarView(rows: List<GridRow>, columns: List<GridColumn>, rules: List<ColorRule>, onOpen: (Long) -> Unit, onAdd: (LocalDate, GridColumn) -> Unit) {
    val dateColumns = columns.filter { it.type == ColumnType.DATE }
    if (dateColumns.isEmpty()) {
        Text("Add a Date column to see this table as a calendar.", modifier = Modifier.padding(24.dp))
        return
    }
    var dateColumnId by remember { mutableStateOf(dateColumns.first().id) }
    val dateColumn = dateColumns.firstOrNull { it.id == dateColumnId } ?: dateColumns.first()
    var month by remember { mutableStateOf(YearMonth.now()) }
    val title = titleColumn(columns)
    val byDay = remember(rows, dateColumn) {
        rows.groupBy { r -> runCatching { LocalDate.parse(r.values[dateColumn.id].orEmpty().take(10)) }.getOrNull() }
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
            Text(month.month.getDisplayName(JTextStyle.FULL, Locale.ENGLISH) + " " + month.year, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(180.dp))
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
            TextButton(onClick = { month = YearMonth.now() }) { Text("Today") }
            if (dateColumns.size > 1) ColumnPicker("Date column", dateColumns, dateColumn) { if (it != null) dateColumnId = it.id }
            val undated = rows.count { r -> byDay[null]?.contains(r) == true }
            if (undated > 0) Text("$undated rows have no date", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(it, modifier = Modifier.weight(1f).padding(4.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        val cells = monthGrid(month)
        val today = LocalDate.now()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().height(118.dp)) {
                    week.forEach { day ->
                        Column(
                            Modifier.weight(1f).fillMaxSize().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(if (day == today) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .then(if (day != null) Modifier.clickable { onAdd(day, dateColumn) } else Modifier)
                                .padding(4.dp),
                        ) {
                            if (day != null) {
                                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium)
                                val list = byDay[day].orEmpty()
                                list.take(4).forEach { r ->
                                    val tint = paintRow(r, columns, rules).rowColor ?: r.color
                                    Text(
                                        title?.let { r.values[it.id] }?.takeIf { it.isNotBlank() } ?: "(row)",
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = Color.Black,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                            .background(tint?.let { Color(it) } ?: Color(0xFFC8E6C9), RoundedCornerShape(4.dp))
                                            .clickable { onOpen(r.id) }.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                                if (list.size > 4) Text("+${list.size - 4} more", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Text("Click an empty part of a day to add a row on that date.", style = MaterialTheme.typography.labelSmall)
    }
}

// ===================== rating =====================

@Composable
fun Stars(value: String, enabled: Boolean, onSet: (String) -> Unit) {
    val n = (parseNumber(value) ?: 0.0).toInt().coerceIn(0, 5)
    Row {
        for (i in 1..5) {
            Text(
                if (i <= n) "★" else "☆",
                color = if (i <= n) Color(0xFFF9A825) else MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.titleMedium,
                modifier = if (enabled) Modifier.clickable { onSet(if (i == n) "" else i.toString()) } else Modifier,
            )
        }
    }
}

// ===================== row page =====================

/** Every field of one row on one page. Text fields are saved with Save; checkboxes, lists and stars at once. */
@Composable
fun RowPageDialog(c: Controller, tableId: Long, rowId: Long, onAttachments: (GridColumn) -> Unit, onDismiss: () -> Unit) {
    val grid = c.ws.grid(tableId) ?: return onDismiss()
    val row = grid.rows.firstOrNull { it.id == rowId } ?: return onDismiss()
    val drafts = remember(rowId) { mutableStateMapOf<Long, String>() }
    var instant by remember { mutableStateOf(0) }
    val live = remember(instant, rowId) { c.ws.grid(tableId)?.rows?.firstOrNull { it.id == rowId } ?: row }
    fun saveAll(): Boolean {
        var bad = 0
        for ((colId, text) in drafts) if (!c.edit(tableId) { c.ws.setCell(tableId, rowId, colId, text) }) bad++
        if (bad > 0) c.say("$bad value(s) did not fit their column and were not saved.")
        return bad == 0
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleColumn(grid.columns)?.let { live.values[it.id] }?.takeIf { it.isNotBlank() } ?: "Row") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                grid.columns.forEach { col ->
                    val value = live.values[col.id].orEmpty()
                    Column {
                        Text(col.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        when {
                            col.locked -> Text(displayValue(col, value).ifEmpty { "-" })
                            col.type.isAttachment -> TextButton(onClick = { onAttachments(col) }) {
                                Text(if (value.isEmpty()) "+ Add" else displayValue(col, value) + "  (open)")
                            }
                            col.type == ColumnType.CHECKBOX -> Checkbox(checked = value == "true", onCheckedChange = {
                                c.edit(tableId) { c.ws.setCell(tableId, rowId, col.id, if (it) "true" else "false") }; instant++
                            })
                            col.type == ColumnType.RATING -> Stars(value, true) { v -> c.edit(tableId) { c.ws.setCell(tableId, rowId, col.id, v) }; instant++ }
                            col.type == ColumnType.LIST -> {
                                var open by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { open = true }) { Text(value.ifEmpty { "choose" } + "  ▾") }
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        DropdownMenuItem(text = { Text("(empty)") }, onClick = { open = false; c.edit(tableId) { c.ws.setCell(tableId, rowId, col.id, "") }; instant++ })
                                        col.listItems.forEach { item ->
                                            DropdownMenuItem(text = { Text(item) }, onClick = { open = false; c.edit(tableId) { c.ws.setCell(tableId, rowId, col.id, item) }; instant++ })
                                        }
                                    }
                                }
                            }
                            else -> {
                                val link = linkOf(col, drafts[col.id] ?: value)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = drafts[col.id] ?: col.withoutAffixes(value),
                                        onValueChange = { drafts[col.id] = it },
                                        singleLine = col.type != ColumnType.TEXT && col.type != ColumnType.ADDRESS,
                                        placeholder = {
                                            Text(when (col.type) { ColumnType.DATE -> "yyyy-mm-dd"; ColumnType.TIME -> "hh:mm"; else -> "" })
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (link != null) {
                                        TextButton(onClick = { openLink(link) }) {
                                            Text(if (isPhoneLink(link)) "Call" else if (link.startsWith("mailto:")) "Email" else if (col.type == ColumnType.ADDRESS) "Map" else "Open")
                                        }
                                    }
                                    if (col.type == ColumnType.DATE) TextButton(onClick = { drafts[col.id] = LocalDate.now().toString() }) { Text("Today") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (saveAll()) onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ===================== colour rules =====================

@Composable
fun RulesDialog(c: Controller, tableId: Long, columns: List<GridColumn>, onDismiss: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val rules = remember(tick) { c.ws.rules(tableId) }
    var column by remember { mutableStateOf(columns.firstOrNull()) }
    var op by remember { mutableStateOf(FilterOp.EQUALS) }
    var value by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<Int?>(BackgroundPalette[3]) }
    var wholeRow by remember { mutableStateOf(true) }
    var bold by remember { mutableStateOf(false) }
    var opMenu by remember { mutableStateOf(false) }
    val ops = column?.let { opsFor(it.type) }.orEmpty()
    if (op !in ops && ops.isNotEmpty()) op = ops.first()
    val valueOk = column != null && (!op.needsValue || isValidFilterValue(column!!.type, op, ruleValue(value)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour rules") },
        text = {
            Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rules.isEmpty()) Text("No rules yet. Rows or cells that match a rule are coloured automatically.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(rules, key = { it.id }) { r ->
                        val col = columns.firstOrNull { it.id == r.columnId }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(20.dp).background(Color(r.color), RoundedCornerShape(4.dp)))
                            Text(
                                "${col?.name ?: "?"} ${col?.let { r.op.label(it.type) } ?: ""} ${r.value}" + (if (r.wholeRow) " → whole row" else " → cell") + (if (r.bold) ", bold" else ""),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { c.ws.deleteRule(tableId, r.id); tick++ }) { Text("Delete") }
                        }
                    }
                }
                HorizontalDivider()
                Text("New rule", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColumnPicker("When", columns.filter { !it.type.isAttachment || true }, column) { column = it }
                    Box {
                        TextButton(onClick = { opMenu = true }) { Text((column?.let { op.label(it.type) } ?: "") + "  ▾") }
                        DropdownMenu(expanded = opMenu, onDismissRequest = { opMenu = false }) {
                            ops.forEach { o -> DropdownMenuItem(text = { Text(column?.let { o.label(it.type) } ?: o.name) }, onClick = { op = o; opMenu = false }) }
                        }
                    }
                    if (op.needsValue) {
                        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, isError = value.isNotBlank() && !valueOk, modifier = Modifier.weight(1f), placeholder = { Text("value") })
                    }
                }
                if (column?.type == ColumnType.DATE) Text("Tip: use {today} as the value, e.g. \"is before {today}\" marks overdue dates.", style = MaterialTheme.typography.labelSmall)
                Swatches(BackgroundPalette, color) { color = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wholeRow, { wholeRow = it }); Text("Colour the whole row")
                    Spacer(Modifier.width(16.dp))
                    Checkbox(bold, { bold = it }); Text("Bold")
                }
                Button(
                    enabled = column != null && color != null && valueOk && (!op.needsValue || value.isNotBlank()),
                    onClick = {
                        val col = column ?: return@Button
                        c.ws.addRule(tableId, col.id, op, value, color ?: return@Button, wholeRow, bold)
                        value = ""
                        tick++
                    },
                ) { Text("Add rule") }
                Text("Rules are checked from top to bottom; the first match wins. They are kept on this PC (not in the Google Sheet).", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ===================== charts =====================

private val ChartColors = listOf(0xFF0F9D58, 0xFF4285F4, 0xFFF4B400, 0xFFDB4437, 0xFFAB47BC, 0xFF00ACC1, 0xFFFF7043, 0xFF9E9D24, 0xFF5C6BC0, 0xFF8D6E63).map { Color(it) }

@Composable
fun ChartDialog(c: Controller, rows: List<GridRow>, columns: List<GridColumn>, onDismiss: () -> Unit) {
    val groupable = columns.filter { !it.isNumeric || it.type == ColumnType.RATING }
    var group by remember { mutableStateOf(groupable.firstOrNull { it.type == ColumnType.LIST } ?: groupable.firstOrNull()) }
    var valueCol by remember { mutableStateOf(columns.firstOrNull { it.type == ColumnType.NUMBER || it.type == ColumnType.CALC }) }
    var agg by remember { mutableStateOf(Aggregate.SUM) }
    var byMonth by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf("Bar") }
    val lines = remember(rows, group, valueCol, agg, byMonth) {
        group?.let { summarize(rows, it, valueCol, if (valueCol == null) Aggregate.COUNT else agg, byMonth) }.orEmpty()
    }
    val max = lines.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    fun fmt(d: Double) = formatNumber(d)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chart and summary") },
        text = {
            Column(Modifier.width(720.dp).heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColumnPicker("Group by", groupable, group) { group = it }
                    ColumnPicker("Value", columns.filter { it.type == ColumnType.NUMBER || it.type == ColumnType.CALC || it.type == ColumnType.RATING }, valueCol, allowNone = "Count rows") { valueCol = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (valueCol != null) listOf(Aggregate.SUM, Aggregate.AVG, Aggregate.MIN, Aggregate.MAX).forEach { a ->
                        FilterChip(selected = agg == a, onClick = { agg = a }, label = { Text(a.label) })
                    }
                    Spacer(Modifier.width(12.dp))
                    listOf("Bar", "Pie", "Line").forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) }) }
                    if (group?.type == ColumnType.DATE) {
                        Checkbox(byMonth, { byMonth = it }); Text("By month")
                    }
                }
                if (lines.isEmpty()) {
                    Text("Nothing to chart.")
                } else {
                    Box(Modifier.fillMaxWidth().height(300.dp)) {
                        when (kind) {
                            "Pie" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                val total = lines.sumOf { it.value.coerceAtLeast(0.0) }.takeIf { it > 0 } ?: 1.0
                                Canvas(Modifier.size(280.dp).padding(10.dp)) {
                                    var start = -90f
                                    lines.forEachIndexed { i, l ->
                                        val sweep = (l.value.coerceAtLeast(0.0) / total * 360.0).toFloat()
                                        drawArc(ChartColors[i % ChartColors.size], start, sweep, useCenter = true)
                                        start += sweep
                                    }
                                }
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    lines.forEachIndexed { i, l ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(12.dp).background(ChartColors[i % ChartColors.size]))
                                            Text("  ${l.label}  ${fmt(l.value)}  (${Math.round(l.value.coerceAtLeast(0.0) / total * 100)}%)", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            "Line" -> Column {
                                val lineColor = ChartColors[1]
                                val axis = MaterialTheme.colorScheme.outline
                                Canvas(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
                                    val minV = minOf(0.0, lines.minOf { it.value })
                                    val span = (max - minV).takeIf { it > 0 } ?: 1.0
                                    fun pt(i: Int, v: Double) = Offset(
                                        if (lines.size == 1) size.width / 2 else size.width * i / (lines.size - 1),
                                        (size.height * (1 - (v - minV) / span)).toFloat(),
                                    )
                                    drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
                                    val path = Path()
                                    lines.forEachIndexed { i, l -> val p = pt(i, l.value); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                                    drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
                                    lines.forEachIndexed { i, l -> drawCircle(lineColor, 6f, pt(i, l.value)) }
                                }
                                Row(Modifier.fillMaxWidth()) {
                                    lines.forEach { l -> Text(l.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                                }
                            }
                            else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                                lines.forEachIndexed { i, l ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(l.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp), style = MaterialTheme.typography.bodySmall)
                                        Box(Modifier.weight(1f)) {
                                            Box(
                                                Modifier.fillMaxWidth((l.value.coerceAtLeast(0.0) / max).toFloat().coerceIn(0.01f, 1f)).height(20.dp)
                                                    .background(ChartColors[i % ChartColors.size], RoundedCornerShape(4.dp)),
                                            )
                                        }
                                        Text("  " + fmt(l.value), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    val total = lines.sumOf { it.value }
                    Text(
                        "${lines.size} groups · ${lines.sumOf { it.count }} rows" + if (valueCol != null && agg == Aggregate.SUM) " · total ${fmt(Math.round(total * 1_000_000) / 1_000_000.0)}" else "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = "${group?.name ?: ""}\t${valueCol?.let { agg.label + " of " + it.name } ?: "Count"}\n" + lines.joinToString("\n") { "${it.label}\t${fmt(it.value)}" }
                runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
                c.say("Summary copied - paste it into Excel or a message")
            }) { Text("Copy summary") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ===================== signature =====================

/** Draw a signature with the mouse; it is saved as a PNG picture. */
@Composable
fun SignaturePadDialog(onSave: (java.nio.file.Path) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var area by remember { mutableStateOf(IntSize(1, 1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign here") },
        text = {
            Box(
                Modifier.size(560.dp, 240.dp).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outline)
                    .onSizeChanged { area = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { current = listOf(it) },
                            onDrag = { change, _ -> change.consume(); current = current + change.position },
                            onDragEnd = { if (current.size > 1) strokes += current; current = emptyList() },
                            onDragCancel = { current = emptyList() },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    (strokes + listOf(current)).forEach { s ->
                        for (i in 1 until s.size) drawLine(Color.Black, s[i - 1], s[i], strokeWidth = 4f, cap = StrokeCap.Round)
                    }
                }
                if (strokes.isEmpty() && current.isEmpty()) Text("Draw with the mouse", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val img = java.awt.image.BufferedImage(area.width.coerceAtLeast(1), area.height.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = java.awt.Color.WHITE
                g.fillRect(0, 0, img.width, img.height)
                g.color = java.awt.Color.BLACK
                g.stroke = java.awt.BasicStroke(4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                strokes.forEach { s -> for (i in 1 until s.size) g.drawLine(s[i - 1].x.toInt(), s[i - 1].y.toInt(), s[i].x.toInt(), s[i].y.toInt()) }
                g.dispose()
                val dir = java.nio.file.Files.createTempDirectory("gsp-sign")
                val stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"))
                val file = dir.resolve("Signature $stamp.png")
                javax.imageio.ImageIO.write(img, "png", file.toFile())
                onSave(file)
            }) { Text("Save signature") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear(); current = emptyList() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ===================== templates, bin, lock =====================

@Composable
fun NewTableDialog(onCreate: (String, Template?) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf<Template?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New table") },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Table name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Start from", style = MaterialTheme.typography.titleSmall)
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.heightIn(max = 340.dp)) {
                    val options: List<Template?> = listOf<Template?>(null) + Templates.all
                    items(options) { t ->
                        val selected = template == t
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(4.dp).fillMaxWidth().height(96.dp).clickable { template = t },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(t?.name ?: "Blank table", fontWeight = FontWeight.Bold)
                                Text(t?.description ?: "Two text columns", style = MaterialTheme.typography.labelSmall, maxLines = 3)
                            }
                        }
                    }
                }
                template?.let { t -> Text("Columns: " + t.columns.joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim().ifEmpty { template?.name ?: "Untitled" }, template) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TrashDialog(c: Controller, onDismiss: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val items = remember(tick) { c.ws.trash() }
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recently deleted tables") },
        text = {
            Column(Modifier.width(520.dp)) {
                if (items.isEmpty()) Text("The bin is empty.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(items, key = { it.id }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontWeight = FontWeight.Medium)
                                val at = java.time.Instant.ofEpochMilli(t.deletedAt).atZone(java.time.ZoneId.systemDefault())
                                Text("${t.rows} rows · deleted ${fmt.format(at)}", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { c.ws.restoreTable(t.id); c.say("\"${t.name}\" restored"); tick++ }) { Text("Restore") }
                            TextButton(onClick = { c.ws.purgeTable(t.id); tick++ }) { Text("Delete forever") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun LockScreen(c: Controller) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Gsheet_Plus is locked", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = pin, onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(12); wrong = false },
                label = { Text("PIN") }, singleLine = true, isError = wrong,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.width(220.dp),
            )
            if (wrong) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
            Button(onClick = { if (!c.unlock(pin)) { wrong = true; pin = "" } }) { Text("Unlock") }
        }
    }
}

@Composable
fun PinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 4 && a == b
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = a, onValueChange = { a = it.filter { ch -> ch.isDigit() }.take(12) }, label = { Text("PIN (4 to 12 digits)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(value = b, onValueChange = { b = it.filter { ch -> ch.isDigit() }.take(12) }, label = { Text("Repeat PIN") }, singleLine = true,
                    isError = b.isNotEmpty() && a != b, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("If you forget the PIN, delete settings.json in the GsheetPlus folder (your tables are not affected).", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSet(a) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ===================== quick add =====================

/** A small form that adds one row to a chosen table (opened from the tray icon). */
@Composable
fun QuickAddContent(c: Controller, onDone: () -> Unit) {
    val tables = remember { c.ws.summaries().sortedByDescending { it.updatedAt } }
    var tableId by remember { mutableStateOf(tables.firstOrNull()?.id) }
    var menu by remember { mutableStateOf(false) }
    val grid = remember(tableId) { tableId?.let { c.ws.grid(it) } }
    val values = remember(tableId) { mutableStateMapOf<Long, String>() }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tables.isEmpty()) {
            Text("Create a table first.")
            return@Column
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("Table: ${grid?.name ?: "choose"}  ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                tables.forEach { t -> DropdownMenuItem(text = { Text(t.name) }, onClick = { menu = false; tableId = t.id }) }
            }
        }
        grid?.columns?.filter { !it.locked && !it.type.isAttachment }?.forEach { col ->
            when (col.type) {
                ColumnType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(values[col.id] == "true", { values[col.id] = if (it) "true" else "false" }); Text(col.name)
                }
                ColumnType.RATING -> Row(verticalAlignment = Alignment.CenterVertically) { Text(col.name + "  "); Stars(values[col.id].orEmpty(), true) { values[col.id] = it } }
                else -> OutlinedTextField(
                    value = values[col.id].orEmpty(), onValueChange = { values[col.id] = it },
                    label = { Text(col.name + if (col.type == ColumnType.LIST) " (" + col.listItems.joinToString(", ") + ")" else if (col.type == ColumnType.DATE) " (yyyy-mm-dd)" else "") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = grid != null && values.values.any { it.isNotBlank() }, onClick = {
                val id = tableId ?: return@Button
                val rowId = c.ws.addRow(id) ?: return@Button
                var bad = 0
                for ((colId, v) in values) if (v.isNotBlank() && !c.ws.setCell(id, rowId, colId, v)) bad++
                c.scheduleSync(id)
                c.say("Row added to ${grid?.name}" + if (bad > 0) " ($bad value(s) did not fit)" else "")
                values.clear()
            }) { Text("Add row") }
            TextButton(onClick = onDone) { Text("Close") }
        }
    }
}
