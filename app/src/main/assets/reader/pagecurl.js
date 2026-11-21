/**
 * OmniTome -- Interactive Page Curl
 *
 * Adds a real-time, finger-following page-turn animation running entirely
 * inside Readium's WebView. The canvas sits inside the same document so
 * there is no Z-order conflict with the ComposeView and no double-firing.
 *
 * Architecture
 * ------------
 *  - A <canvas> (pointer-events: none) is appended to <body>.
 *  - touchstart / touchmove / touchend drive the animation.
 *  - A single animLoop RAF function handles both completion and snap-back.
 *  - pointer-events: none on the canvas means every touch also reaches
 *    Readium's own handlers -- navigation, text selection all work.
 */
(function () {
    'use strict';
    if (window.__omniPageCurl) return;
    window.__omniPageCurl = true;

    /* Guard: body must exist. On some fast chapter loads evaluateJavascript
       fires before the document is fully parsed. */
    var body = document.body || document.documentElement;
    if (!body) { window.__omniPageCurl = false; return; }

    /* ------------------------------------------------------------------
       Canvas
    ------------------------------------------------------------------ */
    var cv  = document.createElement('canvas');
    var ctx = cv.getContext('2d');
    cv.style.cssText =
        'position:fixed;top:0;left:0;pointer-events:none;' +
        'z-index:2147483647;touch-action:none;';
    body.appendChild(cv);

    var W = 0, H = 0;
    function resize() {
        W = cv.width  = window.innerWidth;
        H = cv.height = window.innerHeight;
    }
    resize();
    window.addEventListener('resize', resize, { passive: true });

    /* ------------------------------------------------------------------
       State
    ------------------------------------------------------------------ */
    var touching  = false;
    var startX    = 0;
    var curX      = 0;
    var dir       = 0;      // +1 forward (swipe left), -1 backward (swipe right)
    var animating = false;
    var animRaf   = 0;

    /* Animation parameters set at touchend */
    var animFrom     = 0;   /* progress at which animation starts */
    var animStart    = 0;   /* performance.now() when animation began */
    var animForward  = true; /* true = animate to 1.0, false = snap back to 0 */

    function stopAnim() {
        if (animRaf) { cancelAnimationFrame(animRaf); animRaf = 0; }
        animating = false;
        dir = 0;
        ctx.clearRect(0, 0, W, H);
    }

    /* ------------------------------------------------------------------
       Drawing
    ------------------------------------------------------------------ */
    function draw(p, d) {
        ctx.clearRect(0, 0, W, H);
        if (p <= 0.001) return;

        var foldX = d > 0 ? W * (1 - p) : W * p;
        var cw    = Math.max(16, 88 * (1 - p * 0.62));
        var cp1x  = d > 0 ? foldX + cw * 0.55 : foldX - cw * 0.55;
        var cp1y  = H * 0.28;
        var cp2x  = cp1x;
        var cp2y  = H * 0.72;

        /* 1. Underpage tint */
        ctx.save();
        ctx.beginPath();
        if (d > 0) {
            ctx.rect(0, 0, foldX + cw * 0.6, H);
        } else {
            ctx.rect(foldX - cw * 0.6, 0, W - (foldX - cw * 0.6), H);
        }
        ctx.clip();
        var ug = ctx.createLinearGradient(0, 0, W, 0);
        ug.addColorStop(0,   'rgba(205,200,190,0.35)');
        ug.addColorStop(0.5, 'rgba(228,224,215,0.22)');
        ug.addColorStop(1,   'rgba(210,205,195,0.32)');
        ctx.fillStyle = ug;
        ctx.fillRect(0, 0, W, H);
        ctx.restore();

        /* 2. Curling page slab */
        ctx.save();
        ctx.beginPath();
        if (d > 0) {
            ctx.moveTo(W, 0);
            ctx.lineTo(foldX, 0);
            ctx.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, foldX, H);
            ctx.lineTo(W, H);
        } else {
            ctx.moveTo(0, 0);
            ctx.lineTo(foldX, 0);
            ctx.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, foldX, H);
            ctx.lineTo(0, H);
        }
        ctx.closePath();
        var pg = d > 0
            ? ctx.createLinearGradient(foldX, 0, W, 0)
            : ctx.createLinearGradient(0, 0, foldX, 0);
        pg.addColorStop(0,    'rgba(228,223,212,0.98)');
        pg.addColorStop(0.12, 'rgba(246,242,235,0.97)');
        pg.addColorStop(1,    'rgba(252,250,245,0.96)');
        ctx.fillStyle = pg;
        ctx.fill();
        ctx.restore();

        /* 3. Cast shadow */
        var sw = Math.min(65, cw * 0.85);
        var sg;
        if (d > 0) {
            sg = ctx.createLinearGradient(foldX - sw, 0, foldX, 0);
            sg.addColorStop(0, 'rgba(0,0,0,0)');
            sg.addColorStop(1, 'rgba(0,0,0,0.28)');
            ctx.fillStyle = sg;
            ctx.fillRect(foldX - sw, 0, sw, H);
        } else {
            sg = ctx.createLinearGradient(foldX, 0, foldX + sw, 0);
            sg.addColorStop(0, 'rgba(0,0,0,0.28)');
            sg.addColorStop(1, 'rgba(0,0,0,0)');
            ctx.fillStyle = sg;
            ctx.fillRect(foldX, 0, sw, H);
        }

        /* 4. Fold highlight */
        ctx.save();
        ctx.beginPath();
        ctx.moveTo(foldX, 0);
        ctx.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, foldX, H);
        var hg = ctx.createLinearGradient(0, 0, 0, H);
        hg.addColorStop(0,   'rgba(255,255,255,0.72)');
        hg.addColorStop(0.5, 'rgba(255,255,255,0.18)');
        hg.addColorStop(1,   'rgba(255,255,255,0.72)');
        ctx.strokeStyle = hg;
        ctx.lineWidth   = 2.5;
        ctx.stroke();
        ctx.restore();

        /* 5. Inner shadow on the back of the curled page */
        var iw = Math.min(22, cw * 0.28);
        var ig;
        if (d > 0) {
            ig = ctx.createLinearGradient(foldX, 0, foldX + iw, 0);
            ig.addColorStop(0, 'rgba(0,0,0,0.18)');
            ig.addColorStop(1, 'rgba(0,0,0,0)');
            ctx.fillStyle = ig;
            ctx.fillRect(foldX, 0, iw, H);
        } else {
            ig = ctx.createLinearGradient(foldX - iw, 0, foldX, 0);
            ig.addColorStop(0, 'rgba(0,0,0,0)');
            ig.addColorStop(1, 'rgba(0,0,0,0.18)');
            ctx.fillStyle = ig;
            ctx.fillRect(foldX - iw, 0, iw, H);
        }
    }

    /* ------------------------------------------------------------------
       Single RAF animation loop
       Handles both forward-completion (animForward=true) and snap-back.
    ------------------------------------------------------------------ */
    function animLoop(now) {
        var elapsed  = now - animStart;
        var duration = animForward
            ? Math.max(50, 120 * (1 - animFrom))   /* faster if nearly done */
            : Math.max(35, 80  * animFrom);          /* proportional snap-back */
        var raw  = elapsed / duration;
        var frac = raw < 1 ? raw : 1;
        var p;

        if (animForward) {
            /* Ease-out cubic: decelerates at the end */
            var ease = 1 - (1 - frac) * (1 - frac) * (1 - frac);
            p = animFrom + (1 - animFrom) * ease;
        } else {
            /* Ease-in quad: accelerates back to edge */
            p = animFrom * (1 - frac * frac);
        }

        draw(p, dir);

        if (frac < 1) {
            animRaf = requestAnimationFrame(animLoop);
        } else {
            /* Animation complete */
            ctx.clearRect(0, 0, W, H);
            animating = false;
            animRaf   = 0;
            dir       = 0;
        }
    }

    /* ------------------------------------------------------------------
       Touch handlers
    ------------------------------------------------------------------ */
    document.addEventListener('touchstart', function (e) {
        if (animating) return;
        var t = e.touches[0];
        startX   = t.clientX;
        curX     = t.clientX;
        touching = true;
        dir      = 0;
    }, { passive: true });

    document.addEventListener('touchmove', function (e) {
        if (!touching || animating) return;
        curX = e.touches[0].clientX;
        var dx = curX - startX;
        if (Math.abs(dx) < 4) return;  /* dead zone — tight so curl tracks finger sooner */
        dir = dx < 0 ? 1 : -1;
        draw(Math.min(0.97, Math.abs(dx) / W), dir);
    }, { passive: true });

    document.addEventListener('touchend', function () {
        if (!touching) return;
        touching = false;

        if (dir === 0) { ctx.clearRect(0, 0, W, H); return; }

        var progress = Math.min(0.97, Math.abs(curX - startX) / W);

        animating   = true;
        animFrom    = progress;
        animStart   = performance.now();
        animForward = progress > 0.12;
        animRaf     = requestAnimationFrame(animLoop);
    }, { passive: true });

    document.addEventListener('touchcancel', function () {
        touching = false;
        stopAnim();
    }, { passive: true });

})();
