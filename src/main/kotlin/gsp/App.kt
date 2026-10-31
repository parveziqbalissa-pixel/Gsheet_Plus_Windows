package gsp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private sealed interface Screen {
    object Tables : Screen
    object Link : Screen
    class Grid(val id: Long) : Screen
}

@Composable
fun App(c: Controller) {
    var screen by remember { mutableStateOf<Screen>(Screen.Tables) }
    when (val s = screen) {
        Screen.Tables -> TableListScreen(c, onOpen = { screen = Screen.Grid(it) }, onLink = { screen = Screen.Link })
        Screen.Link -> LinkScreen(c, onBack = { screen = Screen.Tables }, onOpen = { screen = Screen.Grid(it) })
        is Screen.Grid -> GridScreen(c, s.id, onBack = { screen = Screen.Tables })
    }
}

@Composable
private fun TableListScreen(c: Controller, onOpen: (Long) -> Unit, onLink: () -> Unit) {
    var tick by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        val stop = c.ws.changes.listen { tick = it }
        onDispose { stop() }
    }
    val tables = remember(tick) { c.ws.summaries() }
    var search by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val shown = tables.filter { it.name.contains(search, ignoreCase = true) }
        .sortedWith(compareByDescending<Workspace.Summary> { it.pinned }.thenByDescending { it.updatedAt })

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gsheet_Plus", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Search tables") }, singleLine = true, modifier = Modifier.width(260.dp))
            Button(onClick = { creating = true }) { Text("New table") }
            Button(onClick = onLink) { Text("Open Google Sheet") }
            if (c.signedIn.value) TextButton(onClick = { c.signOut() }) { Text("Sign out") }
        }
        HorizontalDivider()
        if (shown.isEmpty()) {
            Text(
                if (tables.isEmpty()) "No tables yet. Create one, or open a Google Sheet." else "No table matches.",
                modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { t ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { onOpen(t.id) },
                    headlineContent = { Text((if (t.pinned) "📌 " else "") + t.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${t.rows} rows, ${t.columns} columns" + if (t.linked) " · linked to Google Sheet" else "") },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { menu = true }) { Text("⋮") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (t.pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; c.ws.setPinned(t.id, !t.pinned) })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = t.id })
                                DropdownMenuItem(text = { Text("Copy table") }, onClick = { menu = false; c.ws.copyTable(t.id, structureOnly = false); c.say("Table copied") })
                                DropdownMenuItem(text = { Text("Copy structure only") }, onClick = { menu = false; c.ws.copyTable(t.id, structureOnly = true); c.say("Structure copied") })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = t.id })
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (creating) NameDialog("New table", "Table name", "Create", "", { name -> creating = false; onOpen(c.ws.createTable(name.ifBlank { "Untitled" })) }, { creating = false })
    renaming?.let { id ->
        NameDialog("Rename table", "Table name", "Save", c.ws.grid(id)?.name.orEmpty(), { renaming = null; c.ws.renameTable(id, it) }, { renaming = null })
    }
    deleting?.let { id ->
        ConfirmDialog("Delete table?", "\"${c.ws.grid(id)?.name.orEmpty()}\" will be deleted from this PC. A linked Google Sheet is not touched.", "Delete", { deleting = null; c.ws.deleteTable(id) }, { deleting = null })
    }
}

@Composable
private fun LinkScreen(c: Controller, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    var filter by remember { mutableStateOf(SheetFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<SpreadsheetInfo?>(null) }

    fun load() {
        loading = true
        c.background("Sheet list") {
            try {
                files = c.api.listSpreadsheets(query, filter, null).files
            } finally {
                loading = false
            }
        }
    }

    fun openTab(info: SpreadsheetInfo, tab: Tab) {
        val existing = c.ws.tableForSheet(info.id, tab.sheetId)
        val id = existing ?: c.ws.createLinkedTable(
            if (info.tabs.size > 1) "${info.title} - ${tab.title}" else info.title,
            Link(info.id, tab.sheetId, tab.title, "https://docs.google.com/spreadsheets/d/${info.id}/edit#gid=${tab.sheetId}"),
        )
        c.syncNow(id)
        onOpen(id)
    }

    fun choose(spreadsheetId: String) {
        loading = true
        c.background("Open Sheet") {
            try {
                val info = c.api.spreadsheet(spreadsheetId)
                if (info.tabs.size == 1) openTab(info, info.tabs[0]) else picked = info
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Tables") }
            Text("Open a Google Sheet", style = MaterialTheme.typography.titleLarge)
        }
        if (!c.auth.isConfigured()) {
            Text("This build has no Google client id. Add the GSP_CLIENT_ID and GSP_CLIENT_SECRET secrets in GitHub and build again.", color = MaterialTheme.colorScheme.error)
        } else if (!c.signedIn.value) {
            Text("Sign in with Google to see your Sheets. A browser window opens; come back here when it says you can close it.")
            Button(onClick = { c.signIn { load() } }) { Text("Sign in with Google") }
        } else {
            val tabs = picked
            if (tabs != null) {
                Text("${tabs.title} has several tabs. Pick one:", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f)) {
                    items(tabs.tabs, key = { it.sheetId }) { t ->
                        ListItem(modifier = Modifier.clickable { openTab(tabs, t) }, headlineContent = { Text(t.title) }, supportingContent = { Text("${t.rows} rows, ${t.columns} columns") })
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = { picked = null }) { Text("Back to the list") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = link, onValueChange = { link = it }, placeholder = { Text("Paste a Sheet link") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(
                        enabled = parseSpreadsheetId(link) != null,
                        onClick = { parseSpreadsheetId(link)?.let { choose(it) } },
                    ) { Text("Open link") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SheetFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f, onClick = { filter = f; load() },
                            label = { Text(when (f) { SheetFilter.ALL -> "All"; SheetFilter.MINE -> "Mine"; SheetFilter.SHARED -> "Shared with me" }) },
                        )
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search by name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { load() }) { Text("Search") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (files.isEmpty() && !loading) Text("Press Search to list your Sheets.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(files, key = { it.id }) { f ->
                        ListItem(
                            modifier = Modifier.clickable { choose(f.id) },
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(f.owner?.let { "Owner: $it" }, f.modified.take(10)).joinToString(" · ")) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
