package gsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

/** Reads Google Sheets and the Drive list. Stage 1 only reads. */
class SheetsApi(private val auth: GoogleAuth) {
    private val http = HttpClient.newHttpClient()

    suspend fun listSpreadsheets(search: String, filter: SheetFilter, pageToken: String?): FilePage {
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
        val json = get(url.toString())
        val array = json.optJSONArray("files") ?: JSONArray()
        val files = List(array.length()) { i ->
            val f = array.getJSONObject(i)
            DriveFile(
                id = f.getString("id"),
                name = f.optString("name").ifEmpty { "(untitled)" },
                modified = f.optString("modifiedTime").take(10),
                owner = if (f.optBoolean("ownedByMe")) "You" else f.optJSONArray("owners")?.optJSONObject(0)?.optString("displayName")?.ifEmpty { null },
            )
        }
        return FilePage(files, json.optString("nextPageToken").ifEmpty { null })
    }

    suspend fun spreadsheet(id: String): SpreadsheetInfo {
        val fields = "properties.title,sheets.properties(sheetId,title,gridProperties(rowCount,columnCount))"
        val json = get("https://sheets.googleapis.com/v4/spreadsheets/${enc(id)}?fields=${enc(fields)}")
        val sheets = json.optJSONArray("sheets") ?: JSONArray()
        val tabs = List(sheets.length()) { i ->
            val p = sheets.getJSONObject(i).getJSONObject("properties")
            val grid = p.optJSONObject("gridProperties")
            Tab(p.optInt("sheetId"), p.optString("title"), grid?.optInt("rowCount") ?: 0, grid?.optInt("columnCount") ?: 0)
        }
        return SpreadsheetInfo(id, json.optJSONObject("properties")?.optString("title").orEmpty(), tabs)
    }

    /** All cells of a tab as text, every row padded to the same width. */
    suspend fun values(id: String, tabTitle: String): List<List<String>> {
        val range = "'" + tabTitle.replace("'", "''") + "'"
        val json = get("https://sheets.googleapis.com/v4/spreadsheets/${enc(id)}/values/${enc(range)}?valueRenderOption=FORMATTED_VALUE")
        val rows = json.optJSONArray("values") ?: JSONArray()
        val parsed = List(rows.length()) { r ->
            val row = rows.getJSONArray(r)
            List(row.length()) { c -> row.opt(c)?.toString().orEmpty() }
        }
        val width = parsed.maxOfOrNull { it.size } ?: 0
        return parsed.map { it + List(width - it.size) { "" } }
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .header("Authorization", "Bearer ${auth.token()}")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = runCatching { JSONObject(response.body()) }.getOrElse { JSONObject() }
        if (response.statusCode() !in 200..299) {
            val message = body.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "Google returned HTTP ${response.statusCode()}." }
            throw ApiException(response.statusCode(), message)
        }
        body
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
}
