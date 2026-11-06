/**
 * HaNiZso Raktár – Google Apps Script háttér
 *
 * A táblázathoz kötött szkript. Web appként közzétéve az Android app ezen
 * keresztül tölti le a készletet és küldi fel a csomagolás közbeni mozgásokat.
 *
 * Lapok:
 *   Termékek  – egy termék = egy sor, a "Készlet" oszlop a valódi készlet
 *   Mozgások  – napló: minden kiadás, bevét, korrekció, import
 *   Mappák    – a létrehozott mappák (pl. "Mosószer/Mosógél"), az üresek is
 *   Import    – ide kell bemásolni a StoDo Excel-exportját (fejléccel)
 *
 * A termékfotók a Drive "HaNiZso Raktár képek" mappájába kerülnek.
 *
 * A titkos kulcs a Script Properties "API_KEY" értéke (menü: Raktár → API kulcs).
 */

var SHEET_PRODUCTS = 'Termékek';
var SHEET_MOVES = 'Mozgások';
var SHEET_IMPORT = 'Import';
var SHEET_FOLDERS = 'Mappák';
var SHEET_IMG_IMPORT = 'KépImport';
var PHOTO_FOLDER_NAME = 'HaNiZso Raktár képek';

var P_HEAD = ['ID', 'EAN', 'Cikkszám', 'Megnevezés', 'Készlet', 'Minimum',
  'Beszerzési ár', 'Eladási ár', 'Leírás', 'Módosítva', 'Módosította', 'Mappa', 'Kép'];
var M_HEAD = ['Időpont', 'Mozgás ID', 'Termék ID', 'EAN', 'Megnevezés',
  'Változás', 'Típus', 'Készlet utána', 'Eszköz'];

// Termékek oszlopindexek (0-tól)
var P = { id: 0, ean: 1, sku: 2, name: 3, qty: 4, min: 5, buy: 6, sell: 7, note: 8, mod: 9, modBy: 10, cat: 11, photo: 12 };
// Mozgások oszlopindexek
var M = { at: 0, id: 1, pid: 2, ean: 3, name: 4, delta: 5, type: 6, after: 7, device: 8 };

var LOG_DAYS_DEFAULT = 90;

/* ------------------------------------------------------------------ */
/* Menü                                                               */
/* ------------------------------------------------------------------ */

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Raktár')
    .addItem('Táblázat előkészítése', 'menuSetup')
    .addItem('StoDo import az Import lapról', 'menuImport')
    .addItem('StoDo képek importálása (KépImport lap)', 'menuImportImages')
    .addSeparator()
    .addItem('API kulcs megjelenítése', 'menuShowKey')
    .addItem('Új API kulcs generálása', 'menuNewKey')
    .addToUi();
}

function menuSetup() {
  setup_();
  SpreadsheetApp.getUi().alert('Kész. A Termékek, Mozgások és Import lapok elő vannak készítve.');
}

function menuShowKey() {
  var key = PropertiesService.getScriptProperties().getProperty('API_KEY');
  if (!key) key = newKey_();
  SpreadsheetApp.getUi().alert('Az app API kulcsa (ezt kell beírni az app Beállításaiba):\n\n' + key);
}

function menuNewKey() {
  var ui = SpreadsheetApp.getUi();
  var r = ui.alert('Új kulcs', 'Új kulcs generálása után a telefonon is át kell írni a kulcsot. Folytatod?', ui.ButtonSet.YES_NO);
  if (r !== ui.Button.YES) return;
  ui.alert('Az új API kulcs:\n\n' + newKey_());
}

function newKey_() {
  var key = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '').slice(0, 8);
  PropertiesService.getScriptProperties().setProperty('API_KEY', key);
  return key;
}

function setup_() {
  var ss = SpreadsheetApp.getActive();
  ss.setSpreadsheetTimeZone('Europe/Budapest');
  var p = ensureSheet_(ss, SHEET_PRODUCTS, P_HEAD);
  p.getRange('B:C').setNumberFormat('@');           // EAN és cikkszám szövegként (vezető nullák!)
  p.getRange('J:J').setNumberFormat('yyyy.mm.dd hh:mm');
  var m = ensureSheet_(ss, SHEET_MOVES, M_HEAD);
  m.getRange('A:A').setNumberFormat('yyyy.mm.dd hh:mm:ss');
  m.getRange('D:D').setNumberFormat('@');
  ensureSheet_(ss, SHEET_FOLDERS, ['Mappa']);
  ensureSheet_(ss, SHEET_IMPORT, null);
  var first = ss.getSheets()[0];
  if (first.getName() === 'Munkalap1' || first.getName() === 'Sheet1') {
    if (first.getLastRow() === 0) ss.deleteSheet(first);
  }
}

