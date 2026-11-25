package com.flashcards.app

object Tsv {
    data class Row(val line: Int, val cells: List<String>)

    /** A row that was not imported, with the text it had, so the screen can show it back. */
    data class BadRow(val line: Int, val why: String, val cells: List<String>)

    data class Analysis(
        val cards: List<List<String>>,
        val bad: List<BadRow>,
        val maxCols: Int,
        /** Formatting lines (code fences, markdown table rules) that were dropped silently. */
        val ignored: Int = 0
    )

    /**
     * Picks the column separator. Tab wins if present anywhere.
     * Otherwise " | " (pipe with spaces) or ";" if at least half of the non-empty lines contain it.
     */
    fun detectDelimiter(text: String): Char {
        if (text.contains('\t')) return '\t'
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return '\t'
        if (lines.count { it.contains(" | ") } * 2 >= lines.size) return '|'
        if (lines.count { it.contains(';') } * 2 >= lines.size) return ';'
        return '\t'
    }

    /** Parser with quoted fields ("" = quote), multi-line cells, CRLF and BOM support. Default separator is Tab. */
    fun parse(input: String, delim: Char = '\t'): List<Row> {
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

        // A pipe only counts as a separator when it has a space on both sides (so |x| in formulas is safe).
        fun isSeparator(i: Int): Boolean {
            val c = text[i]
            if (c != delim) return false
            if (delim != '|') return true
            return i > 0 && i + 1 < text.length && text[i - 1] == ' ' && text[i + 1] == ' '
        }

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
            } else if (isSeparator(i)) {
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

    /** Header test that skips format noise (a code fence above the table is common). */
    fun looksLikeHeader(rows: List<Row>): Boolean =
        rows.firstOrNull { !isNoise(it.cells) }?.let { looksLikeHeader(it) } ?: false

    /**
     * True for lines that are formatting, not cards: ``` code fences (often wrapping the
     * table an AI returns) and markdown table rules such as |---|---|. Dropping these is why
     * a paste straight from a chat no longer reports two "problematic rows".
     */
    private fun isNoise(cells: List<String>): Boolean {
        val filled = cells.map { it.trim() }.filter { it.isNotEmpty() }
        if (filled.isEmpty()) return true
        if (filled.size == 1 && filled[0].startsWith("```")) return true
        val joined = filled.joinToString("").filterNot { it.isWhitespace() }
        return joined.isNotEmpty() && joined.all { it == '-' || it == '|' || it == ':' }
    }

    fun analyze(rows: List<Row>, skipHeader: Boolean): Analysis {
        val cards = ArrayList<List<String>>()
        val bad = ArrayList<BadRow>()
        var maxCols = 2
        var ignored = 0
        // The header is the first real row, not necessarily the first line (a fence may be above it).
        var headerPending = skipHeader
        rows.forEach { r ->
            val cells = r.cells.map { it.trim() }
            if (cells.all { it.isEmpty() }) return@forEach
            if (isNoise(cells)) {
                ignored++
                return@forEach
            }
            if (headerPending) {
                headerPending = false
                return@forEach
            }
            var n = cells.size
            while (n > 0 && cells[n - 1].isEmpty()) n--
            if (n < 2) {
                bad.add(BadRow(r.line, "Only 1 column (a card needs 2 to 5 columns separated by Tab, or by \" | \")", cells))
            } else if (n > 5) {
                bad.add(BadRow(r.line, "More than 5 columns", cells))
            } else {
                cards.add(cells.take(5))
                maxCols = maxOf(maxCols, minOf(n, 5))
            }
        }
        return Analysis(cards, bad, maxCols, ignored)
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

    suspend fun deckTsv(dao: AppDao, deckId: Long): String = fromItems(dao.deckItems(deckId))

    suspend fun idsTsv(dao: AppDao, ids: List<Long>): String {
        val items = ids.sorted().chunked(500).flatMap { dao.itemsOf(it) }
        return fromItems(items)
    }

    fun fromItems(items: List<Item>): String {
        val byCard = items.groupBy { it.cardId }
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
