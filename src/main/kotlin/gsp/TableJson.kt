package gsp

/** Saves and loads a table as JSON. */
object TableJson {
    fun write(t: TableData): String = Json.write(
        linkedMapOf(
            "id" to t.id, "name" to t.name, "pinned" to t.pinned, "updatedAt" to t.updatedAt, "nextId" to t.nextId,
            "link" to t.link?.let {
                linkedMapOf("spreadsheetId" to it.spreadsheetId, "sheetId" to it.sheetId, "tabTitle" to it.tabTitle, "url" to it.url, "lastSyncedAt" to it.lastSyncedAt)
            },
            "deletedRowUuids" to t.deletedRowUuids,
            "deletedColumnNames" to t.deletedColumnNames,
            "columns" to t.columns.map {
                linkedMapOf(
                    "id" to it.id, "name" to it.name, "type" to it.type.name, "width" to it.width, "prefix" to it.prefix,
                    "suffix" to it.suffix, "defaultValue" to it.defaultValue, "listItems" to it.listItems, "formula" to it.formula,
                    "readOnly" to it.readOnly, "bgColor" to it.bgColor, "textColor" to it.textColor, "bold" to it.bold,
                    "italic" to it.italic, "pinned" to it.pinned, "sheetName" to it.sheetName, "sig" to it.sig,
                )
            },
            "rows" to t.rows.map {
                linkedMapOf(
                    "id" to it.id, "uuid" to it.uuid, "color" to it.color, "sourceRow" to it.sourceRow,
                    "values" to it.values.mapKeys { e -> e.key.toString() },
                    "base" to it.base?.mapKeys { e -> e.key.toString() },
                )
            },
            "attachments" to t.attachments.map {
                linkedMapOf(
                    "id" to it.id, "rowId" to it.rowId, "columnId" to it.columnId, "name" to it.name, "mime" to it.mime,
                    "localPath" to it.localPath, "driveId" to it.driveId, "url" to it.url, "sheetSynced" to it.sheetSynced,
                )
            },
            "conflicts" to t.conflicts.map {
                linkedMapOf("id" to it.id, "column" to it.columnName, "kept" to it.kept, "lost" to it.lost, "at" to it.at)
            },
        ),
    )

    private fun Any?.long(): Long = (this as? Number)?.toLong() ?: 0L
    private fun Any?.intOrNull(): Int? = (this as? Number)?.toInt()
    private fun Any?.bool(): Boolean = this as? Boolean ?: false
    private fun Any?.strs(): List<String> = arr().orEmpty().map { it.text() }
    private fun Any?.idMap(): MutableMap<Long, String>? =
        obj()?.entries?.mapNotNull { e -> e.key.toLongOrNull()?.let { it to e.value.text() } }?.toMap(HashMap())

    fun read(json: String): TableData {
        val o = Json.parse(json).obj() ?: error("Not a table file")
        val t = TableData(o["id"].long(), o["name"].text())
        t.pinned = o["pinned"].bool()
        t.updatedAt = o["updatedAt"].long()
        t.nextId = o["nextId"].long().coerceAtLeast(1)
        o["link"].obj()?.let {
            t.link = Link(it["spreadsheetId"].text(), it["sheetId"].intOrNull() ?: 0, it["tabTitle"].text(), it["url"].text(), (it["lastSyncedAt"] as? Number)?.toLong())
        }
        t.deletedRowUuids += o["deletedRowUuids"].strs()
        t.deletedColumnNames += o["deletedColumnNames"].strs()
        for (c in o["columns"].arr().orEmpty().mapNotNull { it.obj() }) {
            t.columns += Col(
                c["id"].long(), c["name"].text(), ColumnType.fromName(c["type"].text()), c["width"].intOrNull() ?: 160,
                c["prefix"].text(), c["suffix"].text(), c["defaultValue"].text(), c["listItems"].strs(), c["formula"].text(),
                c["readOnly"].bool(), c["bgColor"].intOrNull(), c["textColor"].intOrNull(), c["bold"].bool(), c["italic"].bool(),
                c["pinned"].bool(), (c["sheetName"] as? String), (c["sig"] as? String),
            )
        }
        for (r in o["rows"].arr().orEmpty().mapNotNull { it.obj() }) {
            t.rows += Row(
                r["id"].long(), r["uuid"].text(), r["values"].idMap() ?: HashMap(), r["color"].intOrNull(),
                r["base"].idMap(), r["sourceRow"].intOrNull(),
            )
        }
        for (a in o["attachments"].arr().orEmpty().mapNotNull { it.obj() }) {
            t.attachments += Attachment(
                a["id"].long(), a["rowId"].long(), a["columnId"].long(), a["name"].text(), a["mime"].text(),
                a["localPath"] as? String, a["driveId"] as? String, a["url"] as? String, a["sheetSynced"].bool(),
            )
        }
        for (c in o["conflicts"].arr().orEmpty().mapNotNull { it.obj() }) {
            t.conflicts += Conflict(c["id"].long(), c["column"].text(), c["kept"].text(), c["lost"].text(), c["at"].long())
        }
        return t
    }
}
