package gsp

import java.time.LocalDate
import kotlin.math.floor

/** One data row read from a sheet. [sourceRow] is its 1-based row number in the sheet. */
data class SourceRow(val sourceRow: Int, val cells: List<Any?>)

/** A column the user can review before importing. [index] is the 0-based column position in the sheet. */
data class PlanColumn(
    val index: Int,
    val name: String,
    val type: ColumnType,
    val sample: String,
    val readOnly: Boolean = false,
    val listItems: List<String> = emptyList(),
)

class ImportPlan(val columns: List<PlanColumn>, val rows: List<SourceRow>)

/** Turns raw Google Sheets values into columns with guessed types, and converts cells to table values. */
object SheetImport {

    /** Row 1 of [values] is taken as the header. [dateColumns] are columns formatted as dates in the sheet. */
    fun plan(
        values: List<List<Any?>>,
        dateColumns: Set<Int>,
        validations: Map<Int, SheetValidation> = emptyMap(),
        formulaColumns: Set<Int> = emptySet(),
    ): ImportPlan {
        val header = values.firstOrNull().orEmpty()
        val rows = values.drop(1).mapIndexedNotNull { index, cells ->
            if (cells.all { isBlank(it) }) null else SourceRow(sourceRow = index + 2, cells = cells)
        }
        val columnCount = maxOf(header.size, rows.maxOfOrNull { it.cells.size } ?: 0).coerceAtLeast(1)

        val columns = List(columnCount) { c ->
            val filled = rows.mapNotNull { it.cells.getOrNull(c) }.filterNot { isBlank(it) }
            val validation = validations[c]
            val type = inferType(filled, c in dateColumns, validation)
            val name = (header.getOrNull(c)?.toString() ?: "").trim().ifEmpty { "Column ${c + 1}" }
            val sample = filled.take(2)
                .joinToString(", ") { cell -> convert(cell, type).ifEmpty { cell.toString() } }
                .take(30)
            PlanColumn(
                index = c,
                name = name,
                type = type,
                sample = sample,
                readOnly = c in formulaColumns,
                listItems = if (type == ColumnType.LIST) validation?.items.orEmpty() else emptyList(),
            )
        }
        return ImportPlan(columns, rows)
    }

    /** Guesses a column type from its non-empty raw values, its date format and its Sheet validation. */
    fun inferType(filled: List<Any?>, isDateFormat: Boolean, validation: SheetValidation?): ColumnType = when {
        validation != null && validation.checkbox -> ColumnType.CHECKBOX
        validation != null && validation.items.isNotEmpty() -> ColumnType.LIST
        filled.isNotEmpty() && filled.all { it is Boolean } -> ColumnType.CHECKBOX
        isDateFormat && filled.all { it is Number } -> ColumnType.DATE
        filled.isNotEmpty() && filled.all { it is Number } -> ColumnType.NUMBER
        else -> ColumnType.TEXT
    }

    /**
     * Converts one raw sheet value to the text stored for a column of [type]. Returns "" when the
     * value does not fit the type (the cell is then left empty).
     */
    fun convert(raw: Any?, type: ColumnType): String {
        if (raw == null) return ""
        return when (type) {
            ColumnType.TEXT -> when (raw) {
                is String -> raw.trim()
                is Boolean -> raw.toString().uppercase()
                is Number -> formatNumber(raw.toDouble())
                else -> raw.toString()
            }

            ColumnType.NUMBER, ColumnType.CALC -> when (raw) {
                is Number -> formatNumber(raw.toDouble())
                is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::formatNumber).orEmpty()
                else -> ""
            }

            ColumnType.DATE -> when (raw) {
                is Number -> runCatching {
                    // Google Sheets counts days from 1899-12-30.
                    LocalDate.of(1899, 12, 30).plusDays(floor(raw.toDouble()).toLong()).toString()
                }.getOrDefault("")
                is String -> raw.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess }.orEmpty()
                else -> ""
            }

            ColumnType.TIME -> when (raw) {
                is Number -> {
                    // A time in Sheets is a fraction of a day.
                    val minutes = Math.round((raw.toDouble() % 1.0) * 24 * 60).toInt() % (24 * 60)
                    "%02d:%02d".format(minutes / 60, minutes % 60)
                }
                is String -> raw.trim().let { text ->
                    runCatching { java.time.LocalTime.parse(text).withSecond(0).withNano(0).toString() }
                        .getOrDefault(text)
                }
                else -> ""
            }

            ColumnType.LIST, ColumnType.PHONE, ColumnType.ADDRESS, ColumnType.PICTURE, ColumnType.FILE, ColumnType.AUDIO -> when (raw) {
                is String -> raw.trim()
                is Boolean -> raw.toString().uppercase()
                is Number -> formatNumber(raw.toDouble())
                else -> raw.toString()
            }

            ColumnType.CHECKBOX -> when (raw) {
                is Boolean -> raw.toString()
                is String -> raw.trim().lowercase().takeIf { it == "true" || it == "false" }.orEmpty()
                else -> ""
            }
        }
    }

    private fun isBlank(value: Any?): Boolean = value == null || (value is String && value.isBlank())
}
