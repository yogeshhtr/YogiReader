package com.yogi.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A book or document in the library. Files are copied into the app, so they never go missing. */
class Book(
    val id: String,
    val title: String,
    val file: File,
    val added: Long,
    var opened: Long,
    /** 0–1 */
    var progress: Float,
    /** chapter (or PDF page) and scroll position */
    var chapter: Int,
    var ratio: Float
) {
    val ext: String get() = Formats.ext(file.name)
    fun json(): JSONObject = JSONObject().put("id", id).put("t", title).put("f", file.absolutePath).put("a", added).put("o", opened)
        .put("p", progress.toDouble()).put("c", chapter).put("r", ratio.toDouble())

    companion object {
        fun from(o: JSONObject) = Book(o.optString("id"), o.optString("t"), File(o.optString("f")), o.optLong("a"), o.optLong("o"),
            o.optDouble("p", 0.0).toFloat(), o.optInt("c"), o.optDouble("r", 0.0).toFloat())
    }
}

/** One highlight or note. [ch] = chapter (or PDF page); [start]/[len] = where in it. */
class Mark(
    val id: String, val ch: Int, val start: Int, val len: Int, val text: String, val pre: String, val suf: String,
    var color: Int, var note: String, val time: Long
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("ch", ch).put("start", start).put("len", len).put("text", text)
        .put("pre", pre).put("suf", suf).put("color", color).put("note", note).put("time", time)

    companion object {
        fun from(o: JSONObject) = Mark(o.optString("id"), o.optInt("ch"), o.optInt("start"), o.optInt("len"), o.optString("text"),
            o.optString("pre"), o.optString("suf"), o.optInt("color"), o.optString("note"), o.optLong("time"))
        val COLORS = listOf(0xFFFFD600.toInt(), 0xFF5AC878.toInt(), 0xFF50A0FF.toInt(), 0xFFFF6996.toInt())
    }
}

object Library {
    fun all(): List<Book> = Store.str("books", "[]").let { s ->
        try { JSONArray(s).let { a -> (0 until a.length()).map { Book.from(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
    }.filter { it.file.exists() }

    fun save(list: List<Book>) {
        val a = JSONArray()
        for (b in list) a.put(b.json())
        Store.setStr("books", a.toString())
    }

    fun get(id: String): Book? = all().firstOrNull { it.id == id }

    fun update(b: Book) = save(all().map { if (it.id == b.id) b else it })

    fun remove(ctx: Context, b: Book) {
        save(all().filter { it.id != b.id })
        b.file.delete()
        cover(ctx, b).delete()
        File(File(ctx.cacheDir, "books"), b.id).deleteRecursively()
        Store.setStr("marks_" + b.id, "[]")
    }

    private fun nameOf(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Copies a file into the library (or finds it if it's already there). */
    fun import(ctx: Context, uri: Uri): Book {
        val name = nameOf(ctx, uri)
        val ext = Formats.ext(name)
        if (ext !in Formats.EXT) throw Unsupported(
            if (ext in listOf("doc", "xls", "ppt")) "Old Office files (.$ext) aren't supported. Save it as .${ext}x first." else "“.$ext” files aren't supported")
        val dir = File(ctx.filesDir, "library").also { it.mkdirs() }
        val tmp = File(dir, "incoming.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(tmp).use { i.copyTo(it) } } ?: throw Unsupported("Can't read this file")
        val id = Integer.toHexString((name + tmp.length()).hashCode()) + tmp.length().toString(36)
        all().firstOrNull { it.id == id }?.let { tmp.delete(); return it }
        val f = File(dir, "$id.$ext")
        tmp.renameTo(f)
        val b = Book(id, name.substringBeforeLast('.'), f, System.currentTimeMillis(), 0L, 0f, 0, 0f)
        save(listOf(b) + all().filter { it.id != id })
        return b
    }

    // ------------------------------------------------------------------ covers

    fun cover(ctx: Context, b: Book): File = File(File(ctx.filesDir, "covers").also { it.mkdirs() }, b.id + ".jpg")

    /** Makes the cover once: first PDF page, or the EPUB's cover picture. */
    fun makeCover(ctx: Context, b: Book): Bitmap? {
        val f = cover(ctx, b)
        if (f.exists()) return BitmapFactory.decodeFile(f.path)
        val bmp: Bitmap? = try {
            when (b.ext) {
                "pdf" -> ParcelFileDescriptor.open(b.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(0).use { p ->
                            val w = 360
                            val h = (w * p.height / p.width.toFloat()).toInt().coerceIn(200, 720)
                            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            out.eraseColor(Color.WHITE)
                            p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out
                        }
                    }
                }
                "epub" -> Formats.epubCover(b.file)?.let { bytes ->
                    val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                }
                else -> null
            }
        } catch (_: Throwable) { null }
        if (bmp != null) try { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (_: Exception) {}
        return bmp
    }

    // ------------------------------------------------------------------ highlights

    fun marks(b: Book): List<Mark> = Store.str("marks_" + b.id, "[]").let { s ->
        try { JSONArray(s).let { a -> (0 until a.length()).map { Mark.from(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
    }

    fun saveMarks(b: Book, list: List<Mark>) {
        val a = JSONArray()
        for (m in list) a.put(m.json())
        Store.setStr("marks_" + b.id, a.toString())
    }

    /** All highlights and notes as plain text (like Kindle's "My Clippings"). */
    fun export(b: Book, chapterName: (Int) -> String): String {
        val sb = StringBuilder("📖 ${b.title}\n\n")
        for (m in marks(b).sortedWith(compareBy({ it.ch }, { it.start }))) {
            sb.append("— ").append(chapterName(m.ch)).append("\n“").append(m.text.trim()).append("”\n")
            if (m.note.isNotBlank()) sb.append("📝 ").append(m.note).append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }
}
