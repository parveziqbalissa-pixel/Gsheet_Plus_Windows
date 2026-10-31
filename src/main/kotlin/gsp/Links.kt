package gsp

import java.awt.Desktop
import java.net.URI

private val URL_ONLY = Regex("^(https?://\\S+|www\\.\\S+\\.\\S+)$", RegexOption.IGNORE_CASE)
private val EMAIL_ONLY = Regex("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$")
private val INTERNATIONAL_PHONE = Regex("^\\+\\d[\\d\\s().-]{6,}$")

/** Address to open for a cell: a web address, an email (mailto:) or a phone number (tel:); null when the cell is not a link. */
fun linkOf(column: GridColumn, value: String): String? {
    val v = value.trim()
    if (v.isEmpty() || v.contains('\n')) return null
    if (column.isNumeric || column.type.isAttachment || column.type == ColumnType.DATE ||
        column.type == ColumnType.TIME || column.type == ColumnType.CHECKBOX
    ) return null
    val digits = v.count { it.isDigit() }
    return when {
        URL_ONLY.matches(v) -> if (v.startsWith("www.", ignoreCase = true)) "https://$v" else v
        EMAIL_ONLY.matches(v) -> "mailto:$v"
        column.type == ColumnType.PHONE && digits >= 5 && v.all { it.isDigit() || it in "+ ()-.*#" } ->
            "tel:" + v.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        column.type == ColumnType.ADDRESS ->
            "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(v, "UTF-8")
        column.type != ColumnType.PHONE && INTERNATIONAL_PHONE.matches(v) && digits >= 8 ->
            "tel:" + v.filter { it.isDigit() || it == '+' }
        else -> null
    }
}

fun isPhoneLink(link: String): Boolean = link.startsWith("tel:")

/** Opens a link in the browser, mail program or phone app. Returns false if Windows has nothing to open it with. */
fun openLink(link: String): Boolean = runCatching {
    val desktop = Desktop.getDesktop()
    val uri = URI(link)
    when (uri.scheme) {
        "mailto" -> desktop.mail(uri)
        else -> desktop.browse(uri)
    }
    true
}.getOrDefault(false)

/** A column width (in dp) that fits the header and the longest value, within sensible limits. */
fun autoWidth(column: GridColumn, rows: List<GridRow>): Int {
    val longest = maxOf(column.name.length + 4, rows.take(300).maxOfOrNull { r ->
        r.values[column.id].orEmpty().lineSequence().firstOrNull().orEmpty().length
    } ?: 0)
    return (longest * 8 + 28).coerceIn(80, 600)
}
