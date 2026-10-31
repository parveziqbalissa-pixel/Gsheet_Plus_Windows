package gsp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object SignIn : Screen
    data object Pick : Screen
    data class Tabs(val info: SpreadsheetInfo) : Screen
    data class Table(val title: String, val header: List<String>, val rows: List<List<String>>) : Screen
}

private val ID_HEADER = "_gsp_id"
private val PREVIEW_HEADER = Regex(" preview( \\d+)?$")

@Composable
fun App() {
    val auth = remember { GoogleAuth() }
    val api = remember { SheetsApi(auth) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(if (auth.isSignedIn()) Screen.Pick else Screen.SignIn) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(message: String, block: suspend () -> Unit) {
        scope.launch {
            busy = message
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotSignedIn) {
                screen = Screen.SignIn
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gsheet_Plus ${Version.NAME}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (screen !is Screen.SignIn) {
                if (screen !is Screen.Pick) TextButton(onClick = { screen = Screen.Pick }) { Text("All sheets") }
                TextButton(onClick = { auth.signOut(); screen = Screen.SignIn }) { Text("Sign out") }
            }
        }
        if (busy != null) {
            Text(busy!!, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }

        when (val current = screen) {
            Screen.SignIn -> SignInScreen(
                configured = auth.isConfigured(),
                enabled = busy == null,
                onSignIn = { run("Waiting for Google in your browser...") { auth.signIn(); screen = Screen.Pick } },
            )

            Screen.Pick -> PickScreen(
                api = api,
                onError = { error = it },
                onPick = { file ->
                    run("Opening \"${file.name}\"...") {
                        val info = api.spreadsheet(file.id)
                        screen = if (info.tabs.size == 1) openTab(api, info, info.tabs[0]) else Screen.Tabs(info)
                    }
                },
            )

            is Screen.Tabs -> Column {
                Text(current.info.title, style = MaterialTheme.typography.titleMedium)
                Text("Choose a tab:")
                current.info.tabs.forEach { tab ->
                    TextButton(onClick = { run("Loading \"${tab.title}\"...") { screen = openTab(api, current.info, tab) } }) {
                        Text("${tab.title}  (${tab.rows} rows)")
                    }
                }
            }

            is Screen.Table -> TableScreen(current)
        }
    }
}

private suspend fun openTab(api: SheetsApi, info: SpreadsheetInfo, tab: Tab): Screen.Table {
    val all = api.values(info.id, tab.title)
    val header = all.firstOrNull().orEmpty()
    // Hide the app's hidden id column and the picture preview columns, like the phone app does.
    val keep = header.indices.filter { header[it] != ID_HEADER && !PREVIEW_HEADER.containsMatchIn(header[it]) }
    val rows = all.drop(1).map { row -> keep.map { row.getOrElse(it) { "" } } }
    val title = if (info.tabs.size > 1) "${info.title} - ${tab.title}" else info.title
    return Screen.Table(title, keep.map { header[it] }, rows)
}

@Composable
private fun SignInScreen(configured: Boolean, enabled: Boolean, onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
        Text("Open your Google Sheets on this PC.", style = MaterialTheme.typography.titleMedium)
        Text("Your browser opens so you can approve access. Use a Google account that is on the test users list.")
        if (!configured) {
            Text(
                "This build has no Google sign-in key. Add GSP_CLIENT_ID and GSP_CLIENT_SECRET as GitHub secrets and build again.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onSignIn, enabled = enabled && configured) { Text("Sign in with Google") }
    }
}

@Composable
private fun PickScreen(api: SheetsApi, onError: (String?) -> Unit, onPick: (DriveFile) -> Unit) {
    var filter by remember { mutableStateOf(SheetFilter.ALL) }
    var search by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun load(append: Boolean) {
        job?.cancel()
        job = scope.launch {
            loading = true
            onError(null)
            try {
                val page = api.listSpreadsheets(search, filter, if (append) next else null)
                files = if (append) files + page.files else page.files
                next = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(filter, search, reload) {
        delay(if (search.isEmpty()) 0 else 350)
        load(append = false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search your spreadsheets") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(SheetFilter.ALL to "All", SheetFilter.MINE to "Mine", SheetFilter.SHARED to "Shared with me").forEach { (f, label) ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(label) })
            }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth()) {
            items(files, key = { it.id }) { file ->
                ListItem(
                    headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull("Edited ${file.modified}", file.owner).joinToString(" - ")) },
                    modifier = Modifier.clickable { onPick(file) },
                )
                HorizontalDivider()
            }
            item {
                if (next != null) {
                    TextButton(onClick = { load(append = true) }, enabled = !loading) { Text("Load more") }
                } else if (!loading && files.isEmpty()) {
                    Text(
                        if (filter == SheetFilter.SHARED) "No spreadsheets have been shared with you." else "No spreadsheets found.",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TableScreen(table: Screen.Table) {
    val scroll = rememberScrollState()
    val cellWidth = 170.dp
    Column(Modifier.fillMaxSize()) {
        Text(table.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        Text("${table.rows.size} rows. Read-only in this first version.", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Column(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                Row {
                    Box(Modifier.width(48.dp).height(36.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("#") }
                    table.header.forEach { name ->
                        Box(Modifier.width(cellWidth).height(36.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(table.rows.size) { index ->
                        Row {
                            Box(Modifier.width(48.dp).height(34.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("${index + 1}") }
                            table.rows[index].forEach { value ->
                                Box(Modifier.width(cellWidth).height(34.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                                    Text(value.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
