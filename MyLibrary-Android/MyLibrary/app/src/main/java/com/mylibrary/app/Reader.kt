package com.mylibrary.app

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val HOST = "epub.local"

private fun chapterUrl(path: String) =
    "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

private fun buildCss(dark: Boolean, pct: Int): String {
    val bg = if (dark) "#1C1C1E" else "#FBF6EA"
    val fg = if (dark) "#E5E5E7" else "#2B2B2B"
    val link = if (dark) "#6CB4FF" else "#1F5FBF"
    return """
html{background:$bg !important;}
body{margin:0 !important;padding:28px 24px !important;box-sizing:border-box !important;height:100vh !important;
 column-width:calc(100vw - 48px) !important;column-gap:48px !important;column-fill:auto !important;
 color:$fg !important;background:$bg !important;font-size:$pct% !important;line-height:1.55 !important;
 font-family:Georgia,'Times New Roman',serif !important;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto;}
body *{color:inherit !important;background-color:transparent !important;max-width:100% !important;}
img,svg,image{max-width:100% !important;max-height:calc(100vh - 64px) !important;object-fit:contain;}
a{color:$link !important;}
p{margin:0 0 .8em 0;text-align:justify;}
h1,h2,h3,h4{text-align:left !important;line-height:1.25 !important;}
"""
}

private fun inject(html: String, css: String): String {
    val tag = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>$css</style>"
    val i = html.indexOf("</head>", ignoreCase = true)
    return if (i >= 0) html.substring(0, i) + tag + html.substring(i) else tag + html
}

