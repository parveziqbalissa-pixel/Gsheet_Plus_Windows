package gsp

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ApiException(val code: Int, message: String) : Exception(message)

enum class SheetFilter { ALL, MINE, SHARED }

data class DriveFile(val id: String, val name: String, val modified: String, val owner: String?)

data class FilePage(val files: List<DriveFile>, val next: String?)

data class Tab(val sheetId: Int, val title: String, val rows: Int, val columns: Int)

data class SpreadsheetInfo(val id: String, val title: String, val tabs: List<Tab>)

fun colLetter(index: Int): String {
    var n = index
    val sb = StringBuilder()
    while (n >= 0) {
        sb.append('A' + n % 26)
        n = n / 26 - 1
    }
    return sb.reverse().toString()
}

fun parseSpreadsheetId(input: String): String? {
    val text = input.trim()
    Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(text)?.let { return it.groupValues[1] }
    return text.takeIf { Regex("[a-zA-Z0-9_-]{20,}").matches(it) }
}

/**
 * Talks to Google Sheets and Drive. The functions block, so call them off the screen thread.
 * Also the real [SheetsGateway] used by the sync.
 */
class SheetsApi(private val auth: GoogleAuth) : SheetsGateway {
    private val http = HttpClient.newHttpClient()

    fun listSpreadsheets(search: String, filter: SheetFilter, pageToken: String?): FilePage {
        val query = StringBuilder("mimeType='application/vnd.google-apps.spreadsheet' and trashed=false")
        when (filter) {
            SheetFilter.ALL -> Unit
            SheetFilter.MINE -> query.append(" and 'me' in owners")
            SheetFilter.SHARED -> query.append(" and sharedWithMe=true")
        }
        val term = search.trim()
        if (term.isNotEmpty()) {
            query.append(" and name contains '").append(term.replace("\\", "\\\\").replace("'", "\\'")).append("'")
        }
        val fields = "nextPageToken,files(id,name,modifiedTime,ownedByMe,owners(displayName))"
        val url = StringBuilder(
            "https://www.googleapis.com/drive/v3/files?q=${enc(query.toString())}&orderBy=${enc("modifiedTime desc")}" +
                "&pageSize=50&fields=${enc(fields)}&supportsAllDrives=true&includeItemsFromAllDrives=true",
        )
        if (pageToken != null) url.append("&pageToken=").append(enc(pageToken))
        val json = call("GET", url.toString(), null)
        val files = json["files"].arr().orEmpty().mapNotNull { it.obj() }.map { f ->
            DriveFile(
                id = f["id"].text(),
                name = f["name"].text().ifEmpty { "(untitled)" },
                modified = f["modifiedTime"].text().take(10),
                owner = if (f["ownedByMe"] == true) "You" else f["owners"].arr()?.firstOrNull().obj()?.get("displayName").text().ifEmpty { null },
            )
        }
        return FilePage(files, json["nextPageToken"].text().ifEmpty { null })
    }

    fun spreadsheet(id: String): SpreadsheetInfo {
        val fields = "properties.title,sheets.properties(sheetId,title,gridProperties(rowCount,columnCount))"
        val json = call("GET", "https://sheets.googleapis.com/v4/spreadsheets/${enc(id)}?fields=${enc(fields)}", null)
        val tabs = json["sheets"].arr().orEmpty().mapNotNull { it.obj()?.get("properties").obj() }.map { p ->
            val grid = p["gridProperties"].obj()
            Tab(
                (p["sheetId"] as? Number)?.toInt() ?: 0, p["title"].text(),
                (grid?.get("rowCount") as? Number)?.toInt() ?: 0, (grid?.get("columnCount") as? Number)?.toInt() ?: 0,
            )
        }
        return SpreadsheetInfo(id, json["properties"].obj()?.get("title").text(), tabs)
    }

    // ----- SheetsGateway -----