function ensureSheet_(ss, name, head) {
  var sh = ss.getSheetByName(name);
  if (!sh) sh = ss.insertSheet(name);
  if (head && sh.getLastRow() === 0) {
    sh.getRange(1, 1, 1, head.length).setValues([head]).setFontWeight('bold');
    sh.setFrozenRows(1);
  } else if (head) {
    // régebbi verzióból maradt fejléc bővítése (új oszlopok a végén)
    var cur = sh.getRange(1, 1, 1, head.length).getValues()[0];
    if (cur.join('|') !== head.join('|')) sh.getRange(1, 1, 1, head.length).setValues([head]).setFontWeight('bold');
  }
  return sh;
}

/* ------------------------------------------------------------------ */
/* Web app                                                            */
/* ------------------------------------------------------------------ */

function doGet() {
  return json_({ ok: true, app: 'HaNiZso Raktár', time: Date.now() });
}

function doPost(e) {
  var req;
  try {
    req = JSON.parse(e.postData.contents);
  } catch (err) {
    return json_({ ok: false, error: 'bad_json' });
  }
  var key = PropertiesService.getScriptProperties().getProperty('API_KEY');
  if (!key || req.key !== key) return json_({ ok: false, error: 'unauthorized' });

  try {
    if (req.action === 'pull') return json_(pull_(req));
    if (req.action === 'push') return json_(push_(req));
    if (req.action === 'log') return json_(log_(req));
    if (req.action === 'photo') return json_(photo_(req));
    if (req.action === 'photoGet') return json_(photoGet_(req));
    if (req.action === 'ping') return json_({ ok: true, time: Date.now() });
    return json_({ ok: false, error: 'unknown_action' });
  } catch (err) {
    return json_({ ok: false, error: 'server_error', message: String(err && err.message || err) });
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

/* ------------------------------------------------------------------ */
/* Műveletek                                                          */
/* ------------------------------------------------------------------ */

function pull_(req) {
  setupIfNeeded_();
  var days = Number(req.logDays) || LOG_DAYS_DEFAULT;
  return {
    ok: true,
    time: Date.now(),
    products: readProducts_().map(productToJson_),
    folders: readFolders_(),
    moves: readMoves_(Date.now() - days * 864e5, null, null)
  };
}

function log_(req) {
  setupIfNeeded_();
  var from = Number(req.from) || 0;
  var to = req.to ? Number(req.to) : null;
  return { ok: true, moves: readMoves_(from, to, req.productId || null) };
}

/**
 * Csomag feltöltése.
 * req = { device, products:[{id,ean,sku,name,minQty,buyPrice,sellPrice,note}],
 *         deletes:[productId], moves:[{id,productId,ean,name,delta,type,at}] }
 * A mozgások a készlethez HOZZÁADÓDNAK; a már feldolgozott mozgás ID-t kihagyja.
 */
function push_(req) {
  setupIfNeeded_();
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) return { ok: false, error: 'busy' };
  try {
    var ss = SpreadsheetApp.getActive();
    var ps = ss.getSheetByName(SHEET_PRODUCTS);
    var ms = ss.getSheetByName(SHEET_MOVES);
    var device = String(req.device || '').slice(0, 60);
    var now = new Date();

    // Termékek beolvasása
    var last = ps.getLastRow();
    var rows = last > 1 ? ps.getRange(2, 1, last - 1, P_HEAD.length).getValues() : [];
    var byId = {}, byEan = {};
    rows.forEach(function (r, i) {
      if (r[P.id]) byId[String(r[P.id])] = i;
      if (r[P.ean]) byEan[normEan_(r[P.ean])] = i;
    });

    // 1. Termék-módosítások és új termékek
    var created = 0, updated = 0;
    (req.products || []).forEach(function (p) {
      if (!p || !p.id || !p.name) return;
      var idx = byId[String(p.id)];
      if (idx == null) {
        var row = new Array(P_HEAD.length).fill('');
        row[P.id] = String(p.id);
        row[P.qty] = 0;
        rows.push(row);
        idx = rows.length - 1;
        byId[String(p.id)] = idx;
        created++;
      } else {
        updated++;
      }
      var r = rows[idx];
      r[P.ean] = p.ean ? String(p.ean) : '';
      r[P.sku] = p.sku ? String(p.sku) : '';
      r[P.name] = String(p.name);
      r[P.min] = numOr_(p.minQty, 0);
      r[P.buy] = p.buyPrice == null || p.buyPrice === '' ? '' : Number(p.buyPrice);
      r[P.sell] = p.sellPrice == null || p.sellPrice === '' ? '' : Number(p.sellPrice);
      r[P.note] = p.note ? String(p.note) : '';
      r[P.cat] = p.category ? cleanPath_(p.category) : '';
      r[P.mod] = now;
      r[P.modBy] = device;
      if (r[P.ean]) byEan[normEan_(r[P.ean])] = idx;
    });

    // 2. Mozgások (idempotens)
    var seen = {};
    var mLast = ms.getLastRow();
    if (mLast > 1) {
      ms.getRange(2, M.id + 1, mLast - 1, 1).getValues().forEach(function (v) { if (v[0]) seen[String(v[0])] = true; });
    }
    var newMoves = [], applied = 0, skipped = 0, unknown = 0;
    (req.moves || []).forEach(function (mv) {
      if (!mv || !mv.id) return;
      if (seen[String(mv.id)]) { skipped++; return; }
      var idx = byId[String(mv.productId)];
      if (idx == null && mv.ean) idx = byEan[normEan_(mv.ean)];
      if (idx == null) { unknown++; return; }
      var r = rows[idx];
      var delta = Math.round(Number(mv.delta) || 0);
      r[P.qty] = (Number(r[P.qty]) || 0) + delta;
      r[P.mod] = now;
      r[P.modBy] = device;
      seen[String(mv.id)] = true;
      newMoves.push([new Date(Number(mv.at) || Date.now()), String(mv.id), String(r[P.id]),
        r[P.ean] ? String(r[P.ean]) : '', String(r[P.name]), delta, String(mv.type || 'kiadás'), r[P.qty], device]);
      applied++;
    });

    // 3. Törlések
    var deleted = 0;
    var del = {};
    (req.deletes || []).forEach(function (id) { if (byId[String(id)] != null) { del[byId[String(id)]] = true; deleted++; } });
    var outRows = rows.filter(function (r, i) { return !del[i]; });

    // 4. Mappák
    var fs = ss.getSheetByName(SHEET_FOLDERS);
    var folders = readFolders_();
    var fset = {};
    folders.forEach(function (f) { fset[f] = true; });
    (req.folders || []).forEach(function (f) { f = cleanPath_(f); if (f) fset[f] = true; });
    (req.folderDeletes || []).forEach(function (f) {
      f = cleanPath_(f);
      Object.keys(fset).forEach(function (k) { if (k === f || k.indexOf(f + '/') === 0) delete fset[k]; });
    });
    var fl = Object.keys(fset).sort();
    var fLast = fs.getLastRow();
    if (fLast > 1) fs.getRange(2, 1, fLast - 1, 1).clearContent();
    if (fl.length) fs.getRange(2, 1, fl.length, 1).setValues(fl.map(function (f) { return [f]; }));

    // Visszaírás
    if (last > 1) ps.getRange(2, 1, last - 1, P_HEAD.length).clearContent();
    if (outRows.length) ps.getRange(2, 1, outRows.length, P_HEAD.length).setValues(outRows);
    if (newMoves.length) ms.getRange(ms.getLastRow() + 1, 1, newMoves.length, M_HEAD.length).setValues(newMoves);
    SpreadsheetApp.flush();

    var days = Number(req.logDays) || LOG_DAYS_DEFAULT;
    return {
      ok: true, time: Date.now(),
      result: { created: created, updated: updated, deleted: deleted, applied: applied, skipped: skipped, unknown: unknown },
      products: outRows.filter(function (r) { return r[P.id]; }).map(productToJson_),
      folders: fl,
      moves: readMoves_(Date.now() - days * 864e5, null, null)
    };
  } finally {
    lock.releaseLock();
  }
}

/* ------------------------------------------------------------------ */
/* Olvasás                                                            */
/* ------------------------------------------------------------------ */

function setupIfNeeded_() {
  var ss = SpreadsheetApp.getActive();
  if (!ss.getSheetByName(SHEET_PRODUCTS) || !ss.getSheetByName(SHEET_MOVES) || !ss.getSheetByName(SHEET_FOLDERS)) setup_();
}

function readProducts_() {
  var sh = SpreadsheetApp.getActive().getSheetByName(SHEET_PRODUCTS);
  var last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, P_HEAD.length).getValues().filter(function (r) { return r[P.id]; });
}

