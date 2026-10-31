package gsp

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class SortDirection { ASC, DESC }

data class SortSpec(val columnId: Long, val direction: SortDirection)

enum class FilterOp(val needsValue: Boolean) {
    CONTAINS(true),
    EQUALS(true),
    GREATER(true),
    LESS(true),
    IS_EMPTY(false),
    NOT_EMPTY(false),
    CHECKED(false),
    UNCHECKED(false),
    ;

    fun label(type: ColumnType): String = when (this) {
        CONTAINS -> "contains"
        EQUALS -> "equals"
        GREATER -> if (type == ColumnType.DATE) "is after" else "is greater than"
        LESS -> if (type == ColumnType.DATE) "is before" else "is less than"
        IS_EMPTY -> "is empty"
        NOT_EMPTY -> "is not empty"
        CHECKED -> "is checked"
        UNCHECKED -> "is unchecked"
    }
}

data class FilterSpec(val columnId: Long, val op: FilterOp, val value: String)

/** Totals shown in the footer of a NUMBER column. */
enum class Aggregate(val label: String) {
    SUM("Sum"),
    AVG("Avg"),
    MIN("Min"),
    MAX("Max"),
    COUNT("Count"),
}

/** Everything the user can change about how a table is *shown*. Never stored in the database. */
data class ViewOptions(
    val search: String = "",
    val sort: SortSpec? = null,
    /** At most one filter per column, keyed by column id. */
    val filters: Map<Long, FilterSpec> = emptyMap(),
    /** Column whose values rows are grouped by, or null. */
    val groupBy: Long? = null,
    /** Group keys that are folded shut. */
    val collapsed: Set<String> = emptySet(),
)

fun opsFor(type: ColumnType): List<FilterOp> = when (type) {
    ColumnType.TEXT, ColumnType.LIST, ColumnType.PHONE, ColumnType.ADDRESS, ColumnType.PICTURE, ColumnType.FILE, ColumnType.AUDIO ->
        listOf(FilterOp.CONTAINS, FilterOp.EQUALS, FilterOp.IS_EMPTY, FilterOp.NOT_EMPTY)
    ColumnType.NUMBER, ColumnType.CALC, ColumnType.DATE, ColumnType.TIME ->
        listOf(FilterOp.EQUALS, FilterOp.GREATER, FilterOp.LESS, FilterOp.IS_EMPTY, FilterOp.NOT_EMPTY)
    ColumnType.CHECKBOX -> listOf(FilterOp.CHECKED, FilterOp.UNCHECKED)
}

fun isValidFilterValue(type: ColumnType, op: FilterOp, value: String): Boolean {
    if (!op.needsValue) return true
    val v = value.trim()
    if (v.isEmpty()) return false
    return when (type) {
        ColumnType.NUMBER, ColumnType.CALC -> op == FilterOp.CONTAINS || parseNumber(v) != null
        ColumnType.DATE -> op == FilterOp.CONTAINS || runCatching { LocalDate.parse(v) }.isSuccess
        ColumnType.TIME -> op == FilterOp.CONTAINS || runCatching { java.time.LocalTime.parse(v) }.isSuccess
        else -> true
    }
}

/** Returns [data] with rows reduced by search and filters, then ordered by the sort. */
fun applyView(data: GridData, options: ViewOptions): GridData {
    val columnsById = data.columns.associateBy { it.id }
    var rows = data.rows

    val query = options.search.trim()
    if (query.isNotEmpty()) {
        rows = rows.filter { row -> row.values.values.any { it.contains(query, ignoreCase = true) } }
    }

    for (filter in options.filters.values) {
        val column = columnsById[filter.columnId] ?: continue
        if (!isValidFilterValue(column.type, filter.op, filter.value)) continue
        rows = rows.filter { matches(filter, column.type, it.values[column.id].orEmpty()) }
    }

    val sort = options.sort
    if (sort != null) {
        val column = columnsById[sort.columnId]
        if (column != null) rows = sortRows(rows, column, sort.direction)
    }

    return data.copy(rows = rows)
}

private fun matches(spec: FilterSpec, type: ColumnType, text: String): Boolean {
    val v = spec.value.trim()
    return when (spec.op) {
        FilterOp.IS_EMPTY -> text.isEmpty()
        FilterOp.NOT_EMPTY -> text.isNotEmpty()
        FilterOp.CHECKED -> text == "true"
        FilterOp.UNCHECKED -> text != "true"
        FilterOp.CONTAINS -> text.contains(v, ignoreCase = true)
        FilterOp.EQUALS ->
            if (type == ColumnType.NUMBER || type == ColumnType.CALC) {
                val a = parseNumber(text)
                val b = parseNumber(v)
                a != null && b != null && a == b
            } else {
                text.equals(v, ignoreCase = true)
            }

        FilterOp.GREATER, FilterOp.LESS -> {
            val comparison = if (type == ColumnType.NUMBER || type == ColumnType.CALC) {
                val a = parseNumber(text)
                val b = parseNumber(v)
                if (a == null || b == null) return false
                a.compareTo(b)
            } else {
                // Dates are stored as yyyy-MM-dd, so plain text comparison orders them correctly.
                if (text.isEmpty()) return false
                text.compareTo(v, ignoreCase = true)
            }
            if (spec.op == FilterOp.GREATER) comparison > 0 else comparison < 0
        }
    }
}

/** Empty cells always go last, in both directions. The sort is stable. */
private fun sortRows(rows: List<GridRow>, column: GridColumn, direction: SortDirection): List<GridRow> {
    val base: Comparator<String> =
        if (column.isNumeric) {
            Comparator<String> { a, b -> (parseNumber(a) ?: 0.0).compareTo(parseNumber(b) ?: 0.0) }
        } else {
            String.CASE_INSENSITIVE_ORDER
        }
    val directed = if (direction == SortDirection.ASC) base else base.reversed()

    return rows.sortedWith(
        Comparator<GridRow> { r1, r2 ->
            val a = r1.values[column.id].orEmpty()
            val b = r2.values[column.id].orEmpty()
            when {
                a.isEmpty() && b.isEmpty() -> 0
                a.isEmpty() -> 1
                b.isEmpty() -> -1
                else -> directed.compare(a, b)
            }
        },
    )
}

/** Footer value for one column over the rows currently shown ("" when there is nothing to total). */
fun aggregateValue(column: GridColumn, rows: List<GridRow>, aggregate: Aggregate): String {
    val texts = rows.map { it.values[column.id].orEmpty() }.filter { it.isNotEmpty() }
    if (aggregate == Aggregate.COUNT) return texts.size.toString()

    val numbers = texts.mapNotNull(::parseNumber)
    if (numbers.isEmpty()) return ""

    val result = when (aggregate) {
        Aggregate.SUM -> numbers.sum()
        Aggregate.AVG -> numbers.average()
        Aggregate.MIN -> numbers.min()
        Aggregate.MAX -> numbers.max()
        Aggregate.COUNT -> numbers.size.toDouble()
    }
    // Rounding hides binary floating point noise such as 0.1 + 0.2 = 0.30000000000000004.
    val rounded = BigDecimal.valueOf(result).setScale(6, RoundingMode.HALF_UP).toDouble()
    return formatNumber(rounded)
}
