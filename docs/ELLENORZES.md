# BMenetrend 0.3.0 – ellenőrzési jegyzőkönyv

Ellenőrzés: 2026. október 22. Az APK fejlesztői aláírású, csomagneve `hu.viabusz.app`, verziókódja 3 (`versionCode = 3`, `versionName = "0.3.0"`). Az APK aláíró tanúsítványa a helyi fejlesztői környezet debug kulcsa (SHA-256 lenyomat: `58:7b:17:f8:c1:e6:e9:15:9c:ce:68:3c:2d:4f:b0:b5:c7:fc:6e:9e:b3:a1:ef:13:83:ff:40:fa:88:11:6b:3e`).

## Fordítás és automatikus ellenőrzés

- JDK 17, Gradle 8.14.3, Android Gradle Plugin 8.11.0; compile/target SDK 36, min SDK 26.
- A végleges forrásra `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: sikeres.
- 47 sikeres egységteszt (0 hiba, 0 kihagyott):
  - `ViabuszParserTest` (10 teszt): régebbi HTML táblázatok, GPS adatok, route és stop feldolgozás regressziós tesztjei.
  - `ViabuszCalendarTest` (9 teszt): külön tanítási és tanszüneti PDF-menetrendek, naptárak, ünnepnapok, SHA-256 hash és forrásérvényesség ellenőrzése.
  - `KanyargoTest` (14 teszt): PDF forrásfeldolgozás, tanítási és szüneti időszakok, GPS-feed és naptárellenőrzés.
  - `HommTest` (9 teszt): budaörsi 288 és 289 járatok hivatalos menetrendje, hétvégi és ünnepnapi szüneti menetrend, 289-es reggeli rövidmenet külön kezelése, végállomási érkezések kizárása az indulásokból.
  - `SharedStopsTest` (5 teszt): közös ViaBusz/KanyarGO megállók kanonizálása, egyetlen térképi pont, összevont menetrendi indulások, korábbi nevek alias-keresése, szolgáltatónkénti érvényesség megőrzése.
- Lint: 0 hiba, 19 figyelmeztetés. A figyelmeztetések külső könyvtár-/AGP-verziókra, KTX-ajánlásokra, többes számra alkalmas szövegekre, mentési szabályokra és a MapLibre régebbi API-jaira vonatkoznak.
- Angol és magyar lokalizáció: minden felületi elem rendelkezik angol és magyar fordítással (`res/values/` és `res/values-hu/`). A megállónevek tulajdonnevek, eredeti formájukban jelennek meg.

## Emulátoros használat

Pixel 6, Android 15 / API 35, 1080 × 2400, Budapest időzóna, host GPU. Valódi adatokkal és szimulált biatorbágyi/budaörsi/pátyi helyzettel:

- BMenetrend 0.3.0 név és verzió az alkalmazás felületén és metaadataiban.
- Teljes képernyős térkép: a térkép kiterjed a teljes képernyőre, az alsó indulási panel és a felső lebegő keresősáv alá is befut.
- Lebegő felső keresőbuborék: lekerekített sarkú (28 dp), lebegő buborékként (floating blob) fedi le a térképet, magában foglalva a keresőikont, a márkajelzést, a súgószöveget és a névjegy ikont.
- Megálló középre igazítása menümozgáskor: az alsó panel elrejtésekor (bezárásakor) vagy kinyitásakor (megnyitásakor) a térkép automatikusan a kiválasztott állomásra/megállóra fókuszálja és igazítja a középpontját a látható térképrészen.
- Intelligens zoom megállóválasztáskor: a korábbi kötelező 15.0-s szintre való vissza-kicsinyítés megszűnt; ha a felhasználó már közelebbi nézetben (pl. zoom 16–18) van, a térkép megtartja a zoom szintjét, és csak a megállóra fókuszál. Túl távoli nézetben (< 15.0) továbbra is ráközelít a megállóra.
- Angol és magyar rendszernyelv dinamikus támogatása.
- Üres kereső indításakor csak a keresőmező és a vissza gomb látható, felesleges listázás nélkül; gépeléskor azonnali találatok jelennek meg.
- Közös megállók kezelése:
  - A biatorbágyi Vasútállomás kiválasztásakor egyetlen türkiz térképi jelölő és `ViaBusz · KanyarGO` szolgáltatói címke látható (`01-shared-station-en.png`).
  - Az induláslistában a ViaBusz és a KanyarGO indulásai időrendben összefésülve jelennek meg (`02-shared-departures-en.png`), miközben a KanyarGO végállomási érkezése nem keveredik a felszállási indulások közé.
  - Az alias-keresés támogatja a korábbi és forrásbeli neveket is (pl. BIT / Juhász Gyula utca) (`03-alias-search-en.png`, `09-shared-alias-search-en.png`).
- Budaörsi HOMM járatok (288, 289):
  - Keresés a `288`-as járatra: azonnali találat, két választható irány (39 megálló a Lakótelep felé, 20 megálló a Kamaraerdő felé) (`04-homm-search-en.png`, `05-homm-directions-en.png`).
  - A megállók sorrendezett listája és térképi kiválasztása (`06-homm-stops-en.png`).
  - Budaörsi lakótelep megállónál a 288 és 289 menetrendi indulásai megjelennek (`07-homm-departures-en.png`).
  - Élő adatok hiányának tiszta jelzése: a HOMM megállóknál és járatoknál egyértelműen `HOMM timetable · no live data` / `HOMM-menetrend · élő adatok nélkül` felirat jelenik meg, nincs félrevezető GPS-becslés (`08-homm-scheduled-only-en.png`).
- A 0.2.0-ban és 0.1.0-ban bevezetett felületi funkciók zavartalanul működnek: elrejthető és visszanyitható alsó panel, indulási időablakok (30/60/120 perc), kedvencek mentése és perzisztenciája újraindítás után, fekvő és álló tájolás, offline állapot jelzése mentett hálózat böngészésével.

## Képernyőképek

A munkapéldány `artifacts/v0.3/screenshots/` mappájában az ellenőrzött emulátoros felvételek találhatók:
- `01-shared-station-en.png`: közös Vasútállomás megálló és szolgáltatói címkék.
- `02-shared-departures-en.png`: közös induláslista (ViaBusz és KanyarGO).
- `03-alias-search-en.png`: korábbi / forrásbeli megállónevek keresése.
- `04-homm-search-en.png`: HOMM 288 járatkeresés.
- `05-homm-directions-en.png`: HOMM 288 irányválasztás.
- `06-homm-stops-en.png`: HOMM rendezett megállólista.
- `07-homm-departures-en.png`: HOMM indulások a Budaörsi lakótelepen.
- `08-homm-scheduled-only-en.png`: HOMM élő adat nélküli menetrendi jelzés.
- `09-shared-alias-search-en.png`: részletes közös megálló- és alias-keresési találatok.

## Korlátok és döntések

- Fizikai készüléken és API 26-on a működést a lint és az egységtesztek igazolják; az emulátoros tesztelés API 35-ön történt.
- SwiftShader szoftveres emulációban a MapLibre szimbólumhibája miatt host GPU mód szükséges a vizuális ellenőrzéshez.
- Élő GPS csak a ViaBusz és KanyarGO feedekből érhető el, de megbízható járat-/menetazonosító nélkül; ezért a járművek `BUS` / `BUSZ` felirattal jelennek meg, és nem kapcsolódnak önkényesen menetrendi indulásokhoz.
- A HOMM nem rendelkezik publikus élő adattal, a felület szándékosan csak menetrendi adatot jelez.
- A munkanapi és tanítási/szüneti naptárak 2026. december 31-ig hitelesítettek; új naptári évre vagy új PDF kiadásra az app a hash-ellenőrzéssel biztonságosan leállítja a lejárt visszaszámlálást, megőrizve a hálózati böngészést.
- A Vitamin-domb igénymegállóhoz nincs ellenőrzött koordináta és időadat, így nem szerepel a térképen.
