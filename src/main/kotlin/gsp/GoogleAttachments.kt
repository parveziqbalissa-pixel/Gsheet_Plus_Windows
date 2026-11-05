package gsp

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Uploads to Google Drive (folder "Gsheet_Plus", drive.file scope) and writes links and thumbnails into the Sheet. */
class GoogleAttachments(private val auth: GoogleAuth, private val api: SheetsApi) : AttachmentGateway {
    private val http = HttpClient.newHttpClient()

    @Volatile
    private var folderId: String? = null

    override suspend fun upload(file: File, name: String, mime: String, shareByLink: Boolean): Pair<String, String> {
        if (!file.exists()) throw ApiException(0, "The file \"$name\" is no longer on this PC.")
        if (file.length() > MAX_BYTES) throw ApiException(0, "\"$name\" is larger than 25 MB and was not uploaded.")
        val result = try {
            send(file, name, mime, folder())
        } catch (e: ApiException) {
            if (e.code != 404) throw e
            folderId = null
            send(file, name, mime, folder())
        }
        if (shareByLink) {
            // If the account does not allow public links the picture simply has no thumbnail in the Sheet.
            runCatching {
                api.call("POST", "$FILES/${result.first}/permissions", Json.write(mapOf("role" to "reader", "type" to "anyone")))
            }
        }
        return result
    }

