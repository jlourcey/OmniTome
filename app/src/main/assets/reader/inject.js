/**
 * OmniTome Reader — Lightweight JS Injection
 *
 * What this script does:
 *   1. TABLE SCROLL WRAPPING — wraps every <table> in a horizontally
 *      scrollable container so columns never clip off-screen.
 *   2. IMAGE ZOOM MODAL — adds a tap listener to every <img> that opens
 *      a full-screen overlay showing the image at natural size.
 *
 * Design rules:
 *   - Pure vanilla JS, zero dependencies.
 *   - Idempotent: safe to call multiple times on the same document.
 *   - Uses an IIFE (immediately-invoked function expression) so no
 *     variables leak into the global scope.
 *   - Works inside Readium's sandboxed WebView.
 */
(function () {
    'use strict';

    /* ── Guard: run only once per document context ──────────────────────────
       Readium loads each spine item (chapter) as a separate HTML document,
       so this guard fires correctly per chapter — it is NOT shared between
       chapters.
    ─────────────────────────────────────────────────────────────────────── */
    if (window.__omniTomeInjected) return;
    window.__omniTomeInjected = true;


    /* ══════════════════════════════════════════════════════════════════════
       PART 1 — TABLE SCROLL WRAPPING
       ══════════════════════════════════════════════════════════════════════

       Problem: EPUB tables are often sized for wide e-ink screens. On a
       narrow phone the right-hand columns clip off the screen and can't
       be reached by swiping because the WebView itself handles horizontal
       scroll.

       Fix: Wrap each table in a <div> whose overflow-x is set to "auto".
       This creates a local scroll region inside the page — the user swipes
       inside that box to reveal hidden columns while the rest of the page
       stays still.
    ─────────────────────────────────────────────────────────────────────── */

    function wrapTables() {
        var tables = document.querySelectorAll('table');
        for (var i = 0; i < tables.length; i++) {
            var table = tables[i];

            // Skip if already wrapped by a previous injection pass
            if (table.parentNode &&
                table.parentNode.getAttribute &&
                table.parentNode.getAttribute('data-omni-table-wrap')) {
                continue;
            }

            // Create the scrollable wrapper
            var wrapper = document.createElement('div');
            wrapper.setAttribute('data-omni-table-wrap', 'true');
            // Inline styles so the wrapper works without external CSS
            wrapper.style.cssText = [
                'overflow-x: auto',                   // horizontal scroll
                '-webkit-overflow-scrolling: touch',  // smooth momentum on iOS/WebView
                'max-width: 100%',                    // never wider than the viewport
                'margin: 0.75em 0',                   // vertical breathing room
                'position: relative'
            ].join('; ');

            // Insert wrapper before the table, then move the table inside
            table.parentNode.insertBefore(wrapper, table);
            wrapper.appendChild(table);

            // Remove any hardcoded fixed width on the table element itself
            // so it can size to its natural content width inside the wrapper
            table.style.width = '';
        }
    }


    /* ══════════════════════════════════════════════════════════════════════
       PART 2 — IMAGE ZOOM MODAL
       ══════════════════════════════════════════════════════════════════════

       Problem: Inline images are constrained to the column width (our CSS
       caps them at max-width:100%). A small diagram or map that needs to
       be read in detail is impossible to examine at column width.

       Fix: Tap an image → a full-screen dark overlay appears showing the
       image at its natural (or viewport-filling) size. The user can then
       use the system's built-in pinch-to-zoom gesture on the image. Tapping
       the background overlay — or anywhere outside the image — dismisses it.

       The modal is created once and reused for every image tap to avoid
       creating unnecessary DOM nodes.
    ─────────────────────────────────────────────────────────────────────── */

    // These are module-level so the modal is shared across all tap events
    var modal    = null;
    var modalImg = null;

    /**
     * Lazily build the modal DOM on first use.
     * We defer creation so we don't touch the DOM at all for books
     * that contain no images.
     */
    function ensureModal() {
        if (modal) return; // already built

        // ── Outer overlay ────────────────────────────────────────────────
        modal = document.createElement('div');
        modal.id = 'omni-zoom-modal';
        modal.style.cssText = [
            'display: none',
            'position: fixed',        // cover the entire viewport
            'top: 0', 'left: 0',
            'width: 100vw', 'height: 100vh',
            'z-index: 999999',        // above all EPUB content
            'background: rgba(0, 0, 0, 0.88)',
            'cursor: zoom-out',
            'justify-content: center',
            'align-items: center',
            '-webkit-tap-highlight-color: transparent',
            /* Allow native pinch-zoom on the image element itself,
               but prevent the overlay from scrolling the page behind it */
            'touch-action: none',
            /* Smooth fade-in/out */
            'transition: opacity 0.18s ease',
            'opacity: 0'
        ].join('; ');

        // ── Inner image ──────────────────────────────────────────────────
        modalImg = document.createElement('img');
        modalImg.style.cssText = [
            'max-width: 95vw',
            'max-height: 90vh',
            'object-fit: contain',   // maintain aspect ratio
            'border-radius: 4px',
            'box-shadow: 0 8px 40px rgba(0, 0, 0, 0.7)',
            /* Allow native pinch-to-zoom on the image */
            'touch-action: pinch-zoom',
            'user-select: none',
            '-webkit-user-select: none'
        ].join('; ');

        /* Tapping the IMAGE should not dismiss the modal —
           only tapping the dark background should close it. */
        modalImg.addEventListener('click', function (e) {
            e.stopPropagation();
        });

        modal.appendChild(modalImg);
        document.body.appendChild(modal);

        // ── Close on background tap ──────────────────────────────────────
        modal.addEventListener('click', function () {
            closeModal();
        });

        // ── Close on Escape key (for keyboard / Bluetooth keyboard users) ─
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') closeModal();
        });
    }

    function openModal(src, alt) {
        ensureModal();

        modalImg.src = src;                // load the image at full resolution
        modalImg.alt = alt || '';
        modal.style.display = 'flex';      // flex centers the image in the overlay

        // Kick off the fade-in on the next animation frame so the
        // display:flex paint is committed before opacity starts changing
        requestAnimationFrame(function () {
            modal.style.opacity = '1';
        });
    }

    function closeModal() {
        if (!modal) return;
        modal.style.opacity = '0';
        // After the CSS transition finishes, hide the element and free memory
        setTimeout(function () {
            modal.style.display = 'none';
            modalImg.src = '';    // release the image from memory
        }, 200);
    }

    /**
     * Attach a tap listener to every image in the document.
     * Skips images already tagged, decorative images (aria-hidden),
     * and 1×1 tracking pixels.
     */
    function attachImageListeners() {
        var images = document.querySelectorAll('img');
        for (var i = 0; i < images.length; i++) {
            var img = images[i];

            // Already wired up — skip
            if (img.getAttribute('data-omni-zoom')) continue;

            // Skip decorative images (screen-reader hidden)
            if (img.getAttribute('aria-hidden') === 'true') continue;

            // Skip 1×1 tracking pixels — they have no value to zoom into
            if (img.naturalWidth === 1 && img.naturalHeight === 1) continue;

            img.setAttribute('data-omni-zoom', 'true');
            img.style.cursor = 'zoom-in';   // visual cue that the image is tappable

            // Use a closure to capture the correct `img` reference in the loop
            (function (image) {
                image.addEventListener('click', function (e) {
                    e.preventDefault();
                    e.stopPropagation();
                    openModal(image.src, image.alt);
                });
            })(img);
        }
    }


    /* ── Run injections and watch for dynamic content ───────────────────────
       Some EPUB documents load images lazily via JavaScript or inject
       tables dynamically. MutationObserver lets us catch those additions
       and apply our wrappers/listeners automatically.
    ─────────────────────────────────────────────────────────────────────── */

    function runAll() {
        wrapTables();
        attachImageListeners();
    }

    // Run immediately if the DOM is already ready; otherwise wait for it
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', runAll);
    } else {
        runAll();
    }

    // Watch for dynamically added tables or images
    if (window.MutationObserver) {
        var observer = new MutationObserver(function (mutations) {
            var needsReinject = false;
            for (var m = 0; m < mutations.length; m++) {
                var added = mutations[m].addedNodes;
                for (var n = 0; n < added.length; n++) {
                    var node = added[n];
                    // Only care about element nodes
                    if (node.nodeType !== 1) continue;
                    var tag = node.tagName || '';
                    if (tag === 'IMG' || tag === 'TABLE' ||
                        (node.querySelector && node.querySelector('img, table'))) {
                        needsReinject = true;
                        break;
                    }
                }
                if (needsReinject) break;
            }
            if (needsReinject) runAll();
        });

        var root = document.body || document.documentElement;
        if (root) {
            observer.observe(root, { childList: true, subtree: true });
        }
    }

})(); // end IIFE
