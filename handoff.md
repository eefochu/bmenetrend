# BMenetrend – handoff

Utolsó frissítés: 2026-10-24. Ez a fájl a projekt jelenlegi állapotát írja le egy következő fejlesztőnek vagy modellnek. A cél az, hogy ne kelljen újra felderíteni az adatforrásokat, a naptárlogikát vagy a közös megállók kezelését.

A jelenlegi csomagazonosító `hu.app.bmenetrend`; a felület, az állapotüzenetek és a dátumválasztók a rendszernyelvtől függetlenül magyarul jelennek meg. Az alábbi 0.3.0-s kiadási ellenőrzések és csomaglenyomatok a korábbi, 2026-10-22-i állapotot dokumentálják.

## Rövid állapot

A **0.3.0-s kiadás elkészült, lefordítva, tesztelve és becsomagolva**. A felhasználó által legutóbb kért funkciók kódban és a kiadott csomagokban benne vannak:

- a térkép mindig teljes képernyős, és átnyúlik a felső lebegő keresőbuborék és az alsó menü alá is;
- a felső keresősáv lekerekített, árnyékolt lebegő buborékként (floating blob) fedi le a térképet;
- az alsó menü bezárásakor vagy megnyitásakor a térkép automatikusan a kiválasztott megállóhoz igazítja a középpontját;
- megálló kiválasztásakor megszűnt a felesleges kicsinyítés (zoom-out): ha a felhasználó már ráközelített, a térkép megőrzi a zoomot, de a túl távoli (pitched out) nézetből továbbra is ráközelít a megállóra.
- a közös ViaBusz/KanyarGO megállók egy térképi jelölőt és egy közös induláslistát kapnak;
- a budaörsi HOMM 288 és 289 járat megállói, útvonalai és menetrendi indulásai bekerültek;
- a ViaBusz immár külön tanítási és tanszüneti PDF-menetrendet használ;
- a KanyarGO külön tanítási/szüneti naptára megmaradt;
- a HOMM-nál a tanítási és a szüneti/hétvégi/ünnepnapi tábla külön van kezelve;
- a HOMM-hoz szándékosan nincs élő GPS vagy élő érkezési becslés.

A teljes ellenőrzés sikeres volt:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Eredmény: **47 egységteszt sikeres (100%)**, lint: **0 hiba, 19 figyelmeztetés**. A figyelmeztetések főleg külső függőségverziókról és régebbi MapLibre API-król szólnak.

A 0.3.0 kiadási csomagok az `artifacts/` gyökerében és az `artifacts/v0.3/` almappában elkészültek:
- `artifacts/BMenetrend-0.3.0.apk`: 61 808 886 bájt
- `artifacts/BMenetrend-android-forraskod.zip`: 356 658 bájt (77 forrásfájl)
- `artifacts/v0.3/SHA256SUMS.txt`: ellenőrző összegek
- `artifacts/v0.3/VALIDATION.md` és `docs/ELLENORZES.md`: frissített 0.3.0 jegyzőkönyv

## Projekt és korlátozások

- Projektgyökér: `viabusz/`
- Android app: `viabusz/app/`
- Csomagazonosító: `hu.app.bmenetrend`
- Verzió: `versionCode = 3`, `versionName = "0.3.0"`
- Minimum Android: API 26 / Android 8
- Nyelv: Kotlin + Jetpack Compose + Material 3 + MapLibre
- A repository nem Git-munkakönyvtár. Ne feltételezz Git-parancsokat vagy branch-et.
- A teljes workspace `sources/` mappája csak olvasható referenciaanyag. Ne módosítsd, ne mozgasd, ne töröld.
- A `research/` nyers letöltések és ellenőrzési segédanyagok. Az app futásához nem kellenek, de az adatimportok auditálásához hasznosak.

Az aktuális helyi Android környezetben a következő segédscript beállítja a JDK-t és SDK-t:

```sh
source /tmp/viabusz-env.sh
```

Ez lokális környezeti segédlet, nem része a kiadható projektnek. Hordozható gépen JDK 17, Android SDK Platform 36 és Build Tools 35.0.0 kell.

## Mit hol találni

