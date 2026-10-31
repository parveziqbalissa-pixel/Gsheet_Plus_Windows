package gsp

import java.math.BigDecimal

enum class ColumnType(val label: String) {
    TEXT("Text"),
    NUMBER("Number"),
    DATE("Date"),
    CHECKBOX("Checkbox"),
    TIME("Time"),
    LIST("List"),
    PHONE("Phone"),
    ADDRESS("Address"),
    PICTURE("Picture"),
    FILE("File"),
    AUDIO("Audio"),
    CALC("Formula"),
    ;

    /** Pictures, files and recordings: the cell holds one name per line; the Drive links are kept per attachment. */
    val isAttachment: Boolean get() = this == PICTURE || this == FILE || this == AUDIO

    companion object {
        fun fromName(name: String): ColumnType = entries.firstOrNull { it.name == name } ?: TEXT
    }
}

/** One row of the table list screen. */
data class TableSummary(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val rowCount: Int,
    val columnCount: Int,
    val linked: Boolean,
    val pinned: Boolean = false,
)

data class GridColumn(
    val id: Long,
    val name: String,
    val type: ColumnType,
    val width: Int,
    val prefix: String = "",
    val suffix: String = "",
    /** Used for new rows. DATE and TIME also accept "{today}" / "{now}". */
    val defaultValue: String = "",
    val listItems: List<String> = emptyList(),
    /** CALC columns: the formula, e.g. "[Qty] * [Price]". */
    val formula: String = "",
    /** Filled from a Sheet formula (or calculated here): cannot be edited in the app. */
    val readOnly: Boolean = false,
    val bgColor: Int? = null,
    val textColor: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val pinned: Boolean = false,
) {
    val isNumeric: Boolean get() = type == ColumnType.NUMBER || type == ColumnType.CALC
    val locked: Boolean get() = readOnly || type == ColumnType.CALC
}

/** Splits the stored list items (one per line). */
fun parseListItems(raw: String?): List<String> =
    raw.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** The text a new row starts with for [column]. */
fun GridColumn.initialValue(): String {
    val raw = defaultValue.trim()
    return when {
        raw.isEmpty() -> ""
        type == ColumnType.DATE && raw.equals("{today}", true) -> java.time.LocalDate.now().toString()
        type == ColumnType.TIME && raw.equals("{now}", true) ->
            java.time.LocalTime.now().withSecond(0).withNano(0).toString()
        else -> raw
    }
}

/** TEXT columns store their prefix and suffix as part of the value; other types keep them display-only. */
fun GridColumn.withAffixes(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty() || type != ColumnType.TEXT) return value
    val start = if (prefix.isNotEmpty() && !value.startsWith(prefix)) prefix + value else value
    return if (suffix.isNotEmpty() && !start.endsWith(suffix)) start + suffix else start
}

/** The value as typed by the user, without the stored prefix and suffix. */
fun GridColumn.withoutAffixes(value: String): String {
    if (type != ColumnType.TEXT) return value
    return value.removePrefix(prefix).removeSuffix(suffix)
}

/** The lines (attachment names) in a Picture, File or Audio cell. */
fun attachmentLinks(value: String): List<String> = value.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** [values] maps column id to the text to display in that cell ("" when empty). */
data class GridRow(
    val id: Long,
    val values: Map<Long, String>,
    val color: Int? = null,
    val reminderAt: Long? = null,
)

data class GridData(
    val tableId: Long,
    val name: String,
    val columns: List<GridColumn>,
    val rows: List<GridRow>,
)

/** Accepts both "." and "," as decimal separator. Returns null for anything that is not a finite number. */
fun parseNumber(raw: String): Double? =
    raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** 12.0 -> "12", 0.50 -> "0.5", never scientific notation. */
fun formatNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
