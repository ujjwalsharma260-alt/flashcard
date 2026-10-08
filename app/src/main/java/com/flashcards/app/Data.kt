package com.flashcards.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity data class Deck(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

/** state: 0 New, 1 Learning, 2 Review, 3 Relearning */
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
    val lastReview: Long = 0,
    @ColumnInfo(defaultValue = "0") val state: Int = 0,
    @ColumnInfo(defaultValue = "0") val step: Int = 0,
    @ColumnInfo(defaultValue = "0") val fav: Int = 0,
    @ColumnInfo(defaultValue = "0") val suspended: Int = 0,
    @ColumnInfo(defaultValue = "0") val created: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val bookmark: Int = 0,
    @ColumnInfo(defaultValue = "0") val streak: Int = 0,
    @ColumnInfo(defaultValue = "0") val misses: Int = 0
)

/** One piece of content on one face. type: TEXT, LATEX, IMAGE, AUDIO (data = file name for IMAGE/AUDIO). */
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
data class CardRow(val id: Long, val deckId: Long, val deckName: String, val preview: String?, val tags: String, val fav: Int, val suspended: Int, val state: Int, val due: Long, val bookmark: Int)
data class StudyCounts(val bookmarked: Int, val favorites: Int, val weak: Int, val missed: Int)
data class IdTags(val id: Long, val tags: String)

@Dao
interface AppDao {
    @Insert suspend fun insertDeck(d: Deck): Long
    @Query("DELETE FROM Deck WHERE id=:id") suspend fun deleteDeck(id: Long)
    @Query("UPDATE Deck SET name=:name WHERE id=:id") suspend fun renameDeck(id: Long, name: String)
    @Query("DELETE FROM Item WHERE cardId IN (SELECT id FROM Flashcard WHERE deckId=:deck)") suspend fun deleteDeckItems(deck: Long)
    @Query("DELETE FROM Flashcard WHERE deckId=:deck") suspend fun deleteDeckCards(deck: Long)

    @Query("SELECT d.id AS id, d.name AS name, " +
        "(SELECT COUNT(*) FROM Flashcard c WHERE c.deckId=d.id) AS total, " +
        "(SELECT COUNT(*) FROM Flashcard c WHERE c.deckId=d.id AND c.state<>0 AND c.suspended=0 AND c.due<=:now) AS due, " +
        "(SELECT COUNT(*) FROM Flashcard c WHERE c.deckId=d.id AND c.state=0 AND c.suspended=0) AS fresh " +
        "FROM Deck d ORDER BY d.name COLLATE NOCASE")
    fun decks(now: Long): Flow<List<DeckInfo>>

    @Insert suspend fun insertCard(c: Flashcard): Long
    @Update suspend fun updateCard(c: Flashcard)
    @Query("DELETE FROM Flashcard WHERE id=:id") suspend fun deleteCard(id: Long)
    @Query("SELECT * FROM Flashcard WHERE id=:id") suspend fun card(id: Long): Flashcard?

    @Insert suspend fun insertItems(l: List<Item>)
    @Query("DELETE FROM Item WHERE cardId=:id") suspend fun deleteItems(id: Long)
    @Query("SELECT * FROM Item WHERE cardId=:id ORDER BY face, pos") suspend fun items(id: Long): List<Item>
    @Query("SELECT i.* FROM Item i JOIN Flashcard c ON c.id=i.cardId WHERE c.deckId=:deck ORDER BY i.cardId, i.face, i.pos")
    suspend fun deckItems(deck: Long): List<Item>

    @Query("SELECT id FROM Flashcard WHERE deckId=:deck AND suspended=0 AND (state=0 OR due<=:now) ORDER BY (state=0), due LIMIT 200")
    suspend fun queue(deck: Long, now: Long): List<Long>

    @Insert suspend fun log(r: ReviewLog): Long
    @Query("DELETE FROM ReviewLog WHERE id=:id") suspend fun deleteLog(id: Long)

