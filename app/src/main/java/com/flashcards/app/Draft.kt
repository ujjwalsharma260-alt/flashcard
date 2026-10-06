package com.flashcards.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Autosaved editor drafts, so nothing is lost if the editor is closed or interrupted. */
object Drafts {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("drafts", 0)

    fun save(ctx: Context, key: String, tags: String, faces: List<List<EItem>>) {
        try {
            val o = JSONObject()
            o.put("tags", tags)
            val fa = JSONArray()
            for (f in faces) {
                val ia = JSONArray()
                for (e in f) ia.put(JSONObject().put("t", e.type).put("d", e.data))
                fa.put(ia)
            }
            o.put("faces", fa)
            prefs(ctx).edit().putString(key, o.toString()).apply()
        } catch (e: Exception) { }
    }

    fun load(ctx: Context, key: String): Pair<String, List<List<EItem>>>? {
        val s = prefs(ctx).getString(key, null) ?: return null
        return try {
            val o = JSONObject(s)
            val fa = o.getJSONArray("faces")
            val faces = ArrayList<List<EItem>>()
            for (i in 0 until minOf(fa.length(), 5)) {
                val ia = fa.getJSONArray(i)
                val items = ArrayList<EItem>()
                for (j in 0 until ia.length()) {
                    val e = ia.getJSONObject(j)
                    items.add(EItem(e.getString("t"), e.getString("d")))
                }
                faces.add(items)
            }
            Pair(o.optString("tags", ""), faces)
        } catch (e: Exception) { null }
    }

    fun clear(ctx: Context, key: String) { prefs(ctx).edit().remove(key).apply() }
}
