# HaNiZso Raktár

Android raktárkezelő app a HaNiZso webshophoz: vonalkódos kiadás és bevét csomagoláskor, készletlista, napi napló.

- **Adatbázis:** Google-táblázat („HaNiZso Raktár”), köztük Google Apps Script web app. Saját szerver nincs.
- **Működés:** indításkor letölti a készletet, csomagolás közben minden a telefonon történik (net nélkül is), a „Csomagolás vége” gomb elküldi a mozgásokat. Ha elmarad, háttérben automatikusan elmegy.
- **Telepítés:** a szkript beállítása: [`apps-script/TELEPITES.md`](apps-script/TELEPITES.md). Az APK a GitHub **Releases** oldalán található, minden módosítás után automatikusan elkészül.

## Szerkezet
- `apps-script/Code.gs` – a táblázathoz kötött háttér (letöltés, csomagküldés, napló, StoDo import)
- `app/` – Android app (Kotlin, Jetpack Compose, CameraX + ML Kit vonalkódolvasó)
- `.github/workflows/build.yml` – aláírt release APK fordítása
