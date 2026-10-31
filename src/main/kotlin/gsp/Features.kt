package gsp

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ===================== colour rules =====================

/** The colours colour rules give one row: [rowColor] for the whole row, [cells] per column, [bold] columns. */
class RulePaint(val rowColor: Int?, val cells: Map<Long, Int>, val bold: Set<Long>) {
    companion object {
        val NONE = RulePaint(null, emptyMap(), emptySet())
    }
}

/** Applies [rules] to [row]; the first matching rule wins for a cell, and for the row. */
fun paintRow(row: GridRow, columns: List<GridColumn>, rules: List<ColorRule>): RulePaint {
    if (rules.isEmpty()) return RulePaint.NONE
    val byId = columns.associateBy { it.id }
    var rowColor: Int? = null
    var rowBold = false
    val cells = HashMap<Long, Int>()
    val bold = HashSet<Long>()
    for (rule in rules) {
        val col = byId[rule.columnId] ?: continue
        val text = row.values[col.id].orEmpty()
        val hit = when (rule.op) {
            FilterOp.CONTAINS, FilterOp.EQUALS, FilterOp.GREATER, FilterOp.LESS ->
                rule.value.isNotEmpty() && isValidFilterValue(col.type, rule.op, ruleValue(rule.value)) &&
                    matches(FilterSpec(col.id, rule.op, ruleValue(rule.value)), col.type, text)
            else -> matches(FilterSpec(col.id, rule.op, ""), col.type, text)
        }
        if (!hit) continue
        if (rule.wholeRow) {
            if (rowColor == null) { rowColor = rule.color; rowBold = rule.bold }
        } else if (col.id !in cells) {
            cells[col.id] = rule.color
            if (rule.bold) bold += col.id
        }
    }
    if (rowBold) columns.forEach { bold += it.id }
    return RulePaint(rowColor, cells, bold)
}

/** "{today}" in a rule means today's date, so "Due date is before {today}" marks overdue rows. */
fun ruleValue(raw: String): String = raw.trim().replace("{today}", LocalDate.now().toString(), ignoreCase = true)

// ===================== charts and summary =====================

class SummaryLine(val label: String, val value: Double, val count: Int)

/**
 * Groups [rows] by [groupColumn] (by month when it is a date column and [byMonth]) and totals [valueColumn]
 * (or counts rows when it is null).
 */
fun summarize(rows: List<GridRow>, groupColumn: GridColumn, valueColumn: GridColumn?, aggregate: Aggregate, byMonth: Boolean): List<SummaryLine> {
    val groups = LinkedHashMap<String, MutableList<GridRow>>()
    for (r in rows) {
        var key = r.values[groupColumn.id].orEmpty().trim()
        if (groupColumn.type.isAttachment) key = if (key.isEmpty()) "" else "${attachmentLinks(key).size}"
        if (byMonth && groupColumn.type == ColumnType.DATE && key.length >= 7) key = key.substring(0, 7)
        groups.getOrPut(key.ifEmpty { "(empty)" }) { ArrayList() } += r
    }
    val lines = groups.map { (label, list) ->
        val numbers = if (valueColumn == null) emptyList() else list.mapNotNull { parseNumber(it.values[valueColumn.id].orEmpty()) }
        val value = when {
            valueColumn == null || aggregate == Aggregate.COUNT -> list.size.toDouble()
            numbers.isEmpty() -> 0.0
            aggregate == Aggregate.SUM -> numbers.sum()
            aggregate == Aggregate.AVG -> numbers.average()
            aggregate == Aggregate.MIN -> numbers.min()
            else -> numbers.max()
        }
        SummaryLine(label, Math.round(value * 1_000_000) / 1_000_000.0, list.size)
    }
    return if (groupColumn.type == ColumnType.DATE || groupColumn.type == ColumnType.TIME) lines.sortedBy { it.label }
    else lines.sortedByDescending { it.value }
}

// ===================== templates =====================

class TemplateColumn(val name: String, val type: ColumnType, val items: List<String> = emptyList(), val prefix: String = "", val formula: String = "")
class Template(val name: String, val description: String, val columns: List<TemplateColumn>)

