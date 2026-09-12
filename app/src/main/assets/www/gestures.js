/* ══════════════════════════════════════════════════════════════
   Puzzle Skills · Panner Design — touch gestures
   ──────────────────────────────────────────────────────────────
   One finger and two fingers mean different things, and never the
   same thing at the same time:

     • 1 finger on a placed image (manual mode) → move that image
     • 1 finger anywhere else                   → pan the canvas
     • 2 fingers                                → pinch-zoom + pan

   Selection is sticky: tapping an image selects it and it stays
   selected — through panning, zooming, and toolbar edits — until
   another image is tapped or you tap empty felt. That is what makes
   a one-finger drag land on the right thing every time.

   The canvas is moved with a CSS transform on .sheet-outer rather
   than by scrolling, so the transform is the single source of truth
   for where the canvas is and how big it looks. WebView's own pinch
   zoom is switched off natively — two zoom systems fight each other.
   ══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var MIN_SCALE = 0.15;
  var MAX_SCALE = 6;
  var TAP_SLOP_PX = 12;     // finger wobble still counts as a tap
  var TAP_TIME_MS = 500;

  var stage, outer, sheet;
  var view = { x: 0, y: 0, scale: 1 };

  /* Active gesture state */
  var mode = null;          // 'item' | 'pan' | 'pinch' | null
  var drag = null;          // handle from PannerApp.beginItemDrag
  var pointers = new Map(); // pointerId → {x, y}
  var startView = null;
  var startMid = null;
  var startDist = 0;
  var startPt = null;
  var startTime = 0;
  var movedFar = false;
  var pendingIndex = -1;
  var historyPushed = false;    // one undo entry per drag, not per frame

  function app() { return window.PannerApp; }

  /* ── Viewport ─────────────────────────────────────────────── */

  function applyView() {
    if (!outer) return;
    outer.style.transform =
      'translate(' + view.x + 'px,' + view.y + 'px) scale(' + view.scale + ')';
    var lbl = document.getElementById('zoomLevel');
    if (lbl) lbl.textContent = Math.round(view.scale * 100) + '%';
  }

  function setScale(next, originX, originY) {
    next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next));
    // Keep the point under the fingers pinned while the scale changes.
    var k = next / view.scale;
    view.x = originX - (originX - view.x) * k;
    view.y = originY - (originY - view.y) * k;
    view.scale = next;
    applyView();
  }

  /** Frames the whole banner in the stage. */
  function fit() {
    if (!outer || !stage) return;
    var prev = outer.style.transform;
    outer.style.transform = 'none';
    var w = outer.offsetWidth, h = outer.offsetHeight;
    outer.style.transform = prev;
    if (!w || !h) return;

    var availW = stage.clientWidth - 24;
    var availH = stage.clientHeight - 24;
    view.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE,
      Math.min(availW / w, availH / h)));
    view.x = (stage.clientWidth - w * view.scale) / 2;
    view.y = 12;
    applyView();
  }

  function zoomBy(factor) {
    if (!stage) return;
    setScale(view.scale * factor, stage.clientWidth / 2, stage.clientHeight / 2);
  }

  /* ── Coordinate helpers ───────────────────────────────────── */

  function stagePoint(e) {
    var r = stage.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  function midOf(pts) {
    var sx = 0, sy = 0;
    pts.forEach(function (p) { sx += p.x; sy += p.y; });
    return { x: sx / pts.length, y: sy / pts.length };
  }

  function distOf(pts) {
    var a = pts[0], b = pts[1];
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  /** Screen pixels → centimetres on the sheet, at the current zoom. */
  function toCm(px) {
    return px / (app().PXCM * view.scale);
  }

  /* ── Gesture handling ─────────────────────────────────────── */

  function cellIndexAt(target) {
    var cell = target && target.closest ? target.closest('.cell') : null;
    return cell ? +cell.dataset.i : -1;
  }

  function onPointerDown(e) {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    pointers.set(e.pointerId, stagePoint(e));

    if (pointers.size === 2) {
      // A second finger always wins: abandon whatever one finger started
      // (without committing a half-finished move) and switch to pinch.
      if (drag) {
        drag.cancel();
        drag = null;
        if (historyPushed && window.PannerStore) window.PannerStore.undo();
        historyPushed = false;
      }
      var pts = [...pointers.values()];
      mode = 'pinch';
      startView = { x: view.x, y: view.y, scale: view.scale };
      startMid = midOf(pts);
      startDist = distOf(pts) || 1;
      return;
    }

    if (pointers.size !== 1) return;

    startPt = stagePoint(e);
    startTime = Date.now();
    movedFar = false;
    historyPushed = false;
    startView = { x: view.x, y: view.y, scale: view.scale };
    pendingIndex = cellIndexAt(e.target);

    var A = app();
    if (A && A.manualMode && pendingIndex >= 0) {
      // Select on touch-down so the highlight tracks the finger immediately.
      A.selectCell(pendingIndex);
      drag = A.beginItemDrag(pendingIndex);
      mode = drag ? 'item' : 'pan';
    } else {
      mode = 'pan';
    }
  }

  function onPointerMove(e) {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, stagePoint(e));

    if (mode === 'pinch' && pointers.size >= 2) {
      var pts = [...pointers.values()].slice(0, 2);
      var mid = midOf(pts);
      var dist = distOf(pts) || 1;
      var k = dist / startDist;
      var next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, startView.scale * k));
      var ratio = next / startView.scale;
      // Zoom about the initial midpoint, and pan by however far it travelled.
      view.scale = next;
      view.x = mid.x - (startMid.x - startView.x) * ratio;
      view.y = mid.y - (startMid.y - startView.y) * ratio;
      applyView();
      e.preventDefault();
      return;
    }

    if (pointers.size !== 1) return;
    var p = stagePoint(e);
    var dx = p.x - startPt.x, dy = p.y - startPt.y;
    if (!movedFar && Math.hypot(dx, dy) > TAP_SLOP_PX) movedFar = true;

    if (mode === 'item' && drag) {
      if (movedFar && !historyPushed) {
        historyPushed = true;
        if (window.PannerStore) window.PannerStore.pushHistory('تحريك صورة');
      }
      drag.move(toCm(dx), toCm(dy));
      e.preventDefault();
    } else if (mode === 'pan') {
      view.x = startView.x + dx;
      view.y = startView.y + dy;
      applyView();
      e.preventDefault();
    }
  }

  function onPointerUp(e) {
    pointers.delete(e.pointerId);

    if (mode === 'item' && drag) {
      var moved = drag.end();
      drag = null;
      if (moved && window.PannerStore) window.PannerStore.scheduleSave();
    } else if (mode === 'pan' && !movedFar && Date.now() - startTime < TAP_TIME_MS) {
      // A genuine tap on empty felt clears the selection; a tap that was
      // really a short pan leaves it alone.
      var A = app();
      if (A && A.manualMode && pendingIndex < 0) A.selectCell(-1);
    }

    if (pointers.size === 0) {
      mode = null;
      pendingIndex = -1;
    } else if (pointers.size === 1) {
      // Lifting one finger of a pinch: continue as a pan from where we are.
      mode = 'pan';
      startPt = [...pointers.values()][0];
      startView = { x: view.x, y: view.y, scale: view.scale };
      movedFar = true;            // never treat this tail as a tap
    }
  }

  function onPointerCancel(e) {
    pointers.delete(e.pointerId);
    if (drag) { drag.cancel(); drag = null; }
    if (pointers.size === 0) { mode = null; pendingIndex = -1; }
  }

  /* ── Wheel / trackpad, so the desktop browser still works ─── */

  function onWheel(e) {
    if (!e.ctrlKey && !e.metaKey) return;   // plain wheel keeps scrolling
    e.preventDefault();
    var pt = stagePoint(e);
    setScale(view.scale * (e.deltaY < 0 ? 1.1 : 1 / 1.1), pt.x, pt.y);
  }


  /* ── Zoom dock ────────────────────────────────────────────── */

  function buildZoomDock() {
    if (document.querySelector('.zoom-dock')) return;
    var dock = document.createElement('div');
    dock.className = 'zoom-dock';
    dock.innerHTML =
      '<button id="zoomIn" title="تكبير" aria-label="تكبير">＋</button>' +
      '<button id="zoomLevel" class="zoom-level" title="ملاءمة الشاشة">100%</button>' +
      '<button id="zoomOut" title="تصغير" aria-label="تصغير">－</button>';
    document.body.appendChild(dock);
    dock.querySelector('#zoomIn').addEventListener('click', function () { zoomBy(1.15); });
    dock.querySelector('#zoomOut').addEventListener('click', function () { zoomBy(1 / 1.15); });
    dock.querySelector('#zoomLevel').addEventListener('click', fit);
    // The dock floats over the stage; its buttons must not start a pan.
    dock.addEventListener('pointerdown', function (e) { e.stopPropagation(); });
  }

  /* ── Manual toolbar: collapsible, and out of the canvas's way ── */

  function setupManualBar() {
    var bar = document.getElementById('manualBar');
    if (!bar) return;

    var btn = document.createElement('button');
    btn.className = 'mb-collapse';
    btn.id = 'mbCollapse';
    btn.title = 'طيّ الشريط';
    btn.textContent = '▾';
    btn.addEventListener('click', function () {
      var collapsed = bar.classList.toggle('collapsed');
      btn.textContent = collapsed ? '▸' : '▾';
      btn.title = collapsed ? 'إظهار الشريط' : 'طيّ الشريط';
      document.body.classList.toggle('manual-open', !collapsed && bar.classList.contains('on'));
    });
    bar.insertBefore(btn, bar.firstChild);

    // Keep the zoom dock clear of the bar whenever the bar is actually showing.
    var sync = function () {
      document.body.classList.toggle(
        'manual-open',
        bar.classList.contains('on') && !bar.classList.contains('collapsed')
      );
    };
    new MutationObserver(sync).observe(bar, { attributes: true, attributeFilter: ['class'] });
    sync();

    // Taps on the toolbar are toolbar taps, never canvas gestures.
    bar.addEventListener('pointerdown', function (e) { e.stopPropagation(); });
  }

  /* ── Boot ─────────────────────────────────────────────────── */

  function boot() {
    stage = document.querySelector('.stage');
    outer = document.getElementById('sheetOuter');
    sheet = document.getElementById('sheet');
    if (!stage || !outer) return;

    document.body.classList.add('gestures-on');
    buildZoomDock();
    setupManualBar();

    stage.addEventListener('pointerdown', onPointerDown);
    stage.addEventListener('pointermove', onPointerMove, { passive: false });
    stage.addEventListener('pointerup', onPointerUp);
    stage.addEventListener('pointercancel', onPointerCancel);
    stage.addEventListener('pointerleave', onPointerCancel);
    stage.addEventListener('wheel', onWheel, { passive: false });

    // The browser must not claim these gestures for scrolling or its own zoom.
    stage.style.touchAction = 'none';
    stage.style.overflow = 'hidden';

    applyView();
    setTimeout(fit, 300);
    window.addEventListener('orientationchange', function () {
      setTimeout(fit, 300);
    });

    window.PannerView = {
      fit: fit,
      zoomIn: function () { zoomBy(1.15); },
      zoomOut: function () { zoomBy(1 / 1.15); },
      reset: fit,
      get scale() { return view.scale; },
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