    override suspend fun read(spreadsheetId: String, tabTitle: String): SheetRead {
        val range = quoted(tabTitle)
        val base = "https://sheets.googleapis.com/v4/spreadsheets/${enc(spreadsheetId)}"
        val raw = call("GET", "$base/values/${enc(range)}?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER", null)
        val typed = call("GET", "$base/values/${enc(range)}?valueRenderOption=FORMULA", null)
        val values = raw["values"].arr().orEmpty().map { it.arr().orEmpty() }
        val formulas = typed["values"].arr().orEmpty().map { row -> row.arr().orEmpty().map { it.text() } }
        val dates = HashSet<Int>()
        val times = HashSet<Int>()
        val validations = HashMap<Int, SheetValidation>()
        runCatching {
            // Look at the first 100 data rows: the first filled cell of a column shows its date format or dropdown.
            val grid = call(
                "GET",
                "$base?ranges=${enc(range + "!2:101")}&includeGridData=true&fields=" +
                    enc("sheets(properties.gridProperties,data.rowData.values(effectiveFormat.numberFormat.type,dataValidation,formattedValue))"),
                null,
            )
            val sheet = grid["sheets"].arr()?.firstOrNull().obj()
            val rowData = sheet?.get("data").arr()?.firstOrNull().obj()?.get("rowData").arr().orEmpty()
            val seen = HashSet<Int>()
            for ((rowIndex, rd) in rowData.withIndex()) {
                val cells = rd.obj()?.get("values").arr().orEmpty()
                cells.forEachIndexed { c, cell ->
                    val o = cell.obj() ?: return@forEachIndexed
                    if (c in seen) return@forEachIndexed
                    val filled = o["formattedValue"].text().isNotEmpty()
                    val nf = o["effectiveFormat"].obj()?.get("numberFormat").obj()?.get("type").text()
                    val cond = o["dataValidation"].obj()?.get("condition").obj()
                    val kind = cond?.get("type").text()
                    if (kind == "BOOLEAN") {
                        validations[c] = SheetValidation(true, emptyList()); seen += c
                    } else if (kind == "ONE_OF_LIST") {
                        val items = cond?.get("values").arr().orEmpty().map { it.obj()?.get("userEnteredValue").text() }.filter { it.isNotEmpty() }
                        if (items.isNotEmpty()) { validations[c] = SheetValidation(false, items); seen += c }
                    } else if (nf == "DATE" || nf == "DATE_TIME") {
                        dates += c; seen += c
                    } else if (nf == "TIME") {
                        times += c; seen += c
                    } else if (filled) {
                        // The format label can be missing or generic: what the cell shows tells as well.
                        val shown = o["formattedValue"].text()
                        val number = values.getOrNull(rowIndex + 1)?.getOrNull(c) is Number
                        if (number) {
                            if (SheetImport.looksLikeDate(shown)) { dates += c; seen += c }
                            else if (SheetImport.looksLikeTime(shown)) { times += c; seen += c }
                        }
                    }
                }
            }
        }
        val size = tabSize(spreadsheetId, tabTitle)
        return SheetRead(
            if (values.isEmpty()) listOf(emptyList()) else values,
            if (formulas.isEmpty()) listOf(emptyList()) else formulas,
            dates, validations, size.first, size.second, times,
        )
    }

    private fun tabSize(spreadsheetId: String, tabTitle: String): Pair<Int, Int> {
        val tab = spreadsheet(spreadsheetId).tabs.firstOrNull { it.title == tabTitle } ?: return 1000 to 26
        return tab.rows to tab.columns
    }

