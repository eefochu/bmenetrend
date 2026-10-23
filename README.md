# BMenetrend – natív Android, Biatorbágy, Páty és Budaörs

Kotlin + Jetpack Compose alkalmazás, Material 3 / Material You felülettel. Android 8.0 vagy újabb rendszer szükséges; Android 12-től a Rendszer színbeállítás a telefon színeihez igazodik. A Beállítások → Kiemelőszín alatt zöld, kék, lila, rózsaszín vagy narancssárga téma, illetve RGB-csúszkákkal vagy hexadecimális kóddal egyéni szín választható; az app megőrzi a választást. A Beállítások teljes oldalon jelenik meg, és az adatforrás-információk is innen érhetők el. A térkép követi a rendszer világos/sötét módját; sötét módban színesebb vízfelületek, jól elkülönülő utak és olvasható feliratok segítik a tájékozódást.

## A 0.3.0 változat

- A közös ViaBusz/KanyarGO megállók egy térképi pontként jelennek meg, közös induláslistával. Vasútállomás, Ritsmann Iskola, Juhász Gyula utca, Vendel tér és Szentháromság tér. A szemközti peronokat megőrizzük. A korábbi megállónevek kereshetők, a mentett kedvencek megmaradnak.
- Bekerült a budaörsi **HOMM 288 és 289** menetrendje és megállói. A tanítási napok és a tanítási szünet/hétvége/ünnepnap külön táblát használ. Nincs HOMM élő GPS vagy érkezési becslés: a felület ezt egyértelműen jelzi.
- **Javított ViaBusz-naptár:** a korábbi munkanapi HTML-tábla helyett a külön hivatalos tanítási és tanszüneti PDF-ekből számol. Tanszünetben például nincs 3-as indulás. A KanyarGO két naptártípusa továbbra is külön marad.
- Megmaradt az angol alapnyelv és automatikus magyar felület, az üresen megnyíló kereső, a teljesen elrejthető indulási panel és az indulásból közvetlenül megnyitható irány szerinti megállólista.

## Járatok, megállók és térkép

**ViaBusz:** 1, 1P, 2, 3, 4; 74 forrásbeli megállóazonosító, 49 tanítási és 30 tanszüneti munkanapi menet.

**KanyarGO:** 1A, 1D, 1J, 1M, 1MT, 1MV, 1T, 1V; 37 forrásbeli megállóazonosító, tanítási és tanszüneti menetrend. A két időszak menetei nem keverednek. A regisztrációhoz kötött iskolai szakaszokat a felület külön jelzi.

**HOMM:** 288, 289; 78 térképi megálló, 93 tanítási és 56 szüneti menet. A 289-es reggeli rövid menete külön útvonalváltozat. A koordináták Budaörs önkormányzatának hivatalos térképéről származnak.

A natív MapLibre térképen a ViaBusz megállói és járművei zöld, a KanyarGO adatai lila jelölést kapnak. A HOMM megállói narancs, a közös megállók türkiz jelölésűek. A kék pont a készülék helyzete. A kereső és az induláslista jelzi a szolgáltatót is. Részletes megfeleltetés: [KOZOS-MEGALLOK.md](docs/KOZOS-MEGALLOK.md).

Helyengedéllyel a legközelebbi megálló automatikusan kiválasztódik. Kézi választást nem ír felül egy későbbi GPS-frissítés. Megálló választásakor a panel visszanyílik. Kedvencek és 30/60/120 perces indulási időablak helyben menthetők.

## Mit jelentenek az élő adatok?

A ViaBusz és a KanyarGO hivatalos weboldalának nyilvános járműfeedje friss GPS-koordinátákat szolgáltat, de az ellenőrzött válaszok **nem tartalmaznak megbízható járat-/menetazonosítót**. Ezért az azonosítatlan járművek `BUS` / `BUSZ` jelölést kapnak. Rákoppintva a szolgáltató, a járműazonosító és a GPS-adat időpontja olvasható.

