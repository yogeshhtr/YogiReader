package com.yogi.reader

import android.content.Context
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** A book made ready for reading: PDF (shown by pdf.js) or HTML chapters (shown by the WebView). */
class Prepared(
    val pdf: Boolean,
    /** chapter files, relative to [dir] */
    val chapters: List<String>,
    /** table of contents: (title, chapter, anchor id or "") */
    val toc: List<Triple<String, Int, String>>,
    val dir: File
)

class Unsupported(msg: String) : Exception(msg)

/**
 * Turns every supported format into something the reader shows:
 * PDF stays PDF; EPUB is unpacked; Word, Excel, PowerPoint, Kindle (MOBI/AZW3) and text become HTML.
 */
object Formats {
    val EXT = listOf("pdf", "epub", "mobi", "azw", "azw3", "prc", "docx", "xlsx", "pptx", "txt", "md", "csv", "html", "htm", "xhtml",
        "json", "xml", "log", "fb2", "odt")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun kindName(e: String): String = when (e) {
        "pdf" -> "PDF"; "epub" -> "EPUB"; "mobi", "azw", "azw3", "prc" -> "Kindle"; "docx", "odt" -> "Word"; "xlsx" -> "Excel"
        "pptx" -> "PowerPoint"; "md" -> "Markdown"; "csv" -> "CSV"; "html", "htm", "xhtml" -> "Web page"; "fb2" -> "FB2"; else -> "Text"
    }

    fun prepare(ctx: Context, b: Book): Prepared {
        val dir = File(File(ctx.cacheDir, "books"), b.id)
        val ready = File(dir, ".ready")
        val e = ext(b.file.name)
        if (e == "pdf") return Prepared(true, listOf(b.file.name), emptyList(), b.file.parentFile!!)
        if (ready.exists()) return read(dir)
        dir.deleteRecursively()
        dir.mkdirs()
        val p = when (e) {
            "epub" -> epub(b.file, dir)
            "docx" -> docx(b.file, dir)
            "odt" -> odt(b.file, dir)
            "xlsx" -> xlsx(b.file, dir)
            "pptx" -> pptx(b.file, dir)
            "mobi", "azw", "azw3", "prc" -> Mobi.convert(b.file, dir)
            "html", "htm", "xhtml" -> { b.file.copyTo(File(dir, "index.html"), true); Prepared(false, listOf("index.html"), emptyList(), dir) }
            "csv" -> single(dir, b.title, csv(b.file.readText()))
            "md" -> single(dir, b.title, markdown(b.file.readText()))
            "fb2" -> fb2(b.file, dir, b.title)
            else -> single(dir, b.title, "<pre>" + esc(b.file.readText()) + "</pre>")
        }
        save(p)
        ready.writeText("ok")
        return p
    }

    // ------------------------------------------------------------------ cache of the result

    private fun save(p: Prepared) {
        val o = org.json.JSONObject()
        o.put("ch", org.json.JSONArray(p.chapters))
        val t = org.json.JSONArray()
        for ((a, b, c) in p.toc) t.put(org.json.JSONArray().put(a).put(b).put(c))
        o.put("toc", t)
        File(p.dir, ".book.json").writeText(o.toString())
    }

    private fun read(dir: File): Prepared {
        val o = org.json.JSONObject(File(dir, ".book.json").readText())
        val ch = o.getJSONArray("ch").let { a -> (0 until a.length()).map { a.getString(it) } }
        val toc = o.getJSONArray("toc").let { a ->
            (0 until a.length()).map { val x = a.getJSONArray(it); Triple(x.getString(0), x.getInt(1), x.getString(2)) }
        }
        return Prepared(false, ch, toc, dir)
    }

    // ------------------------------------------------------------------ helpers

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(title: String, body: String) =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${esc(title)}</title></head><body>$body</body></html>"