| Terület | Fő fájlok | Szerep |
| --- | --- | --- |
| App belépési pont | `app/src/main/java/hu/app/bmenetrend/MainActivity.kt` | engedélyek, helyzet, Compose gyökér |
| Állapot és frissítés | `TransitViewModel.kt` | kijelölt megálló, kedvencek, 15 másodperces élő frissítés, státuszok |
| Szolgáltatók / cache | `data/TransitRepository.kt` | ViaBusz, KanyarGO és HOMM külön betöltése, napi PDF-ellenőrzés, v3 cache |
| Adatmodellek | `data/Models.kt`, `data/NetworkJson.kt` | schema v3, több szolgáltatós megálló-metaadat |
| Közös megállók | `data/SharedStops.kt`, `data/NetworkMerger.kt` | rögzített kanonizálás, útvonalak és indulások átírása közös azonosítóra |
| Indulásszámítás | `data/DepartureCalculator.kt` | szolgáltatónkénti naptár, végállomási érkezések kiszűrése |
| ViaBusz PDF-naptár | `data/ViabuszTimetableParser.kt`, `data/ServiceCalendar.kt` | tanítási/szüneti validálás |
| KanyarGO PDF-naptár | `data/KanyargoParser.kt`, `data/KanyargoCalendar.kt` | tanítási/szüneti validálás, élő GPS adapter |
| HOMM PDF-naptár | `data/HommParser.kt`, `data/HommCalendar.kt` | 288/289, csak menetrendi adat |
| Compose felület | `ui/ViaBuszScreen.kt` | kereső, iránylista, megállólista, alsó panel, szolgáltatói címkék |
| Térkép | `map/NativeTransitMap.kt` | zöld ViaBusz, lila KanyarGO, narancs HOMM, türkiz közös megálló |
| Magyar szövegek | `res/values/*.xml` | alapnyelv, a felület rendszernyelvtől függetlenül magyar |
| Angol szövegek | `res/values-en/*.xml` | megőrzött fordítások |
| Hálózati snapshotok | `app/src/main/assets/` | futáskor használt, ellenőrzött alapadatok |
| Egységteszt-fixture-ök | `app/src/test/resources/` | assetek és hivatalos oldal/PDF-hivatkozás minták |
| Importok | `scripts/` | reprodukálható Python-importok |
| Adatleírás | `docs/` | források, korlátok, közös megállók, HOMM és ViaBusz részletek |

## Három szolgáltató és naptárszabályok

| Szolgáltató | Járatok | Hálózat | Aktív naptár 2026-ban | Élő adat |
| --- | --- | --- | --- | --- |
| ViaBusz | 1, 1P, 2, 3, 4 | 74 nyers megálló-ID, 79 menet: 49 tanítási + 30 szüneti | 2026-10-01–12-31, csak ellenőrzött munkanapon; őszi/téli szünetben a szüneti PDF | GPS van, de járat-/menet-ID nincs |
| KanyarGO | 1A, 1D, 1J, 1M, 1MT, 1MV, 1T, 1V | 37 nyers megálló-ID, 56 menet: 37 tanítási + 19 szüneti | 2026-09-01–12-31, csak ellenőrzött munkanapon | GPS van, de járat-/menet-ID nincs |
| HOMM | 288, 289 | 78 kanonikus megálló, 149 menet: 93 tanítási + 56 szüneti | 2026-09-01–12-31; a szüneti tábla szombaton, vasárnapon, ünnepnapon és tanítási szünetben aktív | nincs publikus élő adat |

Minden szolgáltató esetében az aktív HTML/PDF-hivatkozásokat és a két importált PDF SHA-256 lenyomatát naponta ellenőrzi az app. Új vagy ismeretlen menetrendi dokumentumnál az adott szolgáltató indulási visszaszámlálása leáll, de a megállók és útvonalak böngészhetők maradnak.

Ne vezesd vissza a régi ViaBusz HTML-es munkanapi táblát az indulásszámításba. Az csak összehasonlítási fixture maradt; nem tud tanítási/szüneti különbséget adni.

## Közös ViaBusz/KanyarGO megállók

Az összevonás **nem** automatikus koordináta-közelségi klaszterezés. A `SharedStops.kt` kifejezetten csak az alábbi ellenőrzött megfeleltetéseket kezeli:

| Közös, kijelzett ViaBusz ID | KanyarGO forrás-ID | Megjegyzés |
| --- | --- | --- |
| `1` Vasútállomás | `kanyargo:29` érkezés, `kanyargo:30` indulás | a KanyarGO állomási érkezése továbbra sem felszállási indulás |
| `2` Ritsmann Iskola | `kanyargo:50` | közös fizikai megálló |
| `67` Juhász Gyula utca (Ellenirány) | `kanyargo:48` | a KanyarGO BIT/Juhász Gyula forrásmegállója |
| `26` Vendel tér | `kanyargo:42` | a KanyarGO forrása több útvonalon aggregálja ezt a pontot |
| `10` Szentháromság tér (észak felé) | `kanyargo:51` | a KanyarGO forrása több útvonalon aggregálja ezt a pontot |

