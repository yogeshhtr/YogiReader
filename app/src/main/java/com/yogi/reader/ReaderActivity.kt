package com.yogi.reader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class ReaderActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var book: Book
    private var prep: Prepared? = null
    private lateinit var web: ReaderWebView
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var info: TextView
    private lateinit var seek: SeekBar
    private lateinit var titleView: TextView
    private var bars = true
    private var chapter = 0
    private var pages = 0
    private var pendingRatio = -1f
    private var pendingAnchor = ""
    private var pendingMark = ""
    private var marks: MutableList<Mark> = ArrayList()
    private var pdfOutline: List<Pair<String, Int>> = emptyList()
    private var tts: TextToSpeech? = null
    private var speaking = false
    private var loader: WebViewAssetLoader? = null
    private val js by lazy { assets.open("mark.js").bufferedReader().readText() + "\n" + assets.open("reader.js").bufferedReader().readText() }

    private val isPdf: Boolean get() = prep?.pdf == true

    // ===================================================================== settings

    private val THEMES = listOf(
        Triple("Light", 0xFFFBFAF6.toInt(), 0xFF1B1B1B.toInt()), Triple("Sepia", 0xFFF3E9D4.toInt(), 0xFF3B2F20.toInt()),
        Triple("Dark", 0xFF1E1E1E.toInt(), 0xFFDCDCDC.toInt()), Triple("Black", 0xFF000000.toInt(), 0xFFC4C4C4.toInt()))
    private val FONTS = listOf("Original" to "", "Serif" to "Georgia, 'Noto Serif', serif", "Sans" to "Roboto, 'Noto Sans', sans-serif",
        "Mono" to "monospace")
    private fun theme() = Store.int("theme", 2).coerceIn(0, 3)
    private fun fontSize() = Store.int("font_size", 19)
    private fun font() = Store.int("font", 1).coerceIn(0, FONTS.size - 1)
    private fun line() = Store.int("line", 16) / 10f
    private fun margin() = Store.int("margin", 20)

    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val b = Library.get(intent.getStringExtra("id") ?: "")
        if (b == null) { finish(); return }
        book = b
        marks = Library.marks(book).toMutableList()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(THEMES[theme()].second)
        web = ReaderWebView(this)
        web.setBackgroundColor(THEMES[theme()].second)
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        buildBars(root)
        setContentView(root)
        paintChrome()
        titleView.text = book.title
        info.text = "Opening…"
        Thread {
            val p = try { Formats.prepare(this, book) } catch (e: Throwable) {
                h.post {
                    AlertDialog.Builder(this).setTitle("Can't open this file")
                        .setMessage(e.message ?: "This file looks damaged or isn't supported.")
                        .setPositiveButton("OK") { _, _ -> finish() }.setCancelable(false).show()
                }
                null
            }
            if (p != null) h.post { start(p) }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        if (::book.isInitialized) {
            book.opened = System.currentTimeMillis()
            Library.update(book)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (speaking) { stopSpeaking(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ===================================================================== bars

    private fun buildBars(root: FrameLayout) {
        top = hbox()
        top.setPadding(dp(4), dp(28), dp(6), dp(8))
        fun btn(t: String, f: () -> Unit) = label(t, 20f, Color.WHITE).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { f() }
        }
        top.addView(btn("‹") { finish() }.apply { textSize = 28f })
        titleView = label("", 15f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(titleView.lin(0, WRAP, 1f))
        top.addView(btn("☰") { contents() })
        top.addView(btn("🔍") { search() })
        top.addView(btn("Aa") { settings() })
        top.addView(btn("⋮") { more() })
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        bottom = vbox()
        bottom.setPadding(dp(16), dp(6), dp(16), dp(22))
        seek = SeekBar(this)
        bottom.addView(seek)
        info = label("", 12f, Color.WHITE).apply { gravity = Gravity.CENTER }
        bottom.addView(info)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                if (!user) return
                info.text = if (isPdf) "Page ${p + 1} of $pages" else if ((prep?.chapters?.size ?: 1) > 1) "Chapter ${p + 1} of ${prep?.chapters?.size}" else "${p}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val p = s?.progress ?: 0
                when {
                    isPdf -> web.evaluateJavascript("YR.go(${p + 1})", null)
                    (prep?.chapters?.size ?: 1) > 1 -> openChapter(p, 0f)
                    else -> web.evaluateJavascript("YR.toRatio(${p / 100f})", null)
                }
            }
        })
    }

    private fun paintChrome() {
        val (_, bg, fg) = THEMES[theme()]
        val barBg = (0xEE000000.toInt() and 0xFF000000.toInt()) or (bg and 0x00FFFFFF)
        top.setBackgroundColor((0xF0 shl 24) or (bg and 0xFFFFFF))
        bottom.setBackgroundColor((0xF0 shl 24) or (bg and 0xFFFFFF))
        for (v in listOf<View>(top, bottom)) tint(v, fg)
        info.setTextColor(fg)
        window.statusBarColor = barBg
        window.navigationBarColor = (0xFF shl 24) or (bg and 0xFFFFFF)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (theme() <= 1) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    private fun tint(v: View, c: Int) {
        if (v is TextView) v.setTextColor(c)
        if (v is LinearLayout) for (i in 0 until v.childCount) tint(v.getChildAt(i), c)
    }

    private fun toggleBars() {
        bars = !bars
        val vis = if (bars) View.VISIBLE else View.GONE
        top.visibility = vis
        bottom.visibility = vis
    }

    // ===================================================================== loading

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(p: Prepared) {
        prep = p
        val l = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/book/", WebViewAssetLoader.InternalStoragePathHandler(this, p.dir))
            .build()
        loader = l
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.textZoom = 100
        s.setSupportZoom(p.pdf)
        s.builtInZoomControls = p.pdf
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.onAction = { onSelection(it) }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                l.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net" && !isPdf) {
                    // a link to another chapter of the book
                    val path = Uri.decode(u.path ?: "").removePrefix("/book/")
                    val i = prep?.chapters?.indexOf(path) ?: -1
                    if (i >= 0) { pendingAnchor = u.fragment ?: ""; openChapter(i, 0f); return true }
                    return false
                }
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isPdf) {
                    val theme = listOf("light", "sepia", "dark", "black")[theme()]
                    web.evaluateJavascript("YR.theme('$theme');YR.open('https://appassets.androidplatform.net/book/${Uri.encode(book.file.name)}'," +
                        "${book.chapter.coerceAtLeast(1)}, ${marksJson(null)})", null)
                } else chapterReady()
            }
        }
        if (p.pdf) {
            chapter = book.chapter.coerceAtLeast(1)
            web.loadUrl("https://appassets.androidplatform.net/assets/pdf.html")
        } else {
            seek.max = if (p.chapters.size > 1) p.chapters.size - 1 else 100
            openChapter(book.chapter.coerceIn(0, p.chapters.size - 1), book.ratio)
        }
    }

    private fun openChapter(i: Int, ratio: Float) {
        val p = prep ?: return
        chapter = i.coerceIn(0, p.chapters.size - 1)
        pendingRatio = ratio
        val url = "https://appassets.androidplatform.net/book/" + p.chapters[chapter].split('/').joinToString("/") { Uri.encode(it) }
        web.loadUrl(url)
    }

    private fun applyTheme() {
        val (_, bg, fg) = THEMES[theme()]
        val link = if (theme() <= 1) "#B5651D" else "#FFB25C"
        val fam = FONTS[font()].second.replace("'", "\\'")
        web.evaluateJavascript("YR.theme('${hex(bg)}','${hex(fg)}','$link',${fontSize()},'$fam',${line()},${margin()})", null)
    }

    private fun chapterReady() {
        val p = prep ?: return
        web.evaluateJavascript(js, null)
        applyTheme()
        web.evaluateJavascript("YR.nav(${chapter > 0}, ${chapter < p.chapters.size - 1})", null)
        web.evaluateJavascript("YR.marks(${marksJson(chapter)})", null)
        val r = pendingRatio
        val a = pendingAnchor
        val m = pendingMark
        pendingRatio = -1f; pendingAnchor = ""; pendingMark = ""
        h.postDelayed({
            when {
                m.isNotEmpty() -> web.evaluateJavascript("YM.scrollToMark('$m')", null)
                a.isNotEmpty() -> web.evaluateJavascript("YR.toAnchor('${a.replace("'", "")}')", null)
                r > 0f -> web.evaluateJavascript("YR.toRatio($r)", null)
            }
        }, 250)
        if (p.chapters.size > 1) seek.progress = chapter
        showProgress(if (r > 0) r else 0f)
    }

    private fun marksJson(ch: Int?): String {
        val a = JSONArray()
        for (m in marks) if (ch == null || m.ch == ch) a.put(m.json())
        return a.toString()
    }

    private fun showProgress(r: Float) {
        val p = prep ?: return
        if (isPdf) return
        val n = p.chapters.size
        book.chapter = chapter
        book.ratio = r
        book.progress = ((chapter + r) / n).coerceIn(0f, 1f)
        info.text = (if (n > 1) "Chapter ${chapter + 1} of $n · " else "") + "${(book.progress * 100).toInt()}%"
        if (n <= 1) seek.progress = (r * 100).toInt()
    }

    // ===================================================================== from the page

    inner class Bridge {
        @JavascriptInterface fun progress(r: Double) = h.post { showProgress(r.toFloat()) }.let { }
        @JavascriptInterface fun tap() = h.post { toggleBars() }.let { }
        @JavascriptInterface fun prev() = h.post { openChapter(chapter - 1, 0.999f) }.let { }
        @JavascriptInterface fun next() = h.post { openChapter(chapter + 1, 0f) }.let { }
        @JavascriptInterface fun markTap(id: String) = h.post { markMenu(id) }.let { }
        @JavascriptInterface fun pdfInfo(n: Int) = h.post {
            pages = n
            seek.max = (n - 1).coerceAtLeast(0)
            page(chapter)
        }.let { }
        @JavascriptInterface fun page(n: Int) = h.post {
            chapter = n
            book.chapter = n
            book.progress = if (pages > 0) n / pages.toFloat() else 0f
            seek.progress = n - 1
            info.text = "Page $n of $pages · ${(book.progress * 100).toInt()}%"
        }.let { }
        @JavascriptInterface fun outline(json: String) = h.post {
            val a = JSONArray(json)
            pdfOutline = (0 until a.length()).map { a.getJSONObject(it) }.map { ("  ".repeat(it.optInt("depth")) + it.optString("title")) to it.optInt("page") }
        }.let { }
        @JavascriptInterface fun results(json: String) = h.post { pdfResults(json) }.let { }
        @JavascriptInterface fun text(t: String) = h.post { speak(t) }.let { }
        @JavascriptInterface fun failed(msg: String) = h.post { info.text = "Couldn't open this PDF" }.let { }
    }

    // ===================================================================== selection menu

    private fun withSelection(then: (JSONObject) -> Unit) {
        web.evaluateJavascript("YR.sel()") { raw ->
            val s = try { JSONArray("[$raw]").optString(0) } catch (_: Exception) { "" }
            if (s.isNullOrEmpty() || s == "null") return@evaluateJavascript
            try { then(JSONObject(s)) } catch (_: Exception) {}
        }
    }

    private fun onSelection(action: Int) {
        withSelection { o ->
            val text = o.optString("text")
            when (action) {
                ReaderWebView.HIGHLIGHT -> addMark(o, "")
                ReaderWebView.NOTE -> ask("Note", "Your note…", "") { n -> addMark(o, n) }
                ReaderWebView.DEFINE -> define(text, sentence(o))
                ReaderWebView.TRANSLATE -> translate(text)
                ReaderWebView.COPY -> {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                    toast("Copied")
                }
                ReaderWebView.SHARE -> shareText("“${text.trim()}”\n— ${book.title}")
            }
            web.evaluateJavascript("window.getSelection().removeAllRanges()", null)
        }
    }

    private fun sentence(o: JSONObject): String = (o.optString("pre").substringAfterLast(". ") + o.optString("text") +
        o.optString("suf").substringBefore(". ")).trim()

    private fun addMark(o: JSONObject, note: String) {
        val ch = if (isPdf) o.optInt("ch", chapter) else chapter
        val m = Mark("m" + System.currentTimeMillis().toString(36), ch, o.optInt("start"), o.optInt("len"), o.optString("text"),
            o.optString("pre"), o.optString("suf"), Store.int("last_color", 0), note, System.currentTimeMillis())
        marks.add(m)
        Library.saveMarks(book, marks)
        web.evaluateJavascript(if (isPdf) "YR.addMark(${m.json()})" else "YR.marks([${m.json()}])", null)
    }

    private fun markMenu(id: String) {
        val m = marks.firstOrNull { it.id == id } ?: return
        val box = vbox()
        box.setPadding(dp(20), dp(10), dp(20), 0)
        box.addView(label("“${m.text.trim()}”", 15f, C.TEXT))
        if (m.note.isNotBlank()) box.addView(label("📝 " + m.note, 14f, C.ACCENT).lin(t = dp(10)))
        val colors = hbox()
        colors.setPadding(0, dp(14), 0, 0)
        val d = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
        for ((i, c) in Mark.COLORS.withIndex()) {
            val v = View(this)
            v.background = rounded(c, dpf(16f), if (i == m.color) dp(3) else 0, Color.WHITE)
            colors.addView(v, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(12) })
            v.setOnClickListener {
                m.color = i
                Store.setInt("last_color", i)
                Library.saveMarks(book, marks)
                remark(m)
                toast("Colour changed")
            }
        }
        box.addView(colors)
        d.setPositiveButton(if (m.note.isBlank()) "Add note" else "Edit note") { _, _ ->
            ask("Note", "Your note…", m.note) { n -> m.note = n; Library.saveMarks(book, marks); remark(m) }
        }
        d.setNegativeButton("Delete") { _, _ ->
            marks.remove(m)
            Library.saveMarks(book, marks)
            web.evaluateJavascript("YM.unmark('${m.id}')", null)
        }
        d.setNeutralButton("Define") { _, _ -> define(m.text, m.text) }
        d.show()
    }

    private fun remark(m: Mark) {
        web.evaluateJavascript("YM.unmark('${m.id}');" + if (isPdf) "YR.addMark(${m.json()})" else "YR.marks([${m.json()}])", null)
    }

    // ===================================================================== dictionary

    private fun define(text: String, context: String) {
        val word = text.trim().split(Regex("\\s+")).take(4).joinToString(" ")
        val body = vbox()
        body.setPadding(dp(20), dp(10), dp(20), dp(6))
        val status = label("Looking up “$word”…", 14f, C.SUB)
        body.addView(status)
        val dlg = AlertDialog.Builder(this).setTitle(word).setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Translate") { _, _ -> translate(word) }
            .show()
        Thread {
            val m = try { Dict.lookup(word) } catch (_: Exception) {
                h.post { status.text = "No internet. The dictionary needs a connection." }
                return@Thread
            }
            h.post {
                if (!dlg.isShowing) return@post
                body.removeAllViews()
                if (m == null) {
                    body.addView(label("No meaning found for “$word”.", 14f, C.SUB))
                    body.addView(button("Search the web") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=define+" + Uri.encode(word)))) } catch (_: Exception) {}
                    }.lin(t = dp(10)))
                    return@post
                }
                if (m.phonetic.isNotEmpty() || m.audio.isNotEmpty()) {
                    val r = hbox()
                    r.addView(label(m.phonetic, 15f, C.SUB).lin(0, WRAP, 1f))
                    if (m.audio.isNotEmpty()) r.addView(label("🔊", 22f).apply {
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener { play(m.audio) }
                    })
                    body.addView(r)
                }
                var lastPos = ""
                for ((pos, def) in m.senses) {
                    if (pos.isNotEmpty() && pos != lastPos) {
                        body.addView(label(pos, 13f, C.ACCENT, true).lin(t = dp(10)))
                        lastPos = pos
                    }
                    body.addView(label("• $def", 14f, C.TEXT).lin(t = dp(4)))
                }
                body.addView(label("Source: ${m.source}", 11f, C.SUB).lin(t = dp(12)))
                body.addView(button("＋ Save to Vocabulary", C.CARD2, C.TEXT) {
                    Dict.saveWord(Dict.Word(m.word, m.senses.firstOrNull()?.second?.substringBefore("\n") ?: "", context, book.title, System.currentTimeMillis()))
                    toast("Saved to Vocabulary")
                }.lin(t = dp(10)))
            }
        }.start()
    }

    private fun play(url: String) {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(if (url.startsWith("//")) "https:$url" else url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release() }
            mp.prepareAsync()
        } catch (_: Exception) {
            toast("Can't play the sound")
        }
    }

    private fun translate(text: String) {
        val tl = Store.str("translate_to", "ne")
        val app = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.google.android.apps.translate")
        try {
            startActivity(app)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=$tl&op=translate&text=" + Uri.encode(text))))
            } catch (_: Exception) {}
        }
    }

    // ===================================================================== menus

    private fun contents() {
        val p = prep ?: return
        val names: List<String>
        val go: List<() -> Unit>
        if (isPdf) {
            if (pdfOutline.isEmpty()) { goToPage(); return }
            names = pdfOutline.map { it.first + "   · p." + it.second }
            go = pdfOutline.map { e -> { web.evaluateJavascript("YR.go(${e.second})", null); Unit } }
        } else if (p.toc.isNotEmpty()) {
            names = p.toc.map { it.first }
            go = p.toc.map { (_, ch, anchor) -> {
                if (ch == chapter && anchor.isNotEmpty()) web.evaluateJavascript("YR.toAnchor('${anchor.replace("'", "")}')", null)
                else { pendingAnchor = anchor; openChapter(ch, 0f) }
            } }
        } else {
            names = p.chapters.indices.map { "Chapter ${it + 1}" }
            go = p.chapters.indices.map { i -> { openChapter(i, 0f) } }
        }
        AlertDialog.Builder(this).setTitle("Contents").setItems(names.toTypedArray()) { _, i -> go[i]() }
            .setNegativeButton(if (isPdf) "Go to page" else "Close") { _, _ -> if (isPdf) goToPage() }.show()
    }

    private fun goToPage() {
        ask("Go to page", "1 – $pages", "", number = true) { t ->
            t.toIntOrNull()?.let { web.evaluateJavascript("YR.go($it)", null) }
        }
    }

    private fun search() {
        ask("Search", if (isPdf) "Search the whole PDF" else "Search this chapter", Store.str("last_search", "")) { q ->
            if (q.isBlank()) return@ask
            Store.setStr("last_search", q)
            if (isPdf) {
                info.text = "Searching…"
                web.evaluateJavascript("YR.search(${JSONObject.quote(q)})", null)
            } else {
                web.findAllAsync(q)
                web.setFindListener { active, count, done ->
                    if (done) info.text = if (count == 0) "No matches for “$q”" else "Match ${active + 1} of $count · tap 🔍 again for the next"
                }
                toast("Tap 🔍 then OK again to find the next one")
                web.findNext(true)
            }
        }
    }

    private fun pdfResults(json: String) {
        val a = JSONArray(json)
        page(chapter)
        if (a.length() == 0) { toast("No matches"); return }
        val items = (0 until a.length()).map { a.getJSONObject(it) }
        AlertDialog.Builder(this).setTitle("${items.size} matches")
            .setItems(items.map { "p.${it.optInt("page")}  …${it.optString("snippet").replace('\n', ' ')}…" }.toTypedArray()) { _, i ->
                web.evaluateJavascript("YR.go(${items[i].optInt("page")})", null)
            }.show()
    }

    private fun page(n: Int) { if (isPdf) info.text = "Page $n of $pages" }

    private fun settings() {
        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), dp(4))
        fun row(name: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            box.addView(label(name, 13f, C.SUB, true).lin(t = dp(8), b = dp(4)))
            val r = hbox()
            opts.forEachIndexed { i, o -> r.addView(chip(o, i == sel) { pick(i) }) }
            box.addView(android.widget.HorizontalScrollView(this).apply { addView(r) })
        }
        var dlg: AlertDialog? = null
        fun redo() { dlg?.dismiss(); settings() }
        row("Theme", THEMES.map { it.first }, theme()) { Store.setInt("theme", it); paintChrome(); web.setBackgroundColor(THEMES[it].second); reapply(); redo() }
        if (!isPdf) {
            row("Text size", listOf("A−", "${fontSize()}", "A+"), 1) {
                if (it != 1) { Store.setInt("font_size", (fontSize() + if (it == 0) -1 else 1).coerceIn(12, 34)); reapply(); redo() }
            }
            row("Font", FONTS.map { it.first }, font()) { Store.setInt("font", it); reapply(); redo() }
            row("Line spacing", listOf("Tight", "Normal", "Relaxed", "Loose"), listOf(13, 16, 19, 22).indexOf(Store.int("line", 16)).coerceAtLeast(0)) {
                Store.setInt("line", listOf(13, 16, 19, 22)[it]); reapply(); redo()
            }
            row("Margins", listOf("Narrow", "Normal", "Wide"), listOf(10, 20, 36).indexOf(margin()).coerceAtLeast(1)) {
                Store.setInt("margin", listOf(10, 20, 36)[it]); reapply(); redo()
            }
        }
        dlg = AlertDialog.Builder(this).setTitle("Reading settings").setView(box).setPositiveButton("Done", null).show()
    }

    private fun reapply() {
        if (isPdf) web.evaluateJavascript("YR.theme('${listOf("light", "sepia", "dark", "black")[theme()]}')", null)
        else applyTheme()
    }

    private fun more() {
        val opts = arrayOf("📝  Highlights & notes (${marks.size})", if (speaking) "⏹  Stop reading aloud" else "🔊  Read aloud",
            "📚  Vocabulary", "🌐  Translate into: " + Locale(Store.str("translate_to", "ne")).displayLanguage, "📤  Share this file", "📂  Open with another app")
        AlertDialog.Builder(this).setItems(opts) { _, i ->
            when (i) {
                0 -> notes()
                1 -> if (speaking) stopSpeaking() else readAloud()
                2 -> startActivity(Intent(this, MainActivity::class.java).putExtra("vocab", true))
                3 -> pickLanguage()
                4 -> shareFile()
                else -> openWith()
            }
        }.show()
    }

    private fun notes() {
        if (marks.isEmpty()) { toast("Select text and tap Highlight or Note"); return }
        val sorted = marks.sortedWith(compareBy({ it.ch }, { it.start }))
        val names = sorted.map { m -> (if (isPdf) "p.${m.ch}" else "Ch ${m.ch + 1}") + "  " + (if (m.note.isNotBlank()) "📝 " else "") +
            "“" + m.text.trim().take(90) + "”" + (if (m.note.isNotBlank()) "\n   " + m.note else "") }
        AlertDialog.Builder(this).setTitle("Highlights & notes").setItems(names.toTypedArray()) { _, i ->
            val m = sorted[i]
            if (isPdf) web.evaluateJavascript("YR.toMark('${m.id}', ${m.ch})", null)
            else if (m.ch == chapter) web.evaluateJavascript("YM.scrollToMark('${m.id}')", null)
            else { pendingMark = m.id; openChapter(m.ch, 0f) }
        }.setPositiveButton("Export") { _, _ ->
            shareText(Library.export(book) { ch -> if (isPdf) "Page $ch" else "Chapter ${ch + 1}" })
        }.setNegativeButton("Close", null).show()
    }

    private fun pickLanguage() {
        val langs = listOf("ne", "hi", "en", "es", "fr", "de", "zh", "ja", "ar", "ru", "pt", "ko")
        AlertDialog.Builder(this).setTitle("Translate into")
            .setItems(langs.map { Locale(it).displayLanguage }.toTypedArray()) { _, i -> Store.setStr("translate_to", langs[i]) }.show()
    }

    private fun shareText(t: String) {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")) } catch (_: Exception) {}
    }

    private fun fileUri(): Uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", book.file)

    private fun shareFile() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, fileUri())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
        } catch (_: Exception) {}
    }

    private fun openWith() {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(book.ext) ?: "*/*"
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
        } catch (_: Exception) { toast("No other app can open it") }
    }

    private fun ask(title: String, hint: String, value: String, number: Boolean = false, ok: (String) -> Unit) {
        val e = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this).setTitle(title).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) })
            .setPositiveButton("OK") { _, _ -> ok(e.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    // ===================================================================== read aloud

    private fun readAloud() {
        if (isPdf) web.evaluateJavascript("YR.pageText($chapter)", null)
        else web.evaluateJavascript("YR.textFromHere()") { raw ->
            val t = try { JSONArray("[$raw]").optString(0) } catch (_: Exception) { "" }
            speak(t)
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) { toast("Nothing to read here"); return }
        val go = {
            speaking = true
            info.text = "🔊 Reading aloud · tap ⋮ to stop"
            val chunks = text.split(Regex("(?<=[.!?।])\\s+")).fold(ArrayList<StringBuilder>()) { acc, s ->
                if (acc.isEmpty() || acc.last().length + s.length > 3500) acc.add(StringBuilder())
                acc.last().append(s).append(' ')
                acc
            }
            for ((i, c) in chunks.withIndex()) tts?.speak(c.toString(), if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "c$i")
        }
        if (tts == null) {
            tts = TextToSpeech(this) { st -> if (st == TextToSpeech.SUCCESS) h.post { go() } else h.post { toast("Text-to-speech isn't available") } }
        } else go()
    }

    private fun stopSpeaking() {
        tts?.stop()
        speaking = false
        info.text = ""
        showProgress(book.ratio)
    }
}
