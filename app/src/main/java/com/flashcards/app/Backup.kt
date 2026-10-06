package com.flashcards.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP backup: backup.json (decks, cards, faces, scheduling) + audio/ recordings. */
object Backup {
    private fun audioDir(ctx: Context) = File(ctx.filesDir, "audio").apply { mkdirs() }

    suspend fun export(ctx: Context, db: Db, uri: Uri): String = withContext(Dispatchers.IO) {
        val dao = db.dao()
        val decks = dao.allDecks()
        val cards = dao.allCards()
        val items = dao.allItems()
        val root = JSONObject()
        root.put("version", 1)
        val da = JSONArray()
        for (d in decks) da.put(JSONObject().put("id", d.id).put("name", d.name))
        root.put("decks", da)
        val ca = JSONArray()
        for (c in cards) {
            ca.put(JSONObject().put("id", c.id).put("deckId", c.deckId).put("tags", c.tags)
                .put("due", c.due).put("interval", c.interval).put("ease", c.ease)
                .put("reps", c.reps).put("lapses", c.lapses).put("lastReview", c.lastReview))
        }
        root.put("cards", ca)
        val ia = JSONArray()
        for (i in items) {
            ia.put(JSONObject().put("cardId", i.cardId).put("face", i.face).put("pos", i.pos)
                .put("type", i.type).put("data", i.data))
        }
        root.put("items", ia)
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot open file")
        ZipOutputStream(os.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(root.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val dir = audioDir(ctx)
            for (n in items.filter { it.type == "AUDIO" && it.data.isNotBlank() }.map { it.data }.distinct()) {
                val f = File(dir, n)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("audio/$n"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        "Backup saved: ${decks.size} decks, ${cards.size} cards"
    }

    /** Adds the backup's decks as new decks. Never deletes or overwrites existing data. */
    suspend fun restore(ctx: Context, db: Db, uri: Uri): String = withContext(Dispatchers.IO) {
        var json: String? = null
        val dir = audioDir(ctx)
        val ins = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
        ZipInputStream(ins.buffered()).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val name = e.name
                if (name == "backup.json") {
                    json = zip.readBytes().toString(Charsets.UTF_8)
                } else if (name.startsWith("audio/") && !e.isDirectory) {
                    val fn = File(name).name
                    File(dir, fn).outputStream().use { o -> zip.copyTo(o) }
                }
                e = zip.nextEntry
            }
        }
        val text = json ?: throw IllegalStateException("Not a flashcards backup")
        val root = JSONObject(text)
        val dao = db.dao()
        var nDecks = 0
        var nCards = 0
        db.withTransaction {
            val dmap = HashMap<Long, Long>()
            val da = root.getJSONArray("decks")
            for (k in 0 until da.length()) {
                val o = da.getJSONObject(k)
                dmap[o.getLong("id")] = dao.insertDeck(Deck(name = o.getString("name")))
                nDecks++
            }
            val cmap = HashMap<Long, Long>()
            val ca = root.getJSONArray("cards")
            for (k in 0 until ca.length()) {
                val o = ca.getJSONObject(k)
                val deck = dmap[o.getLong("deckId")] ?: continue
                cmap[o.getLong("id")] = dao.insertCard(
                    Flashcard(
                        deckId = deck, tags = o.optString("tags", ""), due = o.optLong("due", 0),
                        interval = o.optDouble("interval", 0.0), ease = o.optDouble("ease", 2.5),
                        reps = o.optInt("reps", 0), lapses = o.optInt("lapses", 0), lastReview = o.optLong("lastReview", 0)
                    )
                )
                nCards++
            }
            val list = ArrayList<Item>()
            val ia = root.getJSONArray("items")
            for (k in 0 until ia.length()) {
                val o = ia.getJSONObject(k)
                val cid = cmap[o.getLong("cardId")] ?: continue
                list.add(Item(cardId = cid, face = o.getInt("face"), pos = o.getInt("pos"), type = o.getString("type"), data = o.getString("data")))
            }
            dao.insertItems(list)
        }
        "Restored $nDecks decks, $nCards cards"
    }
}