function productToJson_(r) {
  return {
    id: String(r[P.id]),
    ean: r[P.ean] ? String(r[P.ean]) : '',
    sku: r[P.sku] ? String(r[P.sku]) : '',
    name: String(r[P.name] || ''),
    qty: Math.round(Number(r[P.qty]) || 0),
    minQty: Math.round(Number(r[P.min]) || 0),
    buyPrice: r[P.buy] === '' || r[P.buy] == null ? null : Number(r[P.buy]),
    sellPrice: r[P.sell] === '' || r[P.sell] == null ? null : Number(r[P.sell]),
    note: String(r[P.note] || ''),
    category: String(r[P.cat] || ''),
    photoId: photoIdFromUrl_(r[P.photo])
  };
}

function readFolders_() {
  var sh = SpreadsheetApp.getActive().getSheetByName(SHEET_FOLDERS);
  if (!sh) return [];
  var last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, 1).getValues().map(function (r) { return cleanPath_(r[0]); }).filter(String);
}

/* ------------------------------------------------------------------ */
/* Termékfotók (Google Drive)                                         */
/* ------------------------------------------------------------------ */

function photoFolder_() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('PHOTO_FOLDER_ID');
  if (id) {
    try { var f = DriveApp.getFolderById(id); if (!f.isTrashed()) return f; } catch (e) {}
  }
  var it = DriveApp.getFoldersByName(PHOTO_FOLDER_NAME);
  var folder = it.hasNext() ? it.next() : DriveApp.createFolder(PHOTO_FOLDER_NAME);
  props.setProperty('PHOTO_FOLDER_ID', folder.getId());
  return folder;
}

