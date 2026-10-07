package com.flashcards.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Bulk operations on many cards at once (chunked so large selections stay within SQLite limits). */
object Bulk {
    private const val CH = 500

    suspend fun setFav(db: Db, ids: List<Long>, v: Int) {
        db.withTransaction { for (c in ids.chunked(CH)) db.dao().setFav(c, v) }
    }

    suspend fun setBookmark(db: Db, ids: List<Long>, v: Int) {
        db.withTransaction { for (c in ids.chunked(CH)) db.dao().setBookmark(c, v) }
    }

    suspend fun setSuspended(db: Db, ids: List<Long>, v: Int) {
        db.withTransaction { for (c in ids.chunked(CH)) db.dao().setSusp(c, v) }
    }

    suspend fun move(db: Db, ids: List<Long>, deckId: Long) {
        db.withTransaction { for (c in ids.chunked(CH)) db.dao().setDeck(c, deckId) }
    }

    suspend fun delete(db: Db, ids: List<Long>) {
        db.withTransaction {
            for (c in ids.chunked(CH)) {
                db.dao().deleteItemsOf(c)
                db.dao().deleteCards(c)
            }
        }
    }

    suspend fun addTag(db: Db, ids: List<Long>, tag: String) {
        val t = tag.trim().trimStart('#')
        if (t.isEmpty()) return
        db.withTransaction {
            for (c in ids.chunked(CH)) {
                for (r in db.dao().tagsOf(c)) {
                    val nt = normTags(r.tags + " #" + t)
                    if (nt != r.tags) db.dao().setTags(r.id, nt)
                }
            }
        }
    }

    suspend fun removeTag(db: Db, ids: List<Long>, tag: String) {
        val t = tag.trim().trimStart('#').lowercase()
        if (t.isEmpty()) return
        db.withTransaction {
            for (c in ids.chunked(CH)) {
                for (r in db.dao().tagsOf(c)) {
                    val nt = r.tags.split(Regex("\\s+")).filter { it.isNotBlank() && it.trimStart('#').lowercase() != t }.joinToString(" ")
                    if (nt != r.tags) db.dao().setTags(r.id, nt)
                }
            }
        }
    }

    /** Copies a media file so the duplicate never shares (or loses) the original's file. */
    private fun copyMedia(ctx: Context, type: String, name: String): String {
        try {
            val src = if (type == "AUDIO") File(File(ctx.filesDir, "audio"), name) else ImageStore.file(ctx, name)
            if (!src.exists()) return name
            val ext = name.substringAfterLast('.', "")
            val nn = UUID.randomUUID().toString() + (if (ext.isNotEmpty()) ".$ext" else "")
            src.copyTo(File(src.parentFile, nn))
            return nn
        } catch (e: Exception) {
            return name
        }
    }

    private suspend fun copyCard(ctx: Context, dao: AppDao, cardId: Long, deckId: Long): Long? {
        val c = dao.card(cardId) ?: return null
        val nid = dao.insertCard(Flashcard(deckId = deckId, tags = c.tags, fav = c.fav, bookmark = c.bookmark))
        val items = dao.items(cardId).map {
            Item(
                cardId = nid, face = it.face, pos = it.pos, type = it.type,
                data = if (it.type == "AUDIO" || it.type == "IMAGE") copyMedia(ctx, it.type, it.data) else it.data
            )
        }
        dao.insertItems(items)
        return nid
    }

    /** Duplicates cards as brand-new cards (fresh scheduling) in the same deck. */
    suspend fun duplicateCards(ctx: Context, db: Db, ids: List<Long>): Int = withContext(Dispatchers.IO) {
        var n = 0
        db.withTransaction {
            val dao = db.dao()
            for (id in ids) {
                val c = dao.card(id) ?: continue
                if (copyCard(ctx, dao, id, c.deckId) != null) n++
            }
        }
        n
    }

    suspend fun duplicateDeck(ctx: Context, db: Db, deckId: Long, newName: String): Int = withContext(Dispatchers.IO) {
        var n = 0
        db.withTransaction {
            val dao = db.dao()
            val newId = dao.insertDeck(Deck(name = newName))
            for (c in dao.cardsOfDeck(deckId)) {
                if (copyCard(ctx, dao, c.id, newId) != null) n++
            }
        }
        n
    }

    suspend fun mergeDecks(db: Db, from: Long, into: Long) {
        db.withTransaction {
            db.dao().moveAll(from, into)
            db.dao().deleteDeck(from)
        }
    }
}
