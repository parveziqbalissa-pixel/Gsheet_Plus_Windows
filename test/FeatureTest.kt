import gsp.*
import java.nio.file.Files
import java.time.YearMonth

fun main() {
    val dir = Files.createTempDirectory("feat")
    var ws = Workspace(dir.resolve("data"))
    // templates
    val inv = ws.createFromTemplate("", Templates.all.first { it.name == "Inventory" })
    val g = ws.grid(inv)!!
    check("template columns", g.columns.map { it.name } == listOf("Item", "Photo", "Qty", "Price", "Value", "Supplier"))
    check("template rows", g.rows.size == 3)
    val qty = g.columns[2].id; val price = g.columns[3].id; val value = g.columns[4].id; val item = g.columns[0].id
    val r = g.rows.map { it.id }
    ws.setCell(inv, r[0], item, "Pen"); ws.setCell(inv, r[0], qty, "10"); ws.setCell(inv, r[0], price, "5")
    ws.setCell(inv, r[1], item, "Book"); ws.setCell(inv, r[1], qty, "2"); ws.setCell(inv, r[1], price, "100")
    ws.setCell(inv, r[2], item, "Pen"); ws.setCell(inv, r[2], qty, "1"); ws.setCell(inv, r[2], price, "5")
    check("calc", ws.grid(inv)!!.rows[0].values[value] == "50")
    // views
    val opts = ViewOptions(search = "pen", sort = SortSpec(qty, SortDirection.DESC), filters = mapOf(qty to FilterSpec(qty, FilterOp.GREATER, "1")), groupBy = item)
    ws.saveView(inv, "Pens", opts)
    ws.saveView(inv, "pens", opts.copy(search = "p"))
    check("view replaced by name", ws.views(inv).size == 1 && ws.views(inv)[0].options.search == "p")
    // rules
    ws.addRule(inv, qty, FilterOp.GREATER, "5", 0xFF00FF00.toInt(), wholeRow = true, bold = true)
    ws.addRule(inv, item, FilterOp.EQUALS, "Book", 0xFFFF0000.toInt(), wholeRow = false, bold = false)
    ws.flush()
    ws = Workspace(dir.resolve("data"))
    val v = ws.views(inv)[0].options
    check("view persisted", v.search == "p" && v.sort?.columnId == qty && v.sort?.direction == SortDirection.DESC && v.filters[qty]?.op == FilterOp.GREATER && v.groupBy == item)
    val rules = ws.rules(inv)
    check("rules persisted", rules.size == 2 && rules[0].wholeRow && rules[0].bold)
    val grid = ws.grid(inv)!!
    val p0 = paintRow(grid.rows[0], grid.columns, rules)
    val p1 = paintRow(grid.rows[1], grid.columns, rules)
    check("row rule", p0.rowColor == 0xFF00FF00.toInt() && p0.bold.size == grid.columns.size)
    check("cell rule", p1.rowColor == null && p1.cells[item] == 0xFFFF0000.toInt())
    check("today rule value", ruleValue("{today}").length == 10)
    // summary
    val s = summarize(grid.rows, grid.columns[0], grid.columns[4], Aggregate.SUM, false)
    check("summary", s.size == 2 && s[0].label == "Book" && s[0].value == 200.0 && s[1].label == "Pen" && s[1].value == 55.0 && s[1].count == 2)
    val cnt = summarize(grid.rows, grid.columns[0], null, Aggregate.SUM, false)
    check("count", cnt.first { it.label == "Pen" }.value == 2.0)
    // report and map
    val html = reportHtml("Stock <1>", ReportHeader("My Shop", "Karachi", null), grid.columns, grid.rows) { _, _ -> null }
    check("report escapes and totals", html.contains("Stock &lt;1&gt;") && html.contains("My Shop") && html.contains("<tfoot>") && html.contains(">255<"))
    check("map html", mapHtml("x", listOf("A" to "Lahore")).contains("\"address\":\"Lahore\""))
    // trash
    ws.deleteTable(inv)
    check("deleted", ws.grid(inv) == null && ws.trash().size == 1)
    val back = ws.restoreTable(inv)
    check("restored", back == inv && ws.grid(inv)!!.rows.size == 3 && ws.trash().isEmpty())
    ws.deleteTable(inv); ws.purgeTable(inv)
    check("purged", ws.trash().isEmpty())
    // backups
    ws = Workspace(dir.resolve("data"))
    val keep = ws.createTable("Keep")
    ws.flush()
    val b = Backups(dir.resolve("data"), dir.resolve("backups"))
    val zip = b.create()
    check("backup made", Files.exists(zip) && b.list().size == 1 && b.daily() == null)
    ws.deleteTable(keep); ws.flush()
    b.scheduleRestore(zip)
    check("restore applied", b.applyPending())
    ws = Workspace(dir.resolve("data"))
    check("table back after restore", ws.grid(keep)?.name == "Keep")
    // pin, links, calendar
    val salt = PinLock.newSalt()
    val h = PinLock.hash("1234", salt)
    check("pin", PinLock.check("1234", salt, h) && !PinLock.check("1235", salt, h))
    check("address link", linkOf(GridColumn(1, "A", ColumnType.ADDRESS, 100), "Model Town, Lahore") == "https://www.google.com/maps/search/?api=1&query=Model+Town%2C+Lahore")
    val days = monthGrid(YearMonth.of(2026, 11))
    check("month grid", days.size % 7 == 0 && days.indexOfFirst { it != null } == 6 && days.count { it != null } == 30)
    // rating
    val ratingCol = GridColumn(9, "R", ColumnType.RATING, 100)
    check("rating paste", convertForColumn(ClipCell(ColumnType.TEXT, "4"), ratingCol) == "4" && convertForColumn(ClipCell(ColumnType.TEXT, "9"), ratingCol) == null)
    // hidden columns, created date, sorting
    val hid = ws.createTable("Hid")
    val hc = ws.grid(hid)!!.columns.first().id
    ws.setColumnHidden(hid, hc, true)
    ws.flush()
    val ws2 = Workspace(dir.resolve("data"))
    check("hidden persisted", ws2.hiddenColumns(hid) == setOf(hc) && ws2.summaries().first { it.id == hid }.createdAt > 0)
    val sorted = sortSummaries(ws2.summaries(), "NAME_AZ").map { it.name }
    check("sort by name", sorted == sorted.sortedWith(String.CASE_INSENSITIVE_ORDER))
    println(if (failures == 0) "ALL OK" else "FAILURES: $failures")
}