    @Query("SELECT * FROM Deck") suspend fun allDecks(): List<Deck>
    @Query("SELECT * FROM Flashcard") suspend fun allCards(): List<Flashcard>
    @Query("SELECT * FROM Item") suspend fun allItems(): List<Item>

    @Query("SELECT tags FROM Flashcard WHERE (:deck=0 OR deckId=:deck) AND tags<>''")
    fun tagLists(deck: Long): Flow<List<String>>

    @Query("UPDATE Flashcard SET bookmark=:v WHERE id IN (:ids)") suspend fun setBookmark(ids: List<Long>, v: Int)
    @Query("SELECT id FROM Flashcard WHERE deckId=:deck AND suspended=0 AND bookmark=1 ORDER BY RANDOM() LIMIT :lim")
    suspend fun bookmarkedIds(deck: Long, lim: Int): List<Long>
    @Query("SELECT id FROM Flashcard WHERE deckId=:deck AND suspended=0 AND fav=1 ORDER BY RANDOM() LIMIT :lim")
    suspend fun favoriteIds(deck: Long, lim: Int): List<Long>
    @Query("SELECT id FROM Flashcard WHERE deckId=:deck AND suspended=0 AND misses>0 AND streak<:n ORDER BY RANDOM() LIMIT :lim")
    suspend fun weakIds(deck: Long, n: Int, lim: Int): List<Long>
    @Query("SELECT id FROM Flashcard WHERE deckId=:deck AND suspended=0 AND id IN (SELECT cardId FROM ReviewLog WHERE rating=0 AND time>=:since) ORDER BY RANDOM() LIMIT :lim")
    suspend fun missedIds(deck: Long, since: Long, lim: Int): List<Long>
    @Query("SELECT " +
        "(SELECT COUNT(*) FROM Flashcard WHERE deckId=:deck AND suspended=0 AND bookmark=1) AS bookmarked, " +
        "(SELECT COUNT(*) FROM Flashcard WHERE deckId=:deck AND suspended=0 AND fav=1) AS favorites, " +
        "(SELECT COUNT(*) FROM Flashcard WHERE deckId=:deck AND suspended=0 AND misses>0 AND streak<:n) AS weak, " +
        "(SELECT COUNT(*) FROM Flashcard WHERE deckId=:deck AND suspended=0 AND id IN (SELECT cardId FROM ReviewLog WHERE rating=0 AND time>=:since)) AS missed")
    fun studyCounts(deck: Long, n: Int, since: Long): Flow<StudyCounts>

    @RawQuery(observedEntities = [Flashcard::class, Item::class, Deck::class])
    fun browse(query: SupportSQLiteQuery): Flow<List<CardRow>>

    @Query("UPDATE Flashcard SET fav=:v WHERE id IN (:ids)") suspend fun setFav(ids: List<Long>, v: Int)
    @Query("UPDATE Flashcard SET suspended=:v WHERE id IN (:ids)") suspend fun setSusp(ids: List<Long>, v: Int)
    @Query("UPDATE Flashcard SET deckId=:deck WHERE id IN (:ids)") suspend fun setDeck(ids: List<Long>, deck: Long)
    @Query("SELECT id, tags FROM Flashcard WHERE id IN (:ids)") suspend fun tagsOf(ids: List<Long>): List<IdTags>
    @Query("UPDATE Flashcard SET tags=:tags WHERE id=:id") suspend fun setTags(id: Long, tags: String)
    @Query("DELETE FROM Item WHERE cardId IN (:ids)") suspend fun deleteItemsOf(ids: List<Long>)
    @Query("DELETE FROM Flashcard WHERE id IN (:ids)") suspend fun deleteCards(ids: List<Long>)
    @Query("UPDATE Flashcard SET deckId=:dst WHERE deckId=:src") suspend fun moveAll(src: Long, dst: Long)
    @Query("SELECT * FROM Flashcard WHERE deckId=:deck") suspend fun cardsOfDeck(deck: Long): List<Flashcard>
    @Query("SELECT * FROM Item WHERE cardId IN (:ids) ORDER BY cardId, face, pos") suspend fun itemsOf(ids: List<Long>): List<Item>
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN state INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN step INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE Flashcard SET state=2 WHERE reps>0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN fav INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN suspended INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN created INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE Flashcard SET created=" + System.currentTimeMillis())
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN bookmark INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN streak INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Flashcard ADD COLUMN misses INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [Deck::class, Flashcard::class, Item::class, ReviewLog::class], version = 4, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "flashcards.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { inst = it }
        }
    }
}

