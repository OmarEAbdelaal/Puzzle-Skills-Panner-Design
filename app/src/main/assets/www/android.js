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
     3. Adding photos

     A hidden <input type="file"> inside a <label> is unreliable in a
     WebView — the chooser often never opens. Inside the app we bypass
     the input entirely and call Android's photo picker, which also lets
     the native side downscale each photo before handing it over. In a
     browser the original input still does the job.
     ───────────────────────────────────────────────────────── */

  function wireNativePicker() {
    if (!N.pickImages) return;

    var input = el('fileInput');
    var label = input ? input.closest('label') : null;
    var target = label || document.querySelector('.add-btn');
    if (!target) return;

    // Stop the label from trying to open the (unreliable) file input at all.
    if (input) input.disabled = true;

    target.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      toast('جارٍ فتح معرض الصور…', 1400);
      safe(function () { N.pickImages(); });
    }, true);
  }

  /*
   * Photos arrive one at a time rather than as one batch — thirty photos in a
   * single JSON string would be tens of megabytes crossing the bridge at once.
   * The first one records an undo point; the rest join the same entry.
   */
  var pickBatch = 0;

  /** @param json one image: {src: dataUrl, w, h, name} */
  Host.onImagePicked = function (json) {
    var item = safe(function () { return JSON.parse(json); }, null);
    var A = window.PannerApp;
    if (!item || !A) return;
    if (pickBatch === 0 && window.PannerStore) {
      window.PannerStore.pushHistory('إضافة صور');
    }
    pickBatch += A.addImages([item]);
  };

  /** @param countStr how many the native side managed to decode */
  Host.onPickDone = function (countStr) {
    var n = pickBatch;
    pickBatch = 0;
    var attempted = parseInt(countStr, 10) || 0;
    if (window.PannerStore) window.PannerStore.scheduleSave();
    if (!n) {
      toast(attempted ? 'تعذّرت قراءة الصور المختارة' : 'لم يتم اختيار صور');
      return;
    }
    toast(n === 1 ? '✔ تمت إضافة صورة' : '✔ تمت إضافة ' + n + ' صور');
  };

  Host.onPickFailed = function (msg) {
    toast(msg || 'تعذّر فتح معرض الصور');
  };

  /* ─────────────────────────────────────────────────────────
     4. App menu (bottom sheet): projects, updates, exports
     ───────────────────────────────────────────────────────── */
  var backdrop, sheet, pendingUpdate = null;

  function openSheet() {
    refreshSheet();
    renderProjects();
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
      '<button class="sheet-item accent" id="miSaveProject">' +
        '<span class="ico">💾</span><span class="txt">حفظ نسخة باسم' +
        '<small>احتفظ بالتصميم الحالي لتفتحه لاحقاً</small></span></button>' +
      '<div id="projList" class="proj-list"></div>' +
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
      var A = window.PannerApp;
      if (window.PannerStore) window.PannerStore.pushHistory('إعادة ضبط');
      safe(function () { localStorage.removeItem('felt-banner-manual-v6'); });
      if (A) A.enterManual();
      if (window.PannerStore) window.PannerStore.flush();
      closeSheet();
      toast('تمت إعادة التوزيع التلقائي — يمكنك التراجع بـ ↶');
    });

    el('miSaveProject').addEventListener('click', saveProjectFlow);
    el('miRepo').addEventListener('click', function () {
      closeSheet();
      safe(function () { N.openRepo(); });
    });
  }


  /* ── Saved projects ───────────────────────────────────────── */

  function defaultProjectName() {
    var d = new Date();
    var two = function (n) { return (n < 10 ? '0' : '') + n; };
    return 'بانر ' + d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) +
           ' ' + two(d.getHours()) + two(d.getMinutes());
  }

  function saveProjectFlow() {
    if (!window.PannerStore) return;
    var name = prompt('اسم النسخة المحفوظة:', defaultProjectName());
    if (name === null) return;
    name = String(name).trim().slice(0, 60);
    if (!name) return;
    window.PannerStore.saveProject(name).then(function (ok) {
      toast(ok ? '✔ تم حفظ «' + name + '»' : 'تعذّر الحفظ');
      renderProjects();
    });
  }

  function formatWhen(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    var two = function (n) { return (n < 10 ? '0' : '') + n; };
    return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) +
           ' · ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }

  function renderProjects() {
    var box = el('projList');
    if (!box || !window.PannerStore) return;
    window.PannerStore.listProjects().then(function (list) {
      box.innerHTML = '';
      if (!list.length) {
        var empty = document.createElement('p');
        empty.className = 'proj-empty';
        empty.textContent = 'لا توجد نسخ محفوظة بعد — عملك الحالي محفوظ تلقائياً على أي حال.';
        box.appendChild(empty);
        return;
      }
      list.forEach(function (proj) {
        var row = document.createElement('div');
        row.className = 'proj-row';

        var open = document.createElement('button');
        open.className = 'proj-open';
        open.innerHTML = '';
        open.appendChild(document.createTextNode(proj.name));
        var meta = document.createElement('small');
        meta.textContent = formatWhen(proj.savedAt) + ' · ' + proj.images + ' صورة';
        open.appendChild(meta);
        open.addEventListener('click', function () {
          window.PannerStore.openProject(proj.name).then(function (ok) {
            closeSheet();
            toast(ok ? 'تم فتح «' + proj.name + '»' : 'تعذّر الفتح');
          });
        });

        var delBtn = document.createElement('button');
        delBtn.className = 'proj-del';
        delBtn.title = 'حذف';
        delBtn.textContent = '🗑';
        delBtn.addEventListener('click', function () {
          if (!confirm('حذف «' + proj.name + '»؟')) return;
          window.PannerStore.deleteProject(proj.name).then(renderProjects);
        });

        row.appendChild(open);
        row.appendChild(delBtn);
        box.appendChild(row);
      });
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
    buildMenu();
    wireNativePicker();
    refreshSheet();

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
