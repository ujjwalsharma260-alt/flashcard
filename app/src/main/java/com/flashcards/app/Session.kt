package com.flashcards.app

import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

fun startOfTodayMs(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Builds one "study set". mode: 0 = due + new, 1 = bookmarked, 2 = favorites, 3 = weak cards, 4 = missed today. */
object Session {
    suspend fun build(dao: AppDao, deckId: Long, mode: Int, now: Long): List<Long> {
        val size = AppSettings.sessionSize          // 0 = all
        val limit = if (size <= 0) 500 else size
        val n = max(1, AppSettings.streakN)
        return when (mode) {
            1 -> dao.bookmarkedIds(deckId, limit)
            2 -> dao.favoriteIds(deckId, limit)
            3 -> dao.weakIds(deckId, n, limit)
            4 -> dao.missedIds(deckId, startOfTodayMs(), limit)
            else -> {
                val base = dao.queue(deckId, now)
                if (AppSettings.carryOver && size > 0) {
                    // e.g. 20 cards with 25%: about 15 fresh cards + up to 5 older cards you struggled with
                    val wanted = max(1, (size * AppSettings.carryPercent / 100.0).roundToInt())
                    val weak = dao.weakIds(deckId, n, wanted * 3).take(wanted)
                    val weakSet = weak.toSet()
                    val regular = base.filter { it !in weakSet }.take(max(0, size - weak.size))
                    if (weak.isEmpty()) regular else (regular + weak).shuffled()
                } else base.take(limit)
            }
        }
    }
}
