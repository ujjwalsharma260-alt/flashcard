package com.flashcards.app

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Calendar

/**
 * Search language (all terms are combined with AND, new operators can be added in one place):
 *   word            text in any face / LaTeX source / tags / deck name
 *   tag:x  deck:x   has:image  has:audio  has:formula  faces:3  faces:3+
 *   favorite  suspended  due  overdue  state:new|learning|review|relearning
 *   added:today|yesterday|7d|30d
 */
object CardSearch {
    private const val DAY = 86_400_000L

    private const val BASE =
        "SELECT c.id AS id, c.deckId AS deckId, d.name AS deckName, " +
        "(SELECT data FROM Item WHERE cardId=c.id AND type IN ('TEXT','LATEX') ORDER BY face, pos LIMIT 1) AS preview, " +
        "c.tags AS tags, c.fav AS fav, c.suspended AS suspended, c.state AS state, c.due AS due " +
        "FROM Flashcard c JOIN Deck d ON d.id=c.deckId WHERE 1=1"

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun tokens(q: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        for (ch in q) {
            if (ch == '"') inQ = !inQ
            else if (ch.isWhitespace() && !inQ) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            } else sb.append(ch)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun build(q: String, deckScope: Long, now: Long): SimpleSQLiteQuery {
        val sql = StringBuilder(BASE)
        val args = ArrayList<Any?>()
        if (deckScope != 0L) { sql.append(" AND c.deckId=?"); args.add(deckScope) }

        fun text(t: String) {
            sql.append(" AND (c.tags LIKE ? OR d.name LIKE ? OR EXISTS(SELECT 1 FROM Item i WHERE i.cardId=c.id AND i.type IN ('TEXT','LATEX') AND i.data LIKE ?))")
            val p = "%$t%"
            args.add(p); args.add(p); args.add(p)
        }
        fun hasType(type: String) {
            sql.append(" AND EXISTS(SELECT 1 FROM Item i WHERE i.cardId=c.id AND i.type='$type')")
        }

        for (t in tokens(q)) {
            val low = t.lowercase()
            val colon = t.indexOf(':')
            val key = if (colon > 0) low.substring(0, colon) else ""
            val v = if (colon > 0) t.substring(colon + 1).trim() else ""
            val vl = v.lowercase()
            if (low == "favorite" || low == "favorites" || low == "starred") {
                sql.append(" AND c.fav=1")
            } else if (low == "suspended" || (key == "state" && vl == "suspended")) {
                sql.append(" AND c.suspended=1")
            } else if (low == "due") {
                sql.append(" AND c.state<>0 AND c.suspended=0 AND c.due<=?"); args.add(now)
            } else if (low == "overdue") {
                sql.append(" AND c.state<>0 AND c.suspended=0 AND c.due<=?"); args.add(now - DAY)
            } else if (key == "state" && vl in listOf("new", "learning", "review", "relearning")) {
                sql.append(" AND c.state=?"); args.add(listOf("new", "learning", "review", "relearning").indexOf(vl))
            } else if (key == "tag" && v.isNotEmpty()) {
                val bare = v.trimStart('#')
                sql.append(" AND ((' '||c.tags||' ') LIKE ? OR (' '||c.tags||' ') LIKE ?)")
                args.add("% #$bare %"); args.add("% $bare %")
            } else if (key == "deck" && v.isNotEmpty()) {
                sql.append(" AND d.name LIKE ?"); args.add("%$v%")
            } else if (key == "has" && vl in listOf("image", "images")) {
                hasType("IMAGE")
            } else if (key == "has" && vl == "audio") {
                hasType("AUDIO")
            } else if (key == "has" && (vl == "formula" || vl == "latex")) {
                hasType("LATEX")
            } else if (key == "faces" && v.isNotEmpty()) {
                val plus = v.endsWith("+")
                val n = v.trimEnd('+').toIntOrNull()
                if (n != null) {
                    sql.append(" AND (SELECT COUNT(DISTINCT face) FROM Item WHERE cardId=c.id) ").append(if (plus) ">=" else "=").append(" ?")
                    args.add(n)
                } else text(t)
            } else if (key == "added" && vl in listOf("today", "yesterday", "7d", "30d")) {
                val today = startOfToday()
                when (vl) {
                    "today" -> { sql.append(" AND c.created>=?"); args.add(today) }
                    "yesterday" -> { sql.append(" AND c.created>=? AND c.created<?"); args.add(today - DAY); args.add(today) }
                    "7d" -> { sql.append(" AND c.created>=?"); args.add(today - 6 * DAY) }
                    else -> { sql.append(" AND c.created>=?"); args.add(today - 29 * DAY) }
                }
            } else {
                text(t)
            }
        }
        sql.append(" ORDER BY c.id DESC LIMIT 1000")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /** Adds the token to the query, or removes it if it is already there. */
    fun toggle(q: String, token: String): String {
        val parts = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val has = parts.any { it.equals(token, ignoreCase = true) }
        val out = if (has) parts.filter { !it.equals(token, ignoreCase = true) } else parts + token
        return out.joinToString(" ")
    }

    fun has(q: String, token: String): Boolean = q.trim().split(Regex("\\s+")).any { it.equals(token, ignoreCase = true) }
}
