package com.yogi.reader

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A word's meaning from the free online dictionary (dictionaryapi.dev), with Wikipedia as a fallback. */
class Meaning(val word: String, val phonetic: String, val audio: String, val senses: List<Pair<String, String>>, val source: String)

object Dict {
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "YogiReader")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** null = no meaning found; throws when there's no internet */
    fun lookup(raw: String): Meaning? {
        val w = raw.trim().trim('.', ',', ';', ':', '!', '?', '"', '“', '”', '‘', '’', '(', ')', '[', ']').lowercase()
        if (w.isEmpty()) return null
        val en = URLEncoder.encode(w, "UTF-8").replace("+", "%20")
        get("https://api.dictionaryapi.dev/api/v2/entries/en/$en")?.let { s ->
            val a = JSONArray(s)
            var phon = ""
            var audio = ""
            val senses = ArrayList<Pair<String, String>>()
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                if (phon.isEmpty()) phon = e.optString("phonetic")
                val ps = e.optJSONArray("phonetics") ?: JSONArray()
                for (k in 0 until ps.length()) {
                    val p = ps.getJSONObject(k)
                    if (phon.isEmpty()) phon = p.optString("text")
                    if (audio.isEmpty() && p.optString("audio").isNotEmpty()) audio = p.optString("audio")
                }
                val ms = e.optJSONArray("meanings") ?: JSONArray()
                for (k in 0 until ms.length()) {
                    val m = ms.getJSONObject(k)
                    val pos = m.optString("partOfSpeech")
                    val ds = m.optJSONArray("definitions") ?: JSONArray()
                    for (j in 0 until minOf(3, ds.length())) {
                        val d = ds.getJSONObject(j)
                        val ex = d.optString("example")
                        senses.add(pos to (d.optString("definition") + if (ex.isNotEmpty()) "\n   “$ex”" else ""))
                    }
                }
            }
            if (senses.isNotEmpty()) return Meaning(w, phon, audio, senses.take(8), "Free Dictionary")
        }
        get("https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(raw.trim().replace(' ', '_'), "UTF-8"))?.let { s ->
            val o = JSONObject(s)
            val ex = o.optString("extract")
            if (ex.isNotEmpty() && o.optString("type") != "disambiguation") return Meaning(o.optString("title"), "", "", listOf("" to ex), "Wikipedia")
        }
        return null
    }

    // ------------------------------------------------------------------ vocabulary (like Kindle's Vocabulary Builder)

    class Word(val word: String, val meaning: String, val context: String, val book: String, val time: Long)

    fun words(): List<Word> = try {
        JSONArray(Store.str("vocab", "[]")).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Word(it.optString("w"), it.optString("m"), it.optString("c"), it.optString("b"), it.optLong("t"))
            }
        }
    } catch (_: Exception) { emptyList() }

    fun saveWord(w: Word) {
        val list = listOf(w) + words().filter { it.word != w.word }
        val a = JSONArray()
        for (x in list.take(1000)) a.put(JSONObject().put("w", x.word).put("m", x.meaning).put("c", x.context).put("b", x.book).put("t", x.time))
        Store.setStr("vocab", a.toString())
    }

    fun removeWord(word: String) {
        val a = JSONArray()
        for (x in words().filter { it.word != word }) a.put(JSONObject().put("w", x.word).put("m", x.meaning).put("c", x.context).put("b", x.book).put("t", x.time))
        Store.setStr("vocab", a.toString())
    }
}