A számozott indulások **Scheduled / Menetrend szerint** jelölésűek. Az alkalmazás nem rendeli találgatással egy buszhoz a menetrendben legközelebbi indulást. Ha a szolgáltató tényleges jármű–megálló ETA-párt küld, az külön, ellenőrzött megállói jelzésként használható; a hiányzó járatazonosítót ez sem pótolja.

A számozott, járatonkénti valós idejű érkezéshez további szolgáltatói adatkapcsolat kell, például GTFS + GTFS-Realtime. Az app 15 másodperces szünetekkel frissít az előtérben; 180 másodpercnél régebbi GPS-pozíciót nem mutat élőként.

## Menetrend, érvényesség, kimaradások

Mindhárom szolgáltató tanítási és tanszüneti menetrendjét a hivatalos PDF-ekből, vizuálisan ellenőrzött import tartalmazza. A kliens naponta ellenőrzi az aktuális oldalt és a PDF-ek lenyomatát. Új vagy megváltozott dokumentum esetén az érintett menetrend visszaszámlálása leáll, amíg az importot nem frissítik; a megállók, útvonalak és friss GPS-adatok tovább használhatók.

A szolgáltatók külön cache-t és érvényességi dátumot használnak: az egyik hibája nem kapcsolja ki a másik menetrendjét. Offline a mentett hálózat böngészhető; menetrendi indulás csak aznapra érvényes, ellenőrzött adatból számolható.

A magyar munkanaptár **2026-ra** ellenőrzött. A KanyarGO és HOMM tanítási/tanszüneti választásának ellenőrzött tartománya 2026. szeptember 1.–december 31., az őszi és téli szünettel; a ViaBusz új tanítási táblája október 1-jétől érvényes. A HOMM szombati, vasárnapi és ünnepnapi közlekedése a szolgáltató szüneti tábláját követi; a ViaBusz és KanyarGO csak ellenőrzött munkanapokra ad menetrendi indulásokat. A következő évekhez a hivatalos munkanap-áthelyezések és menetrendi források ellenőrzése szükséges. Ellenőrizetlen napra a program figyelmeztetést ad, és nem állít elő bizonytalan visszaszámlálást.

A PDF-ben említett Vitamin-domb igénymegállóhoz nincs ellenőrzött térképi koordináta és indulási idő, ezért ez nem szerepel a térképen.

Részletes forrásleírás: [docs/ADATFORRAS.md](docs/ADATFORRAS.md) és [docs/KANYARGO.md](docs/KANYARGO.md).

## Országos Volán és BKK az EMMA/MÁVPlusz rendszeréből

A Volán-buszok országosan, a BKK buszai, villamosai, trolijai és metrójárművei Budapesten az **EMMA/MÁVPlusz GraphQL** adatforrásából jelennek meg. Közvetlen BKK API-t nem használunk. A Volán-jelölők sárgák, a BKK buszai világoskék, a trolik piros, a villamosok sárga közlekedési ikont kapnak. A járműnél a vonalszám, azonosító/rendszám, célállomás, sebesség és a forrásadat ideje olvasható. A régi vagy időbélyeg nélküli pozíciókat nem mutatjuk.

Az **Országos hálózat** alapból bekapcsolt, és a teljes országot egy kérés tölti be; nincs térképrészlethez kötött nagyítási korlát. Kikapcsolva a Biatorbágyot, Pátyot és Budaörsöt érintő Volán-vonalak és a Budapesten belüli BKK-járművek maradnak. Az országos vonalkatalógus aktív jármű nélkül is kereshető; az ellenőrzött 36 helyi Volán-vonal hivatalos menetrendje továbbra is megnyitható. A Volán- és BKK-megállók országosan kereshetők, térképen kiválaszthatók és kedvencekhez adhatók. A megállókat számozatlan pontok jelzik, közepes nagyítástól nagyobb megállógyűrűkkel; a járművek távolabbról is láthatók. Egy járműre koppintva megnyílik a vonalszámot, irányt, járműadatokat és az EMMA által közölt, sorrendezett megállólistát tartalmazó alsó kártya. A kiválasztott megálló indulási tábláját szintén az EMMA szolgáltatja; csak a valós idejűnek jelölt adatok kapnak élő jelzést. A megállólista helyben gyorsítótárazott. Vonatokat ez az integráció nem ad hozzá. Részletek: [VOLAN.md](docs/VOLAN.md).

