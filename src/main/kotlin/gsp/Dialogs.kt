package gsp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun NameDialog(title: String, label: String, confirmText: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add or change a column: name, type, prefix/suffix, default value, list items, formula. */
@Composable
fun ColumnDialog(existing: GridColumn?, onConfirm: (GridColumn) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: ColumnType.TEXT) }
    var prefix by remember { mutableStateOf(existing?.prefix.orEmpty()) }
    var suffix by remember { mutableStateOf(existing?.suffix.orEmpty()) }
    var default by remember { mutableStateOf(existing?.defaultValue.orEmpty()) }
    var items by remember { mutableStateOf(existing?.listItems.orEmpty().joinToString("\n")) }
    var formula by remember { mutableStateOf(existing?.formula.orEmpty()) }
    var typeMenu by remember { mutableStateOf(false) }
    val formulaOk = type != ColumnType.CALC || FormulaEval.isValid(formula)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add column" else "Column settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Column name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    TextButton(onClick = { typeMenu = true }) { Text("Type: ${type.label}  ▾") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        ColumnType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; typeMenu = false })
                        }
                    }
                }
                if (type == ColumnType.TEXT || type == ColumnType.NUMBER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Prefix") }, singleLine = true, modifier = Modifier.width(140.dp))
                        OutlinedTextField(value = suffix, onValueChange = { suffix = it }, label = { Text("Suffix") }, singleLine = true, modifier = Modifier.width(140.dp))
                    }
                }
                if (type == ColumnType.LIST) {
                    OutlinedTextField(value = items, onValueChange = { items = it }, label = { Text("List items (one per line)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
                if (type == ColumnType.CALC) {
                    OutlinedTextField(
                        value = formula, onValueChange = { formula = it },
                        label = { Text("Formula, e.g. [Price] * [Qty]") }, singleLine = true,
                        isError = formula.isNotBlank() && !formulaOk, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Use + - * / ^ and ROUND, ABS, MIN, MAX, SUM. Column names go in [square brackets].", style = MaterialTheme.typography.labelSmall)
                } else if (!type.isAttachment) {
                    OutlinedTextField(
                        value = default, onValueChange = { default = it },
                        label = { Text(if (type == ColumnType.DATE) "Default for new rows ({today})" else if (type == ColumnType.TIME) "Default for new rows ({now})" else "Default for new rows") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && formulaOk && (type != ColumnType.CALC || formula.isNotBlank()),
                onClick = {
                    onConfirm(
                        GridColumn(
                            id = existing?.id ?: 0, name = name.trim(), type = type, width = existing?.width ?: 160,
                            prefix = prefix, suffix = suffix, defaultValue = default.trim(),
                            listItems = parseListItems(items), formula = formula.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FilterDialog(column: GridColumn, existing: FilterSpec?, onApply: (FilterSpec) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val ops = remember(column.type) { opsFor(column.type) }
    var op by remember { mutableStateOf(existing?.op?.takeIf { it in ops } ?: ops.first()) }
    var value by remember { mutableStateOf(existing?.value.orEmpty()) }
    val valid = isValidFilterValue(column.type, op, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter \"${column.name}\"") },
        text = {
            Column {
                ops.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = option == op, onClick = { op = option }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == op, onClick = null)
                        Text(option.label(column.type), Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                if (op.needsValue) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = value, onValueChange = { value = it },
                        label = { Text(if (column.type == ColumnType.DATE) "Date (yyyy-MM-dd)" else "Value") },
                        singleLine = true, isError = value.isNotBlank() && !valid,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(FilterSpec(column.id, op, value.trim())) }, enabled = valid) { Text("Apply") } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

val BackgroundPalette = listOf(0xFFFFCDD2, 0xFFFFE0B2, 0xFFFFF9C4, 0xFFC8E6C9, 0xFFB2EBF2, 0xFFBBDEFB, 0xFFD1C4E9, 0xFFF8BBD0, 0xFFD7CCC8, 0xFFE0E0E0).map { it.toInt() }
val TextPalette = listOf(0xFFB71C1C, 0xFFE65100, 0xFF827717, 0xFF1B5E20, 0xFF006064, 0xFF0D47A1, 0xFF4A148C, 0xFF880E4F, 0xFF3E2723, 0xFF000000).map { it.toInt() }

@Composable
private fun Swatches(colors: List<Int>, current: Int?, onPick: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPick(null) }) { Text(if (current == null) "None ✓" else "None") }
        colors.forEach { c ->
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == current) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
fun ColumnStyleDialog(column: GridColumn, onSave: (Int?, Int?, Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    var bg by remember { mutableStateOf(column.bgColor) }
    var text by remember { mutableStateOf(column.textColor) }
    var bold by remember { mutableStateOf(column.bold) }
    var italic by remember { mutableStateOf(column.italic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colors and font: ${column.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Background"); Swatches(BackgroundPalette, bg) { bg = it }
                Text("Text colour"); Swatches(TextPalette, text) { text = it }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(bold, { bold = it }); Text("Bold") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(italic, { italic = it }); Text("Italic") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(bg, text, bold, italic) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RowColorDialog(current: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Row colour") },
        text = { Swatches(BackgroundPalette, current) { onPick(it) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun HistoryDialog(conflicts: List<Conflict>, onRestore: (Conflict) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync history") },
        text = {
            if (conflicts.isEmpty()) {
                Text("No conflicts. When a cell is changed here and in the Sheet at the same time, the app's value is kept and the Sheet's old value is listed here.")
            } else {
                LazyColumn(Modifier.height(320.dp).width(460.dp)) {
                    items(conflicts.reversed(), key = { it.id }) { c ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(c.columnName, style = MaterialTheme.typography.labelLarge)
                            Text("Kept: ${c.kept.ifEmpty { "(empty)" }}", style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Sheet had: ${c.lost.ifEmpty { "(empty)" }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onRestore(c) }) { Text("Restore") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (conflicts.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") } },
    )
}