private class EpubClient(val zip: ZipFile) : WebViewClient() {
    private fun empty(code: Int, msg: String) =
        WebResourceResponse("text/plain", "UTF-8", code, msg, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val u = request.url
        // Seguridad: solo se sirve contenido del propio libro. Todo lo demás se bloquea.
        if (u.scheme != "https" || u.host != HOST) return empty(403, "Forbidden")
        val path = Epub.norm(Uri.decode(u.encodedPath ?: "").trimStart('/'))
        val entry = (try { zip.getEntry(path) } catch (e: Exception) { null }) ?: return empty(404, "Not found")
        val mime = Epub.mime(path)
        return try {
            if (mime == "text/html") {
                if (entry.size > 20L * 1024 * 1024) return empty(413, "Too large")
                val html = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
                val css = (view as? ReaderWebView)?.css ?: ""
                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(inject(html, css).toByteArray(Charsets.UTF_8)))
            } else {
                WebResourceResponse(mime, null, zip.getInputStream(entry))
            }
        } catch (e: Exception) {
            empty(500, "Error")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onPageFinished(view: WebView, url: String?) {
        view.postDelayed({ (view as? ReaderWebView)?.applyPending() }, 180)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
}

/** WebView paginado: el texto fluye en columnas y se pasa de página como en un libro. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ReaderWebView(ctx: Context) : WebView(ctx) {
    @Volatile var css: String = ""
    var pendingFraction = 0f
    var onTapCenter: () -> Unit = {}
    var onEdge: (Boolean) -> Unit = {}
    var onPage: (Int, Int) -> Unit = { _, _ -> }
    private var lastFraction = 0f
    private var anim: ObjectAnimator? = null

    private val pageW: Int get() = width.coerceAtLeast(1)

    fun pageCount(): Int = ceil(computeHorizontalScrollRange().toFloat() / pageW).toInt().coerceAtLeast(1)
    fun pageIndex(): Int = (scrollX.toFloat() / pageW).roundToInt().coerceIn(0, pageCount() - 1)
    fun fraction(): Float = pageIndex().toFloat() / pageCount()

    private fun report() {
        val n = pageCount()
        val i = pageIndex()
        lastFraction = i.toFloat() / n
        onPage(i, n)
    }

    fun applyPending() {
        val n = pageCount()
        val p = if (pendingFraction >= 1f) n - 1 else (pendingFraction * n).roundToInt().coerceIn(0, n - 1)
        scrollTo(p * pageW, 0)
        report()
    }

    private fun goTo(page: Int) {
        anim?.cancel()
        anim = ObjectAnimator.ofInt(this, "scrollX", scrollX, page * pageW).apply { duration = 200; start() }
        postDelayed({ report() }, 240)
    }

    fun flip(forward: Boolean) {
        val n = pageCount()
        val t = pageIndex() + if (forward) 1 else -1
        when {
            t < 0 -> onEdge(false)
            t >= n -> onEdge(true)
            else -> goTo(t)
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when {
                e.x < width * 0.3f -> flip(false)
                e.x > width * 0.7f -> flip(true)
                else -> onTapCenter()
            }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy) && abs(vx) > 800f) { flip(vx < 0); return true }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow != 0 && w != ow) { // rotación: mantener el punto de lectura
            pendingFraction = lastFraction
            postDelayed({ applyPending() }, 350)
        }
    }
}

@Composable
fun ReaderScreen(book: Book, store: Store, dark: Boolean, onToggleTheme: () -> Unit, onClose: () -> Unit) {
    val a = LocalAqua.current
    val file = remember(book.id) { File(store.booksDir, book.file) }
    val info = remember(book.id) { Epub.parse(file) }
    if (info == null || info.spine.isEmpty()) {
        Box(Modifier.fillMaxSize().pinstripes(a).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo abrir este libro.", color = a.text)
                Spacer(Modifier.height(12.dp))
                GlossButton(onClose) { Text("Volver", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    } else {
        ReaderContent(book, info, file, store, dark, onToggleTheme, onClose)
    }
}

@Composable
private fun ReaderContent(
    book: Book, info: EpubInfo, file: File, store: Store,
    dark: Boolean, onToggleTheme: () -> Unit, onClose: () -> Unit
) {
    val a = LocalAqua.current
    val zip = remember(book.id) { ZipFile(file) }
    DisposableEffect(book.id) { onDispose { try { zip.close() } catch (_: Exception) { } } }

    val saved = remember(book.id) { store.getPosition(book.id) }
    var chapter by remember { mutableIntStateOf(saved.first.coerceIn(0, info.spine.lastIndex)) }
    val pending = remember { floatArrayOf(saved.second) }
    var fontPct by remember { mutableIntStateOf(store.fontPct) }
    var bars by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf("") }
    val chapterNow by rememberUpdatedState(chapter)

    val bg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFBF6EA)
    val fg = if (dark) Color(0xFFE5E5E7) else Color(0xFF2B2B2B)
    val css = remember(dark, fontPct) { buildCss(dark, fontPct) }

    LaunchedEffect(Unit) { delay(2500); bars = false }

    Box(Modifier.fillMaxSize().background(bg).systemBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                ReaderWebView(c).apply {
                    settings.apply {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        blockNetworkLoads = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                        setGeolocationEnabled(false)
                        javaScriptCanOpenWindowsAutomatically = false
                    }
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setBackgroundColor(bg.toArgb())
                    webViewClient = EpubClient(zip)
                    onTapCenter = { bars = !bars }
                    onEdge = { forward ->
                        if (forward && chapterNow < info.spine.lastIndex) { pending[0] = 0f; chapter = chapterNow + 1 }
                        else if (!forward && chapterNow > 0) { pending[0] = 1f; chapter = chapterNow - 1 }
                    }
                    onPage = { i, n ->
                        store.savePosition(book.id, chapterNow, i.toFloat() / n)
                        pageInfo = "Cap. ${chapterNow + 1}/${info.spine.size}  ·  ${i + 1}/$n"
                    }
                }
            },
            update = { wv ->
                wv.setBackgroundColor(bg.toArgb())
                val key = "$chapter|$dark|$fontPct"
                val old = wv.tag as? String
                if (old != key) {
                    val sameChapter = old != null && old.substringBefore('|') == chapter.toString()
                    wv.pendingFraction = if (sameChapter) wv.fraction() else pending[0]
                    wv.tag = key
                    wv.css = css
                    wv.loadUrl(chapterUrl(info.spine[chapter]))
                }
            }
        )

        Text(
            pageInfo, color = fg.copy(alpha = 0.5f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
        )

        AnimatedVisibility(bars, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(a.barTop, a.barBottom)))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GlossButton(onClose, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Text(
                    book.title, color = a.text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                GlossButton({
                    fontPct = (fontPct - 10).coerceAtLeast(80); store.fontPct = fontPct
                }, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Text("A−", color = Color.White, fontWeight = FontWeight.Bold)
                }
                GlossButton({
                    fontPct = (fontPct + 10).coerceAtMost(220); store.fontPct = fontPct
                }, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Text("A+", color = Color.White, fontWeight = FontWeight.Bold)
                }
                GlossButton({ showToc = true }, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Icon(Icons.Filled.Menu, null, tint = Color.White)
                }
                GlossButton(onToggleTheme, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, null, tint = Color.White)
                }
            }
        }
    }

    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            confirmButton = { TextButton({ showToc = false }) { Text("Cerrar") } },
            title = { Text("Capítulos") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(info.spine) { i, _ ->
                        Text(
                            info.chapterTitles[i] ?: "Capítulo ${i + 1}",
                            fontWeight = if (i == chapter) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { pending[0] = 0f; chapter = i; showToc = false }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        )
    }
}
