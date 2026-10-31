package gsp

/** What the Sheet's data validation says about a column (the dropdown list, or a checkbox). */
class SheetValidation(val checkbox: Boolean, val items: List<String>)

/** Identifies the validation the app has put on a Sheet column, so it is only pushed when it changes. */
fun columnSig(type: ColumnType, items: List<String>): String? = when (type) {
    ColumnType.LIST -> "LIST:" + items.joinToString("|")
    ColumnType.CHECKBOX -> "CHECKBOX"
    else -> null
}

/** One canonical text per cell so the app's values and the Sheet's values can be compared. */
fun normalizeCell(type: ColumnType, raw: String): String {
    val text = raw.trim()
    return when (type) {
        ColumnType.NUMBER, ColumnType.CALC, ColumnType.RATING -> parseNumber(text)?.let(::formatNumber).orEmpty()
        ColumnType.CHECKBOX -> text.lowercase()
        else -> text
    }
}

/** A value that tells listeners when it changes (so the core needs no UI or coroutine library). */
class Signal<T>(initial: T) {
    @Volatile
    var value: T = initial
        set(v) {
            field = v
            for (l in listeners) l(v)
        }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(T) -> Unit>()

    /** Calls [listener] on every change; returns a function that stops listening. */
    fun listen(listener: (T) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }
}