## Fordítás és telepítés

JDK 17, Android SDK Platform 36 és Build Tools 35.0.0 szükséges. Android Studio alatt ezt a projektmappát nyisd meg; a `local.properties` a helyi SDK elérési útját tartalmazza.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Az APK a `app/build/outputs/apk/debug/app-debug.apk` fájlban keletkezik. A mellékelt `BMenetrend-0.3.0.apk` fejlesztői aláírású, és a korábban átadott ViaBusz telepítővel azonos aláírást használ. Saját, eltérő kulccsal fordított APK csak az azonos kulcsú telepítést tudja frissíteni. Áruházi kiadáshoz saját release-aláírás szükséges.

API-kulcs, fiók és saját szerver az alapfunkciókhoz nem kell.

## Adatvédelem és térkép

A készülék GPS-koordinátája helyben szolgál a legközelebbi megálló kiszámítására. Az EMMA-adatforrás minden alkalommal azonos országos határokat kap, nem a készülék helyét vagy az aktuális térképrészletet. Helyengedély nélkül a térkép, kereső és menetrend továbbra is működik. Nincs háttérbeli helyzetkövetés, analitika, reklám vagy bejelentkezés. A térképszolgáltató a megjelenített térképrészlet lekérését és a kapcsolat IP-címét megkapja.

Térkép: [MapLibre Native](https://maplibre.org/maplibre-native/android/) és [OpenFreeMap](https://openfreemap.org/quick_start/), OpenStreetMap/OpenMapTiles adatokkal. Források: [ViaBusz](https://viabusz.biatorbagy.hu/) [KanyarGO](https://kanyargo.paty.hu/), [HOMM](https://homm.hu/helyi-kozlekedes/) és [Budaörs térképe](https://budaors.hu/terkep/megallok). A BMenetrend független, nem hivatalos kliens.

## Fejlesztési megjegyzések

- `data/`: szolgáltatói adapterek, naptárak, forrásérvényesség, cache és indulásszámítás.
- `ui/`: Compose képernyők, keresés és húzható panel.
- `map/`: natív térkép és jelölők.
- `res/values/`, `res/values-hu/`: angol és magyar szövegek.
- `assets/`: ellenőrzött hálózati pillanatképek.
- `scripts/`: a szolgáltatói PDF-importok reprodukálásához szükséges eszközök.

A MapLibre 11.8 OpenGL emulátoron SwiftShader módban ismert szimbólum-megjelenítési hibába futhat. Az emulátor grafikai módját `Hardware` / host GPU módra állítsd; a kész APK-t ezzel ellenőriztük. [Upstream hibajegy](https://github.com/maplibre/maplibre-native/issues/3648).

A kereső felismeri a járműtípusokat magyarul és angolul: például az `1 villamos`, `villamos 1` vagy `1 tram` az 1-es villamosra keres. A pontos vonalszámok előre kerülnek, a megállókereső gyorsítótárazott, ékezetfüggetlen szövegindexet használ. A járműkártya az EMMA következő megállója és a menet sorrendje alapján mutatja a két érintett megállót; a megállóban tartózkodás közelségből és sebességből számított, külön jelölt GPS-becslés.

A vonalak közlekedési ikonnal és saját színű számbuborékkal jelennek meg. Keresés után irányt, majd felszállóhelyet választhatsz; az induláslista a kiválasztott útirányra szűr. Az EMMA által közölt útvonal a térképen is látszik, a járműkártya pedig színes megállóláncon mutatja az aktuális szakaszt. Az indulásoknál a várható és menetrendi idő, a késés, illetve az elérhető akadálymentességi és kerékpár-információ is megjelenik.

A kereső Helyek, Megállók és Járművek részekre bontja a találatokat, kategóriánként három előnézettel és Továbbiak gombbal. A Photon/OpenStreetMap helykereső az olyan kereséseket is felismeri, mint a `11.kerület`; helyválasztáskor a térkép a találatra ugrik. A járművek rendszám és célállomás alapján is kereshetők.