object Templates {
    val all = listOf(
        Template("Expenses", "Track daily spending by category", listOf(
            TemplateColumn("Date", ColumnType.DATE), TemplateColumn("Item", ColumnType.TEXT),
            TemplateColumn("Category", ColumnType.LIST, listOf("Food", "Transport", "Bills", "Shopping", "Health", "Other")),
            TemplateColumn("Amount", ColumnType.NUMBER, prefix = "Rs "), TemplateColumn("Receipt", ColumnType.PICTURE),
        )),
        Template("Inventory", "Stock, prices and stock value", listOf(
            TemplateColumn("Item", ColumnType.TEXT), TemplateColumn("Photo", ColumnType.PICTURE), TemplateColumn("Qty", ColumnType.NUMBER),
            TemplateColumn("Price", ColumnType.NUMBER), TemplateColumn("Value", ColumnType.CALC, formula = "[Qty] * [Price]"),
            TemplateColumn("Supplier", ColumnType.TEXT),
        )),
        Template("Customers", "Names, phones and addresses", listOf(
            TemplateColumn("Name", ColumnType.TEXT), TemplateColumn("Phone", ColumnType.PHONE), TemplateColumn("Email", ColumnType.TEXT),
            TemplateColumn("Address", ColumnType.ADDRESS), TemplateColumn("Notes", ColumnType.TEXT),
        )),
        Template("Attendance", "Who was present each day", listOf(
            TemplateColumn("Date", ColumnType.DATE), TemplateColumn("Name", ColumnType.TEXT), TemplateColumn("Present", ColumnType.CHECKBOX),
            TemplateColumn("Time in", ColumnType.TIME), TemplateColumn("Notes", ColumnType.TEXT),
        )),
        Template("Student marks", "Marks per subject with total", listOf(
            TemplateColumn("Student", ColumnType.TEXT), TemplateColumn("Class", ColumnType.TEXT), TemplateColumn("Maths", ColumnType.NUMBER),
            TemplateColumn("English", ColumnType.NUMBER), TemplateColumn("Science", ColumnType.NUMBER),
            TemplateColumn("Total", ColumnType.CALC, formula = "[Maths] + [English] + [Science]"),
        )),
        Template("Rent tracker", "Rent due and paid per tenant", listOf(
            TemplateColumn("Tenant", ColumnType.TEXT), TemplateColumn("Phone", ColumnType.PHONE), TemplateColumn("Month", ColumnType.DATE),
            TemplateColumn("Rent", ColumnType.NUMBER, prefix = "Rs "), TemplateColumn("Paid", ColumnType.CHECKBOX),
            TemplateColumn("Status", ColumnType.LIST, listOf("Due", "Paid", "Late")),
        )),
        Template("Tasks", "To-dos with due date and status", listOf(
            TemplateColumn("Task", ColumnType.TEXT), TemplateColumn("Due", ColumnType.DATE),
            TemplateColumn("Priority", ColumnType.LIST, listOf("High", "Medium", "Low")),
            TemplateColumn("Status", ColumnType.LIST, listOf("To do", "Doing", "Done")), TemplateColumn("Rating", ColumnType.RATING),
        )),
        Template("Medicines", "Medicines with photo and dose", listOf(
            TemplateColumn("Medicine", ColumnType.TEXT), TemplateColumn("Photo", ColumnType.PICTURE), TemplateColumn("For", ColumnType.TEXT),
            TemplateColumn("Dose", ColumnType.TEXT), TemplateColumn("Time", ColumnType.TIME), TemplateColumn("Expiry", ColumnType.DATE),
        )),
    )
}

// ===================== calendar =====================