function photoIdFromUrl_(v) {
  var m = String(v || '').match(/[-\w]{25,}/);
  return m ? m[0] : '';
}

/** req = {productId, mime, data(base64)} → a fotót a Drive-ra menti, a termék Kép oszlopába a linket írja. */
function photo_(req) {
  setupIfNeeded_();
  if (!req.productId || !req.data) return { ok: false, error: 'bad_request' };
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) return { ok: false, error: 'busy' };
  try {
    var ps = SpreadsheetApp.getActive().getSheetByName(SHEET_PRODUCTS);
    var last = ps.getLastRow();
    if (last < 2) return { ok: false, error: 'unknown_product' };
    var ids = ps.getRange(2, 1, last - 1, 1).getValues();
    var row = -1;
    for (var i = 0; i < ids.length; i++) if (String(ids[i][0]) === String(req.productId)) { row = i + 2; break; }
    if (row < 0) return { ok: false, error: 'unknown_product' };
    var r = ps.getRange(row, 1, 1, P_HEAD.length).getValues()[0];
    var old = photoIdFromUrl_(r[P.photo]);
    if (old) { try { DriveApp.getFileById(old).setTrashed(true); } catch (e) {} }
    var name = (r[P.ean] ? String(r[P.ean]) + ' - ' : '') + String(r[P.name] || req.productId);
    var blob = Utilities.newBlob(Utilities.base64Decode(req.data), req.mime || 'image/jpeg', name.slice(0, 80) + '.jpg');
    var file = photoFolder_().createFile(blob);
    var url = 'https://drive.google.com/file/d/' + file.getId() + '/view';
    ps.getRange(row, P.photo + 1).setValue(url);
    return { ok: true, photoId: file.getId() };
  } finally {
    lock.releaseLock();
  }
}

