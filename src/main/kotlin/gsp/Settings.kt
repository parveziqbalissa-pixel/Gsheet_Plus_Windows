package gsp

import java.nio.file.Files
import java.nio.file.Path

/** The few app settings, kept in settings.json next to the data. */
class AppSettings(private val file: Path) {
    /** Seconds between automatic syncs of the open table; 0 turns it off. */
    var syncSeconds: Int = 15
    var dark: Boolean = false
    /** Height in dp of table rows that hold pictures (0 = no pictures in the table). */
    var pictureRows: Int = 96
    var reportBusiness: String = ""
    var reportLine2: String = ""
    var reportLogo: String? = null
    var dailyBackup: Boolean = true
    var pinSalt: String? = null
    var pinHash: String? = null
    var tableView: String = "LIST"
    var tableZoom: Float = 1f
    var tableSort: String = "MODIFIED"

    init {
        runCatching {
            val o = Json.parse(Files.readString(file)).obj()
            if (o != null) {
                syncSeconds = (o["syncSeconds"] as? Number)?.toInt()?.takeIf { it == 0 || it in 5..3600 } ?: 15
                dark = o["dark"] == true
                pictureRows = (o["pictureRows"] as? Number)?.toInt()?.takeIf { it == 0 || it in 48..400 } ?: 96
                reportBusiness = o["reportBusiness"].text()
                reportLine2 = o["reportLine2"].text()
                reportLogo = o["reportLogo"] as? String
                dailyBackup = o["dailyBackup"] != false
                pinSalt = o["pinSalt"] as? String
                pinHash = o["pinHash"] as? String
                tableView = (o["tableView"] as? String) ?: "LIST"
                tableZoom = ((o["tableZoom"] as? Number)?.toFloat() ?: 1f).coerceIn(0.7f, 1.6f)
                tableSort = (o["tableSort"] as? String) ?: "MODIFIED"
            }
        }
    }

    fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, Json.write(mapOf(
                "syncSeconds" to syncSeconds, "dark" to dark, "pictureRows" to pictureRows,
                "reportBusiness" to reportBusiness, "reportLine2" to reportLine2, "reportLogo" to reportLogo,
                "dailyBackup" to dailyBackup, "pinSalt" to pinSalt, "pinHash" to pinHash,
                "tableView" to tableView, "tableZoom" to tableZoom.toDouble(), "tableSort" to tableSort,
            )))
        }
    }
}