/**
 * Spaced repetition (SM-2 style, adaptive per card):
 * New -> Learning (1 min, 10 min) -> Review; a failed Review card goes to Relearning.
 * Each card has its own "ease": Again/Hard lower it, Easy raises it, so hard cards come back sooner.
 * Optional streak bonus: after N correct answers in a row the next gap is multiplied (card appears less often).
 * rating: 0=Again 1=Hard 2=Good 3=Easy
 */
object Scheduler {
    const val DAY = 86_400_000L
    private const val MIN = 60_000L
    private val steps = longArrayOf(1 * MIN, 10 * MIN)

    private fun newStreak(c: Flashcard, rating: Int): Int = when (rating) {
        0 -> 0
        1 -> c.streak
        else -> c.streak + 1
    }

    /** Only updates the statistics (used when a card is studied outside its schedule, e.g. in a Bookmarked session). */
    fun statsOnly(c: Flashcard, rating: Int, now: Long): Flashcard =
        c.copy(streak = newStreak(c, rating), misses = c.misses + (if (rating == 0) 1 else 0), reps = c.reps + 1, lastReview = now)

    fun next(c: Flashcard, rating: Int, now: Long, streakN: Int = 0, bonus: Double = 1.0): Flashcard {
        val streak = newStreak(c, rating)
        val useBonus = streakN > 0 && streak >= streakN
        var state = c.state
        var step = c.step
        var iv = c.interval
        var ease = c.ease
        var lapses = c.lapses
        var due = now
        when (state) {
            0, 1 -> when (rating) {
                0 -> { state = 1; step = 0; due = now + steps[0] }
                1 -> { state = 1; due = now + steps[minOf(step, steps.lastIndex)] }
                2 -> {
                    if (step + 1 < steps.size) { state = 1; step += 1; due = now + steps[step] }
                    else { state = 2; step = 0; iv = 1.0; due = now + DAY }
                }
                else -> { state = 2; step = 0; iv = 4.0; due = now + 4 * DAY }
            }
            2 -> when (rating) {
                0 -> { lapses++; ease = maxOf(1.3, ease - 0.2); iv = maxOf(1.0, iv * 0.5); state = 3; step = 0; due = now + 10 * MIN }
                1 -> { ease = maxOf(1.3, ease - 0.15); iv = maxOf(1.0, iv * 1.2); due = now + (iv * DAY).toLong() }
                2 -> {
                    iv = maxOf(1.0, iv * ease)
                    if (useBonus) iv *= bonus
                    due = now + (iv * DAY).toLong()
                }
                else -> {
                    ease += 0.15
                    iv = maxOf(1.0, iv * ease * 1.3)
                    if (useBonus) iv *= bonus
                    due = now + (iv * DAY).toLong()
                }
            }
            else -> when (rating) {
                0 -> { due = now + 10 * MIN }
                1, 2 -> { state = 2; iv = maxOf(1.0, iv); due = now + (iv * DAY).toLong() }
                else -> { state = 2; iv = maxOf(1.0, iv * 1.3); due = now + (iv * DAY).toLong() }
            }
        }
        return c.copy(
            due = due, interval = iv, ease = ease, reps = c.reps + 1, lapses = lapses, lastReview = now,
            state = state, step = step, streak = streak, misses = c.misses + (if (rating == 0) 1 else 0)
        )
    }
}