A szemközti ViaBusz-peronok (`85`, `20`, `69`, `66`) szándékosan külön maradnak. A rögzített eredet, távolságok és a forrásbeli iránykorlátok a `docs/KOZOS-MEGALLOK.md` fájlban vannak.

Technikai következmények:

- `TransitStop` új mezői: `providerIds`, `aliases`, `sourceStopIds`.
- A kereső a kijelzett név mellett az `aliases` mezőben lévő korábbi nevekre is keres.
- `NetworkMerger.merge()` végén `SharedStops.merge()` átírja a megállólistát, a route direction `stopIds` listáit és a trip `stopTimes` listáit a kanonikus ID-kra.
- `DepartureCalculator` és `TransitViewModel.selectStop()` a nyers szolgáltatói megálló-ID-t is feloldja a közös ID-ra.
- A szerializált formátum schema 3, de a dekóder schema 1–3-at olvas a régi helyi cache-ek kompatibilitása miatt.
- A cache fájlok szándékosan `*-network-v3.json` néven készülnek, így a 0.2-ből maradt hibás ViaBusz-naptáradat nem használódik újra.

## Hivatalos források és részletes dokumentáció

- ViaBusz: `https://viabusz.biatorbagy.hu/`; részletek: `docs/VIABUSZ.md`
- KanyarGO: `https://kanyargo.paty.hu/`; részletek: `docs/KANYARGO.md`
- HOMM: `https://homm.hu/helyi-kozlekedes/`; részletek: `docs/HOMM.md`
- Budaörs önkormányzati megállótérképe: `https://budaors.hu/terkep/megallok`; a HOMM-koordináták API-ja a `docs/HOMM.md` fájlban szerepel.
- Közös megálló-mapping: `docs/KOZOS-MEGALLOK.md`
- Átfogó adatforrás- és korlátdokumentáció: `docs/ADATFORRAS.md`

A HOMM teljes főmátrixa és a régebbi, megállónkénti PDF-ek kilenc helyen eltérnek. Az app a hivatalos oldalon elsődlegesként szereplő teljes tanítási/szüneti mátrixot használja. A konkrét eltérések tételesen `docs/HOMM.md` alatt vannak; ne keverd a két kiadvány időpontjait találgatással.

## Automatikus ellenőrzések

A tesztfutás 47 sikeres tesztből áll:

- `ViabuszParserTest`: 10 régi HTML/GPS parser-regresszió;
- `ViabuszCalendarTest`: 9 PDF- és tanítási/szüneti naptárteszt;
- `KanyargoTest`: 14 PDF, forrásváltozás, GPS és naptárteszt;
- `HommTest`: 9 hivatalos HOMM-import-, hétvége-, rövidmenet- és végállomásteszt;
- `SharedStopsTest`: 5 közös megálló-, alias-, menetmegőrzés- és szolgáltatónkénti érvényességteszt.

A legfontosabb kézi emulátoros ellenőrzések:

- Vasútállomás kiválasztása: egy térképi pont, `ViaBusz · KanyarGO` címke és kevert induláslista;
- HOMM `288` keresése → két irány → 39 illetve 20 megálló → Budaörsi lakótelep → menetrendi 288/289 indulások;
- HOMM kijelölt megállón `HOMM timetable · no live data` / `HOMM-menetrend · élő adatok nélkül` jelenik meg;
- a 0.2-ből megtartott ellenőrzések: angol/magyar rendszerhez igazodó UI, üres kereső, elrejthető alsó panel, indulásról nyitható megállólista.

Az új képernyőképek: `artifacts/v0.3/screenshots/`. Ezek helyi ellenőrzési bizonyítékok, nem a kiadott alkalmazás részei.

## Kiadási fájlok és ellenőrző összegek (0.3.0)

A csomagolás megtörtént, a kiadási fájlok:
- `artifacts/BMenetrend-0.3.0.apk`: `2bd91f44428dc032750c1351a2fb3f900b3c165348fa4238c2689b3641301518`
- `artifacts/BMenetrend-android-forraskod.zip`: `0be4f81eeeb57b5908af73cc7d86d1e6d282690b1946c5319b175a701846a97c`

Aláírás ellenőrzése:
```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs artifacts/BMenetrend-0.3.0.apk
```
Tanúsítvány SHA-256 lenyomat: `58:7b:17:f8:c1:e6:e9:15:9c:ce:68:3c:2d:4f:b0:b5:c7:fc:6e:9e:b3:a1:ef:13:83:ff:40:fa:88:11:6b:3e`.