    private fun single(dir: File, title: String, body: String, toc: List<Triple<String, Int, String>> = emptyList()): Prepared {
        File(dir, "index.html").writeText(page(title, body))
        return Prepared(false, listOf("index.html"), toc, dir)
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) } catch (_: Exception) {}
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun unzip(file: File, dir: File) {
        ZipFile(file).use { z ->
            for (e in z.entries()) {
                val out = File(dir, e.name).canonicalFile
                if (!out.path.startsWith(dir.canonicalPath)) continue   // no escaping the folder
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }

    private fun Element.kids(tag: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element && n.tagName == tag) out.add(n); n = n.nextSibling }
        return out
    }

    private fun Element.all(tag: String): List<Element> {
        val l = getElementsByTagName(tag)
        return (0 until l.length).map { l.item(it) as Element }
    }

    // ------------------------------------------------------------------ EPUB

    private fun epub(file: File, dir: File): Prepared {
        unzip(file, dir)
        val container = xml(File(dir, "META-INF/container.xml").readBytes())
        val opfPath = container.documentElement.all("rootfile").firstOrNull()?.getAttribute("full-path") ?: throw Unsupported("Broken EPUB")
        val opfFile = File(dir, opfPath)
        val base = opfPath.substringBeforeLast('/', "")
        fun rel(href: String) = (if (base.isEmpty()) href else "$base/$href").let { java.net.URLDecoder.decode(it, "UTF-8") }
        val opf = xml(opfFile.readBytes()).documentElement
        val items = HashMap<String, Element>()
        for (it in opf.all("item")) items[it.getAttribute("id")] = it
        for (it in opf.all("opf:item")) items[it.getAttribute("id")] = it
        val spine = (opf.all("itemref") + opf.all("opf:itemref")).mapNotNull { r -> items[r.getAttribute("idref")]?.getAttribute("href") }
            .map { rel(it) }.filter { File(dir, it).exists() }
        if (spine.isEmpty()) throw Unsupported("This EPUB has no chapters")
        // table of contents: EPUB 3 nav, else EPUB 2 NCX
        val toc = ArrayList<Triple<String, Int, String>>()
        fun add(title: String, href: String) {
            val path = href.substringBefore('#')
            val i = spine.indexOf(path)
            if (i >= 0 && title.isNotBlank()) toc.add(Triple(title.trim(), i, href.substringAfter('#', "")))
        }
        val nav = items.values.firstOrNull { it.getAttribute("properties").split(' ').contains("nav") }
        if (nav != null) try {
            val navPath = rel(nav.getAttribute("href"))
            val nb = navPath.substringBeforeLast('/', "")
            val d = xml(File(dir, navPath).readBytes()).documentElement
            val tocNav = d.all("nav").firstOrNull { it.getAttribute("epub:type") == "toc" } ?: d.all("nav").firstOrNull()
            tocNav?.all("a")?.forEach { a -> add(a.textContent, (if (nb.isEmpty()) "" else "$nb/") + a.getAttribute("href")) }
        } catch (_: Exception) {
        }
        if (toc.isEmpty()) {
            val ncx = items.values.firstOrNull { it.getAttribute("media-type") == "application/x-dtbncx+xml" }
            if (ncx != null) try {
                val ncxPath = rel(ncx.getAttribute("href"))
                val nb = ncxPath.substringBeforeLast('/', "")
                val d = xml(File(dir, ncxPath).readBytes()).documentElement
                for (np in d.all("navPoint")) {
                    val label = np.all("text").firstOrNull()?.textContent ?: continue
                    val src = np.kids("content").firstOrNull()?.getAttribute("src") ?: continue
                    add(label, java.net.URLDecoder.decode((if (nb.isEmpty()) "" else "$nb/") + src, "UTF-8"))
                }
            } catch (_: Exception) {
            }
        }
        return Prepared(false, spine, toc, dir)
    }

    /** The EPUB's cover picture (for the library). */
    fun epubCover(file: File): ByteArray? = try {
        ZipFile(file).use { z ->
            val c = xml(z.getInputStream(z.getEntry("META-INF/container.xml")).readBytes())
            val opfPath = c.documentElement.all("rootfile").first().getAttribute("full-path")
            val base = opfPath.substringBeforeLast('/', "")
            val opf = xml(z.getInputStream(z.getEntry(opfPath)).readBytes()).documentElement
            val items = opf.all("item") + opf.all("opf:item")
            val coverId = opf.all("meta").firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
            val item = items.firstOrNull { it.getAttribute("properties").contains("cover-image") }
                ?: items.firstOrNull { it.getAttribute("id") == coverId }
                ?: items.firstOrNull { it.getAttribute("media-type").startsWith("image") && it.getAttribute("href").contains("cover", true) }
            val href = item?.getAttribute("href") ?: return null
            val path = java.net.URLDecoder.decode(if (base.isEmpty()) href else "$base/$href", "UTF-8")
            z.getEntry(path)?.let { z.getInputStream(it).readBytes() }
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ Word (DOCX)

    private fun rels(zip: ZipFile, path: String): Map<String, String> {
        val e = zip.getEntry(path) ?: return emptyMap()
        val d = xml(zip.getInputStream(e).readBytes()).documentElement
        return d.all("Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
    }

    private fun docx(file: File, dir: File): Prepared {
        val sb = StringBuilder()
        val toc = ArrayList<Triple<String, Int, String>>()
        ZipFile(file).use { z ->
            val doc = xml(z.getInputStream(z.getEntry("word/document.xml") ?: throw Unsupported("Broken Word file")).readBytes())
            val rel = rels(z, "word/_rels/document.xml.rels")
            File(dir, "media").mkdirs()
            fun image(id: String): String {
                val target = rel[id] ?: return ""
                val path = if (target.startsWith("/")) target.drop(1) else "word/$target"
                val e = z.getEntry(path) ?: return ""
                val name = "media/" + path.substringAfterLast('/')
                z.getInputStream(e).use { i -> File(dir, name).outputStream().use { i.copyTo(it) } }
                return "<img src=\"$name\">"
            }
            fun runs(p: Element): String {
                val out = StringBuilder()
                for (r in p.all("w:r")) {
                    val rp = r.kids("w:rPr").firstOrNull()
                    val b = rp?.kids("w:b")?.isNotEmpty() == true
                    val i = rp?.kids("w:i")?.isNotEmpty() == true
                    val u = rp?.kids("w:u")?.isNotEmpty() == true
                    val t = StringBuilder()
                    var n = r.firstChild
                    while (n != null) {
                        if (n is Element) when (n.tagName) {
                            "w:t" -> t.append(esc(n.textContent))
                            "w:tab" -> t.append("&emsp;")
                            "w:br" -> t.append("<br>")
                            "w:drawing", "w:pict" -> n.all("a:blip").forEach { bl -> t.append(image(bl.getAttribute("r:embed"))) }
                        }
                        n = n.nextSibling
                    }
                    var s = t.toString()
                    if (b) s = "<b>$s</b>"
                    if (i) s = "<i>$s</i>"
                    if (u) s = "<u>$s</u>"
                    out.append(s)
                }
                return out.toString()
            }
            fun para(p: Element): String {
                val style = p.all("w:pStyle").firstOrNull()?.getAttribute("w:val") ?: ""
                val text = runs(p)
                val lvl = Regex("(?i)heading\\s*(\\d)").find(style)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (style.equals("Title", true)) 1 else null
                val list = p.all("w:numPr").isNotEmpty()
                return when {
                    lvl != null -> {
                        val id = "h" + toc.size
                        toc.add(Triple(p.textContent.trim().ifEmpty { "Section" }, 0, id))
                        "<h${lvl.coerceIn(1, 6)} id=\"$id\">$text</h${lvl.coerceIn(1, 6)}>"
                    }
                    list -> "<li>$text</li>"
                    text.isBlank() -> "<p>&nbsp;</p>"
                    else -> "<p>$text</p>"
                }
            }
            val body = doc.documentElement.all("w:body").firstOrNull() ?: throw Unsupported("Empty Word file")
            var n = body.firstChild
            while (n != null) {
                if (n is Element) when (n.tagName) {
                    "w:p" -> sb.append(para(n))
                    "w:tbl" -> {
                        sb.append("<div class=\"yr-scroll\"><table>")
                        for (tr in n.kids("w:tr")) {
                            sb.append("<tr>")
                            for (tc in tr.kids("w:tc")) sb.append("<td>" + tc.kids("w:p").joinToString("") { runs(it) + "<br>" } + "</td>")
                            sb.append("</tr>")
                        }
                        sb.append("</table></div>")
                    }
                }
                n = n.nextSibling
            }
        }
        return single(dir, file.nameWithoutExtension, sb.toString(), toc)
    }

    private fun odt(file: File, dir: File): Prepared {
        val sb = StringBuilder()
        ZipFile(file).use { z ->
            val d = xml(z.getInputStream(z.getEntry("content.xml") ?: throw Unsupported("Broken document")).readBytes())
            for (p in d.documentElement.all("text:p") + d.documentElement.all("text:h")) {
                val t = esc(p.textContent)
                sb.append(if (p.tagName == "text:h") "<h2>$t</h2>" else "<p>$t</p>")
            }
        }
        return single(dir, file.nameWithoutExtension, sb.toString())
    }

    // ------------------------------------------------------------------ Excel (XLSX)

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (!c.isLetter()) break; n = n * 26 + (c.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    private fun xlsx(file: File, dir: File): Prepared {
        val sb = StringBuilder()
        val toc = ArrayList<Triple<String, Int, String>>()
        ZipFile(file).use { z ->
            val shared = ArrayList<String>()
            z.getEntry("xl/sharedStrings.xml")?.let { e ->
                val d = xml(z.getInputStream(e).readBytes())
                for (si in d.documentElement.all("si")) shared.add(si.all("t").joinToString("") { it.textContent })
            }
            val wb = xml(z.getInputStream(z.getEntry("xl/workbook.xml") ?: throw Unsupported("Broken Excel file")).readBytes())
            val rel = rels(z, "xl/_rels/workbook.xml.rels")
            for ((si, sheet) in wb.documentElement.all("sheet").withIndex()) {
                val name = sheet.getAttribute("name")
                val target = rel[sheet.getAttribute("r:id")] ?: continue
                val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
                val e = z.getEntry(path) ?: continue
                val d = xml(z.getInputStream(e).readBytes())
                val id = "s$si"
                toc.add(Triple(name, 0, id))
                sb.append("<h2 id=\"$id\">${esc(name)}</h2><div class=\"yr-scroll\"><table>")
                var rows = 0
                for (row in d.documentElement.all("row")) {
                    if (++rows > 3000) { sb.append("<tr><td>… more rows not shown</td></tr>"); break }
                    val cells = HashMap<Int, String>()
                    var max = -1
                    for (c in row.kids("c")) {
                        val ix = colIndex(c.getAttribute("r"))
                        val v = c.kids("v").firstOrNull()?.textContent ?: c.all("t").joinToString("") { it.textContent }
                        val text = when (c.getAttribute("t")) { "s" -> shared.getOrNull(v.toIntOrNull() ?: -1) ?: ""; else -> v }
                        if (ix >= 0 && ix < 200) { cells[ix] = text; if (ix > max) max = ix }
                    }
                    sb.append("<tr>")
                    for (i in 0..max) sb.append("<td>" + esc(cells[i] ?: "") + "</td>")
                    sb.append("</tr>")
                }
                sb.append("</table></div>")
            }
        }
        return single(dir, file.nameWithoutExtension, sb.toString(), toc)
    }

    // ------------------------------------------------------------------ PowerPoint (PPTX)

    private fun pptx(file: File, dir: File): Prepared {
        val sb = StringBuilder()
        val toc = ArrayList<Triple<String, Int, String>>()
        ZipFile(file).use { z ->
            val slides = z.entries().toList().map { it.name }.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                .sortedBy { Regex("\\d+").findAll(it).last().value.toInt() }
            File(dir, "media").mkdirs()
            for ((i, s) in slides.withIndex()) {
                val d = xml(z.getInputStream(z.getEntry(s)).readBytes())
                val rel = rels(z, s.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels")
                val paras = d.documentElement.all("a:p").map { p -> p.all("a:t").joinToString("") { it.textContent } }.filter { it.isNotBlank() }
                val id = "p$i"
                toc.add(Triple("${i + 1}. " + (paras.firstOrNull()?.take(60) ?: "Slide"), 0, id))
                sb.append("<div class=\"yr-slide\" id=\"$id\"><div style=\"opacity:.6;font-size:.8em\">Slide ${i + 1}</div>")
                for ((k, p) in paras.withIndex()) sb.append(if (k == 0) "<h3>${esc(p)}</h3>" else "<p>${esc(p)}</p>")
                for (bl in d.documentElement.all("a:blip")) {
                    val t = rel[bl.getAttribute("r:embed")] ?: continue
                    val path = java.io.File("ppt/slides/" + t).normalize().path.replace('\\', '/')
                    val e = z.getEntry(path) ?: continue
                    val name = "media/" + path.substringAfterLast('/')
                    z.getInputStream(e).use { inp -> File(dir, name).outputStream().use { inp.copyTo(it) } }
                    sb.append("<img src=\"$name\">")
                }
                sb.append("</div>")
            }
        }
        return single(dir, file.nameWithoutExtension, sb.toString(), toc)
    }

    // ------------------------------------------------------------------ text formats

    private fun csv(t: String): String {
        val sb = StringBuilder("<div class=\"yr-scroll\"><table>")
        for ((i, line) in t.lineSequence().withIndex()) {
            if (i > 5000) break
            if (line.isBlank()) continue
            val cells = ArrayList<String>()
            var cur = StringBuilder()
            var q = false
            for (ch in line) {
                when {
                    ch == '"' -> q = !q
                    ch == ',' && !q -> { cells.add(cur.toString()); cur = StringBuilder() }
                    else -> cur.append(ch)
                }
            }
            cells.add(cur.toString())
            sb.append("<tr>" + cells.joinToString("") { (if (i == 0) "<th>" else "<td>") + esc(it) + (if (i == 0) "</th>" else "</td>") } + "</tr>")
        }
        return sb.append("</table></div>").toString()
    }

    private fun markdown(t: String): String {
        val sb = StringBuilder()
        for (line in t.lines()) {
            val l = esc(line)
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)\\*"), "<i>$1</i>")
                .replace(Regex("`(.+?)`"), "<code>$1</code>")
            sb.append(when {
                line.startsWith("### ") -> "<h3>${l.drop(4)}</h3>"
                line.startsWith("## ") -> "<h2>${l.drop(3)}</h2>"
                line.startsWith("# ") -> "<h1>${l.drop(2)}</h1>"
                line.startsWith("- ") || line.startsWith("* ") -> "<li>${l.drop(2)}</li>"
                line.startsWith("&gt; ") -> "<blockquote>${l.drop(5)}</blockquote>"
                line.isBlank() -> "<br>"
                else -> "<p>$l</p>"
            })
        }
        return sb.toString()
    }

    private fun fb2(file: File, dir: File, title: String): Prepared {
        val d = xml(file.readBytes()).documentElement
        val sb = StringBuilder()
        val toc = ArrayList<Triple<String, Int, String>>()
        for ((i, sec) in d.all("section").withIndex()) {
            val t = sec.kids("title").firstOrNull()?.textContent?.trim()
            if (!t.isNullOrEmpty()) { toc.add(Triple(t, 0, "f$i")); sb.append("<h2 id=\"f$i\">${esc(t)}</h2>") }
            for (p in sec.kids("p")) sb.append("<p>${esc(p.textContent)}</p>")
        }
        return single(dir, title, sb.toString(), toc)
    }
}

/**
 * Kindle books (MOBI, AZW, AZW3, PRC): reads the PalmDB records, undoes PalmDOC compression and keeps the pictures.
 * DRM-protected Kindle books can't be opened (Amazon locks them).
 */
object Mobi {
    fun convert(file: File, dir: File): Prepared {
        RandomAccessFile(file, "r").use { f ->
            val n = readShort(f, 76)
            val offsets = LongArray(n) { readInt(f, 78 + it * 8.toLong()).toLong() and 0xFFFFFFFFL }
            fun record(i: Int): ByteArray {
                val start = offsets[i]
                val end = if (i + 1 < n) offsets[i + 1] else f.length()
                val b = ByteArray((end - start).toInt())
                f.seek(start)
                f.readFully(b)
                return b
            }
            val r0 = record(0)
            val compression = u16(r0, 0)
            val textRecords = u16(r0, 8)
            val encrypted = u16(r0, 12)
            if (encrypted != 0) throw Unsupported("This Kindle book is DRM-protected and can't be opened")
            if (compression == 17480) throw Unsupported("This Kindle book uses HUFF/CDIC compression, which isn't supported yet")
            val isMobi = r0.size > 20 && String(r0, 16, 4, Charsets.US_ASCII) == "MOBI"
            val headerLen = if (isMobi) u32(r0, 20) else 0
            val encoding = if (isMobi) u32(r0, 28) else 1252
            val firstImage = if (isMobi && headerLen >= 0x5C) u32(r0, 16 + 0x5C) else -1
            val extraFlags = if (isMobi && headerLen >= 0xE4) u16(r0, 16 + 0xE2) else 0
            val out = ByteArrayOutputStream()
            for (i in 1..textRecords) {
                if (i >= n) break
                var rec = record(i)
                rec = rec.copyOf(rec.size - trailing(rec, extraFlags))
                out.write(if (compression == 2) palmDoc(rec) else rec)
            }
            var html = String(out.toByteArray(), if (encoding == 65001) Charsets.UTF_8 else charset("windows-1252"))
            // pictures: recindex="00012" (MOBI) and kindle:embed:000C (AZW3)
            File(dir, "img").mkdirs()
            fun img(index: Int): String {
                if (firstImage < 0) return ""
                val ri = firstImage + index - 1
                if (ri !in 0 until n) return ""
                val name = "img/$index.jpg"
                val f2 = File(dir, name)
                if (!f2.exists()) try { f2.writeBytes(record(ri)) } catch (_: Exception) {}
                return name
            }
            html = Regex("recindex=[\"']?(\\d+)[\"']?", RegexOption.IGNORE_CASE).replace(html) { m -> "src=\"" + img(m.groupValues[1].toInt()) + "\"" }
            html = Regex("kindle:embed:([0-9A-Z]{4})(\\?mime=[^\"']*)?").replace(html) { m -> img(m.groupValues[1].toInt(32)) }
            html = html.replace(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE), "<hr style=\"opacity:.15\">")
            val body = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: html
            File(dir, "index.html").writeText("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" " +
                "content=\"width=device-width, initial-scale=1\"></head><body>$body</body></html>")
            // headings become the table of contents
            return Prepared(false, listOf("index.html"), emptyList(), dir)
        }
    }

    private fun readShort(f: RandomAccessFile, at: Long): Int { f.seek(at); return f.readUnsignedShort() }
    private fun readInt(f: RandomAccessFile, at: Long): Int { f.seek(at); return f.readInt() }
    private fun u16(b: ByteArray, at: Int): Int = if (at + 1 < b.size) ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF) else 0
    private fun u32(b: ByteArray, at: Int): Int = if (at + 3 < b.size)
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF) else -1

    /** size of the extra bytes at the end of a text record (MOBI "trailing entries", as calibre reads them) */
    private fun trailing(rec: ByteArray, flags: Int): Int {
        fun entry(size: Int): Int {
            var num = 0
            for (k in maxOf(0, size - 4) until size) {
                val x = rec[k].toInt() and 0xFF
                if (x and 0x80 != 0) num = 0
                num = (num shl 7) or (x and 0x7F)
            }
            return num
        }
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) num += entry(rec.size - num)
            f = f shr 1
            if (num >= rec.size) return rec.size
        }
        if (flags and 1 != 0 && rec.size - num - 1 >= 0) num += (rec[rec.size - num - 1].toInt() and 0x3) + 1
        return num.coerceIn(0, rec.size)
    }

    /** PalmDOC (LZ77) decompression */
    private fun palmDoc(src: ByteArray): ByteArray {
        var out = ByteArray(maxOf(8192, src.size * 3))
        var o = 0
        fun put(b: Int) {
            if (o >= out.size) out = out.copyOf(out.size * 2)
            out[o++] = b.toByte()
        }
        var i = 0
        while (i < src.size) {
            val c = src[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> put(c)
                c in 0x01..0x08 -> { var k = 0; while (k < c && i < src.size) { put(src[i++].toInt()); k++ } }
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= src.size) break
                    val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF
                    val len = (pair and 7) + 3
                    for (k in 0 until len) {
                        val from = o - dist
                        put(if (from >= 0 && dist > 0) out[from].toInt() else ' '.code)
                    }
                }
            }
        }
        return out.copyOf(o)
    }
}
