package com.flashcards.app

object Tsv {
    data class Row(val line: Int, val cells: List<String>)
    data class Analysis(val cards: List<List<String>>, val bad: List<Pair<Int, String>>, val maxCols: Int)

    /** Tab-separated parser with quoted fields ("" = quote), multi-line cells, CRLF and BOM support. */
    fun parse(input: String): List<Row> {
        val text = input.removePrefix("\uFEFF")
        val rows = ArrayList<Row>()
        var cells = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var fieldStart = true
        var line = 1
        var rowLine = 1

        fun endField() { cells.add(sb.toString()); sb.setLength(0); fieldStart = true }
        fun endRow() { endField(); rows.add(Row(rowLine, cells)); cells = ArrayList(); rowLine = line }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                } else {
                    if (c == '\n') line++
                    sb.append(c)
                }
            } else if (c == '"' && fieldStart && sb.isEmpty()) {
                inQ = true
                fieldStart = false
            } else if (c == '\t') {
                endField()
            } else if (c == '\n') {
                line++
                endRow()
            } else if (c == '\r') {
                // ignore
            } else {
                sb.append(c)
                fieldStart = false
            }
            i++
        }
        if (sb.isNotEmpty() || cells.isNotEmpty()) endRow()
        return rows
    }

    private val headerWords = Regex(
        "^(face\\s*\\d|side\\s*\\d|question|answer|front|back|hint|explanation|example|formula|term|definition|notes?|diagram|q|a|prompt|response|topic|concept|meaning)$",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeHeader(r: Row): Boolean {
        val filled = r.cells.filter { it.isNotBlank() }
        return filled.isNotEmpty() && filled.all { headerWords.matches(it.trim()) }
    }

    fun analyze(rows: List<Row>, skipHeader: Boolean): Analysis {
        val cards = ArrayList<List<String>>()
        val bad = ArrayList<Pair<Int, String>>()
        var maxCols = 2
        rows.forEachIndexed { idx, r ->
            if (idx == 0 && skipHeader) return@forEachIndexed
            val cells = r.cells.map { it.trim() }
            if (cells.all { it.isEmpty() }) return@forEachIndexed
            var n = cells.size
            while (n > 0 && cells[n - 1].isEmpty()) n--
            if (cells.size < 2) {
                bad.add(Pair(r.line, "Only 1 column (cards need 2 to 5 columns separated by Tab)"))
            } else if (n > 5) {
                bad.add(Pair(r.line, "More than 5 columns"))
            } else {
                cards.add(cells.take(5))
                maxCols = maxOf(maxCols, minOf(n, 5))
            }
        }
        return Analysis(cards, bad, maxCols)
    }

    /** A cell that is entirely $$...$$ becomes an editable formula item; anything else is kept exactly as text. */
    fun cellToItem(cell: String): EItem {
        val t = cell.trim()
        val d = "\$\$"
        if (t.length > 4 && t.startsWith(d) && t.endsWith(d)) {
            val inner = t.substring(2, t.length - 2).trim()
            if (inner.isNotEmpty() && !inner.contains(d)) return EItem("LATEX", inner)
        }
        return EItem("TEXT", cell)
    }

    private fun esc(s: String): String =
        if (s.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun build(cards: List<List<String>>): String {
        val n = (cards.maxOfOrNull { it.size } ?: 2).coerceIn(2, 5)
        val sb = StringBuilder()
        sb.append((1..n).joinToString("\t") { "Face$it" }).append("\n")
        for (c in cards) {
            sb.append((0 until n).joinToString("\t") { esc(c.getOrElse(it) { "" }) }).append("\n")
        }
        return sb.toString()
    }

    const val TEMPLATE = "Face1\tFace2\tFace3\tFace4\tFace5"

    suspend fun deckTsv(dao: AppDao, deckId: Long): String {
        val byCard = dao.deckItems(deckId).groupBy { it.cardId }
        val cards = byCard.values.map { list ->
            list.groupBy { it.face }.toSortedMap().values.take(5).map { faceItems ->
                faceItems.mapNotNull { x ->
                    when (x.type) {
                        "TEXT" -> x.data
                        "LATEX" -> "\$\$" + x.data + "\$\$"
                        else -> null
                    }
                }.joinToString("\n")
            }
        }
        return build(cards)
    }
}
