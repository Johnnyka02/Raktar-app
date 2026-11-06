# HaNiZso Raktár – fejlesztési dokumentáció (Claude-nak)

Ez a fájl a projekt teljes műszaki leírása. Minden munkamenet elején ezt olvasd el.
A részletes háttér a `docs/FEJLESZTES.md`-ben van.

## Kinek és mire
- Tulajdonos: Barna Zsolt (BARNA ZSOLT EV., GitHub: Johnnyka02, Google: evbarnazsolt@gmail.com).
  Temu-eladó (illatszer, mosószer, öblítő, háztartási vegyiáru), saját magyar raktárral.
- Cél: a StoDo raktárkezelő app kiváltása egy saját Android appal, aminek az adatait
  **a Claude is látja** (Google Sheets-összekötővel), hogy készletet elemezzen és
  összevesse az Estella-Hungary nagyker árlistájával (utánrendelés).
- Használat: **egy telefon, naponta egyszer, 1–2 órás csomagolás**. Néha a feleség csomagol
  (ezért van eszköznév a naplóban). A felhasználói felület és minden szöveg **magyar**.
- A felhasználó **nem programozó**: magyarázz egyszerűen, a lépéseket számozva add meg.
- Kritikus gondolkodótársat kér: mérlegelj, mondj ellent, ha kell.

## Architektúra (saját szerver NINCS)
```
Android app (Kotlin, Compose)  ──HTTPS POST JSON──►  Google Apps Script web app  ──►  Google-táblázat
  helyi JSON állapot (offline)                       (a felhasználó fiókjában fut)     "HaNiZso Raktár"
                                                                                       ▲
                                                     Claude (Sheets-összekötő) ────────┘ olvassa
```
- Google-táblázat: **"HaNiZso Raktár"**, ID: `1LsSQ1vo5UA76pWWB3iufE1RuBTAWAKzPJOOZ9Lhahiw`
  (evbarnazsolt@gmail.com Drive-ján). Lapok: `Termékek`, `Mozgások`, `Mappák`, `Import`.
- Termékfotók: Drive „HaNiZso Raktár képek” mappa (ID a Script Properties `PHOTO_FOLDER_ID`-ban),
  a Termékek „Kép” oszlopában a Drive-link.
- Apps Script: `apps-script/Code.gs`, a táblázathoz kötve, web appként közzétéve
  (Végrehajtás: én; Hozzáférés: bárki). Hitelesítés: Script Properties `API_KEY`,
  minden kérés JSON-jében `key` mezőként jön.
- A kód módosítása után a felhasználónak kézzel kell bemásolnia a `Code.gs`-t, és
  **Telepítések kezelése → Új verzió**-val újratelepíteni (így az URL nem változik).

## Szinkron-modell (a legfontosabb szabályok)
1. Indításkor `sync()`: ha nincs várakozó változás → `pull`, különben `push`.
2. Csomagolás közben minden **helyben** történik (`LocalState.pendingMoves` stb.), fájlba mentve.
3. „Csomagolás vége” gomb → `push`, a válaszban friss termékek + 90 napi napló jön.
4. Biztonsági háló: `ProcessLifecycleOwner.onStop` → `SyncWorker` (WorkManager, hálózat feltétellel).
5. **A mozgás mindig VÁLTOZÁS (delta), soha nem végső készlet.** A szerver hozzáadja.
6. **Idempotencia:** minden mozgásnak kliens-UUID-je van; a szerver a már meglévő
   `Mozgás ID`-t kihagyja → ismételt küldés nem von le kétszer.
7. A termék adatai (név, EAN, ár…) `pendingProducts`-ként mennek, a `qty` mező itt mindig 0
   és a szerver figyelmen kívül hagyja; készletet csak mozgás változtat.
8. Megjelenített készlet = `serverProducts` + `pendingProducts` (adatok) − `pendingDeletes` + Σ `pendingMoves.delta`
   (`LocalState.products()`).

