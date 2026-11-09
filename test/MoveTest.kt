import gsp.*

fun main() {
    val dir = java.nio.file.Files.createTempDirectory("gsp")
    val ws = Workspace(dir)
    val sheet = FakeSheet()
    val hdr = listOf("Date","Time","Description","Check","tedt","Column 3","Pic","Pic preview","file preview","File","Voice")
    sheet.grid = arrayListOf(ArrayList<Any?>(hdr), ArrayList<Any?>(listOf(null,null,"hyderali",false,"builder","hi",null,null,null,"txt",null)))
    val engine = SyncEngine(ws, FakeGateway(sheet))
    val id = ws.createLinkedTable("T", Link("S", 0, "Tab", "u"))
    runSync { engine.sync(id) }
    runSync { engine.sync(id) }
    println(ws.grid(id)!!.columns.map { it.name })
    println(sheet.grid[0])
    val g = ws.grid(id)!!
    ws.moveColumn(id, g.columns.first { it.name == "Description" }.id, -1)
    println("after local move: " + ws.grid(id)!!.columns.map { it.name })
    runSync { engine.sync(id) }
    println(sheet.grid[0])
    println(ws.grid(id)!!.columns.map { it.name })
    ws.moveColumn(id, ws.grid(id)!!.columns.first { it.name == "File" }.id, -1)
    runSync { engine.sync(id) }
    println(sheet.grid[0])
    println(ws.grid(id)!!.columns.map { it.name })
}
