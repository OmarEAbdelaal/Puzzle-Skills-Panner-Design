/* ══════════════════════════════════════════════════════════════
   Puzzle Skills · Panner Design — persistence
   ──────────────────────────────────────────────────────────────
   Nothing is ever lost when the app closes, however it closes.

     • the current project is written back on every change (debounced)
       and restored on launch
     • every movement goes on an undo/redo stack that is itself saved,
       so undo still works after a restart
     • projects can be saved under a name and reopened later

   Storage is IndexedDB, not localStorage. Images the user adds are
   held as data URLs and a handful of photos will blow straight past
   localStorage's few-megabyte quota — which fails by throwing on
   write, i.e. exactly the silent data loss this is here to prevent.
   ══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var DB_NAME = 'puzzle-panner';
  var DB_VERSION = 1;
  var STORE = 'projects';          // saved + autosaved project states
  var META = 'meta';               // undo/redo stack, last-opened marker
  var BLOBS = 'images';            // photo pixels, written once per image id

  var CURRENT_KEY = '__current__';
  var HISTORY_KEY = '__history__';
  var AUTOSAVE_MS = 900;
  var HISTORY_LIMIT = 120;

  var dbPromise = null;
  var saveTimer = 0;
  var ready = false;               // becomes true once a restore has been tried
  var history = { past: [], future: [] };

  function app() { return window.PannerApp; }

  /* ── IndexedDB plumbing ───────────────────────────────────── */

  function openDb() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise(function (resolve, reject) {
      var req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = function () {
        var db = req.result;
        if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
        if (!db.objectStoreNames.contains(META)) db.createObjectStore(META);
        if (!db.objectStoreNames.contains(BLOBS)) db.createObjectStore(BLOBS);
      };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    }).catch(function (e) {
      console.warn('IndexedDB unavailable', e);
      return null;
    });
    return dbPromise;
  }

  function tx(storeName, mode, fn) {
    return openDb().then(function (db) {
      if (!db) return null;
      return new Promise(function (resolve, reject) {
        var t = db.transaction(storeName, mode);
        var req = fn(t.objectStore(storeName));
        t.oncomplete = function () { resolve(req ? req.result : null); };
        t.onerror = function () { reject(t.error); };
        t.onabort = function () { reject(t.error); };
      });
    }).catch(function (e) {
      console.warn('store ' + mode + ' failed', e);
      return null;
    });
  }

  var put = function (store, key, value) {
    return tx(store, 'readwrite', function (os) { return os.put(value, key); });
  };
  var get = function (store, key) {
    return tx(store, 'readonly', function (os) { return os.get(key); });
  };
  var del = function (store, key) {
    return tx(store, 'readwrite', function (os) { return os.delete(key); });
  };
  var allKeys = function (store) {
    return tx(store, 'readonly', function (os) { return os.getAllKeys(); });
  };

  /* ── Photo pixels, stored once and shared by every state ──
     A state records images by id only. The pixels live here, written the
     first time an image is seen, so an undo stack or a shelf of saved
     projects costs kilobytes rather than a copy of every photograph. */

  var srcCache = Object.create(null);   // id → data URL, for fast rehydration

  function resolveSrc(id) { return srcCache[id] || null; }

  /** Writes any photo we have not stored yet. */
  function syncBlobs() {
    var A = app();
    if (!A) return Promise.resolve();
    var inline = A.inlineImages();
    var pending = [];
    for (var id in inline) {
      if (srcCache[id] === inline[id]) continue;
      srcCache[id] = inline[id];
      pending.push(put(BLOBS, id, inline[id]));
    }
    return Promise.all(pending);
  }

  function loadBlobs() {
    return tx(BLOBS, 'readonly', function (os) { return os.getAllKeys(); })
      .then(function (keys) {
        if (!keys || !keys.length) return null;
        return Promise.all(keys.map(function (k) {
          return get(BLOBS, k).then(function (v) { if (v) srcCache[k] = v; });
        }));
      });
  }

  /**
   * Deletes photo data no live state refers to any more, so deleting a
   * project eventually reclaims its photos too.
   */
  function collectGarbage() {
    var live = Object.create(null);
    var mark = function (st) {
      if (st && Array.isArray(st.images)) {
        st.images.forEach(function (i) { if (i && i.id) live[i.id] = true; });
      }
    };
    history.past.forEach(function (h) { mark(h.state); });
    history.future.forEach(function (h) { mark(h.state); });

    return allKeys(STORE).then(function (keys) {
      return Promise.all((keys || []).map(function (k) {
        return get(STORE, k).then(mark);
      }));
    }).then(function () {
      return tx(BLOBS, 'readonly', function (os) { return os.getAllKeys(); });
    }).then(function (blobKeys) {
      var dead = (blobKeys || []).filter(function (k) { return !live[k]; });
      dead.forEach(function (k) { delete srcCache[k]; });
      return Promise.all(dead.map(function (k) { return del(BLOBS, k); }));
    }).catch(function (e) {
      console.warn('gc skipped', e);
    });
  }

  /* ── Autosave ─────────────────────────────────────────────── */

  function snapshot() {
    var A = app();
    return A ? A.captureState({ light: true }) : null;
  }

  function writeCurrent() {
    var st = snapshot();
    if (!st) return Promise.resolve();
    st.savedAt = Date.now();
    return syncBlobs().then(function () { return put(STORE, CURRENT_KEY, st); });
  }

  /** Debounced: called on every edit, writes at most once per AUTOSAVE_MS. */
  function scheduleSave() {
    if (!ready) return;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(writeCurrent, AUTOSAVE_MS);
  }

  /** Writes immediately — for when the app may be about to disappear. */
  function flush() {
    if (!ready) return Promise.resolve();
    clearTimeout(saveTimer);
    return writeCurrent();
  }

  /* ── Undo / redo ──────────────────────────────────────────── */

  /**
   * Records the state *before* a change, so undo can return to it.
   * Call it just before mutating, not after.
   */
  function pushHistory(label) {
    if (!ready) return;
    var st = snapshot();
    if (!st) return;
    history.past.push({ label: label || '', state: st });
    if (history.past.length > HISTORY_LIMIT) history.past.shift();
    history.future.length = 0;          // a new edit invalidates the redo path
    syncBlobs();
    persistHistory();
    updateButtons();
  }

  var histTimer = 0;

  /**
   * Persists the stack so undo still works after a restart. Entries reference
   * photos by id, so this stays small; it is debounced anyway because a busy
   * editing session pushes entries faster than it is worth writing them.
   */
  function persistHistory() {
    clearTimeout(histTimer);
    histTimer = setTimeout(function () {
      put(META, HISTORY_KEY, {
        past: history.past.slice(-40),
        future: history.future.slice(0, 40),
      });
    }, 400);
  }

  function undo() {
    if (!history.past.length) return false;
    var current = snapshot();
    var entry = history.past.pop();
    if (current) history.future.unshift({ label: entry.label, state: current });
    app().applyState(entry.state, resolveSrc);
    persistHistory();
    updateButtons();
    flush();
    return true;
  }

  function redo() {
    if (!history.future.length) return false;
    var current = snapshot();
    var entry = history.future.shift();
    if (current) history.past.push({ label: entry.label, state: current });
    app().applyState(entry.state, resolveSrc);
    persistHistory();
    updateButtons();
    flush();
    return true;
  }

  function updateButtons() {
    var u = document.getElementById('mbUndo');
    var r = document.getElementById('mbRedo');
    if (u) u.disabled = !history.past.length;
    if (r) r.disabled = !history.future.length;
  }

  /* ── Named projects ───────────────────────────────────────── */

  function projectKey(name) { return 'p:' + name; }

  function saveProject(name) {
    var st = snapshot();
    if (!st) return Promise.resolve(false);
    st.savedAt = Date.now();
    st.name = name;
    return syncBlobs()
      .then(function () { return put(STORE, projectKey(name), st); })
      .then(function () { return true; });
  }

  function listProjects() {
    return allKeys(STORE).then(function (keys) {
      if (!keys) return [];
      var names = keys
        .filter(function (k) { return typeof k === 'string' && k.indexOf('p:') === 0; })
        .map(function (k) { return k.slice(2); });
      return Promise.all(names.map(function (n) {
        return get(STORE, projectKey(n)).then(function (st) {
          return {
            name: n,
            savedAt: st ? st.savedAt : 0,
            images: st && st.images ? st.images.length : 0,
          };
        });
      }));
    }).then(function (list) {
      return list.sort(function (a, b) { return b.savedAt - a.savedAt; });
    });
  }

  function openProject(name) {
    return get(STORE, projectKey(name)).then(function (st) {
      if (!st) return false;
      pushHistory('فتح مشروع');
      app().applyState(st, resolveSrc);
      flush();
      return true;
    });
  }

  function deleteProject(name) {
    return del(STORE, projectKey(name))
      .then(collectGarbage)
      .then(function () { return true; });
  }

  /* ── Restore on launch ────────────────────────────────────── */

  function restore() {
    return loadBlobs()
      .then(function () {
        return Promise.all([get(STORE, CURRENT_KEY), get(META, HISTORY_KEY)]);
      })
      .then(function (res) {
        var st = res[0], hist = res[1];
        if (hist && Array.isArray(hist.past)) {
          history.past = hist.past;
          history.future = Array.isArray(hist.future) ? hist.future : [];
        }
        var restored = false;
        if (st && app()) restored = app().applyState(st, resolveSrc);
        ready = true;
        updateButtons();
        // Idle cleanup, and only after the current state is on disk — otherwise
        // photos added since launch would be swept before anything referenced them.
        setTimeout(function () { flush().then(collectGarbage); }, 5000);
        return restored;
      })
      .catch(function (e) {
        console.warn('restore failed', e);
        ready = true;
        return false;
      });
  }

  /* ── Watching for changes ─────────────────────────────────── */

  function watch() {
    // Any control that alters the layout also alters what we should be saving.
    var ids = ['fabricW', 'imgW', 'gap', 'edgeAll', 'edgeTop', 'edgeBottom',
               'edgeLeft', 'edgeRight', 'repeat', 'bannerRepeat', 'sat',
               'order', 'exportDPI', 'fillMin'];
    ids.forEach(function (id) {
      var n = document.getElementById(id);
      if (!n) return;
      n.addEventListener('change', function () { pushHistory('إعداد'); scheduleSave(); });
      n.addEventListener('input', scheduleSave);
    });

    ['guidesBtn', 'rotateBtn', 'splitBtn', 'fillBtn', 'sizeMode', 'edgeModeBtn',
     'manualBtn', 'manualResetBtn', 'mbFront', 'mbBack', 'mbRot', 'mbDup',
     'mbDel', 'mbFillNow'].forEach(function (id) {
      var n = document.getElementById(id);
      if (!n) return;
      // Capture phase: record the state before the button's own handler runs.
      n.addEventListener('click', function () { pushHistory(id); }, true);
      n.addEventListener('click', scheduleSave);
    });

    var sw = document.getElementById('swatches');
    if (sw) {
      sw.addEventListener('click', function () { pushHistory('لون'); }, true);
      sw.addEventListener('click', scheduleSave);
    }

    ['mbW', 'mbH'].forEach(function (id) {
      var n = document.getElementById(id);
      if (!n) return;
      n.addEventListener('focus', function () { pushHistory('مقاس'); });
      n.addEventListener('change', scheduleSave);
    });

    // Last chance to write before the app goes away. pagehide covers the
    // Android case where the activity is destroyed without a clean unload.
    window.addEventListener('pagehide', flush);
    window.addEventListener('beforeunload', flush);
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') flush();
    });
  }

  /* ── Public surface ───────────────────────────────────────── */

  window.PannerStore = {
    restore: restore,
    flush: flush,
    scheduleSave: scheduleSave,
    pushHistory: pushHistory,
    undo: undo,
    redo: redo,
    canUndo: function () { return history.past.length > 0; },
    canRedo: function () { return history.future.length > 0; },
    saveProject: saveProject,
    listProjects: listProjects,
    openProject: openProject,
    deleteProject: deleteProject,
    updateButtons: updateButtons,
  };

  function boot() {
    watch();
    restore();

    var u = document.getElementById('mbUndo');
    var r = document.getElementById('mbRedo');
    if (u) u.addEventListener('click', undo);
    if (r) r.addEventListener('click', redo);
    updateButtons();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
