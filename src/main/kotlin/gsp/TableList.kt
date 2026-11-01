package gsp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val TableViews = listOf("LIST" to "List", "SMALL" to "Small tiles", "BIG" to "Big tiles", "DETAILS" to "Details")
val TableSorts = listOf(
    "MODIFIED" to "Last modified", "NAME_AZ" to "Name A to Z", "NAME_ZA" to "Name Z to A",
    "CREATED" to "Date created", "ROWS" to "Most rows",
)

/** Pinned tables first, then the chosen order. */
fun sortSummaries(list: List<Workspace.Summary>, sort: String): List<Workspace.Summary> {
    val order: Comparator<Workspace.Summary> = when (sort) {
        "NAME_AZ" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        "NAME_ZA" -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
        "CREATED" -> compareByDescending { it.createdAt }
        "ROWS" -> compareByDescending { it.rows }
        else -> compareByDescending { it.updatedAt }
    }
    return list.sortedWith(compareByDescending<Workspace.Summary> { it.pinned }.then(order))
}

private fun day(millis: Long): String = if (millis <= 0) "-" else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

private fun scaled(base: TextUnit, zoom: Float): TextUnit = (base.value * zoom).sp

@Composable
fun TablesBody(
    tables: List<Workspace.Summary>,
    view: String,
    zoom: Float,
    sort: String,
    onSort: (String) -> Unit,
    onOpen: (Workspace.Summary) -> Unit,
    menu: @Composable (Workspace.Summary) -> Unit,
) {
    if (tables.isEmpty()) {
        Text("No tables yet. Create one, or open a Google Sheet.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val title = MaterialTheme.typography.titleMedium
    val small = MaterialTheme.typography.bodySmall
    when (view) {
        "SMALL", "BIG" -> {
            val big = view == "BIG"
            LazyVerticalGrid(
                columns = GridCells.Adaptive(((if (big) 230 else 150) * zoom).dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(tables, key = { it.id }) { t ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(6.dp).fillMaxWidth().clickable { onOpen(t) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (big) {
                                Box(
                                    Modifier.fillMaxWidth().height((140 * zoom).dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val cover = t.cover
                                    if (cover != null) {
                                        AttachmentThumb(cover, (140 * zoom).dp, picture = true)
                                    } else {
                                        Text(t.name.take(1).uppercase(), fontSize = scaled(40.sp, zoom), color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (t.pinned) "📌 " else "") + t.name,
                                        fontSize = scaled(if (big) title.fontSize else MaterialTheme.typography.bodyMedium.fontSize, zoom),
                                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text("${t.rows} rows" + if (t.linked) " · Sheet" else "", fontSize = scaled(small.fontSize, zoom), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                menu(t)
                            }
                        }
                    }
                }
            }
        }
        "DETAILS" -> {
            val wName = (260 * zoom).dp
            val wDate = (130 * zoom).dp
            val wSize = (170 * zoom).dp
            val wWhere = (140 * zoom).dp
            val text = scaled(MaterialTheme.typography.bodyMedium.fontSize, zoom)
            Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeadCell("Name", wName, "NAME_AZ", sort, text, onSort)
                    HeadCell("Created", wDate, "CREATED", sort, text, onSort)
                    HeadCell("Modified", wDate, "MODIFIED", sort, text, onSort)
                    HeadCell("Size", wSize, "ROWS", sort, text, onSort)
                    HeadCell("Where", wWhere, null, sort, text, onSort)
                    Box(Modifier.width(48.dp))
                }
                LazyColumn(Modifier.width(wName + wDate + wDate + wSize + wWhere + 48.dp)) {
                    items(tables, key = { it.id }) { t ->
                        Row(Modifier.clickable { onOpen(t) }.padding(vertical = (4 * zoom).dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (t.pinned) "📌 " else "") + t.name, fontSize = text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(wName).padding(horizontal = 8.dp))
                            Text(day(t.createdAt), fontSize = text, modifier = Modifier.width(wDate).padding(horizontal = 8.dp))
                            Text(day(t.updatedAt), fontSize = text, modifier = Modifier.width(wDate).padding(horizontal = 8.dp))
                            Text("${t.rows} rows · ${t.columns} cols", fontSize = text, modifier = Modifier.width(wSize).padding(horizontal = 8.dp))
                            Text(if (t.linked) "Google Sheet" else "This PC", fontSize = text, modifier = Modifier.width(wWhere).padding(horizontal = 8.dp))
                            menu(t)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(tables, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(horizontal = 16.dp, vertical = (8 * zoom).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text((if (t.pinned) "📌 " else "") + t.name, fontSize = scaled(title.fontSize, zoom), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.rows} rows, ${t.columns} columns" + if (t.linked) " · linked to Google Sheet" else "", fontSize = scaled(small.fontSize, zoom), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    menu(t)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HeadCell(label: String, width: Dp, key: String?, sort: String, text: TextUnit, onSort: (String) -> Unit) {
    val mark = when {
        key == null -> ""
        sort == key -> " ▼"
        key == "NAME_AZ" && sort == "NAME_ZA" -> " ▲"
        else -> ""
    }
    Text(
        label + mark, fontWeight = FontWeight.Bold, fontSize = text,
        modifier = Modifier.width(width).padding(horizontal = 8.dp)
            .then(if (key != null) Modifier.clickable { onSort(if (key == "NAME_AZ" && sort == "NAME_AZ") "NAME_ZA" else key) } else Modifier),
    )
}

// ===================== manage columns =====================

fun typeGlyph(type: ColumnType): String = when (type) {
    ColumnType.TEXT -> "Tt"
    ColumnType.NUMBER -> "123"
    ColumnType.DATE -> "📅"
    ColumnType.CHECKBOX -> "☑"
    ColumnType.TIME -> "🕒"
    ColumnType.LIST -> "☰"
    ColumnType.PHONE -> "📞"
    ColumnType.ADDRESS -> "🏠"
    ColumnType.PICTURE -> "🖼"
    ColumnType.FILE -> "📄"
    ColumnType.AUDIO -> "🎵"
    ColumnType.CALC -> "Σ"
    ColumnType.RATING -> "★"
    ColumnType.SIGNATURE -> "✍"
}

/** Reorder, hide, edit, delete and add columns. Hidden columns stay in the Google Sheet. */
@Composable
fun ManageColumnsDialog(c: Controller, tableId: Long, columns: List<GridColumn>, hidden: Set<Long>, onEdit: (GridColumn) -> Unit, onDelete: (GridColumn) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Manage Columns")
                Text("${columns.size} columns · ${columns.count { it.id in hidden }} hidden", style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            LazyColumn(Modifier.width(560.dp).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(columns, key = { it.id }) { col ->
                    val isHidden = col.id in hidden
                    val index = columns.indexOf(col)
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                TextButton(enabled = index > 0, onClick = { c.edit(tableId) { c.ws.moveColumn(tableId, col.id, -1) } }) { Text("▲") }
                                TextButton(enabled = index < columns.lastIndex, onClick = { c.edit(tableId) { c.ws.moveColumn(tableId, col.id, 1) } }) { Text("▼") }
                            }
                            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text(typeGlyph(col.type), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text(
                                col.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (isHidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).padding(start = 12.dp).clickable { onEdit(col) },
                            )
                            TextButton(onClick = { c.ws.setColumnHidden(tableId, col.id, !isHidden) }) { Text(if (isHidden) "🙈 Show" else "👁 Hide") }
                            TextButton(onClick = { onDelete(col) }) { Text("🗑", color = Color(0xFFD9534F)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { Button(onClick = onAdd) { Text("+ Add Column") } },
    )
}