/** req = {photoId} → a kép base64-ben (a telefonra töltéshez). */
function photoGet_(req) {
  if (!req.photoId) return { ok: false, error: 'bad_request' };
  var file = DriveApp.getFileById(String(req.photoId));
  var b = file.getBlob();
  return { ok: true, mime: b.getContentType(), data: Utilities.base64Encode(b.getBytes()) };
}

function readMoves_(from, to, productId) {
  var sh = SpreadsheetApp.getActive().getSheetByName(SHEET_MOVES);
  var last = sh.getLastRow();
  if (last < 2) return [];
  var out = [];
  sh.getRange(2, 1, last - 1, M_HEAD.length).getValues().forEach(function (r) {
    var at = r[M.at] instanceof Date ? r[M.at].getTime() : Number(r[M.at]) || 0;
    if (at < from) return;
    if (to && at > to) return;
    if (productId && String(r[M.pid]) !== String(productId)) return;
    out.push({
      id: String(r[M.id]), at: at, productId: String(r[M.pid] || ''), ean: String(r[M.ean] || ''),
      name: String(r[M.name] || ''), delta: Number(r[M.delta]) || 0, type: String(r[M.type] || ''),
      qtyAfter: r[M.after] === '' ? null : Number(r[M.after]), device: String(r[M.device] || '')
    });
  });
  return out;
}

/* ------------------------------------------------------------------ */
/* StoDo import                                                       */
/* ------------------------------------------------------------------ */

var IMPORT_FIELDS = {
  name: ['megnevezes', 'termeknev', 'termek neve', 'nev', 'name', 'product', 'termek'],
  ean: ['ean', 'vonalkod', 'barcode', 'gtin'],
  sku: ['cikkszam', 'sku', 'termekkod', 'kod', 'azonosito', 'article'],
  qty: ['keszlet', 'mennyiseg', 'darabszam', 'quantity', 'qty', 'stock', 'darab'],
  min: ['minimalis', 'minimum', 'min'],
  buy: ['beszerzesi', 'beszerzes', 'bekerulesi', 'purchase', 'cost'],
  sell: ['eladasi', 'fogyasztoi', 'sale price', 'selling', 'price', 'ar'],
  cat: ['kategoria', 'csoport', 'mappa', 'category', 'group'],
  note: ['leiras', 'megjegyzes', 'description', 'note']
};

function menuImport() {
  var ui = SpreadsheetApp.getUi();
  var res = importFromSheet_();
  ui.alert(res.message);
}