## API (Apps Script `doPost`, body = JSON, Content-Type text/plain)
| action | kérés | válasz |
|---|---|---|
| `ping` | `{key}` | `{ok,time}` |
| `pull` | `{key, logDays?=90}` | `{ok,time,products[],moves[]}` |
| `push` | `{key, device, products[], deletes[], moves[], logDays?}` | `{ok,time,result{created,updated,deleted,applied,skipped,unknown},products[],moves[]}` |
| `log`  | `{key, from, to?, productId?}` | `{ok,moves[]}` |
| `photo` | `{key, productId, mime, data(base64)}` | `{ok, photoId}` – a régi fotót kukába teszi |
| `photoGet` | `{key, photoId}` | `{ok, mime, data(base64)}` |
A `pull` és `push` válasza `folders[]`-t is tartalmaz; a `push` kérés `folders[]` (új mappák) és `folderDeletes[]` mezőt is fogad.
Hibák: `{ok:false,error:"unauthorized"|"busy"|"bad_json"|"unknown_action"|"server_error",message?}`.
Az Apps Script POST válasza 302 → googleusercontent GET; az OkHttp ezt helyesen követi.

## Táblázat oszlopai (Code.gs `P` és `M` indexei – ne cseréld fel a sorrendet!)
- **Termékek:** ID | EAN | Cikkszám | Megnevezés | Készlet | Minimum | Beszerzési ár | Eladási ár | Leírás | Módosítva | Módosította | Mappa | Kép
  (a JSON-ban a Leírás mező neve `note`, a Mappa `category`, a Kép `photoId`)
- **Mappák:** Mappa (útvonal `/` elválasztóval, pl. `Mosószer/Mosógél`; az üres mappák miatt kell, a termékek mappái ettől függetlenül is látszanak)
- **Mozgások:** Időpont | Mozgás ID | Termék ID | EAN | Megnevezés | Változás | Típus | Készlet utána | Eszköz
- Típusok: `kiadás`, `bevét`, `korrekció`, `kézi`, `visszavonás`, `import`.
- EAN és Cikkszám szöveg formátumú (vezető nullák!). Időzóna: Europe/Budapest.
- `Raktár` menü: Táblázat előkészítése, StoDo import az Import lapról, API kulcs megjelenítése / új kulcs.

## Android app
- Csomag: `hu.hanizso.raktar`, minSdk 26, target/compile 35, Kotlin 2.0.21, AGP 8.7.3, Gradle wrapper 8.11.1, JDK 17 cél.
- Fájlok:
  - `data/Models.kt` – Product, Move, MoveType, Settings, LocalState
  - `data/Api.kt` – OkHttp hívások, magyar hibaüzenetek (`ApiException`)
  - `data/Store.kt` – singleton, helyi állapot `filesDir/state.json` (atomi írás + `.bak`), beállítások SharedPreferences-ben, `sync()` mutex alatt
  - `data/SyncWorker.kt` – háttérküldés
  - `RaktarApp.kt` – háttérbe kerüléskor ütemezi a küldést
  - `ui/AppViewModel.kt` – vonalkód-kezelés, hang+rezgés, csomagolás vége összesítő
  - `ui/Scanner.kt` – CameraX + ML Kit (beépített modell); ugyanaz a kód csak 1,5 s „eltűnés” után számít újra
  - `ui/Screens.kt` – főképernyő, Kiadás / Bevét / Napló fül, beállítások, összesítő
  - `ui/Stock.kt` – Készlet fül mappanézettel (morzsamenü, almappák, új/üres mappa törlése), ékezetfüggetlen keresés (`fold()`), bélyegképek
  - `ui/ProductScreen.kt` – teljes képernyős termék-űrlap: fotó (kamera/galéria), EAN beolvasás kamerával, mappaválasztó, leírás
  - `data/ImageUtil.kt` – fotó elforgatása (EXIF), kicsinyítés max. 1280 px, JPEG 80%, bélyegkép-gyorsítótár
  - `ui/Theme.kt` – színek (zöld-teal elsődleges, OK/Figyelem/Hiba színek)