    override suspend fun apply(spreadsheetId: String, sheetId: Int, tabTitle: String, ops: WriteOps) {
        val base = "https://sheets.googleapis.com/v4/spreadsheets/${enc(spreadsheetId)}"
        val (rows, cols) = tabSize(spreadsheetId, tabTitle)
        val grow = ArrayList<Any?>()
        if (ops.needRows > rows) grow += mapOf("appendDimension" to mapOf("sheetId" to sheetId, "dimension" to "ROWS", "length" to ops.needRows - rows))
        if (ops.needColumns > cols) grow += mapOf("appendDimension" to mapOf("sheetId" to sheetId, "dimension" to "COLUMNS", "length" to ops.needColumns - cols))
        if (grow.isNotEmpty()) call("POST", "$base:batchUpdate", Json.write(mapOf("requests" to grow)))

        if (ops.cells.isNotEmpty()) {
            val data = ArrayList<Any?>()
            for ((r, cells) in ops.cells.entries.groupBy({ it.key.first }, { it.key.second to it.value }).toSortedMap()) {
                val sorted = cells.sortedBy { it.first }
                var i = 0
                while (i < sorted.size) {
                    var j = i
                    while (j + 1 < sorted.size && sorted[j + 1].first == sorted[j].first + 1) j++
                    val start = sorted[i].first
                    val end = sorted[j].first
                    data += mapOf(
                        "range" to quoted(tabTitle) + "!" + colLetter(start) + (r + 1) + ":" + colLetter(end) + (r + 1),
                        "majorDimension" to "ROWS",
                        "values" to listOf((i..j).map { sorted[it].second }),
                    )
                    i = j + 1
                }
            }
            for (chunk in data.chunked(200)) {
                call("POST", "$base/values:batchUpdate", Json.write(mapOf("valueInputOption" to "USER_ENTERED", "data" to chunk)))
            }
        }

        val requests = ArrayList<Any?>()
        for (c in ops.hideColumns) {
            requests += mapOf(
                "updateDimensionProperties" to mapOf(
                    "range" to mapOf("sheetId" to sheetId, "dimension" to "COLUMNS", "startIndex" to c, "endIndex" to c + 1),
                    "properties" to mapOf("hiddenByUser" to true),
                    "fields" to "hiddenByUser",
                ),
            )
        }
        for ((c, v) in ops.validations) {
            val range = mapOf("sheetId" to sheetId, "startRowIndex" to 1, "startColumnIndex" to c, "endColumnIndex" to c + 1)
            val rule = when {
                v == null -> null
                v.checkbox -> mapOf("condition" to mapOf("type" to "BOOLEAN"), "strict" to true, "showCustomUi" to true)
                else -> mapOf(
                    "condition" to mapOf("type" to "ONE_OF_LIST", "values" to v.items.map { mapOf("userEnteredValue" to it) }),
                    "strict" to false, "showCustomUi" to true,
                )
            }
            requests += mapOf("setDataValidation" to (if (rule == null) mapOf("range" to range) else mapOf("range" to range, "rule" to rule)))
        }
        for (r in ops.deleteRows.sortedDescending()) {
            requests += mapOf("deleteDimension" to mapOf("range" to mapOf("sheetId" to sheetId, "dimension" to "ROWS", "startIndex" to r, "endIndex" to r + 1)))
        }
        for (c in ops.deleteColumns.sortedDescending()) {
            requests += mapOf("deleteDimension" to mapOf("range" to mapOf("sheetId" to sheetId, "dimension" to "COLUMNS", "startIndex" to c, "endIndex" to c + 1)))
        }
        for ((c, kind) in ops.formats) {
            val format = when (kind) {
                "DATE" -> mapOf("numberFormat" to mapOf("type" to "DATE", "pattern" to "yyyy-mm-dd"))
                "TIME" -> mapOf("numberFormat" to mapOf("type" to "TIME", "pattern" to "hh:mm"))
                else -> emptyMap()
            }
            requests += mapOf(
                "repeatCell" to mapOf(
                    "range" to mapOf("sheetId" to sheetId, "startRowIndex" to 1, "startColumnIndex" to c, "endColumnIndex" to c + 1),
                    "cell" to mapOf("userEnteredFormat" to format),
                    "fields" to "userEnteredFormat.numberFormat",
                ),
            )
        }
        for ((from, destination) in ops.moveColumns) {
            requests += mapOf(
                "moveDimension" to mapOf(
                    "source" to mapOf("sheetId" to sheetId, "dimension" to "COLUMNS", "startIndex" to from, "endIndex" to from + 1),
                    "destinationIndex" to destination,
                ),
            )
        }
        if (requests.isNotEmpty()) call("POST", "$base:batchUpdate", Json.write(mapOf("requests" to requests)))
    }

    /** Creates a spreadsheet with one tab; returns its id, url and the tab. */
    fun createSpreadsheet(title: String, tabTitle: String): Triple<String, String, Tab> {
        val body = Json.write(mapOf("properties" to mapOf("title" to title), "sheets" to listOf(mapOf("properties" to mapOf("title" to tabTitle)))))
        val json = call("POST", "https://sheets.googleapis.com/v4/spreadsheets", body)
        val id = json["spreadsheetId"].text()
        val url = json["spreadsheetUrl"].text()
        val p = json["sheets"].arr()?.firstOrNull().obj()?.get("properties").obj()
        val tab = Tab((p?.get("sheetId") as? Number)?.toInt() ?: 0, p?.get("title").text(), 1000, 26)
        return Triple(id, url, tab)
    }

    // ----- plumbing -----

    private fun quoted(tab: String) = "'" + tab.replace("'", "''") + "'"

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    fun call(method: String, url: String, body: String?): Map<String, Any?> {
        val builder = HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer ${auth.token()}")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val json = runCatching { Json.parse(response.body()).obj() }.getOrNull() ?: emptyMap()
        if (response.statusCode() !in 200..299) {
            val message = json["error"].obj()?.get("message").text().ifEmpty { "Google returned HTTP ${response.statusCode()}." }
            throw ApiException(response.statusCode(), message)
        }
        return json
    }
}
