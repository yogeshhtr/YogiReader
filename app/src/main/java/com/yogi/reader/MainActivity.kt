package com.yogi.reader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** The library: continue reading, all books and documents, and the vocabulary. */
class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private var vocab = false

    private val MIMES = arrayOf("application/pdf", "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "text/*", "application/octet-stream", "application/x-fictionbook+xml")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        col = vbox()
        col.setPadding(0, dp(28), 0, dp(30))
        sv.addView(col)
        setContentView(sv)
        vocab = intent.getBooleanExtra("vocab", false)
        handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        vocab = i.getBooleanExtra("vocab", false)
        handle(i)
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    /** "Open with Yogi Reader" from Files, WhatsApp, Gmail… */
    private fun handle(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) importAndOpen(listOf(uri))
    }

    private fun importAndOpen(uris: List<Uri>) {
        toast(if (uris.size == 1) "Opening…" else "Adding ${uris.size} files…")
        Thread {
            var last: Book? = null
            var err: String? = null
            for (u in uris) try { last = Library.import(this, u) } catch (e: Exception) { err = e.message ?: "Can't open this file" }
            h.post {
                err?.let { AlertDialog.Builder(this).setMessage(it).setPositiveButton("OK", null).show() }
                val b = last
                if (b != null && uris.size == 1) open(b) else draw()
            }
        }.start()
    }

    private fun open(b: Book) = startActivity(Intent(this, ReaderActivity::class.java).putExtra("id", b.id))

    private fun add() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, MIMES).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 7)
        } catch (_: Exception) {
            toast("No file picker found")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7 || res != RESULT_OK || data == null) return
        val list = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) list.add(c.getItemAt(i).uri) }
        if (list.isEmpty()) data.data?.let { list.add(it) }
        if (list.isNotEmpty()) importAndOpen(list)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (vocab) { vocab = false; draw() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ===================================================================== screen

    private fun draw() {
        col.removeAllViews()
        if (vocab) { vocabulary(); return }
        val head = hbox()
        head.setPadding(dp(20), dp(4), dp(14), dp(10))
        head.addView(label("Yogi Reader", 30f, C.TEXT, true).lin(0, WRAP, 1f))
        head.addView(label("📚", 22f).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { vocab = true; draw() } })
        head.addView(button("＋ Add") { add() })
        col.addView(head)
        val books = Library.all().sortedByDescending { maxOf(it.opened, it.added) }
        if (books.isEmpty()) {
            val c = card()
            c.addView(label("Your library is empty", 18f, C.TEXT, true))
            c.addView(label("Tap ＋ Add, or open any file with “Yogi Reader” from Yogi Files, WhatsApp, Gmail or Downloads.\n\n" +
                "Reads PDF, EPUB, Kindle (MOBI, AZW3), Word (DOCX), Excel (XLSX), PowerPoint (PPTX), FB2, Markdown, CSV and text.\n\n" +
                "Select any words to highlight them, add a note, look them up in the dictionary or translate them.", 14f, C.SUB).lin(t = dp(8)))
            col.addView(c)
            return
        }
        // continue reading
        books.firstOrNull { it.opened > 0 }?.let { b ->
            val c = card(0xFF2A2118.toInt())
            val r = hbox()
            val cv = coverView(b, dp(70), dp(100))
            r.addView(cv)
            val mid = vbox()
            mid.addView(label("Continue reading", 12f, C.ACCENT, true))
            mid.addView(label(b.title, 17f, C.TEXT, true).apply { maxLines = 2 })
            mid.addView(progressBar(b.progress).lin(t = dp(8)))
            mid.addView(label("${(b.progress * 100).toInt()}% · ${Formats.kindName(b.ext)}", 12f, C.SUB).lin(t = dp(4)))
            r.addView(mid.lin(0, WRAP, 1f, l = dp(14)))
            c.addView(r)
            c.setOnClickListener { open(b) }
            col.addView(c)
        }
        col.addView(label("LIBRARY · ${books.size}", 12f, C.SUB, true).apply { setPadding(dp(22), dp(14), dp(22), dp(6)) })
        val w = (resources.displayMetrics.widthPixels - dp(56)) / 3
        var row: LinearLayout? = null
        for ((i, b) in books.withIndex()) {
            if (i % 3 == 0) {
                row = hbox()
                row.gravity = Gravity.TOP
                row.setPadding(dp(16), 0, dp(16), dp(10))
                col.addView(row)
            }
            val cell = vbox()
            cell.addView(coverView(b, w, (w * 1.45f).toInt()))
            cell.addView(label(b.title, 12f, C.TEXT).apply { maxLines = 2 }.lin(t = dp(6)))
            cell.addView(label(if (b.progress > 0) "${(b.progress * 100).toInt()}%" else "New", 11f, if (b.progress > 0) C.SUB else C.ACCENT))
            cell.setOnClickListener { open(b) }
            cell.setOnLongClickListener { bookMenu(b); true }
            row?.addView(cell, LinearLayout.LayoutParams(w, WRAP).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    private fun progressBar(p: Float): View {
        val bg = FrameLayout(this)
        bg.background = rounded(C.CARD2, dpf(3f))
        val fill = View(this)
        fill.background = rounded(C.ACCENT, dpf(3f))
        bg.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        bg.post { fill.layoutParams = FrameLayout.LayoutParams((bg.width * p.coerceIn(0f, 1f)).toInt(), MATCH) }
        bg.layoutParams = LinearLayout.LayoutParams(MATCH, dp(5))
        return bg
    }

    private val TYPE_COLORS = mapOf("PDF" to 0xFFD9453B.toInt(), "EPUB" to 0xFF3E8E5E.toInt(), "Kindle" to 0xFF2F6FB0.toInt(),
        "Word" to 0xFF2B579A.toInt(), "Excel" to 0xFF217346.toInt(), "PowerPoint" to 0xFFC94F2E.toInt())

    /** The book's cover, or a coloured card with its title when it has none. */
    private fun coverView(b: Book, w: Int, hgt: Int): View {
        val f = FrameLayout(this)
        val kind = Formats.kindName(b.ext)
        f.background = rounded(TYPE_COLORS[kind] ?: 0xFF5E5A55.toInt(), dpf(10f))
        f.clipToOutline = true
        val t = label(b.title, 12f, Color.WHITE, true).apply { maxLines = 5; setPadding(dp(8), dp(10), dp(8), 0) }
        f.addView(t, FrameLayout.LayoutParams(MATCH, WRAP))
        f.addView(label(kind, 10f, 0xCCFFFFFF.toInt(), true).apply { setPadding(dp(8), 0, dp(8), dp(6)) },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        f.layoutParams = LinearLayout.LayoutParams(w, hgt)
        Thread {
            val bmp = Library.makeCover(this, b)
            if (bmp != null) h.post { iv.setImageBitmap(bmp) }
        }.start()
        return f
    }

    private fun bookMenu(b: Book) {
        AlertDialog.Builder(this).setTitle(b.title)
            .setItems(arrayOf("📖  Open", "📝  Export highlights & notes", "🗑  Remove from library")) { _, i ->
                when (i) {
                    0 -> open(b)
                    1 -> {
                        val t = Library.export(b) { ch -> if (b.ext == "pdf") "Page $ch" else "Chapter ${ch + 1}" }
                        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")) } catch (_: Exception) {}
                    }
                    else -> AlertDialog.Builder(this).setTitle("Remove “${b.title}”?")
                        .setMessage("The copy in Yogi Reader and its highlights are deleted. The original file is not touched.")
                        .setPositiveButton("Remove") { _, _ -> Library.remove(this, b); draw() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun vocabulary() {
        val head = hbox()
        head.setPadding(dp(8), dp(4), dp(20), dp(10))
        head.addView(label("‹", 30f, C.TEXT, true).apply { setPadding(dp(12), 0, dp(12), 0); setOnClickListener { vocab = false; draw() } })
        head.addView(label("Vocabulary", 28f, C.TEXT, true).lin(0, WRAP, 1f))
        col.addView(head)
        val words = Dict.words()
        if (words.isEmpty()) {
            col.addView(label("Words you look up and save appear here, with the sentence you found them in.", 14f, C.SUB).apply {
                setPadding(dp(22), dp(6), dp(22), 0)
            })
            return
        }
        for (w in words) {
            val c = card()
            c.addView(label(w.word, 18f, C.ACCENT, true))
            if (w.meaning.isNotEmpty()) c.addView(label(w.meaning, 14f, C.TEXT).lin(t = dp(4)))
            if (w.context.isNotEmpty()) c.addView(label("“${w.context}”", 13f, C.SUB).lin(t = dp(6)))
            c.addView(label("— ${w.book}", 11f, C.SUB).lin(t = dp(4)))
            c.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Remove “${w.word}”?").setPositiveButton("Remove") { _, _ -> Dict.removeWord(w.word); draw() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            col.addView(c)
        }
    }
}
