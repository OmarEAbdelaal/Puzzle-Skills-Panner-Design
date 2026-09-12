/* ══════════════════════════════════════════════════════════════
   Puzzle Skills · Panner Design — Android app layer
   ──────────────────────────────────────────────────────────────
   Bridges the shared web UI to the native shell:
     • streams exported PNGs straight into the phone gallery
     • in-app update check / download / install
     • phone-sized ergonomics (zoom dock, grouped settings, toasts)

   Every feature degrades gracefully: opened in a plain browser
   this file adds nothing and the page behaves exactly as before.
   ══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var N = window.PannerNative;          // injected by the native shell
  var inApp = !!(N && N.appVersion);
  var Host = (window.PannerHost = {});

  if (!inApp) return;                   // plain browser → nothing to do
  document.body.classList.add('in-app');

  /* ─────────────────────────────────────────────────────────
     Small helpers
     ───────────────────────────────────────────────────────── */
  function el(id) { return document.getElementById(id); }

  function safe(fn, fallback) {
    try { return fn(); } catch (e) { console.warn(e); return fallback; }
  }

  var toastEl = null, toastTimer = 0;
  function toast(msg, ms) {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.className = 'app-toast';
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('show'); }, ms || 2600);
  }
  Host.toast = toast;

  /* ─────────────────────────────────────────────────────────
     1. Export → device gallery
     The web engine hands us a PNG Blob per band. We stream it to
     the native side in 3-byte-aligned base64 chunks, so each chunk
     decodes on its own and memory stays flat even for a 300 DPI
     banner several metres long.
     ───────────────────────────────────────────────────────── */
  var CHUNK_BYTES = 512 * 1024;               // → ~700 KB of base64 per hop
  var STEP = CHUNK_BYTES - (CHUNK_BYTES % 3); // keep chunks self-contained

  function encodeChunk(bytes) {
    var bin = '', SUB = 0x8000;
    for (var i = 0; i < bytes.length; i += SUB) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + SUB));
    }
    return btoa(bin);
  }

  /* Blob.arrayBuffer() is missing on WebView builds older than 76. */
  function readBlob(blob) {
    if (blob.arrayBuffer) return blob.arrayBuffer();
    return new Promise(function (resolve, reject) {
      var fr = new FileReader();
      fr.onload = function () { resolve(fr.result); };
      fr.onerror = function () { reject(fr.error); };
      fr.readAsArrayBuffer(blob);
    });
  }

  Host.saveBlob = function (blob, fileName) {
    return new Promise(function (resolve) {
      if (!N.beginSave(fileName)) {
        toast('تعذّر بدء الحفظ — تحقّق من مساحة التخزين');
        resolve(false);
        return;
      }
      readBlob(blob).then(function (buf) {
        var bytes = new Uint8Array(buf), off = 0;

        function pump() {
          try {
            if (off >= bytes.length) {
              var saved = N.endSave();
              if (saved) {
                toast('✔ تم الحفظ في معرض الصور · ' + saved);
                resolve(true);
              } else {
                toast('تعذّر حفظ الملف');
                resolve(false);
              }
              return;
            }
            var slice = bytes.subarray(off, Math.min(off + STEP, bytes.length));
            if (!N.appendSave(encodeChunk(slice))) {
              N.abortSave();
              toast('انقطع الحفظ — قد تكون المساحة ممتلئة');
              resolve(false);
              return;
            }
            off += slice.length;
            setTimeout(pump, 0);           // yield: keep the UI responsive
          } catch (e) {
            console.error(e);
            safe(function () { N.abortSave(); });
            toast('خطأ أثناء الحفظ');
            resolve(false);
          }
        }
        pump();
      }).catch(function (e) {
        console.error(e);
        safe(function () { N.abortSave(); });
        toast('تعذّر قراءة الصورة المصدَّرة');
        resolve(false);
      });
    });
  };

  /* ─────────────────────────────────────────────────────────
     2. Settings drawer: group the controls so a phone screen
        stays scannable instead of being one long grid.
     ───────────────────────────────────────────────────────── */
  function groupDrawer() {
    var row = document.querySelector('.ctrls-drawer .ctrl-row');
    if (!row) return;

    // anchor control → heading inserted before it
    var groups = [
      ['fabricW', '📐 المقاس'],
      ['repeat', '🔁 التكرار'],
      ['sat', '🎨 المظهر'],
      ['rotateBtn', '⚙️ التعبئة الذكية'],
      ['exportDPI', '⬇ التصدير']
    ];
    groups.forEach(function (g) {
      var anchor = el(g[0]);
      if (!anchor) return;
      var cell = anchor.closest('.ctrl');
      if (!cell || !cell.parentNode) return;
      var h = document.createElement('div');
      h.className = 'ctrl-group-title';
      h.textContent = g[1];
      cell.parentNode.insertBefore(h, cell);
    });
  }

  /* ─────────────────────────────────────────────────────────
     3. Zoom dock — pinch is native, these are the precise controls
     ───────────────────────────────────────────────────────── */
  var zoom = 1;
  function applyZoom() {
    var outer = el('sheetOuter');
    if (outer) outer.style.transform = 'scale(' + zoom + ')';
    var lbl = el('zoomLevel');
    if (lbl) lbl.textContent = Math.round(zoom * 100) + '%';
  }
  function setZoom(z) {
    zoom = Math.min(3, Math.max(0.2, Math.round(z * 20) / 20));
    applyZoom();
  }
  function fitToScreen() {
    var outer = el('sheetOuter'), stage = document.querySelector('.stage');
    if (!outer || !stage) return;
    var prev = outer.style.transform;
    outer.style.transform = 'none';
    var w = outer.offsetWidth, h = outer.offsetHeight;
    outer.style.transform = prev;
    if (!w || !h) return;
    var availW = stage.clientWidth - 24;
    var availH = stage.clientHeight - 24;
    setZoom(Math.min(availW / w, availH / h, 1));
  }

  function buildZoomDock() {
    var dock = document.createElement('div');
    dock.className = 'zoom-dock';
    dock.innerHTML =
      '<button id="zoomIn"  title="تكبير"  aria-label="تكبير">＋</button>' +
      '<button id="zoomLevel" class="zoom-level" title="اضغط للملاءمة">100%</button>' +
      '<button id="zoomOut" title="تصغير" aria-label="تصغير">－</button>';
    document.body.appendChild(dock);
    el('zoomIn').addEventListener('click', function () { setZoom(zoom + 0.1); });
    el('zoomOut').addEventListener('click', function () { setZoom(zoom - 0.1); });
    el('zoomLevel').addEventListener('click', fitToScreen);
  }

  /* ─────────────────────────────────────────────────────────
     4. App menu (bottom sheet): updates, exports, layout reset
     ───────────────────────────────────────────────────────── */
  var backdrop, sheet, pendingUpdate = null;

  function openSheet() {
    refreshSheet();
    backdrop.classList.add('open');
    sheet.classList.add('open');
  }
  function closeSheet() {
    backdrop.classList.remove('open');
    sheet.classList.remove('open');
  }

  function buildMenu() {
    var bar = document.querySelector('.bar-inner');
    var toggle = el('ctrlsToggle');
    if (!bar) return;

    var btn = document.createElement('button');
    btn.className = 'app-menu-btn';
    btn.id = 'appMenuBtn';
    btn.title = 'قائمة التطبيق';
    btn.setAttribute('aria-label', 'قائمة التطبيق');
    btn.innerHTML = '⋮';
    if (toggle && toggle.parentNode === bar) bar.insertBefore(btn, toggle);
    else bar.appendChild(btn);

    backdrop = document.createElement('div');
    backdrop.className = 'sheet-backdrop';
    document.body.appendChild(backdrop);

    sheet = document.createElement('div');
    sheet.className = 'app-sheet';
    sheet.innerHTML =
      '<div class="grip"></div>' +
      '<h3>Puzzle Skills · تصميم بانر الجوخ</h3>' +
      '<p class="sub">تصميم: إسراء عبد الظاهر</p>' +
      '<button class="sheet-item" id="miUpdate">' +
        '<span class="ico">⬆️</span><span class="txt">التحقق من التحديثات' +
        '<small id="miUpdateSub">الإصدار الحالي —</small></span></button>' +
      '<button class="sheet-item" id="miExports">' +
        '<span class="ico">🖼️</span><span class="txt">فتح مجلد التصديرات' +
        '<small>Pictures / PuzzleSkills</small></span></button>' +
      '<button class="sheet-item" id="miShare">' +
        '<span class="ico">📤</span><span class="txt">مشاركة آخر تصدير' +
        '<small>إرسال ملف PNG الأخير</small></span></button>' +
      '<button class="sheet-item" id="miResetLayout">' +
        '<span class="ico">↺</span><span class="txt">إعادة ضبط التوزيع اليدوي' +
        '<small>حذف الترتيب المحفوظ والعودة للتوزيع التلقائي</small></span></button>' +
      '<button class="sheet-item" id="miRepo">' +
        '<span class="ico">🌐</span><span class="txt">صفحة المشروع على GitHub' +
        '<small>المصدر والإصدارات</small></span></button>' +
      '<p class="version-line" id="verLine"></p>';
    document.body.appendChild(sheet);

    btn.addEventListener('click', openSheet);
    backdrop.addEventListener('click', closeSheet);

    el('miUpdate').addEventListener('click', function () {
      if (pendingUpdate) { startDownload(); return; }
      el('miUpdateSub').textContent = 'جارٍ البحث…';
      safe(function () { N.checkForUpdates(true); });
    });
    el('miExports').addEventListener('click', function () {
      closeSheet();
      safe(function () { N.openExportsFolder(); });
    });
    el('miShare').addEventListener('click', function () {
      closeSheet();
      safe(function () { N.shareLastExport(); });
    });
    el('miResetLayout').addEventListener('click', function () {
      safe(function () { localStorage.removeItem('felt-banner-manual-v6'); });
      closeSheet();
      toast('تمت إعادة الضبط — سيُعاد تشغيل التطبيق');
      setTimeout(function () { location.reload(); }, 700);
    });
    el('miRepo').addEventListener('click', function () {
      closeSheet();
      safe(function () { N.openRepo(); });
    });
  }

  function refreshSheet() {
    var v = safe(function () { return N.appVersion(); }, '—');
    var line = el('verLine');
    if (line) line.textContent = 'الإصدار ' + v;
    var sub = el('miUpdateSub');
    if (!sub) return;
    if (pendingUpdate) {
      sub.textContent = 'يتوفّر إصدار ' + pendingUpdate.version + ' — اضغط للتنزيل';
      el('miUpdate').classList.add('accent');
    } else {
      sub.textContent = 'الإصدار الحالي ' + v;
      el('miUpdate').classList.remove('accent');
    }
  }

  function startDownload() {
    if (!pendingUpdate) return;
    closeSheet();
    toast('جارٍ تنزيل التحديث…');
    safe(function () { N.downloadUpdate(pendingUpdate.url, pendingUpdate.version); });
  }

  /* Called from Kotlin once a release check finishes. */
  Host.onUpdateResult = function (json) {
    var r = safe(function () { return JSON.parse(json); }, null);
    if (!r) return;

    if (r.error) {
      if (r.userInitiated) toast('تعذّر التحقق من التحديثات — تحقّق من الاتصال');
      var s0 = el('miUpdateSub');
      if (s0) refreshSheet();
      return;
    }

    if (r.available) {
      pendingUpdate = { version: r.version, url: r.url, notes: r.notes || '' };
      var b = el('appMenuBtn');
      if (b && !b.querySelector('.update-badge')) {
        var dot = document.createElement('span');
        dot.className = 'update-badge';
        b.appendChild(dot);
      }
      refreshSheet();
      if (r.userInitiated) openSheet();
      else toast('يتوفّر تحديث جديد ' + r.version + ' — من قائمة ⋮');
    } else {
      pendingUpdate = null;
      refreshSheet();
      if (r.userInitiated) toast('أنت على أحدث إصدار ✔');
    }
  };

  /* Called from Kotlin while the APK downloads. */
  Host.onUpdateProgress = function (pct) {
    toast('تنزيل التحديث… ' + pct + '%', 1200);
  };

  /* ─────────────────────────────────────────────────────────
     5. Boot
     ───────────────────────────────────────────────────────── */
  function boot() {
    groupDrawer();
    buildZoomDock();
    buildMenu();
    refreshSheet();

    // The stage starts fitted so the whole banner is visible at a glance.
    setTimeout(fitToScreen, 350);
    window.addEventListener('orientationchange', function () {
      setTimeout(fitToScreen, 350);
    });

    // Android back button closes whatever is open before leaving the app.
    Host.onBackPressed = function () {
      if (sheet && sheet.classList.contains('open')) { closeSheet(); return true; }
      var drawer = el('ctrlsDrawer');
      if (drawer && drawer.classList.contains('open')) {
        drawer.classList.remove('open');
        var t = el('ctrlsToggle');
        if (t) t.classList.remove('open');
        return true;
      }
      return false;                       // let the shell handle it
    };

    // Silent daily check so the update badge is there when it matters.
    setTimeout(function () { safe(function () { N.checkForUpdates(false); }); }, 2500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
