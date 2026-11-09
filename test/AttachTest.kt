import gsp.*
import java.io.File
import java.nio.file.Files

class FakeAtt : AttachmentGateway {
    var uploads = 0
    var writes = 0
    var lastLinks: List<LinkCell> = emptyList()
    var lastCells: Map<Pair<Int, Int>, String> = emptyMap()
    var lastHeaders: Map<Int, String> = emptyMap()
    var header = mutableListOf("Name", "Pic", "_gsp_id")
    override suspend fun upload(file: File, name: String, mime: String, shareByLink: Boolean): Pair<String, String> {
        uploads++
        return "drive$uploads" to "http://link/$uploads"
    }
    var linkData: Map<Pair<Int, Int>, List<Pair<String, String>>> = emptyMap()
    override suspend fun links(spreadsheetId: String, tabTitle: String, columns: List<Int>) = linkData
    override suspend fun header(spreadsheetId: String, tabTitle: String) = header.toList()
    override suspend fun write(spreadsheetId: String, sheetId: Int, tabTitle: String, links: List<LinkCell>, cells: Map<Pair<Int, Int>, String>, headers: Map<Int, String>) {
        writes++; lastLinks = links; lastCells = cells; lastHeaders = headers
        headers.forEach { (i, t) -> while (header.size <= i) header += ""; header[i] = t }
    }
}


fun main() {
    val dir = Files.createTempDirectory("att")
    val ws = Workspace(dir)
    val t = ws.createLinkedTable("T", Link("S", 0, "Tab", "u"))
    val pic = ws.addColumn(t, "Pic", ColumnType.PICTURE)!!
    val row = ws.addRow(t)!!
    ws.withTable(t) { tb ->
        tb.columns.forEach { it.sheetName = it.name }
        tb.rows.first { it.id == row }.sourceRow = 2
    }
    val img = File(dir.toFile(), "a.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
    check("add attachment", ws.addAttachmentFile(t, row, pic, img.toPath(), "image/png"))
    val fake = FakeAtt()
    val sync = AttachmentSync(ws, fake)
    check("first run uploads 1", runSync { sync.run(t) } == 1)
    check("link written", fake.lastLinks.size == 1 && fake.lastLinks[0].row == 1 && fake.lastLinks[0].col == 1 && fake.lastLinks[0].runs[0].uri == "http://link/1")
    check("preview formula", fake.lastCells[1 to 3]!!.startsWith("=IMAGE(") && fake.lastCells[1 to 3]!!.contains("drive1"))
    check("preview header added at end", fake.lastHeaders == mapOf(3 to "Pic preview"))
    check("marked synced", ws.attachments(t).all { it.sheetSynced && it.url != null })
    val writes = fake.writes
    check("second run uploads 0", runSync { sync.run(t) } == 0)
    check("second run writes nothing", fake.writes == writes)
    ws.removeAttachment(t, ws.attachments(t).first().id)
    runSync { sync.run(t) }
    check("stale preview cleared", fake.writes == writes + 1 && fake.lastCells[1 to 3] == "")
    // links made elsewhere (phone) show up as attachments
    val t2 = ws.createLinkedTable("T2", Link("S2", 0, "Tab", "u"))
    val pc = ws.addColumn(t2, "Pic", ColumnType.PICTURE)!!
    val r2 = ws.addRow(t2)!!
    ws.withTable(t2) { tb ->
        tb.columns.forEach { it.sheetName = it.name }
        tb.rows.first { it.id == r2 }.also { it.sourceRow = 2; it.values[pc] = "a.pdf\nb.png" }
    }
    fake.linkData = mapOf((1 to 1) to listOf("a.pdf" to "https://drive.google.com/file/d/ABC123/view", "b.png" to "https://drive.google.com/open?id=XYZ"))
    runSync { AttachmentSync(ws, fake).run(t2) }
    val got = ws.attachments(t2)
    check("pulled 2 attachments", got.size == 2 && got[0].driveId == "ABC123" && got[1].driveId == "XYZ" && got[1].mime == "image/png")
    check("pulled not re-written", got.all { it.sheetSynced })
    val text = GridColumn(1, "T", ColumnType.TEXT, 160)
    val phone = GridColumn(2, "P", ColumnType.PHONE, 160)
    val num = GridColumn(3, "N", ColumnType.NUMBER, 160)
    check("url", linkOf(text, "https://a.com/x?y=1") == "https://a.com/x?y=1")
    check("www", linkOf(text, "www.google.com") == "https://www.google.com")
    check("email", linkOf(text, "a@b.com") == "mailto:a@b.com")
    check("phone col", linkOf(phone, "+92 300-1234567") == "tel:+923001234567")
    check("phone col plain", linkOf(phone, "0300 1234567") == "tel:03001234567")
    check("text intl phone", linkOf(text, "+92 300 1234567") == "tel:+923001234567")
    check("text plain number not link", linkOf(text, "03001234567") == null)
    check("sentence not link", linkOf(text, "see www.google.com now") == null)
    check("number col not link", linkOf(num, "www.a.com") == null)
    check("auto width", autoWidth(text, listOf(GridRow(1, mapOf(1L to "x".repeat(30))))) == 268)
    val sf = java.nio.file.Files.createTempDirectory("set").resolve("settings.json")
    val st = AppSettings(sf)
    check("settings default", st.syncSeconds == 15 && !st.dark)
    st.syncSeconds = 0; st.dark = true; st.save()
    val st2 = AppSettings(sf)
    check("settings saved", st2.syncSeconds == 0 && st2.dark)
}
