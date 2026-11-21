package com.ereader.app.ui.reader

import android.content.res.Configuration
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ereader.app.R
import com.ereader.app.domain.model.Annotation
import com.ereader.app.ui.theme.EReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import timber.log.Timber
import java.util.Locale

/**
 * ReaderActivity — hosts the Readium EPUB navigator.
 *
 * Annotation flow (EPUB):
 *  1. The user long-presses to select text → WebView shows the system text
 *     selection action mode.
 *  2. Our [android.view.ActionMode.Callback] injects "Highlight" and "Add Note"
 *     items into that action mode menu (via
 *     [EpubNavigatorFragment.Configuration.selectionActionModeCallback]).
 *  3. When the user taps one of those items, [onActionItemClicked] fires. We
 *     read the selected text from the last-known locator's [Locator.text.highlight]
 *     field — Readium populates this field whenever text is selected in the WebView.
 *  4. [ReaderViewModel.onTextSelected] stores the selection as [PendingAnnotation].
 *  5. The Compose HUD observes [pendingAnnotation] and shows the colour-picker /
 *     note dialog.
 *  6. On save, the ViewModel writes to Room via [AnnotationRepository].
 *  7. The [annotations] StateFlow re-emits → [applyAnnotationDecorations] pushes
 *     the new highlight to the Readium fragment immediately.
 *
 * CSS + JS injection:
 *  Readium 3.0 doesn't expose an HtmlInjector API in its Configuration, so we
 *  inject our override stylesheet and script by calling [WebView.evaluateJavascript]
 *  directly on the WebView(s) inside the fragment's view hierarchy. We trigger
 *  injection every time the currentLocator changes (i.e., when the user navigates
 *  to a new page or chapter). Both the CSS and JS are idempotent — the CSS checks
 *  for an existing element ID before inserting, and the JS has a window-level guard.
 */