/** The days shown for a month, Monday first; null fills the gaps before the 1st and after the last day. */
fun monthGrid(month: YearMonth): List<LocalDate?> {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1
    val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val cells: MutableList<LocalDate?> = MutableList(lead) { null }
    cells.addAll(days)
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

// ===================== report (printable page, saved as PDF from the browser) =====================

class ReportHeader(val business: String, val line2: String, val logoPath: String?)

private fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * A printable HTML report of [rows] with a letterhead and column totals. [pictureUrl] gives a picture link for
 * a cell's first picture (or null).
 */
fun reportHtml(
    title: String, header: ReportHeader, columns: List<GridColumn>, rows: List<GridRow>,
    pictureUrl: (GridRow, GridColumn) -> String?,
): String {
    val logo = header.logoPath?.let { p ->
        runCatching {
            val bytes = Files.readAllBytes(Path.of(p))
            val mime = mimeFromName(p).takeIf { it.startsWith("image/") } ?: "image/png"
            "<img class=\"logo\" src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\">"
        }.getOrNull()
    }.orEmpty()
    val sb = StringBuilder()
    sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title><style>")
    sb.append("""
        body{font-family:Segoe UI,Arial,sans-serif;margin:24px;color:#222}
        .head{display:flex;align-items:center;gap:16px;border-bottom:3px solid #0f9d58;padding-bottom:12px;margin-bottom:16px}
        .logo{max-height:70px;max-width:160px}
        .biz{font-size:22px;font-weight:700}.line2{color:#555}
        h1{font-size:18px;margin:4px 0 2px}.meta{color:#777;font-size:12px;margin-bottom:12px}
        table{border-collapse:collapse;width:100%;font-size:12px}
        th{background:#0f9d58;color:#fff;text-align:left;padding:6px}
        td{border-bottom:1px solid #ddd;padding:6px;vertical-align:top}
        td.n{text-align:right}tr:nth-child(even) td{background:#f6f8f7}
        tfoot td{font-weight:700;border-top:2px solid #0f9d58;background:#eef7f1}
        img.cell{max-width:90px;max-height:70px;border-radius:4px}
        .bar{position:fixed;top:8px;right:8px}.bar button{font-size:14px;padding:8px 14px;background:#0f9d58;color:#fff;border:0;border-radius:6px;cursor:pointer}
        @media print{.bar{display:none}body{margin:8mm}}
    """.trimIndent())
    sb.append("</style></head><body>")
    sb.append("<div class=\"bar\"><button onclick=\"window.print()\">Print / Save as PDF</button></div>")
    sb.append("<div class=\"head\">").append(logo).append("<div>")
    if (header.business.isNotBlank()) sb.append("<div class=\"biz\">").append(esc(header.business)).append("</div>")
    if (header.line2.isNotBlank()) sb.append("<div class=\"line2\">").append(esc(header.line2)).append("</div>")
    sb.append("</div></div>")
    sb.append("<h1>").append(esc(title)).append("</h1>")
    sb.append("<div class=\"meta\">").append(rows.size).append(" rows · ")
        .append(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy"))).append("</div>")
    sb.append("<table><thead><tr>")
    columns.forEach { sb.append("<th>").append(esc(it.name)).append("</th>") }
    sb.append("</tr></thead><tbody>")
    for (r in rows) {
        sb.append("<tr>")
        for (c in columns) {
            val v = r.values[c.id].orEmpty()
            val cls = if (c.isNumeric) " class=\"n\"" else ""
            sb.append("<td").append(cls).append(">")
            when {
                c.type == ColumnType.PICTURE || c.type == ColumnType.SIGNATURE -> {
                    val url = pictureUrl(r, c)
                    if (url != null) sb.append("<img class=\"cell\" src=\"").append(esc(url)).append("\">")
                    else sb.append(esc(v.replace("\n", ", ")))
                }
                c.type == ColumnType.CHECKBOX -> sb.append(if (v == "true") "✔" else "")
                c.type == ColumnType.RATING -> sb.append("★".repeat((parseNumber(v) ?: 0.0).toInt().coerceIn(0, 5)))
                c.isNumeric && v.isNotEmpty() -> sb.append(esc(c.prefix + v + c.suffix))
                else -> sb.append(esc(v).replace("\n", "<br>"))
            }
            sb.append("</td>")
        }
        sb.append("</tr>")
    }
    sb.append("</tbody>")
    if (columns.any { it.type == ColumnType.NUMBER || it.type == ColumnType.CALC }) {
        sb.append("<tfoot><tr>")
        columns.forEachIndexed { i, c ->
            val total = if (c.type == ColumnType.NUMBER || c.type == ColumnType.CALC) aggregateValue(c, rows, Aggregate.SUM) else ""
            sb.append("<td").append(if (c.isNumeric) " class=\"n\"" else "").append(">")
            sb.append(if (i == 0 && total.isEmpty()) "Total" else if (total.isNotEmpty()) esc(c.prefix + total + c.suffix) else "")
            sb.append("</td>")
        }
        sb.append("</tr></tfoot>")
    }
    sb.append("</table></body></html>")
    return sb.toString()
}

// ===================== map of addresses =====================

/** A web page that puts [places] (label to address, or "lat, lng") on an OpenStreetMap map. */
fun mapHtml(title: String, places: List<Pair<String, String>>): String {
    val data = Json.write(places.map { mapOf("label" to it.first, "address" to it.second) })
    return """<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)} - map</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#map{height:100%;margin:0}#info{position:absolute;z-index:999;top:8px;left:50px;background:#fff;padding:6px 10px;border-radius:6px;font:13px Segoe UI,Arial;box-shadow:0 1px 4px #0003}</style>
</head><body><div id="map"></div><div id="info">Finding addresses...</div><script>
const places = $data;
const map = L.map('map').setView([30.37, 69.34], 5);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19, attribution: '&copy; OpenStreetMap'}).addTo(map);
const bounds = []; let found = 0, missing = 0;
function add(lat, lng, p) {
  const m = L.marker([lat, lng]).addTo(map);
  m.bindPopup('<b>' + p.label.replace(/</g,'&lt;') + '</b><br>' + p.address.replace(/</g,'&lt;') +
    '<br><a target="_blank" href="https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(p.address) + '">Open in Google Maps</a>');
  bounds.push([lat, lng]); found++; map.fitBounds(bounds, {padding: [40, 40], maxZoom: 15});
}
async function run() {
  for (const p of places) {
    const ll = p.address.match(/^\s*(-?\d+(\.\d+)?)\s*,\s*(-?\d+(\.\d+)?)\s*$/);
    if (ll) { add(parseFloat(ll[1]), parseFloat(ll[3]), p); continue; }
    try {
      const r = await fetch('https://nominatim.openstreetmap.org/search?format=json&limit=1&q=' + encodeURIComponent(p.address));
      const j = await r.json();
      if (j.length) add(parseFloat(j[0].lat), parseFloat(j[0].lon), p); else missing++;
    } catch (e) { missing++; }
    document.getElementById('info').textContent = found + ' on the map' + (missing ? ', ' + missing + ' not found' : '');
    await new Promise(r => setTimeout(r, 1100));
  }
  document.getElementById('info').textContent = found + ' on the map' + (missing ? ', ' + missing + ' not found' : '');
}
run();
</script></body></html>"""
}

// ===================== backups =====================

/** Zip backups of the data folder in [backupDir]; a restore is applied on the next start. */
class Backups(private val dataDir: Path, private val backupDir: Path) {
    private val pending: Path get() = backupDir.resolve("restore_pending.zip")

    fun list(): List<Path> = if (!Files.isDirectory(backupDir)) emptyList() else Files.list(backupDir).use { s ->
        s.filter { it.fileName.toString().matches(Regex("backup_.*\\.zip")) }.toList()
    }.sortedByDescending { it.fileName.toString() }

    /** Makes a backup now; keeps the newest [keep]. */
    fun create(keep: Int = 10): Path {
        Files.createDirectories(backupDir)
        val stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val target = backupDir.resolve("backup_$stamp.zip")
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            Files.walk(dataDir).use { walk ->
                walk.filter { Files.isRegularFile(it) && !it.startsWith(dataDir.resolve("trash")) }.forEach { file ->
                    zip.putNextEntry(ZipEntry(dataDir.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
        list().drop(keep).forEach { runCatching { Files.deleteIfExists(it) } }
        return target
    }

    /** One backup a day: makes one if the newest is older than today. */
    fun daily(): Path? {
        val today = LocalDate.now().toString()
        if (list().any { it.fileName.toString().startsWith("backup_$today") }) return null
        return create()
    }

    /** Marks [backup] to be restored at the next start (the running app would overwrite the files otherwise). */
    fun scheduleRestore(backup: Path) {
        Files.copy(backup, pending, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Called before the data is loaded: replaces the tables with the pending backup. Returns true if it did. */
    fun applyPending(): Boolean {
        if (!Files.exists(pending)) return false
        return runCatching {
            Files.createDirectories(dataDir)
            Files.list(dataDir).use { s -> s.filter { it.fileName.toString().matches(Regex("table_\\d+\\.json")) }.toList() }
                .forEach { Files.deleteIfExists(it) }
            ZipInputStream(Files.newInputStream(pending)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = dataDir.resolve(entry.name).normalize()
                    if (!out.startsWith(dataDir) || entry.isDirectory) continue
                    Files.createDirectories(out.parent)
                    Files.copy(zip, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            Files.deleteIfExists(pending)
            true
        }.getOrDefault(false)
    }
}

// ===================== PIN lock =====================

object PinLock {
    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun hash(pin: String, salt: String): String {
        var bytes = (salt + ":" + pin).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(20_000) { bytes = md.digest(bytes) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun check(pin: String, salt: String?, hash: String?): Boolean = salt != null && hash != null && hash(pin, salt) == hash
}
