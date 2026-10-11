package com.mylibrary.app

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class EpubInfo(
    val title: String,
    val author: String,
    /** Rutas (dentro del zip) de los capítulos, en orden de lectura. */
    val spine: List<String>,
    val chapterTitles: Map<Int, String>
)

/** Lector de EPUB propio, sin librerías externas: un .epub es un zip con XHTML. */
object Epub {
    private fun parser(i: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(i, null)
    }

    /** Normaliza rutas y elimina "..": evita salirse del zip (path traversal). */
    fun norm(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> { if (out.isNotEmpty()) out.removeAt(out.lastIndex) }
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "xhtml", "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun parse(file: File): EpubInfo? = try {
        ZipFile(file).use { parseZip(it) }
    } catch (e: Exception) {
        null
    }

    private fun parseZip(zip: ZipFile): EpubInfo? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        var rootPath: String? = null
        zip.getInputStream(container).use { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rootfile") {
                    rootPath = p.getAttributeValue(null, "full-path")
                    break
                }
                ev = p.next()
            }
        }
        val opf = rootPath?.let { norm(Uri.decode(it)) } ?: return null
        val dir = opf.substringBeforeLast('/', "")
        val opfEntry = zip.getEntry(opf) ?: return null

        val manifest = HashMap<String, Pair<String, String>>() // id -> (href, media-type)
        val ids = ArrayList<String>()
        var title = ""
        var author = ""
        var ncxId: String? = null

        zip.getInputStream(opfEntry).use { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name.substringAfter(':')) {
                        "title" -> { if (title.isEmpty()) title = p.nextText().trim() }
                        "creator" -> { if (author.isEmpty()) author = p.nextText().trim() }
                        "item" -> {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                manifest[id] = href to (p.getAttributeValue(null, "media-type") ?: "")
                            }
                        }
                        "spine" -> ncxId = p.getAttributeValue(null, "toc")
                        "itemref" -> p.getAttributeValue(null, "idref")?.let { ids.add(it) }
                    }
                }
                ev = p.next()
            }
        }

        fun resolve(href: String): String =
            norm((if (dir.isEmpty()) "" else "$dir/") + Uri.decode(href.substringBefore('#')))

        val spine = ids.mapNotNull { id ->
            manifest[id]?.takeIf { it.second.contains("html") }?.let { resolve(it.first) }
        }.filter { zip.getEntry(it) != null }

        // Títulos de capítulo desde el índice NCX (si existe)
        val titles = HashMap<Int, String>()
        val ncxHref = ncxId?.let { manifest[it]?.first }
            ?: manifest.values.firstOrNull { it.second.contains("ncx") }?.first
        if (ncxHref != null) {
            val e = zip.getEntry(resolve(ncxHref))
            if (e != null) {
                try {
                    zip.getInputStream(e).use { s ->
                        val p = parser(s)
                        var ev = p.eventType
                        var label = ""
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG) {
                                when (p.name.substringAfter(':')) {
                                    "text" -> label = p.nextText().trim()
                                    "content" -> {
                                        val src = p.getAttributeValue(null, "src")
                                        if (src != null && label.isNotEmpty()) {
                                            val idx = spine.indexOf(resolve(src))
                                            if (idx >= 0) titles.putIfAbsent(idx, label)
                                        }
                                        label = ""
                                    }
                                }
                            }
                            ev = p.next()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return EpubInfo(title, author, spine, titles)
    }
}