    private fun send(file: File, name: String, mime: String, folder: String): Pair<String, String> {
        val boundary = "gsp" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun text(value: String) = body.write(value.toByteArray(Charsets.UTF_8))
        text("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
        text(Json.write(mapOf("name" to name, "parents" to listOf(folder))))
        text("\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n")
        body.write(file.readBytes())
        text("\r\n--$boundary--")
        val request = HttpRequest.newBuilder(URI("$UPLOAD?uploadType=multipart&fields=" + enc("id,webViewLink")))
            .header("Authorization", "Bearer ${auth.token()}")
            .header("Content-Type", "multipart/related; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = runCatching { Json.parse(response.body()).obj() }.getOrNull() ?: emptyMap()
        if (response.statusCode() !in 200..299) {
            val detail = json["error"].obj()?.get("message").text()
            throw ApiException(
                response.statusCode(),
                if (response.statusCode() == 403) "Google Drive refused the upload. Is the Google Drive API enabled? $detail".trim()
                else "Google Drive error ${response.statusCode()}. $detail".trim(),
            )
        }
        val id = json["id"].text()
        return id to json["webViewLink"].text().ifEmpty { "https://drive.google.com/file/d/$id/view" }
    }

    private fun folder(): String {
        folderId?.let { return it }
        val query = "mimeType='application/vnd.google-apps.folder' and name='$FOLDER_NAME' and trashed=false"
        val found = api.call("GET", "$FILES?q=${enc(query)}&fields=${enc("files(id)")}&pageSize=1", null)["files"].arr()?.firstOrNull().obj()
        val id = found?.get("id").text().ifEmpty {
            api.call("POST", "$FILES?fields=id", Json.write(mapOf("name" to FOLDER_NAME, "mimeType" to "application/vnd.google-apps.folder")))["id"].text()
        }
        folderId = id
        return id
    }

    override suspend fun links(spreadsheetId: String, tabTitle: String, columns: List<Int>): Map<Pair<Int, Int>, List<Pair<String, String>>> {
        val result = HashMap<Pair<Int, Int>, List<Pair<String, String>>>()
        val quoted = "'" + tabTitle.replace("'", "''") + "'"
        for (chunk in columns.chunked(20)) {
            val ranges = chunk.joinToString("&") { "ranges=" + enc("$quoted!${colLetter(it)}2:${colLetter(it)}") }
            val fields = enc("sheets(data(startRow,rowData(values(formattedValue,hyperlink,textFormatRuns,chipRuns))))")
            val json = api.call("GET", "$SHEETS/${enc(spreadsheetId)}?$ranges&includeGridData=true&fields=$fields", null)
            val data = json["sheets"].arr()?.firstOrNull().obj()?.get("data").arr().orEmpty()
            data.forEachIndexed { i, d ->
                val column = chunk.getOrNull(i) ?: return@forEachIndexed
                val o = d.obj() ?: return@forEachIndexed
                val startRow = (o["startRow"] as? Number)?.toInt() ?: 0
                o["rowData"].arr().orEmpty().forEachIndexed { r, rd ->
                    val cell = rd.obj()?.get("values").arr()?.firstOrNull().obj() ?: return@forEachIndexed
                    val text = cell["formattedValue"].text()
                    if (text.isEmpty()) return@forEachIndexed
                    val runs = cell["textFormatRuns"].arr().orEmpty().mapNotNull { it.obj() }.map { run ->
                        ((run["startIndex"] as? Number)?.toInt() ?: 0) to run["format"].obj()?.get("link").obj()?.get("uri").text()
                    }
                    val whole = cell["hyperlink"].text()
                    // Files attached in Google Sheets as smart chips (Insert > Drive file) keep their link in chipRuns.
                    val chips = cell["chipRuns"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { run ->
                        val uri = run["chip"].obj()?.get("richLinkProperties").obj()?.get("uri").text()
                        if (uri.isEmpty()) null else ((run["startIndex"] as? Number)?.toInt() ?: 0) to uri
                    }
                    val entries = ArrayList<Pair<String, String>>()
                    var offset = 0
                    for (line in text.split("\n")) {
                        val chipUri = chips.firstOrNull { it.first >= offset && it.first <= offset + line.length }?.second
                        val uri = chipUri ?: runs.filter { it.first <= offset }.maxByOrNull { it.first }?.second?.ifEmpty { null }
                            ?: (if (runs.isEmpty() && !text.contains("\n")) whole.ifEmpty { null } else null)
                        val shown = line.trim().trim('@').trim()
                        if (uri != null && line.trim().isNotEmpty()) entries += (if (chipUri != null && shown.isEmpty()) "Drive file" else line.trim()) to uri
                        offset += line.length + 1
                    }
                    if (entries.isNotEmpty()) result[(startRow + r) to column] = entries
                }
            }
        }
        return result
    }

    override suspend fun header(spreadsheetId: String, tabTitle: String): List<String> {
        val range = "'" + tabTitle.replace("'", "''") + "'!1:1"
        val json = api.call("GET", "$SHEETS/${enc(spreadsheetId)}/values/${enc(range)}?valueRenderOption=UNFORMATTED_VALUE", null)
        return json["values"].arr()?.firstOrNull().arr().orEmpty().map { it.text() }
    }

    override suspend fun write(
        spreadsheetId: String, sheetId: Int, tabTitle: String,
        links: List<LinkCell>, cells: Map<Pair<Int, Int>, String>, headers: Map<Int, String>,
    ) {
        val base = "$SHEETS/${enc(spreadsheetId)}"
        val quoted = "'" + tabTitle.replace("'", "''") + "'"
        val maxColumn = maxOf(headers.keys.maxOrNull() ?: -1, cells.keys.maxOfOrNull { it.second } ?: -1)
        if (maxColumn >= 0) {
            val size = api.spreadsheet(spreadsheetId).tabs.firstOrNull { it.sheetId == sheetId }
            if (size != null && maxColumn + 1 > size.columns) {
                val grow = mapOf("requests" to listOf(mapOf("appendDimension" to mapOf("sheetId" to sheetId, "dimension" to "COLUMNS", "length" to maxColumn + 1 - size.columns))))
                api.call("POST", "$base:batchUpdate", Json.write(grow))
            }
        }
        val data = ArrayList<Any?>()
        for ((c, title) in headers) data += mapOf("range" to "$quoted!${colLetter(c)}1", "majorDimension" to "ROWS", "values" to listOf(listOf(title)))
        for ((pos, value) in cells) data += mapOf("range" to "$quoted!${colLetter(pos.second)}${pos.first + 1}", "majorDimension" to "ROWS", "values" to listOf(listOf(value)))
        for (chunk in data.chunked(200)) {
            api.call("POST", "$base/values:batchUpdate", Json.write(mapOf("valueInputOption" to "USER_ENTERED", "data" to chunk)))
        }
        val requests = links.map { cell ->
            mapOf(
                "updateCells" to mapOf(
                    "rows" to listOf(
                        mapOf(
                            "values" to listOf(
                                mapOf(
                                    "userEnteredValue" to mapOf("stringValue" to cell.text),
                                    "textFormatRuns" to cell.runs.map { run ->
                                        mapOf("startIndex" to run.start, "format" to (if (run.uri != null) mapOf("link" to mapOf("uri" to run.uri)) else emptyMap<String, Any?>()))
                                    },
                                ),
                            ),
                        ),
                    ),
                    "fields" to "userEnteredValue,textFormatRuns",
                    "start" to mapOf("sheetId" to sheetId, "rowIndex" to cell.row, "columnIndex" to cell.col),
                ),
            )
        }
        for (chunk in requests.chunked(100)) {
            api.call("POST", "$base:batchUpdate", Json.write(mapOf("requests" to chunk)))
        }
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private companion object {
        const val FOLDER_NAME = "Gsheet_Plus"
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val SHEETS = "https://sheets.googleapis.com/v4/spreadsheets"
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
