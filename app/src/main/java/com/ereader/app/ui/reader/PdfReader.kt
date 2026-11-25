package com.ereader.app.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * OmniTome's PDF viewer, built on Android's own PdfRenderer — the PDF engine
 * that's part of every Android phone, so the app bundles no extra PDF code.
 *
 *  - Swipe left/right to turn pages.
 *  - Pinch or double-tap to zoom; drag to move around while zoomed.
 *  - Tap to show the top bar (back, title) and bottom bar (page X of Y + slider).
 *  - The page you're on is saved (Continue Reading, progress, Calibre sync,
 *    reading streak, and Read when you reach the end) like any other book.
 *  - The reading theme colours the space around the pages.
 */

/** Opens a PDF and renders pages to bitmaps. PdfRenderer allows one page open at a time. */
private class PdfPages(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val count: Int = renderer.pageCount

    /** Page bitmaps by (page, width), sized to about 1/8 of the app's memory. */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            runCatching {
                renderer.openPage(index).use { page ->
                    val w = widthPx.coerceIn(200, 4096)
                    val h = (w.toLong() * page.height / page.width).toInt().coerceIn(1, 6000)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.WHITE)   // PDFs assume white paper
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.onFailure { Timber.e(it, "PDF page $index failed to render") }
                .getOrNull()?.also { cache.put(key, it) }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        cache.evictAll()
    }
}

@Composable
internal fun PdfReader(
    file: File,
    title: String,
    initialPage: Int,
    theme: ReaderTheme,
    barsVisible: Boolean,
    onToggleBars: () -> Unit,
    onPageChanged: (page: Int, pageCount: Int) -> Unit,
    onClose: () -> Unit,
    /** +1 / -1 requests (volume keys). */
    pageTurns: kotlinx.coroutines.flow.Flow<Int>? = null
) {
    // Open the file once. Password-protected or damaged PDFs fail here.
    val pages = remember(file) { runCatching { PdfPages(file) } }
    DisposableEffect(file) { onDispose { pages.getOrNull()?.close() } }

    val background = readerHudSurface(theme).copy(alpha = 1f)
    val onBackground = readerHudOnSurface(theme)

    val doc = pages.getOrNull()
    if (doc == null || doc.count == 0) {
        Box(Modifier.fillMaxSize().background(background).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (pages.exceptionOrNull() is SecurityException)
                        "This PDF is password-protected, which OmniTome can't open yet."
                    else "This PDF couldn't be opened. The file may be damaged.",
                    color = onBackground
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("Back") }
            }
        }
        return
    }

    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, doc.count - 1)) { doc.count }
    var zoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Volume keys (when turned on in Settings).
    LaunchedEffect(pagerState, pageTurns) {
        pageTurns?.collect { d ->
            val target = (pagerState.currentPage + d).coerceIn(0, doc.count - 1)
            pagerState.animateScrollToPage(target)
        }
    }

    // Save progress whenever the page changes (including the first page shown).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { onPageChanged(it, doc.count) }
    }

    Box(Modifier.fillMaxSize().background(background)) {
        HorizontalPager(
            state                    = pagerState,
            beyondViewportPageCount  = 1,                 // pre-render the next/previous page
            userScrollEnabled        = !zoomed,           // while zoomed, drags move the page instead
            modifier                 = Modifier.fillMaxSize()
        ) { index ->
            PdfPage(
                doc          = doc,
                index        = index,
                isCurrent    = index == pagerState.currentPage,
                onZoomChange = { if (index == pagerState.currentPage) zoomed = it },
                onTap        = onToggleBars
            )
        }

        // ── Top bar: back + title ─────────────────────────────────────────────
        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = readerHudSurface(theme), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library", tint = onBackground)
                    }
                    Text(title, color = onBackground, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ── Bottom bar: page X of Y + slider to jump ──────────────────────────
        AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = readerHudSurface(theme), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    var sliderPage by remember(pagerState.currentPage) { mutableFloatStateOf(pagerState.currentPage.toFloat()) }
                    Text(
                        "Page ${sliderPage.roundToInt() + 1} of ${doc.count}",
                        color = onBackground,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (doc.count > 1) {
                        Slider(
                            value                 = sliderPage,
                            onValueChange         = { sliderPage = it },
                            onValueChangeFinished = { scope.launch { pagerState.scrollToPage(sliderPage.roundToInt()) } },
                            valueRange            = 0f..(doc.count - 1).toFloat()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    doc: PdfPages,
    index: Int,
    isCurrent: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    var size   by remember { mutableStateOf(IntSize.Zero) }
    var scale  by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Leaving a page resets its zoom.
    LaunchedEffect(isCurrent) { if (!isCurrent) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale > 1f) { onZoomChange(scale > 1f) }

    // Render at screen width; re-render sharper (2x) once zoomed in.
    val sharp = scale > 1.4f
    LaunchedEffect(size.width, sharp) {
        if (size.width > 0) doc.render(index, if (sharp) size.width * 2 else size.width)?.let { bitmap = it }
    }

    fun clamp(o: Offset): Offset {
        val maxX = size.width * (scale - 1) / 2f
        val maxY = size.height * (scale - 1) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // Pinch to zoom, drag while zoomed. Single-finger drags at normal size
            // are left alone so the pager can turn the page.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale  = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale <= 1f) Offset.Zero else clamp(offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else {
                            scale = 2.5f
                            // Zoom towards where you tapped.
                            offset = clamp(Offset((size.width / 2f - tap.x) * 1.5f, (size.height / 2f - tap.y) * 1.5f))
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            Image(
                bitmap             = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
            )
        }
    }
}
