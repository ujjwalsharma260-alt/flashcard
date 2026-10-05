package com.flashcards.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Deck(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(indices = [Index("deckId"), Index("due")])
data class Flashcard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val tags: String = "",
    val due: Long = 0,
    val interval: Double = 0.0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastReview: Long = 0
)

/** One piece of content on one face. type: TEXT, LATEX (IMAGE, AUDIO added later). */
@Entity(indices = [Index("cardId")])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long, val face: Int, val pos: Int, val type: String, val data: String
)

@Entity data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long, val time: Long, val rating: Int, val interval: Double
)

data class DeckInfo(val id: Long, val name: String, val total: Int, val due: Int, val fresh: Int)
data class CardRow(val id: Long, val tags: String, val preview: String?, val due: Long, val reps: Int)

@Dao
interface AppDao {
    @Insert suspend fun insertDeck(d: Deck): Long
    @Query("DELETE FROM Deck WHERE id=:id") suspend fun deleteDeck(id: Long)
    @Query("DELETE FROM Item WHERE cardId IN (SELECT id FROM Flashcard WHERE deckId=:deck)") suspend fun deleteDeckItems(deck: Long)
    @Query("DELETE FROM Flashcard WHERE deckId=:deck") suspend fun deleteDeckCards(deck: Long)

    @Query("SELECT d.id AS id, d.name AS name, " +
        "(SELECT COUNT(*) FROM Flashcard c WHERE c.deckId=d.id) AS total, " +
        "(SELECT COUNT(*) FROM Flashcard c WHERE c.deckId=d.id AND c.reps>0 AND c.due<=:now) AS due, " +
        "(SELECT COUNT(*) FROM Flashcard c WHERE c.deckId=d.id AND c.reps=0) AS fresh " +
        "FROM Deck d ORDER BY d.name COLLATE NOCASE")
    fun decks(now: Long): Flow<List<DeckInfo>>

    @Insert suspend fun insertCard(c: Flashcard): Long
    @Update suspend fun updateCard(c: Flashcard)
    @Query("DELETE FROM Flashcard WHERE id=:id") suspend fun deleteCard(id: Long)
    @Query("SELECT * FROM Flashcard WHERE id=:id") suspend fun card(id: Long): Flashcard?

    @Insert suspend fun insertItems(l: List<Item>)
    @Query("DELETE FROM Item WHERE cardId=:id") suspend fun deleteItems(id: Long)
    @Query("SELECT * FROM Item WHERE cardId=:id ORDER BY face, pos") suspend fun items(id: Long): List<Item>

    @Query("SELECT id FROM Flashcard WHERE deckId=:deck AND (reps=0 OR due<=:now) ORDER BY (reps=0), due LIMIT 200")
    suspend fun queue(deck: Long, now: Long): List<Long>

    @Insert suspend fun log(r: ReviewLog)

    @Query("SELECT c.id AS id, c.tags AS tags, " +
        "(SELECT data FROM Item WHERE cardId=c.id ORDER BY face, pos LIMIT 1) AS preview, " +
        "c.due AS due, c.reps AS reps FROM Flashcard c WHERE c.deckId=:deck AND (:q='' " +
        "OR c.tags LIKE '%'||:q||'%' OR EXISTS(SELECT 1 FROM Item i WHERE i.cardId=c.id AND i.data LIKE '%'||:q||'%')) " +
        "ORDER BY c.id DESC LIMIT 1000")
    fun cards(deck: Long, q: String): Flow<List<CardRow>>
}

@Database(entities = [Deck::class, Flashcard::class, Item::class, ReviewLog::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "flashcards.db").build().also { inst = it }
        }
    }
}

/** Spaced repetition, kept separate from UI. rating: 0=Again 1=Hard 2=Good 3=Easy */
object Scheduler {
    const val DAY = 86_400_000L
    fun next(c: Flashcard, rating: Int, now: Long): Flashcard {
        var ease = c.ease
        var iv = c.interval
        var lapses = c.lapses
        val due: Long
        when (rating) {
            0 -> { lapses++; ease = maxOf(1.3, ease - 0.2); iv = 0.0; due = now + 10 * 60_000L }
            1 -> { ease = maxOf(1.3, ease - 0.15); iv = if (iv < 1) 1.0 else maxOf(1.0, iv * 1.2); due = now + (iv * DAY).toLong() }
            2 -> { iv = if (iv < 1) 1.0 else iv * ease; due = now + (iv * DAY).toLong() }
            else -> { ease += 0.15; iv = if (iv < 1) 4.0 else iv * ease * 1.3; due = now + (iv * DAY).toLong() }
        }
        return c.copy(due = due, interval = iv, ease = ease, reps = c.reps + 1, lapses = lapses, lastReview = now)
    }
}
