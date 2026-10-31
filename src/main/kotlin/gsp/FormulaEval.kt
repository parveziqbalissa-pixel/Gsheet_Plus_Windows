package gsp

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Calculated columns: a small formula language over other columns of the same row.
 *
 * Example: `[Qty] * [Price] + 2`. Numbers, `[Column name]`, + - * / ^, parentheses,
 * and the functions ROUND(x, digits), ABS(x), MIN(a, b, ...), MAX(a, b, ...), SUM(a, b, ...).
 * An empty cell counts as 0; text that is not a number makes the result empty.
 */
object FormulaEval {

    /** Names of the columns used in [expression], in order of appearance. */
    fun references(expression: String): List<String> =
        Regex("\\[([^\\]]+)]").findAll(expression).map { it.groupValues[1].trim() }.toList()

    /** True if [expression] can be parsed (it may still fail later on bad data). */
    fun isValid(expression: String): Boolean = evaluate(expression) { "0" } != null

    /**
     * Returns the result, or null when the formula is invalid, refers to an unknown column,
     * divides by zero or uses a non-numeric cell. [valueOf] gives the text of a column by name, or
     * null if there is no such column.
     */
    fun evaluate(expression: String, valueOf: (String) -> String?): Double? {
        if (expression.isBlank()) return null
        return try {
            val parser = Parser(expression, valueOf)
            val result = parser.parseExpression()
            parser.expectEnd()
            result.takeIf { it.isFinite() }
        } catch (e: FormulaException) {
            null
        }
    }

    /** The result as the text stored/shown in a cell. */
    fun format(value: Double): String =
        formatNumber(BigDecimal(value).setScale(9, RoundingMode.HALF_UP).toDouble())

    private class FormulaException : Exception()

    private class Parser(private val text: String, private val valueOf: (String) -> String?) {
        private var pos = 0

        fun expectEnd() {
            skipSpaces()
            if (pos != text.length) throw FormulaException()
        }

        fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                skipSpaces()
                left = when (peek()) {
                    '+' -> { pos++; left + parseTerm() }
                    '-' -> { pos++; left - parseTerm() }
                    else -> return left
                }
            }
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (true) {
                skipSpaces()
                left = when (peek()) {
                    '*' -> { pos++; left * parsePower() }
                    '/' -> {
                        pos++
                        val right = parsePower()
                        if (right == 0.0) throw FormulaException()
                        left / right
                    }
                    else -> return left
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipSpaces()
            if (peek() == '^') {
                pos++
                return Math.pow(base, parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipSpaces()
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            val c = peek() ?: throw FormulaException()
            return when {
                c == '(' -> {
                    pos++
                    val inner = parseExpression()
                    skipSpaces()
                    if (peek() != ')') throw FormulaException()
                    pos++
                    inner
                }
                c == '[' -> {
                    val end = text.indexOf(']', pos)
                    if (end < 0) throw FormulaException()
                    val name = text.substring(pos + 1, end).trim()
                    pos = end + 1
                    val raw = valueOf(name) ?: throw FormulaException()
                    if (raw.isBlank()) 0.0 else parseNumber(raw) ?: throw FormulaException()
                }
                c.isDigit() || c == '.' -> parseNumberLiteral()
                c.isLetter() -> parseFunction()
                else -> throw FormulaException()
            }
        }

        private fun parseNumberLiteral(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: throw FormulaException()
        }

        private fun parseFunction(): Double {
            val start = pos
            while (pos < text.length && text[pos].isLetter()) pos++
            val name = text.substring(start, pos).uppercase()
            skipSpaces()
            if (peek() != '(') throw FormulaException()
            pos++
            val args = ArrayList<Double>()
            skipSpaces()
            if (peek() == ')') {
                pos++
            } else {
                while (true) {
                    args += parseExpression()
                    skipSpaces()
                    when (peek()) {
                        ',' -> pos++
                        ')' -> { pos++; break }
                        else -> throw FormulaException()
                    }
                }
            }
            return when (name) {
                "ABS" -> if (args.size == 1) Math.abs(args[0]) else throw FormulaException()
                "ROUND" -> when (args.size) {
                    1 -> BigDecimal(args[0]).setScale(0, RoundingMode.HALF_UP).toDouble()
                    2 -> BigDecimal(args[0]).setScale(args[1].toInt().coerceIn(0, 12), RoundingMode.HALF_UP).toDouble()
                    else -> throw FormulaException()
                }
                "MIN" -> if (args.isNotEmpty()) args.min() else throw FormulaException()
                "MAX" -> if (args.isNotEmpty()) args.max() else throw FormulaException()
                "SUM" -> args.sum()
                else -> throw FormulaException()
            }
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}

/**
 * Fills in the value of every calculated column of one row. [values] maps column id to the text
 * of each stored column and receives the results (empty text when a formula cannot be calculated).
 */
fun applyCalculatedColumns(columns: List<GridColumn>, values: MutableMap<Long, String>) {
    val byName = HashMap<String, Long>()
    columns.forEach { byName[it.name.trim().lowercase()] = it.id }
    for (column in columns) {
        if (column.type != ColumnType.CALC) continue
        val result = FormulaEval.evaluate(column.formula) { name ->
            byName[name.trim().lowercase()]?.let { id -> values[id].orEmpty() }
        }
        values[column.id] = result?.let(FormulaEval::format).orEmpty()
    }
}