function importFromSheet_() {
  setup_();
  var ss = SpreadsheetApp.getActive();
  var is = ss.getSheetByName(SHEET_IMPORT);
  var data = is.getDataRange().getValues();
  var hi = -1;
  for (var i = 0; i < data.length; i++) {
    if (data[i].filter(function (c) { return typeof c === 'string' && c.trim(); }).length >= 2) { hi = i; break; }
  }
  if (hi < 0) return { ok: false, message: 'Az Import lapon nem találtam fejlécet. Másold be a StoDo exportot a fejléc sorral együtt.' };
  var head = data[hi].map(function (h) { return fold_(h); });
  var col = {}, used = {};
  ['name', 'ean', 'sku', 'qty', 'min', 'buy', 'sell', 'cat', 'note'].forEach(function (f) {
    var keys = IMPORT_FIELDS[f];
    for (var k = 0; k < keys.length; k++) {
      for (var c = 0; c < head.length; c++) {
        if (used[c]) continue;
        if (head[c] === keys[k] || head[c].indexOf(keys[k]) >= 0) { col[f] = c; used[c] = true; break; }
      }
      if (col[f] != null) break;
    }
  });
  if (col.name == null) return { ok: false, message: 'Nem találtam a megnevezés oszlopát. Fejlécek: ' + data[hi].join(', ') };

  var lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    var ps = ss.getSheetByName(SHEET_PRODUCTS);
    var ms = ss.getSheetByName(SHEET_MOVES);
    var last = ps.getLastRow();
    var rows = last > 1 ? ps.getRange(2, 1, last - 1, P_HEAD.length).getValues() : [];
    var byEan = {}, byName = {};
    rows.forEach(function (r, i) {
      if (r[P.ean]) byEan[normEan_(r[P.ean])] = i;
      byName[fold_(r[P.name])] = i;
    });
    var now = new Date(), created = 0, updated = 0, moves = [], newFolders = {};
    for (var j = hi + 1; j < data.length; j++) {
      var d = data[j];
      var name = String(d[col.name] || '').trim();
      if (!name) continue;
      var ean = col.ean != null ? String(d[col.ean] || '').replace(/\s+/g, '') : '';
      // Ha van vonalkód, csak az alapján egyeztet (azonos nevű, de más vonalkódú termék külön termék marad)
      var idx = ean ? byEan[normEan_(ean)] : byName[fold_(name)];
      var isNew = idx == null;
      if (isNew) {
        var row = new Array(P_HEAD.length).fill('');
        row[P.id] = Utilities.getUuid();
        row[P.qty] = 0;
        row[P.min] = 2;
        rows.push(row);
        idx = rows.length - 1;
        created++;
      } else {
        updated++;
      }
      var r = rows[idx];
      r[P.name] = name;
      if (ean) r[P.ean] = ean;
      if (col.sku != null && String(d[col.sku]).trim()) r[P.sku] = String(d[col.sku]).trim();
      if (col.min != null && parseNum_(d[col.min]) != null) r[P.min] = Math.max(0, Math.round(parseNum_(d[col.min])));
      if (col.buy != null && parseNum_(d[col.buy]) != null) r[P.buy] = parseNum_(d[col.buy]);
      if (col.sell != null && parseNum_(d[col.sell]) != null) r[P.sell] = parseNum_(d[col.sell]);
      if (col.cat != null && String(d[col.cat]).trim()) { r[P.cat] = cleanPath_(d[col.cat]); newFolders[r[P.cat]] = true; }
      if (col.note != null && String(d[col.note]).trim()) r[P.note] = String(d[col.note]).trim();
      if (col.qty != null && parseNum_(d[col.qty]) != null) {
        var q = Math.round(parseNum_(d[col.qty]));
        var delta = q - (Number(r[P.qty]) || 0);
        r[P.qty] = q;
        if (delta !== 0) moves.push([now, 'import-' + Utilities.getUuid(), String(r[P.id]), r[P.ean] ? String(r[P.ean]) : '', name, delta, 'import', q, 'StoDo import']);
      }
      r[P.mod] = now;
      r[P.modBy] = 'StoDo import';
      if (r[P.ean]) byEan[normEan_(r[P.ean])] = idx;
      byName[fold_(name)] = idx;
    }
    if (last > 1) ps.getRange(2, 1, last - 1, P_HEAD.length).clearContent();
    if (rows.length) ps.getRange(2, 1, rows.length, P_HEAD.length).setValues(rows);
    if (moves.length) ms.getRange(ms.getLastRow() + 1, 1, moves.length, M_HEAD.length).setValues(moves);
    var fs = ss.getSheetByName(SHEET_FOLDERS);
    var fset = {};
    readFolders_().forEach(function (f) { fset[f] = true; });
    Object.keys(newFolders).forEach(function (f) { if (f) fset[f] = true; });
    var fl = Object.keys(fset).sort();
    var fLast = fs.getLastRow();
    if (fLast > 1) fs.getRange(2, 1, fLast - 1, 1).clearContent();
    if (fl.length) fs.getRange(2, 1, fl.length, 1).setValues(fl.map(function (f) { return [f]; }));
  } finally {
    lock.releaseLock();
  }
  var found = Object.keys(col).map(function (k) { return k + ' = "' + data[hi][col[k]] + '"'; }).join(', ');
  return { ok: true, message: 'Import kész: ' + created + ' új, ' + updated + ' frissített termék.\n\nFelismert oszlopok: ' + found };
}

/* ------------------------------------------------------------------ */
/* StoDo képek importálása                                            */
/* ------------------------------------------------------------------ */
/*
 * KépImport lap: B1 = a StoDo Excel-export (.xlsx, beágyazott képekkel) Drive-azonosítója,
 * 3. sortól: A = kép útvonala a zip-ben (pl. xl/media/image1.jpeg) | B = Vonalkód | C = Név.
 * A képeket a "HaNiZso Raktár képek" mappába menti, és a termék Kép oszlopába írja a linket.
 * A már képpel rendelkező termékeket kihagyja, így időtúllépés után újra futtatható.
 */
