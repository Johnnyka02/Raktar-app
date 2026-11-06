# Telepítés: Google-táblázat és Apps Script

Ezt egyszer kell megcsinálni, számítógépen, böngészőben (a saját Google-fiókoddal).

## 1. A táblázat
1. Nyisd meg a **HaNiZso Raktár** táblázatot a Google Drive-on (vagy hozz létre egy üres táblázatot ezzel a névvel).
2. Felül: **Bővítmények → Apps Script**. Megnyílik a szkriptszerkesztő.

## 2. A kód bemásolása
1. A szerkesztőben a `Kód.gs` (vagy `Code.gs`) fájl teljes tartalmát töröld ki.
2. Másold be helyette az `apps-script/Code.gs` fájl teljes tartalmát.
3. Mentés: **Ctrl+S** (vagy a lemez ikon).

## 3. A táblázat előkészítése
1. Térj vissza a táblázat lapjára, és **frissítsd az oldalt** (F5).
2. Megjelenik egy új **Raktár** menü. Kattints: **Raktár → Táblázat előkészítése**.
3. Az első futtatáskor a Google engedélyt kér:
   - **Folytatás** → válaszd ki a fiókodat.
   - Megjelenik: „A Google nem ellenőrizte ezt az alkalmazást”. Ez a saját szkripted, ezért: **Speciális → Ugrás a(z) … oldalra (nem biztonságos)** → **Engedélyezés**.
4. Futtasd újra: **Raktár → Táblázat előkészítése**. Létrejön a *Termékek*, a *Mozgások* és az *Import* lap.

## 4. Az API kulcs
**Raktár → API kulcs megjelenítése.** Másold ki a kulcsot, ezt kell beírni az app Beállításaiba. Senkivel ne oszd meg.

## 5. Közzététel web appként
1. A szkriptszerkesztőben jobb felül: **Telepítés (Deploy) → Új telepítés (New deployment)**.
2. A fogaskerék ikonnál a típus: **Webalkalmazás (Web app)**.
3. Beállítások:
   - **Leírás:** HaNiZso Raktár
   - **Végrehajtás mint (Execute as):** *Én*
   - **Ki férhet hozzá (Who has access):** *Bárki (Anyone)*
4. **Telepítés**, majd másold ki a **Webalkalmazás URL-jét** (`https://script.google.com/macros/s/…/exec`). Ezt is be kell írni az app Beállításaiba.

> A „Bárki” azt jelenti, hogy a cím elérhető, de az API kulcs nélkül a szkript semmit nem ad ki és semmit nem fogad el.

## 6. StoDo adatok áthozása
1. A StoDo-ban: **Beállítások → Excel import és export → export**.
2. Nyisd meg az exportált fájlt, jelöld ki az egészet (fejléccel együtt), és másold be a táblázat **Import** lapjára, az A1 cellától.
3. **Raktár → StoDo import az Import lapról.** A végén kiírja, hány terméket vett fel, és mely oszlopokat ismerte fel.

## Ha később módosul a kód
Másold be az új `Code.gs`-t, ments, majd **Telepítés → Telepítések kezelése → (ceruza) → Verzió: Új verzió → Telepítés**. Így az URL nem változik, nem kell a telefonon átírni.