- Fotók: helyben `filesDir/photos/<termékID>.jpg`, feltöltés a szinkron végén (`pendingPhotos`), egyenként; hiba esetén a sorban marad.
- Mappák: `LocalState.folders()` = szerver + várakozó mappák + a termékek mappái (szülőkkel), mínusz törölt. Csak üres mappa törölhető.
- Kamerás olvasásnál ugyanaz a kód csak 1,5 s „eltűnés” után számít újra; kézi/Bluetooth bevitelnél minden csippantás számít.
- Bluetooth/USB vonalkódolvasó: billentyűzetként a kézi beviteli mezőbe ír, Enter = beküldés (`onPreviewKeyEvent`).
- ABI: csak `arm64-v8a`, `armeabi-v7a` (méret ~19 MB). Lint release-nél kikapcsolva.

## Fordítás és kiadás
- Helyben: `export ANDROID_HOME=<sdk>; ./gradlew assembleRelease`. Aláírás `keystore.properties`-ből
  (gitignore-ban!) vagy környezeti változókból: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `VERSION_CODE`.
- CI: `.github/workflows/build.yml` – push a `main`-re → aláírt APK, **versionCode = 100 + run_number**
  (hogy mindig nagyobb legyen a helyben fordított 1.0.x verzióknál), GitHub Release `v1.0.<versionCode>` taggel.
- Helyi kiadásnál mindig adj meg növekvő `VERSION_CODE`-ot (eddig: 1 = 1.0.1, 2 = 1.0.2). Titkok: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` (=`raktar`).
- A kulcstár (`hanizso-raktar.jks`, alias `raktar`) a felhasználónál van. **Soha ne commitold, ne generálj újat**
  (más kulccsal aláírt APK nem frissíti a telepítettet). Ha kell, kérd el tőle.
- Az első APK (1.0.1, versionCode 1) helyben készült és alá van írva ugyanazzal a kulccsal.

## Ismert környezeti buktatók (Claude felhős munkamenet)
- Az Android-letöltésekhez a felhasználó engedélyezte: dl.google.com, maven.google.com, services.gradle.org, plugins.gradle.org, repo.maven.apache.org.
- A repo.maven.apache.org néha 429-et ad: várj 15–20 mp-et és próbáld újra.
- A GitHub Actions titok-API (`/actions/secrets`) a proxyn **le van tiltva**, titkot csak a felhasználó állíthat be a webes felületen.
- Új repót létrehozni innen nem lehet; a felhasználó hozza létre, majd `add_repo`.
- A tároló új neve **Johnnyka02/Raktar-app** (a régi `raktar-app` átirányít).
- A felhős konténer ideiglenes: az SDK-t és a kulcsot nem tárolja tartósan.

## Állapot (2026-11-06)
- Kész: Apps Script (szimulált táblázaton tesztelve: delta, idempotencia, törlés, import, rossz kulcs, mappák, fotó feltöltés/csere/letöltés),
  Android app 1.0.2 (mappák, fotó, EAN-olvasó az űrlapon, többsoros leírás, ékezetfüggetlen keresés), CI workflow.
- Hátra van / a felhasználóra vár: Apps Script bemásolása és közzététele, GitHub-titkok beállítása, első valós teszt a telefonon, StoDo-export importja.
- Ötletek később: napló régebbi időszakra (`log` action már megvan), Estella-árlista összevetés a felügyelő ügynökben,
  utánrendelési lista exportja, termékfotó.

## Munkaszabályok
- Commit üzenetek magyarul; a végén a munkamenet által megadott attribúciós sorok.
- Bármely API-változásnál a `Code.gs` és az `Api.kt` együtt módosul, és frissítsd ezt a fájlt is.
- Visszafelé kompatibilitás: a telefonon tárolt `state.json` szerkezetét csak új, alapértékes mezőkkel bővítsd.
- Felhasználónak szóló szövegek: rövid, egyszerű magyar mondatok, hiba esetén mit tegyen.
