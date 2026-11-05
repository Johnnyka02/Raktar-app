# Fejlesztési háttér és döntések

## Miért így épült (döntési napló, 2026-11-05)
1. **StoDo-ból nem lehet adatot kinyerni:** saját felhőben tárol, nincs API, nincs webes felület, nincs Sheets-szinkron, automatikus export sincs. Csak kézi Excel/CSV export. → saját app kell.
2. **Böngészős (Claude artifact) változat elvetve:** a felhasználó natív appot kért; az artifactban nincs élő kamera, csak fotóból olvasás.
3. **InvenTree (nyílt forrású) elvetve:** szerver kell hozzá (Docker, PostgreSQL), az otthoni laptop leállása megbénítaná a raktárat, és Drive-ra nem tud menteni.
4. **AppSheet elvetve:** valószínűleg havidíjas két felhasználóra.
5. **Választott megoldás:** natív Android app + Google-táblázat adatbázis + Apps Script közvetítő. Ingyenes, nincs üzemeltetés, mobilneten is megy, és a Claude a Sheets-összekötővel olvassa.
6. **Csomagos szinkron** (indításkor le, csomagolás végén fel) a felhasználó javaslatára: egy telefon, napi egy csomagolás mellett nincs egyidejű szerkesztés, így ez egyszerű és megbízható. A biztonság kedvéért háttérküldés is van.
7. **Fix IP / saját szerver nincs és nem is lesz** (a felhasználó nem akar fix IP-t). Minden szerveroldali logika az Apps Scriptben marad.

## Szerveroldali részletek (Code.gs)
- `push_` sorrendje: (1) termék-upsert (új termék létrejön 0 készlettel), (2) mozgások hozzáadása a készlethez,
  ismeretlen termék ID esetén EAN alapján is keres, (3) törlések, (4) a teljes Termékek tartomány visszaírása egyben,
  (5) új mozgássorok hozzáfűzése. Mindez `LockService.getScriptLock()` alatt.
- A Mozgás ID-k halmazát minden pushnál a teljes B oszlopból olvassa. Több tízezer sorig ez rendben van;
  ha nagyon megnő, érdemes lehet évente archiválni a Mozgások lapot.
- `importFromSheet_`: fejlécfelismerés ékezet nélküli kisbetűs kulcsszavakkal (`IMPORT_FIELDS`),
  EAN (vezető nullák nélkül) vagy név alapján egyeztet, a készletváltozást `import` típusú mozgásként naplózza.
- `parseNum_` kezeli: „1 050 Ft”, „1.050”, „2,5”.

## Kliensoldali részletek
- `Store.update` mutex alatt ír, majd a StateFlow-t frissíti. A `sync` külön mutexet használ, és a küldés
  alatt keletkezett új mozgásokat megtartja (csak az elküldött ID-ket törli a sorból).
- `undoMove`: ha a mozgás még nincs elküldve, egyszerűen törlődik; ha már igen, ellenmozgás (`visszavonás`).
- `deleteProduct`: szerveren lévő terméknél `pendingDeletes`; csak helyben létezőnél egyszerű eltávolítás.
- Kamera-debounce: azonos kód csak 1,5 s „nem látszik” után számít újra (két egyforma termék egymás után: elvenni, újra odatartani).
- Kézi bevitelnél nincs debounce (Bluetooth-olvasóval gyorsan lehet két azonos terméket csipogni).
- Napló: szerver (90 nap) + várakozó mozgások, napokra csoportosítva, napi összesítő termékenként, keresés név/EAN/eszköz szerint.

## Tesztelési ellenőrzőlista (valós telefonon)
- [ ] Beállítások: URL + kulcs mentése, „Kapcsolat tesztelése” → „A kapcsolat működik.”
- [ ] Letöltés: termékek megjelennek a Készlet fülön.
- [ ] Kiadás kamerával: EAN-13 beolvasás, hang, rezgés, −1, maradt X.
- [ ] Ugyanaz a termék kétszer egymás után (elvenni, visszatartani) → két levonás.
- [ ] Ismeretlen kód → „Új termék ezzel a kóddal” → mentés → újra beolvasva felismeri.
- [ ] Repülő mód: kiadás működik, fejlécben „X el nem küldött”; net vissza → Csomagolás vége elküldi.
- [ ] App bezárása küldés nélkül → pár percen belül a táblázatban megjelennek a mozgások.
- [ ] Visszavonás küldés előtt és után.
- [ ] Bevét +10 mennyiséggel.
- [ ] Napló: napok, összesítés, eszköznév.
- [ ] Bluetooth vonalkódolvasó (ha lesz).

## Kiadás menete
1. Módosítás → commit → push a `main`-re.
2. GitHub Actions lefordítja → Releases oldalon `HaNiZso-Raktar-1.0.<n>.apk`.
3. A felhasználó letölti és telepíti (felülírja a régit, az adatok megmaradnak, mert ugyanaz a kulcs).
4. Ha az Apps Script is változott: kézi bemásolás + új verzió telepítése (lásd `apps-script/TELEPITES.md`).