@AndroidEntryPoint
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID    = "extra_book_id"
        const val EXTRA_BOOK_TITLE = "extra_book_title"

        private const val MENU_HIGHLIGHT = 1001
        private const val MENU_ADD_NOTE  = 1002
    }

    private val viewModel: ReaderViewModel by viewModels()
    private var navigatorFragment: EpubNavigatorFragment? = null

    // Most-recently-seen locator — updated from the fragment's currentLocator Flow.
    private var lastKnownLocator: Locator? = null

    // Page curl event bus
    private val _pageTurnFlow = MutableSharedFlow<PageTurnDirection>(extraBufferCapacity = 1)
    private val pageTurnFlow  = _pageTurnFlow.asSharedFlow()

    // ── Text-to-speech ────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    // Pre-split page text, refilled each time startReading() is called.
    private val ttsChunks = mutableListOf<String>()
    // Index of the NEXT chunk to speak (incremented before each speak() call
    // so UtteranceProgressListener.onDone can advance correctly).
    private var ttsNextChunk = 0
    // Track both book-level and chapter-level progression.
    // totalProgression only changes when moving between chapters; progression
    // (chapter-relative) changes on every paginated page turn within a chapter.
    private var lastTotalProgression:   Double? = null
    private var lastChapterProgression: Double? = null

    // Timestamp of the last touch-up event (ACTION_UP), in System.currentTimeMillis().
    // The Compose PageCurlOverlay is only shown when no finger has been on screen for
    // > 1 000 ms — meaning the page turn was driven by TTS (goForward), not a manual swipe.
    // Manual swipes are handled by the JS canvas curl inside the WebView, which tracks the
    // finger in real-time and needs no Compose layer on top.
    private var lastTouchUpTimeMs = 0L

    // ── Tap detection for HUD toggle ──────────────────────────────────────────
    // We track ACTION_DOWN / ACTION_UP directly in dispatchTouchEvent rather
    // than relying on GestureDetector.onSingleTapUp, because the Readium
    // WebView can interfere with the GestureDetector's internal state machine
    // and prevent onSingleTapUp from firing reliably.
    private var tapDownX          = 0f
    private var tapDownY          = 0f
    private var tapDownEventMs    = 0L
    // Time of the last gesture that was NOT a clean short tap (i.e. a
    // long-press that started text selection, or a swipe/drag). The 800 ms
    // suppression window after this prevents the next tap — which the user
    // uses to dismiss the text-selection handles — from accidentally toggling
    // the HUD open.
    private var lastGestureTimeMs = 0L

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Track tap start for HUD toggle detection (done here rather than via a
        // GestureDetector because the Readium WebView can silently swallow events
        // that would prevent GestureDetector.onSingleTapUp from firing).
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapDownX       = event.x
                tapDownY       = event.y
                tapDownEventMs = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                // Record when the finger last left the screen. The locator watcher
                // uses this to distinguish TTS-driven page turns (no recent touch)
                // from manual swipes handled by the JS page-curl canvas.
                lastTouchUpTimeMs = System.currentTimeMillis()

                val dx = kotlin.math.abs(event.x - tapDownX)
                val dy = kotlin.math.abs(event.y - tapDownY)
                val dt = event.eventTime - tapDownEventMs
                val slopPx      = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2f
                val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()

                // If this touch ended a long-press or a drag (text selection,
                // swipe) record the time so the NEXT tap (e.g. dismissing the
                // selection handles) is suppressed from toggling the HUD.
                if (dx >= slopPx || dy >= slopPx || dt >= longPressMs) {
                    lastGestureTimeMs = System.currentTimeMillis()
                }

                val msSinceLastGesture = System.currentTimeMillis() - lastGestureTimeMs
                if (dx < slopPx && dy < slopPx && dt < longPressMs
                        && msSinceLastGesture > 1500L) {
                    viewModel.toggleHud()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // ── CSS / JS injection assets ─────────────────────────────────────────────
    //
    // Loaded once from assets in launchNavigator(). Base64-encoded so they can
    // be safely embedded in a JavaScript string literal without escaping issues.
    // Empty strings mean the corresponding injection is skipped.

    private var cssBase64:   String = ""   // override.css encoded as base64
    private var jsInject:    String = ""   // inject.js as a raw script string
    private var pageCurlJs:  String = ""   // pagecurl.js — interactive finger-following curl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Apply reader theme to window immediately — colours the status bar,
        // nav bar, and window background before the book finishes loading.
        // On API ≤ 34 this sets the opaque status-bar colour directly.
        // On API 35+ the system forces edge-to-edge (transparent bars) so the
        // window background, FrameLayout, and ComposeView backgrounds must match.
        applyActivityTheme()

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run {
            Timber.e("ReaderActivity started without EXTRA_BOOK_ID")
            finish()
            return
        }

        viewModel.loadBook(bookId)
        initTts()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.readerState.collect { state ->
                    when (state) {
                        is ReaderState.Loading -> showLoading()
                        is ReaderState.Ready   -> launchNavigator(state)
                        is ReaderState.Error   -> showError(state.message)
                    }
                }
            }
        }

        // Re-apply highlights whenever the annotation list changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.annotations.collect { annotations ->
                    val frag = navigatorFragment ?: return@collect
                    injectJsHighlights(frag, annotations)
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState) /* suppress nav re-entry */ }

    // ── Navigator setup ───────────────────────────────────────────────────────

    private fun launchNavigator(state: ReaderState.Ready) {
        if (navigatorFragment != null) return

        // Load CSS and JS assets once. Base64-encode the CSS so it can be embedded
        // safely in a JS string literal regardless of what characters it contains.
        cssBase64 = try {
            val css = assets.open("reader/override.css").bufferedReader().readText()
            Base64.encodeToString(css.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.w(e, "Could not load override.css — publisher styles will not be corrected")
            ""
        }
        jsInject = try {
            assets.open("reader/inject.js").bufferedReader().readText()
        } catch (e: Exception) {
            Timber.w(e, "Could not load inject.js — table scroll and image zoom unavailable")
            ""
        }
        pageCurlJs = try {
            assets.open("reader/pagecurl.js").bufferedReader().readText()
        } catch (e: Exception) {
            Timber.w(e, "Could not load pagecurl.js — interactive page curl unavailable")
            ""
        }

        val initialLocator = state.lastLocator?.let { json ->
            runCatching { Locator.fromJSON(org.json.JSONObject(json)) }.getOrNull()
        }

        val isLargeScreen = resources.configuration.screenLayout and
                Configuration.SCREENLAYOUT_SIZE_MASK >= Configuration.SCREENLAYOUT_SIZE_LARGE
        val isLandscape   = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val preferences   = buildSpreadPrefs(isLargeScreen && isLandscape)

        // ── Text-selection action mode callback ───────────────────────────────
        val selectionCallback = object : android.view.ActionMode.Callback {

            override fun onCreateActionMode(
                mode: android.view.ActionMode,
                menu: Menu
            ): Boolean {
                menu.add(Menu.NONE, MENU_HIGHLIGHT, 100, "Highlight")
                menu.add(Menu.NONE, MENU_ADD_NOTE,  101, "Add Note")
                return true
            }

            override fun onPrepareActionMode(
                mode: android.view.ActionMode,
                menu: Menu
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode,
                item: MenuItem
            ): Boolean {
                return when (item.itemId) {
                    MENU_HIGHLIGHT, MENU_ADD_NOTE -> {
                        lifecycleScope.launch {
                            val selectionLocator = (navigatorFragment as? SelectableNavigator)
                                ?.currentSelection()
                                ?.locator
                            val locator = selectionLocator ?: lastKnownLocator ?: run {
                                Timber.w("Annotation skipped — no locator available")
                                return@launch
                            }
                            val selectedText = locator.text?.highlight ?: ""
                            viewModel.onTextSelected(selectedText, locator)
                        }
                        mode.finish()
                        // Clear the WebView's text selection immediately so the
                        // user doesn't have to tap again to dismiss the handles.
                        // This also stops the dismissal-tap from accidentally
                        // triggering the HUD.
                        primaryWebView()?.evaluateJavascript(
                            "if(window.getSelection)window.getSelection().removeAllRanges();", null
                        )
                        // Record this as a gesture event so the 1500 ms suppression
                        // window prevents any inadvertent HUD toggle right after.
                        lastGestureTimeMs = System.currentTimeMillis()
                        true
                    }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }

        val fragmentFactory = EpubNavigatorFactory(
            publication = state.publication
        ).createFragmentFactory(
            initialLocator     = initialLocator,
            initialPreferences = preferences,
            configuration      = EpubNavigatorFragment.Configuration(
                selectionActionModeCallback = selectionCallback
            )
        )

        supportFragmentManager.fragmentFactory = fragmentFactory

        val fragment = supportFragmentManager.fragmentFactory
            .instantiate(classLoader, EpubNavigatorFragment::class.java.name)
                as EpubNavigatorFragment

        navigatorFragment = fragment

        supportFragmentManager.beginTransaction()
            .replace(R.id.reader_container, fragment, "epub_navigator")
            .commitAllowingStateLoss()

        // Track locator changes: update ViewModel progress, cache for annotations,
        // re-inject CSS/JS, re-apply highlight decorations, and fire page-curl events.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fragment.currentLocator.collect { locator ->
                    lastKnownLocator = locator
                    viewModel.updateCurrentLocator(locator)

                    // Re-inject after every page/chapter change. Both CSS and JS
                    // are idempotent so calling this repeatedly is harmless.
                    injectCssAndJs(fragment)

                    // Re-apply highlights on every page/chapter load.
                    // JS mark injection is used exclusively — Readium's applyDecorations
                    // was removed because it applies its own low-opacity layer that makes
                    // pastel colours (yellow, green, purple) nearly invisible, and its
                    // DOM modifications can race with and remove the injected <mark> elements.
                    val currentAnnotations = viewModel.annotations.value
                    injectJsHighlights(fragment, currentAnnotations)

                    // ── Page curl detection ───────────────────────────────────
                    // Check totalProgression first (crosses chapter boundaries).
                    // Fall back to chapter-relative progression so intra-chapter
                    // page turns in paginated mode also trigger the curl.
                    val newTotal = locator.locations.totalProgression
                    val newChap  = locator.locations.progression
                    val prevTotal = lastTotalProgression
                    val prevChap  = lastChapterProgression

                    val direction: PageTurnDirection? = when {
                        prevTotal != null && newTotal != null && newTotal != prevTotal ->
                            if (newTotal > prevTotal) PageTurnDirection.FORWARD else PageTurnDirection.BACKWARD
                        prevChap != null && newChap != null && newChap != prevChap ->
                            if (newChap > prevChap) PageTurnDirection.FORWARD else PageTurnDirection.BACKWARD
                        else -> null
                    }
                    // Only emit from here when the locator change was NOT caused by a
                    // manual swipe. Manual swipes set lastTouchUpTimeMs on ACTION_UP;
                    // the locator change Readium triggers arrives well within 1 000 ms.
                    // TTS goForward() has no touch event so its locator change is always
                    // > 1 000 ms after the last touch-up — safe to show the Compose curl.
                    val msSinceTouchUp = System.currentTimeMillis() - lastTouchUpTimeMs
                    if (direction != null && msSinceTouchUp > 2500L) {
                        _pageTurnFlow.tryEmit(direction)
                    }

                    lastTotalProgression   = newTotal
                    lastChapterProgression = newChap
                }
            }
        }

        // Apply the Activity window background immediately — before the first
        // locator fires — so the status bar, nav bar, and container all show
        // the reader theme from the very first frame.
        applyActivityTheme()

        // Collect reader-preference changes and push them into every WebView.
        // Also re-applies the Activity theme so switching themes mid-session
        // updates the system bars and window background immediately.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    viewModel.fontSizeSp,
                    viewModel.readerThemePref,
                    viewModel.fontFamilyPref
                ) { _, _, _ -> Unit }
                .collect {
                    applyActivityTheme()
                    applyReaderPreferences()
                }
            }
        }

        attachHud()
    }

    // ── Text-to-speech engine ─────────────────────────────────────────────────

    /**
     * Initialises the [TextToSpeech] engine once and registers a progress
     * listener so we can advance through chunks automatically.
     *
     * [ttsReady] is set to true only after the engine reports SUCCESS — any
     * startReading() call before that shows a Toast instead of crashing.
     */
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (!ttsReady) {
                Timber.w("TextToSpeech init failed with status $status")
                return@TextToSpeech
            }
            tts?.language = Locale.getDefault()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { /* no-op */ }

                override fun onDone(utteranceId: String) {
                    // Called on a TTS synthesis thread — post to main thread.
                    runOnUiThread {
                        if (ttsNextChunk < ttsChunks.size) {
                            // More chunks on this page — keep going.
                            speakNextChunk()
                        } else if (viewModel.ttsState.value == TtsState.PLAYING) {
                            // All chunks spoken. Try to advance to the next page and
                            // continue reading so the user can listen hands-free.
                            lifecycleScope.launch {
                                val frag = navigatorFragment
                                if (frag == null) { viewModel.onTtsStopped(); return@launch }

                                // Snapshot progression before advancing so we can detect
                                // whether the navigator actually moved (end-of-book guard).
                                val progressBefore = lastKnownLocator?.locations?.totalProgression

                                val advanced = try {
                                    frag.goForward()
                                    true
                                } catch (e: Exception) {
                                    Timber.w("TTS goForward() failed: ${e.message}")
                                    false
                                }

                                if (!advanced) { viewModel.onTtsStopped(); return@launch }

                                // Wait for the new page to load and locator to update.
                                kotlinx.coroutines.delay(750)

                                val progressAfter = lastKnownLocator?.locations?.totalProgression
                                if (progressAfter == null || progressAfter == progressBefore) {
                                    // Navigator didn't move — we're at the end of the book.
                                    viewModel.onTtsStopped()
                                } else if (viewModel.ttsState.value == TtsState.PLAYING) {
                                    // Still playing — extract text from the new page and continue.
                                    startReading()
                                }
                            }
                        }
                    }
                }

                @Deprecated("Required override for API < 21")
                override fun onError(utteranceId: String) {
                    runOnUiThread { viewModel.onTtsStopped() }
                }
            })
        }
    }

    /**
     * Extracts only the TEXT VISIBLE ON THE CURRENT PAGE and begins speaking.
     *
     * Readium's paginated mode lays out the chapter as horizontal CSS columns.
     * `document.body.innerText` would return the ENTIRE chapter — so if a chapter
     * spans 20 pages the first tap would read all 20 pages from the start, and
     * tapping again on page 5 would still re-read page 1.
     *
     * Fix: query every block-level element, check whether its centre X is within
     * the current viewport (0 → window.innerWidth), and collect only those texts.
     * Falls back to full body text if no block elements are found.
     */
    fun startReading() {
        if (!ttsReady) {
            Toast.makeText(this, "Text-to-speech is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val webView = primaryWebView() ?: return
        val visibleTextJs = """
            (function() {
                var vw = window.innerWidth;
                var result = [];
                var seen = new Set ? new Set() : null;   // graceful fallback for very old WebViews
                var elems = document.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,td,th,blockquote,pre,figcaption');
                for (var i = 0; i < elems.length; i++) {
                    var el = elems[i];
                    var rect = el.getBoundingClientRect();
                    if (rect.height <= 0) continue;
                    var cx = rect.left + rect.width / 2;
                    if (cx >= 0 && cx < vw) {
                        var t = (el.innerText || el.textContent || '').trim();
                        if (t && (!seen || !seen.has(t))) {
                            if (seen) seen.add(t);
                            result.push(t);
                        }
                    }
                }
                if (result.length === 0 && document.body) {
                    return document.body.innerText || document.body.textContent || '';
                }
                return result.join(' ');
            })()
        """.trimIndent()
        webView.evaluateJavascript(visibleTextJs) { raw ->
            val text = try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { "" }
            if (text.isBlank()) return@evaluateJavascript

            ttsChunks.clear()
            ttsChunks.addAll(chunkText(text))
            ttsNextChunk = 0
            viewModel.onTtsStarted()
            speakNextChunk()
        }
    }

    /** Stops the engine mid-chunk and saves the position for [resumeReading]. */
    fun pauseReading() {
        tts?.stop()
        viewModel.onTtsPaused()
        // ttsNextChunk already points at the NEXT unseen chunk so resume works.
    }

    /** Resumes from the chunk that was about to play when [pauseReading] was called. */
    fun resumeReading() {
        if (ttsNextChunk < ttsChunks.size) {
            viewModel.onTtsResumed()
            speakNextChunk()
        } else {
            viewModel.onTtsStopped()
        }
    }

    /** Stops reading and clears all buffered chunks. */
    fun stopReading() {
        tts?.stop()
        ttsChunks.clear()
        ttsNextChunk = 0
        viewModel.onTtsStopped()
    }

    /** Speaks [ttsChunks][ttsNextChunk] and advances the index. */
    private fun speakNextChunk() {
        val chunk = ttsChunks.getOrNull(ttsNextChunk) ?: run {
            viewModel.onTtsStopped()
            return
        }
        val utteranceId = "omni_tts_$ttsNextChunk"
        ttsNextChunk++
        tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Splits [text] into chunks that each fit within Android's TTS input limit
     * (~4 000 characters). Splits on sentence boundaries where possible so the
     * engine doesn't cut off mid-sentence between chunks.
     */
    private fun chunkText(text: String): List<String> {
        val maxLen = minOf(TextToSpeech.getMaxSpeechInputLength(), 4_000)
        // Split on sentence-ending punctuation followed by whitespace.
        val sentences = text.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()

        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue

            if (buf.length + s.length + 1 > maxLen) {
                if (buf.isNotEmpty()) { chunks.add(buf.toString().trim()); buf.clear() }
                // Sentence longer than the limit — hard-split by character count.
                if (s.length > maxLen) {
                    var start = 0
                    while (start < s.length) {
                        chunks.add(s.substring(start, minOf(start + maxLen, s.length)))
                        start += maxLen
                    }
                    continue
                }
            }
            buf.append(s).append(' ')
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
        return chunks.filter { it.isNotBlank() }
    }

    /** Returns the first [WebView] found inside the navigator fragment's view tree. */
    private fun primaryWebView(): WebView? {
        val root = navigatorFragment?.view ?: return null
        var found: WebView? = null
        forEachWebView(root) { wv -> if (found == null) found = wv }
        return found
    }

    // ── CSS + JS WebView injection ────────────────────────────────────────────

    /**
     * Injects the override stylesheet and JS into every WebView found inside
     * [fragment]'s view hierarchy.
     *
     * Readium may keep multiple WebViews alive simultaneously (current page plus
     * adjacent pre-loaded pages in paginated mode), so we walk the full tree and
     * apply to each one.
     *
     * CSS injection: creates a <style> element with a unique ID on first run,
     * skips if the element already exists (prevents stacking duplicate sheets).
     *
     * JS injection: the script itself has a window.__omniTomeInjected guard so
     * re-running it after the CSS doesn't double-initialize anything.
     */
    private fun injectCssAndJs(fragment: EpubNavigatorFragment) {
        val root = fragment.view ?: return

        forEachWebView(root) { webView ->
            // ── CSS via inline <style> ────────────────────────────────────────
            // We base64-encode the CSS so no character in the stylesheet can
            // break the surrounding JavaScript string literal. atob() decodes
            // it back to a plain string inside the browser context.
            if (cssBase64.isNotBlank()) {
                webView.evaluateJavascript("""
                    (function() {
                        if (document.getElementById('omni-css')) return;
                        var s = document.createElement('style');
                        s.id = 'omni-css';
                        s.textContent = atob('$cssBase64');
                        if (document.head) document.head.appendChild(s);
                    })();
                """.trimIndent(), null)
            }

            // ── JS — run the full inject.js script ───────────────────────────
            // The script's IIFE and window.__omniTomeInjected guard make it
            // safe to evaluate multiple times on the same document.
            if (jsInject.isNotBlank()) {
                webView.evaluateJavascript(jsInject, null)
            }

            // ── Interactive page-curl canvas ──────────────────────────────────
            // pagecurl.js appends a pointer-events:none <canvas> that follows the
            // reader's finger in real time. window.__omniPageCurl guards against
            // double-injection on the same document.
            if (pageCurlJs.isNotBlank()) {
                webView.evaluateJavascript(pageCurlJs, null)
            }
        }

        // Re-apply font size, theme, and font-family after every page/chapter load.
        // This must come AFTER the CSS injection so our preference JS can win over
        // the publisher stylesheet even on fresh document loads.
        applyReaderPreferences()
    }

    /**
     * Injects a `<style id="omni-user-prefs">` element into every WebView with
     * the user's chosen font size, background theme, and font family.
     *
     * WHY a style element, not inline styles:
     * Readium may re-layout the page (column reflow, resize events) after our
     * `evaluateJavascript` returns. Inline `element.style` values survive that,
     * but Readium's own CSS injections — which also run as stylesheet rules — can
     * win on specificity when the publisher used `!important` on specific selectors.
     * A stylesheet inserted LAST in `<head>` wins the cascade for same-specificity
     * `!important` collisions because later stylesheets take precedence. This is
     * the same technique used for `override.css` which already applies correctly.
     *
     * Called:
     *  • At the end of [injectCssAndJs] (so prefs survive chapter/page loads)
     *  • Whenever any preference StateFlow emits a new value (see [launchNavigator])
     */
    private fun applyReaderPreferences() {
        val root = navigatorFragment?.view ?: return
        val sp     = viewModel.fontSizeSp.value
        val theme  = viewModel.readerThemePref.value
        val family = viewModel.fontFamilyPref.value

        val bgColor = when (theme) {
            ReaderTheme.DAY   -> "#ffffff"
            ReaderTheme.NIGHT -> "#1a1a1a"
            ReaderTheme.SEPIA -> "#f4ecd8"
            ReaderTheme.PAPER -> "#f0ebe0"
        }
        val fgColor = when (theme) {
            ReaderTheme.DAY   -> "#1a1a1a"
            ReaderTheme.NIGHT -> "#e0e0e0"
            ReaderTheme.SEPIA -> "#5c4033"
            ReaderTheme.PAPER -> "#1c1410"
        }
        val fontCss = when (family) {
            ReaderFontFamily.SERIF  -> "Georgia, 'Times New Roman', serif"
            ReaderFontFamily.SANS   -> "'Helvetica Neue', Arial, sans-serif"
            ReaderFontFamily.MONO   -> "'Courier New', Courier, monospace"
        }

        // Build the CSS as a plain string, then base64-encode it so special
        // characters (quotes, apostrophes, newlines) can't break the JS template.
        val css = """
            html, body {
                font-size:        ${sp}px    !important;
                font-family:      $fontCss   !important;
                background-color: $bgColor   !important;
                color:            $fgColor   !important;
                min-height:       100vh      !important;
            }
            p, span, li, td, th, div, blockquote, pre, a, cite, em, strong, h1, h2, h3, h4, h5, h6 {
                font-size:   inherit !important;
                font-family: inherit !important;
                color:       inherit !important;
            }
        """.trimIndent()

        val cssB64 = Base64.encodeToString(
            css.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )

        // Insert (or replace) a <style> element in <head>.
        // Removing + re-adding ensures the new rules are the LAST stylesheet in the
        // document, winning any same-specificity !important conflicts.
        val js = """
            (function() {
                var old = document.getElementById('omni-user-prefs');
                if (old) old.parentNode.removeChild(old);
                var s = document.createElement('style');
                s.id = 'omni-user-prefs';
                s.textContent = atob('$cssB64');
                if (document.head) document.head.appendChild(s);
            })();
        """.trimIndent()

        // Base64-encode the background colour for JS inline-style injection below.
        val bgColorB64 = Base64.encodeToString(bgColor.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // JS that sets inline styles directly on <html> and <body>. Inline styles
        // win over any stylesheet rule, ensuring the background fills the full
        // viewport even on short pages where the HTML body doesn't reach the
        // bottom of the WebView on its own.
        val inlineStyleJs = """
            (function() {
                var bg = atob('$bgColorB64');
                var de = document.documentElement;
                if (de) {
                    de.style.setProperty('background-color', bg, 'important');
                    de.style.setProperty('min-height', '100vh', 'important');
                }
                var b = document.body;
                if (b) {
                    b.style.setProperty('background-color', bg, 'important');
                    b.style.setProperty('min-height', '100vh', 'important');
                }
            })();
        """.trimIndent()

        val colorInt = try { android.graphics.Color.parseColor(bgColor) } catch (_: Exception) { null }

        forEachWebView(root) { wv ->
            wv.evaluateJavascript(js, null)
            wv.evaluateJavascript(inlineStyleJs, null)
            // Set the Android-level WebView background (visible in over-scroll
            // areas and in regions the HTML document doesn't paint).  We post()
            // so this runs after Readium's own render queue clears — if called
            // synchronously Readium may reset the background to its own default
            // before the first frame is drawn.
            if (colorInt != null) {
                wv.post { try { wv.setBackgroundColor(colorInt) } catch (_: Exception) {} }
            }
        }

        // Apply the same background to the Activity window (system bar areas, etc.)
        applyActivityTheme()
    }

    /**
     * Applies the current reader theme to the entire Activity window.
     *
     * This is separate from [applyReaderPreferences] so it can be called as soon
     * as the reader opens — before any WebView locator fires — ensuring the status
     * bar, navigation bar, and FrameLayout background all match the reading theme
     * from the very first frame, not just after the first page loads.
     *
     * Uses [window.setBackgroundDrawable] which works in both edge-to-edge mode
     * (where the background shows through transparent system bars) and in the
     * traditional layout mode (where system bar colours are set separately).
     */
    private fun applyActivityTheme() {
        val theme = viewModel.readerThemePref.value
        val bgColor = when (theme) {
            ReaderTheme.DAY   -> "#ffffff"
            ReaderTheme.NIGHT -> "#1a1a1a"
            ReaderTheme.SEPIA -> "#f4ecd8"
            ReaderTheme.PAPER -> "#f0ebe0"
        }
        try {
            val colorInt = android.graphics.Color.parseColor(bgColor)

            // Window background — this is what shows through transparent system bars
            // in edge-to-edge mode, behind the FrameLayout, and during transitions.
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(colorInt)
            )

            // FrameLayout container — covers the area inside the window decorations.
            findViewById<android.widget.FrameLayout>(R.id.reader_container)
                ?.setBackgroundColor(colorInt)

            // System bar colors (effective on non-edge-to-edge builds / older APIs).
            window.statusBarColor     = colorInt
            window.navigationBarColor = colorInt

            // Bar icon appearance — dark icons on light themes, light icons on Night.
            val isLight = theme != ReaderTheme.NIGHT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val lightFlags =
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(
                    if (isLight) lightFlags else 0,
                    lightFlags
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    if (isLight) {
                        window.decorView.systemUiVisibility or
                            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else {
                        window.decorView.systemUiVisibility and
                            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    }
            }
        } catch (_: Exception) {}
    }

    /**
     * Walks [view]'s hierarchy and calls [action] for every [WebView] found.
     * Handles arbitrarily nested ViewGroups.
     */
    private fun forEachWebView(view: View, action: (WebView) -> Unit) {
        when (view) {
            is WebView    -> action(view)
            is ViewGroup  -> repeat(view.childCount) { i -> forEachWebView(view.getChildAt(i), action) }
        }
    }

    // ── JavaScript-based highlight injection ──────────────────────────────────
    //
    // Readium's applyDecorations API can silently no-op when the locator CFI
    // doesn't match the document exactly. As a reliable fallback we inject a
    // small JS snippet that walks the DOM text nodes, finds the saved selected
    // text, and wraps it in a <mark class="omni-highlight"> element with the
    // saved background colour. This fires after every page/chapter load and
    // after every annotation change.
    //
    // Limitations: only highlights the FIRST occurrence of the selected text on
    // the current page. Selections that span multiple DOM nodes are skipped
    // gracefully. Annotations whose text is not on the current page are ignored.

    private fun injectJsHighlights(
        fragment: EpubNavigatorFragment,
        annotations: List<com.ereader.app.domain.model.Annotation>
    ) {
        // Build a JSON array: [{ id, text, color }, ...]
        // Only include HIGHLIGHT and NOTE types that have actual selected text.
        val jsonArray = JSONArray()
        annotations
            .filter {
                it.type != com.ereader.app.data.local.entity.AnnotationType.COMMENT &&
                !it.selectedText.isNullOrBlank()
            }
            .forEach { ann ->
                jsonArray.put(
                    JSONObject()
                        .put("id",    ann.id)
                        .put("text",  ann.selectedText ?: "")
                        .put("color", ann.color.hex)   // e.g. "#FFD54F"
                )
            }

        if (jsonArray.length() == 0) return   // nothing to inject

        // Base64-encode so special chars in the selected text can't break the JS
        val dataB64 = Base64.encodeToString(
            jsonArray.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val js = """
            (function() {
                // ── Remove previous omni highlights ──────────────────────────
                document.querySelectorAll('mark.omni-highlight').forEach(function(m) {
                    while (m.firstChild) m.parentNode.insertBefore(m.firstChild, m);
                    m.parentNode.removeChild(m);
                });
                // Merge any text-node fragments left over from the previous injection
                // cycle. surroundContents() splits text nodes at the selection
                // boundaries; without normalize() a second injection can't find the
                // full string via indexOf() because it is now split across sibling nodes.
                if (document.body) document.body.normalize();

                // ── Parse highlight data ──────────────────────────────────────
                var highlights;
                try { highlights = JSON.parse(atob('$dataB64')); }
                catch(e) { return; }

                // ── Hex → rgba helper (70% alpha for clear visibility) ──────
                function hexRgba(hex) {
                    var r = parseInt(hex.slice(1,3),16);
                    var g = parseInt(hex.slice(3,5),16);
                    var b = parseInt(hex.slice(5,7),16);
                    return 'rgba('+r+','+g+','+b+',0.7)';
                }

                // ── Smart-quote normalisation ─────────────────────────────────
                // Android's text-selection API yields plain ASCII apostrophes/
                // quotes (U+0027, U+0022), but typeset EPUBs use curly/smart
                // variants (U+2018–U+201D). Normalise both sides before comparing
                // so "Tharion’s" in the DOM matches stored text "Tharion's".
                // Replacement is always 1-for-1 (each Unicode char → 1 ASCII char)
                // so character offsets into the original node remain valid.
                function normQ(s) {
                    return s
                        .replace(/[‘’‚‛ʼ]/g, "'")
                        .replace(/[“”„‟]/g,       '"')
                        .replace(/–|—/g,                    '-');
                }

                // ── Apply each highlight ──────────────────────────────────────
                highlights.forEach(function(h) {
                    if (!h.text) return;
                    var target = normQ(h.text);
                    var walker = document.createTreeWalker(
                        document.body, NodeFilter.SHOW_TEXT, null
                    );
                    var node;
                    while ((node = walker.nextNode())) {
                        var idx = normQ(node.textContent).indexOf(target);
                        if (idx < 0) continue;
                        try {
                            var range = document.createRange();
                            range.setStart(node, idx);
                            // Use target.length — normQ is 1-for-1, so the
                            // offset into the original node is still correct.
                            range.setEnd(node, idx + target.length);
                            var mark = document.createElement('mark');
                            mark.className = 'omni-highlight';
                            mark.id = 'omni-hl-' + h.id;
                            // setProperty with 'important' priority is the correct way
                            // to apply !important via JS in Android WebView.
                            // cssText with !important in the value string is unreliable
                            // in older WebView implementations.
                            mark.style.setProperty('background-color', hexRgba(h.color), 'important');
                            mark.style.setProperty('color', 'inherit', 'important');
                            mark.style.borderRadius = '2px';
                            // surroundContents wraps the selected range in the mark element.
                            // It throws HierarchyRequestError when the selection partially
                            // overlaps an element boundary (e.g. <em>mid</em>word). In that
                            // case we simply skip the highlight — the node stays unmodified
                            // so the next inject attempt on a fresh page can succeed.
                            range.surroundContents(mark);
                        } catch(e) { /* selection spans multiple nodes — skip */ }
                        break;  // only first match per highlight
                    }
                });
            })();
        """.trimIndent()

        val root = fragment.view ?: return
        forEachWebView(root) { wv -> wv.evaluateJavascript(js, null) }
    }

    // ── Compose HUD ───────────────────────────────────────────────────────────

    private fun attachHud() {
        val container = findViewById<android.widget.FrameLayout>(R.id.reader_container)
        val hudView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                EReaderTheme {
                    ReaderControlsHud(
                        viewModel            = viewModel,
                        onNavigateToLocator  = { locatorJson ->
                            lifecycleScope.launch {
                                val locator = runCatching {
                                    Locator.fromJSON(JSONObject(locatorJson))
                                }.getOrNull() ?: return@launch
                                navigatorFragment?.go(locator)
                            }
                        },
                        onStartReading       = { startReading() },
                        onPauseReading       = { pauseReading() },
                        onResumeReading      = { resumeReading() },
                        onStopReading        = { stopReading() },
                        onClose              = { stopReading(); finish() }
                    )
                }
            }
        }

        // MATCH_PARENT so Compose fillMaxSize() constraints are unambiguous.
        container.addView(
            hudView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Z-order strategy:
        //   HUD hidden  → Readium's WebView sits on top. Every touch goes straight to
        //                  it, so page-turn swipes and text-selection long-presses work
        //                  with zero interference from the ComposeView.
        //   HUD visible → ComposeView comes to front so the bar buttons are tappable.
        //                  Page turns are not expected while the HUD is open.
        //
        // The fragment transaction is async (commitAllowingStateLoss), so the
        // Readium view is inserted on the next Looper message and naturally lands
        // on top of the ComposeView — that's the correct initial state (HUD hidden).
        // We only need to react when hudVisible changes after that.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.hudVisible.collect { visible ->
                    if (visible) {
                        hudView.bringToFront()
                    } else {
                        navigatorFragment?.view?.let { fragView ->
                            // Make the fragment root transparent so that, in the
                            // status-bar and nav-bar zones that Readium doesn't
                            // cover with a WebView, the ComposeView's reader-theme
                            // background (set via hudView.setBackgroundColor) shows
                            // through on API 35+ (forced edge-to-edge).
                            fragView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            fragView.bringToFront()
                        }
                    }
                }
            }
        }

        // Keep the ComposeView's own Android background colour in sync with the
        // reader theme. This paints the system-bar inset areas (status bar and
        // nav bar zones) with the correct colour even when Compose content is
        // transparent there, because the ComposeView itself is MATCH_PARENT and
        // covers those regions.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.readerThemePref.collect { theme ->
                    val hex = when (theme) {
                        ReaderTheme.DAY   -> "#ffffff"
                        ReaderTheme.NIGHT -> "#1a1a1a"
                        ReaderTheme.SEPIA -> "#f4ecd8"
                        ReaderTheme.PAPER -> "#f0ebe0"
                    }
                    try {
                        hudView.setBackgroundColor(android.graphics.Color.parseColor(hex))
                    } catch (_: Exception) {}
                }
            }
        }

        // Page curl: pipe Activity-level pageTurnFlow into the ViewModel so the
        // PageCurlOverlay inside the HUD's ComposeView can collect it.
        // Also briefly bring the ComposeView to front so the Canvas animation
        // is visible over the WebView (480 ms curl + 80 ms buffer = 560 ms total).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pageTurnFlow.collect { dir ->
                    viewModel.dispatchPageTurn(dir)
                    if (!viewModel.hudVisible.value) {
                        hudView.bringToFront()
                        kotlinx.coroutines.delay(560)
                        // Return WebView to top only if the HUD hasn't been opened
                        // by the user during the animation window.
                        if (!viewModel.hudVisible.value) {
                            navigatorFragment?.view?.bringToFront()
                        }
                    }
                }
            }
        }
    }

    // ── Loading / error states ────────────────────────────────────────────────

    private fun showLoading() { Timber.d("ReaderActivity: loading…") }

    private fun showError(message: String) {
        android.widget.Toast.makeText(
            this, "Could not open book: $message", android.widget.Toast.LENGTH_LONG
        ).show()
        finish()
    }

    // ── Two-page spread preferences ───────────────────────────────────────────

    private fun buildSpreadPrefs(twoPage: Boolean): EpubPreferences {
        val base = EpubPreferences(scroll = false)
        if (!twoPage) return base
        return try {
            val spreadClass = runCatching {
                Class.forName("org.readium.r2.navigator.epub.Spread")
            }.getOrElse {
                Class.forName("org.readium.r2.navigator.epub.css.Spread")
            }
            val bothValue = spreadClass.enumConstants
                ?.firstOrNull { (it as Enum<*>).name == "BOTH" }
                ?: return base

            val copyFn = base.javaClass.methods.firstOrNull { m ->
                m.name == "copy" && m.parameters.any { p -> p.name == "spread" }
            } ?: return base

            val args = copyFn.parameters.map { param ->
                when (param.name) { "scroll" -> false; "spread" -> bothValue; else -> null }
            }.toTypedArray()

            @Suppress("UNCHECKED_CAST")
            (copyFn.invoke(base, *args) as? EpubPreferences) ?: base
        } catch (e: Exception) {
            Timber.w("Two-page spread unavailable: ${e.message}")
            base
        }
    }

    @Deprecated("Replaced by OnBackPressedDispatcher in API 33+")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
