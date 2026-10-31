package gsp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
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
    object Settings : Screen
    class Grid(val id: Long) : Screen
}

@Composable
fun App(c: Controller) {
    var screen by remember { mutableStateOf<Screen>(Screen.Tables) }
    when (val s = screen) {
        Screen.Tables -> TableListScreen(c, onOpen = { screen = Screen.Grid(it) }, onLink = { screen = Screen.Link }, onSettings = { screen = Screen.Settings })
        Screen.Settings -> SettingsScreen(c, onBack = { screen = Screen.Tables })
        Screen.Link -> LinkScreen(c, onBack = { screen = Screen.Tables }, onOpen = { screen = Screen.Grid(it) })
        is Screen.Grid -> GridScreen(c, s.id, onBack = { screen = Screen.Tables })
    }
}

@Composable
private fun TableListScreen(c: Controller, onOpen: (Long) -> Unit, onLink: () -> Unit, onSettings: () -> Unit) {
    var tick by remember { mutableStateOf(0L) }
    DisposableEffect(Unit) {
        val stop = c.ws.changes.listen { tick = it }
        onDispose { stop() }
    }
    val tables = remember(tick) { c.ws.summaries() }
    var search by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var binOpen by remember { mutableStateOf(false) }
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
            TextButton(onClick = { binOpen = true }) { Text("Bin") }
            if (c.hasPin.value) TextButton(onClick = { c.lockNow() }) { Text("Lock") }
            TextButton(onClick = onSettings) { Text("Settings") }
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

    if (creating) {
        NewTableDialog(onCreate = { name, template ->
            creating = false
            onOpen(if (template == null) c.ws.createTable(name) else c.ws.createFromTemplate(name, template))
        }, onDismiss = { creating = false })
    }
    if (binOpen) TrashDialog(c) { binOpen = false }
    renaming?.let { id ->
        NameDialog("Rename table", "Table name", "Save", c.ws.grid(id)?.name.orEmpty(), { renaming = null; c.ws.renameTable(id, it) }, { renaming = null })
    }
    deleting?.let { id ->
        ConfirmDialog("Delete table?", "\"${c.ws.grid(id)?.name.orEmpty()}\" goes to the Bin, where you can restore it. A linked Google Sheet is not touched.", "Delete", { deleting = null; c.ws.deleteTable(id) }, { deleting = null })
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

@Composable
private fun SettingsScreen(c: Controller, onBack: () -> Unit) {
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Tables") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        Text("Google account", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (c.signedIn.value) "Signed in" else "Not signed in", modifier = Modifier.width(120.dp))
            if (c.signedIn.value) {
                Button(onClick = { confirmSignOut = true }) { Text("Sign out") }
            } else {
                Button(onClick = { c.signIn() }, enabled = c.auth.isConfigured()) { Text("Sign in with Google") }
            }
        }
        Text("Signing out keeps your tables on this PC. Sheets sync again after you sign in.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Automatic sync of the open table", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Off", 5 to "5 seconds", 15 to "15 seconds", 30 to "30 seconds", 60 to "1 minute").forEach { (seconds, label) ->
                FilterChip(selected = c.syncSeconds.value == seconds, onClick = { c.setSyncSeconds(seconds) }, label = { Text(label) })
            }
        }
        Text("Edits are always sent a moment after you make them. This is how often changes made elsewhere (for example on your phone) are fetched.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = c.dark.value, onCheckedChange = { c.setDark(it) })
            Text("Dark mode")
        }
        HorizontalDivider()
        Text("Pictures in the table", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Names only", 72 to "Small", 96 to "Medium", 150 to "Large", 220 to "Extra large").forEach { (h, label) ->
                FilterChip(selected = c.pictureRows.value == h, onClick = { c.setPictureRows(h) }, label = { Text(label) })
            }
        }
        HorizontalDivider()
        Text("Report letterhead", style = MaterialTheme.typography.titleMedium)
        val header = c.reportHeader.value
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = header.business, onValueChange = { c.setReportHeader(ReportHeader(it, header.line2, header.logoPath)) }, label = { Text("Business or your name") }, singleLine = true, modifier = Modifier.width(260.dp))
            OutlinedTextField(value = header.line2, onValueChange = { c.setReportHeader(ReportHeader(header.business, it, header.logoPath)) }, label = { Text("Second line (address, phone)") }, singleLine = true, modifier = Modifier.width(320.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(header.logoPath?.let { "Logo: " + java.io.File(it).name } ?: "No logo", modifier = Modifier.width(240.dp))
            TextButton(onClick = {
                val chooser = javax.swing.JFileChooser()
                chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Pictures", "png", "jpg", "jpeg", "gif")
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    c.setReportHeader(ReportHeader(header.business, header.line2, chooser.selectedFile.absolutePath))
                }
            }) { Text("Choose logo...") }
            if (header.logoPath != null) TextButton(onClick = { c.setReportHeader(ReportHeader(header.business, header.line2, null)) }) { Text("Remove logo") }
        }
        Text("Report / PDF in a table opens the rows shown as a printable page; choose \"Save as PDF\" in the print window, then share the PDF on WhatsApp or by email.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Backups", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = c.dailyBackup.value, onCheckedChange = { c.setDailyBackup(it) })
            Text("Back up all tables once a day (keeps the last 10)")
            Button(onClick = { c.backupNow() }) { Text("Back up now") }
        }
        var backupList by remember { mutableStateOf(c.backups.list()) }
        var restoring by remember { mutableStateOf<java.nio.file.Path?>(null) }
        if (backupList.isEmpty()) Text("No backups yet.", style = MaterialTheme.typography.bodySmall)
        backupList.take(5).forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.fileName.toString().removePrefix("backup_").removeSuffix(".zip").replace('_', ' '), modifier = Modifier.width(240.dp))
                TextButton(onClick = { restoring = b }) { Text("Restore") }
            }
        }
        TextButton(onClick = { backupList = c.backups.list() }) { Text("Refresh list") }
        restoring?.let { b ->
            ConfirmDialog(
                "Restore this backup?",
                "All tables will be replaced by the ones in this backup when Gsheet_Plus starts again. The app closes now; open it again to finish.",
                "Restore and close",
                { c.backups.scheduleRestore(b); kotlin.system.exitProcess(0) },
                { restoring = null },
            )
        }
        HorizontalDivider()
        Text("PIN lock", style = MaterialTheme.typography.titleMedium)
        var pinDialog by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (c.hasPin.value) "On - asked when the app opens" else "Off", modifier = Modifier.width(220.dp))
            Button(onClick = { pinDialog = true }) { Text(if (c.hasPin.value) "Change PIN" else "Set a PIN") }
            if (c.hasPin.value) TextButton(onClick = { c.setPin(null); c.say("PIN lock removed") }) { Text("Remove PIN") }
        }
        if (pinDialog) PinDialog(onSet = { pinDialog = false; c.setPin(it); c.say("PIN lock is on") }, onDismiss = { pinDialog = false })
        HorizontalDivider()
        Text("Quick add", style = MaterialTheme.typography.titleMedium)
        Text("Right-click the Gsheet_Plus icon in the taskbar tray (near the clock) and choose \"Quick add row\" to add a row without opening a table. You can also drag files from Explorer onto a selected Picture or File cell.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Data", style = MaterialTheme.typography.titleMedium)
        Text(c.dataFolder.toString(), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { runCatching { java.awt.Desktop.getDesktop().open(c.dataFolder.toFile()) } }) { Text("Open data folder") }
        HorizontalDivider()
        Text("Gsheet_Plus ${Version.NAME}", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmSignOut) {
        ConfirmDialog("Sign out?", "Your tables stay on this PC.", "Sign out", { confirmSignOut = false; c.signOut() }, { confirmSignOut = false })
    }
}
