package gsp

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Saves the rows now shown as a CSV or Excel file chosen by the user. */
object Export {
    private fun pick(name: String, ext: String, label: String): File? {
        val chooser = JFileChooser()
        chooser.dialogTitle = "Save as"
        chooser.selectedFile = File(name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + ext)
        chooser.fileFilter = FileNameExtensionFilter(label, ext)
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val f = chooser.selectedFile
        return if (f.name.endsWith(".$ext", ignoreCase = true)) f else File(f.parentFile, f.name + "." + ext)
    }

    private fun cells(grid: GridData, rows: GridData): List<List<String>> =
        listOf(grid.columns.map { it.name }) + rows.rows.map { r -> grid.columns.map { c -> r.values[c.id].orEmpty() } }

    private fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun csv(c: Controller, grid: GridData, shown: GridData) {
        val file = pick(grid.name, "csv", "CSV file (*.csv)") ?: return
        try {
            val text = cells(grid, shown).joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } }
            file.writeText("﻿" + text + "\r\n", Charsets.UTF_8)
            c.say("Saved ${file.name}")
        } catch (e: Exception) {
            c.say("Could not save: ${e.message}")
        }
    }

    private fun xml(v: String): String = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .filter { it == '\n' || it == '\t' || it == '\r' || it >= ' ' }

    fun xlsx(c: Controller, grid: GridData, shown: GridData) {
        val file = pick(grid.name, "xlsx", "Excel file (*.xlsx)") ?: return
        try {
            val data = cells(grid, shown)
            val sheet = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            data.forEachIndexed { r, row ->
                sheet.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { i, v ->
                    if (v.isEmpty()) return@forEachIndexed
                    val ref = colLetter(i) + (r + 1)
                    val type = if (r == 0) null else grid.columns[i].type
                    val number = if (type == ColumnType.NUMBER || type == ColumnType.CALC) parseNumber(v) else null
                    if (number != null) sheet.append("<c r=\"$ref\"><v>${formatNumber(number)}</v></c>")
                    else sheet.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>")
                }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                fun put(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
                put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
                put("xl/worksheets/sheet1.xml", sheet.toString())
            }
            c.say("Saved ${file.name}")
        } catch (e: Exception) {
            c.say("Could not save: ${e.message}")
        }
    }
}

/** Turning a local table into a Google Sheet. */
object Linking {
    fun createSheet(c: Controller, tableId: Long) {
        val name = c.ws.grid(tableId)?.name ?: return
        c.background("Create Sheet") {
            if (!c.auth.isSignedIn()) throw NotSignedIn()
            val (id, url, tab) = c.api.createSpreadsheet(name, "Sheet1")
            c.ws.setLink(tableId, Link(id, tab.sheetId, tab.title, url))
            c.say("Google Sheet created - syncing...")
            c.syncNow(tableId)
        }
    }
}
