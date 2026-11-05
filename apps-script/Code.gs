/**
 * HaNiZso Raktár – Google Apps Script háttér
 *
 * A táblázathoz kötött szkript. Web appként közzétéve az Android app ezen
 * keresztül tölti le a készletet és küldi fel a csomagolás közbeni mozgásokat.
 *
 * Lapok:
 *   Termékek  – egy termék = egy sor, a "Készlet" oszlop a valódi készlet
 *   Mozgások  – napló: minden kiadás, bevét, korrekció, import
 *   Import    – ide kell bemásolni a StoDo Excel-exportját (fejléccel)
 *
 * A titkos kulcs a Script Properties "API_KEY" értéke (menü: Raktár → API kulcs).
 */

var SHEET_PRODUCTS = 'Termékek';
var SHEET_MOVES = 'Mozgások';
var SHEET_IMPORT = 'Import';

var P_HEAD = ['ID', 'EAN', 'Cikkszám', 'Megnevezés', 'Készlet', 'Minimum',
  'Beszerzési ár', 'Eladási ár', 'Megjegyzés', 'Módosítva', 'Módosította'];
var M_HEAD = ['Időpont', 'Mozgás ID', 'Termék ID', 'EAN', 'Megnevezés',
  'Változás', 'Típus', 'Készlet utána', 'Eszköz'];

// Termékek oszlopindexek (0-tól)
var P = { id: 0, ean: 1, sku: 2, name: 3, qty: 4, min: 5, buy: 6, sell: 7, note: 8, mod: 9, modBy: 10 };
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
  if (!ss.getSheetByName(SHEET_PRODUCTS) || !ss.getSheetByName(SHEET_MOVES)) setup_();
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
    note: String(r[P.note] || '')
  };
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
  sell: ['eladasi', 'fogyasztoi', 'sale price', 'selling', 'price', 'ar']
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
  ['name', 'ean', 'sku', 'qty', 'min', 'buy', 'sell'].forEach(function (f) {
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
    var now = new Date(), created = 0, updated = 0, moves = [];
    for (var j = hi + 1; j < data.length; j++) {
      var d = data[j];
      var name = String(d[col.name] || '').trim();
      if (!name) continue;
      var ean = col.ean != null ? String(d[col.ean] || '').replace(/\s+/g, '') : '';
      var idx = (ean && byEan[normEan_(ean)] != null) ? byEan[normEan_(ean)] : byName[fold_(name)];
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
  } finally {
    lock.releaseLock();
  }
  var found = Object.keys(col).map(function (k) { return k + ' = "' + data[hi][col[k]] + '"'; }).join(', ');
  return { ok: true, message: 'Import kész: ' + created + ' új, ' + updated + ' frissített termék.\n\nFelismert oszlopok: ' + found };
}

/* ------------------------------------------------------------------ */
/* Segédfüggvények                                                    */
/* ------------------------------------------------------------------ */

function normEan_(s) { return String(s || '').replace(/\s+/g, '').replace(/^0+/, ''); }

function fold_(s) {
  return String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').trim();
}

function numOr_(v, d) { var n = Number(v); return isFinite(n) ? n : d; }

function parseNum_(v) {
  if (v === '' || v == null) return null;
  if (typeof v === 'number') return v;
  var s = String(v).replace(/[^\d,.\-]/g, '').replace(/\.(?=\d{3}(\D|$))/g, '').replace(',', '.');
  var n = parseFloat(s);
  return isFinite(n) ? n : null;
}