function menuImportImages() {
  SpreadsheetApp.getUi().alert(importImages_());
}

function importImages_() {
  setupIfNeeded_();
  var ss = SpreadsheetApp.getActive();
  var is = ss.getSheetByName(SHEET_IMG_IMPORT);
  if (!is) return 'Nincs KépImport lap.';
  var fileId = String(is.getRange('B1').getValue() || '').trim();
  if (!fileId) return 'A KépImport lap B1 cellájába kell a StoDo export fájl Drive-azonosítója.';
  var zipBlob = DriveApp.getFileById(fileId).getBlob().setContentType('application/zip');
  var parts = Utilities.unzip(zipBlob);
  var byPath = {};
  parts.forEach(function (b) { byPath[b.getName()] = b; byPath[b.getName().split('/').pop()] = b; });
  var last = is.getLastRow();
  var map = last >= 3 ? is.getRange(3, 1, last - 2, 3).getValues() : [];

  var lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    var ps = ss.getSheetByName(SHEET_PRODUCTS);
    var pLast = ps.getLastRow();
    if (pLast < 2) return 'Előbb a termékeket kell importálni (Raktár → StoDo import).';
    var rows = ps.getRange(2, 1, pLast - 1, P_HEAD.length).getValues();
    var byEan = {}, byName = {};
    rows.forEach(function (r, i) {
      if (r[P.ean]) byEan[normEan_(r[P.ean])] = i;
      byName[fold_(r[P.name])] = i;
    });
    var folder = photoFolder_();
    var start = Date.now(), done = 0, had = 0, noProduct = 0, noImage = 0, timeout = false;
    for (var k = 0; k < map.length; k++) {
      if (Date.now() - start > 300000) { timeout = true; break; }
      var m = map[k];
      if (!m[0]) continue;
      var ean = String(m[1] || '').trim();
      var idx = (ean && byEan[normEan_(ean)] != null) ? byEan[normEan_(ean)] : byName[fold_(m[2])];
      if (idx == null) { noProduct++; continue; }
      if (rows[idx][P.photo]) { had++; continue; }
      var blob = byPath[String(m[0])] || byPath[String(m[0]).split('/').pop()];
      if (!blob) { noImage++; continue; }
      var name = (rows[idx][P.ean] ? String(rows[idx][P.ean]) + ' - ' : '') + String(rows[idx][P.name]);
      var file = folder.createFile(blob.copyBlob().setName(name.slice(0, 80) + '.jpg'));
      rows[idx][P.photo] = 'https://drive.google.com/file/d/' + file.getId() + '/view';
      done++;
    }
    ps.getRange(2, P.photo + 1, rows.length, 1).setValues(rows.map(function (r) { return [r[P.photo]]; }));
  } finally {
    lock.releaseLock();
  }
  return 'Képimport: ' + done + ' új kép.' + (had ? ' ' + had + ' terméknek már volt képe.' : '') +
    (noProduct ? ' ' + noProduct + ' képhez nem találtam terméket.' : '') + (noImage ? ' ' + noImage + ' kép hiányzott a fájlból.' : '') +
    (timeout ? '\n\nNem fért bele az időbe: futtasd újra ugyanezt a menüt, a maradékkal folytatja.' : '');
}

/* ------------------------------------------------------------------ */
/* Segédfüggvények                                                    */
/* ------------------------------------------------------------------ */

function normEan_(s) { return String(s || '').replace(/\s+/g, '').replace(/^0+/, ''); }

function fold_(s) {
  return String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').trim();
}

/** "Mosószer / Mosógél" → "Mosószer/Mosógél" (a StoDo „>” elválasztóját is kezeli) */
function cleanPath_(s) {
  return String(s || '').split(/[\/>\\]/).map(function (x) { return x.trim(); }).filter(String).join('/');
}

function numOr_(v, d) { var n = Number(v); return isFinite(n) ? n : d; }

function parseNum_(v) {
  if (v === '' || v == null) return null;
  if (typeof v === 'number') return v;
  var s = String(v).replace(/[^\d,.\-]/g, '').replace(/\.(?=\d{3}(\D|$))/g, '').replace(',', '.');
  var n = parseFloat(s);
  return isFinite(n) ? n : null;
}
