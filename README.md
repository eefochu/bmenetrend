# 🚏 BMenetrend

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![OpenFreeMap](https://img.shields.io/badge/OpenFreeMap-Keyless%20maps-207D92)](https://openfreemap.org/)
[![MapLibre](https://img.shields.io/badge/MapLibre-Native%20Android-blue?logo=maplibre&logoColor=white)](https://maplibre.org)

> Modern, natív Androidos menetrend és élő járatkövető a budapesti agglomerációhoz és Magyarországhoz. Valós idejű járműpozíciók, szinkronizált helyi menetrendek és egységesített átszállópontok — reklámok, követés és felesleges sallang nélkül.

---

## ✨ Főbb funkciók

- **Több hálózat egy helyen**: A biatorbágyi, pátyi és budaörsi helyi járatok, valamint az országos Volánbusz és budapesti BKK vonalak egyetlen letisztult felületen.
- **Élő járműradar**: Valós idejű GPS-pozíciók, útvonal-geometria, megállók közötti haladás, késési adatok és 15 másodpercenként frissülő járműkártyák.
- **Natív vektortérkép**: Teljes képernyős [MapLibre](https://maplibre.org/) motor [OpenFreeMap](https://openfreemap.org/) vektoros csempékkel, automatikus világos témával és saját, színes éjszakai térképstílussal. API-kulcs és számlázási fiók nélkül.
- **Kategorizált villámkereső**: Gyors keresés három kategóriában: **Helyek** (Photon / OpenStreetMap), **Megállók** (peronelrendezéssel) és **Járművek** (járatszám, rendszám vagy célállomás alapján).
- **Önálló peronok**: A megállók külön, saját forráskoordinátáikkal jelennek meg; az egymást fedő jelölők minimális eltolással külön kiválaszthatók.
- **Utazástervezés**: Külön Tervezés fül cím- és megállókereséssel, EMMA-alapú országos útvonalakkal, ViaBusz-, KanyarGO- és HOMM-csatlakozásokkal, külön Volán- és buszos alternatívákkal, legkorábbi érkezés szerinti sorrenddel, valódi gyalogos szakaszokkal és átszállási tartalékkal. Indulás most vagy kiválasztott napon és időpontban; színes menetidősávok és megnyitható, időpontokkal, peronokkal és köztes megállókkal ellátott útvonallánc.
- **Kedvenc megállók**: Saját fül a szívvel mentett állomások és megállók érkező járataival, valós idejű jelzésekkel és legfeljebb 5 órás indulási ablakkal. A visszaszámlálás tömör: `4p`, `1ó 3p`.
- **Vasút**: Országos MÁVPlusz-vonatkövetés, vonalszám, vonatszám, vonatnév, vágányadatok és a tényleges útvonal megállói. A vasúti megállók és járművek tágabb nézetben is láthatók, azonos méretezéssel.
- **Közeli járatok**: Alapértelmezett alsó kártya a helyzetedhez vagy a térkép közepéhez közeli megállók következő indulásaival, élő és menetrendi jelzéssel.
- **Open Sans**: Beépített betűkészlet, 700-as vastagságú járatszámokkal; a metrók és HÉV-ek csak a számozott emblémakört használják.
- **Material You & egyéni színek**: Finom animációk, edge-to-edge felület, rendszerhez igazodó dinamikus Monet színek (Android 12+) vagy egyéni RGB/HEX színválasztó.
- **Fiókmentes használat**: Nincs beépített analitika. A térkép- és keresőszolgáltatók a kérések kiszolgálásához megkapják az IP-címet és a kért adatokat.

---

## 🚌 Támogatott közlekedési hálózatok

| Szolgáltató | Terület | Lefedettség és jellemzők |
|---|---|---|
| **ViaBusz** | Biatorbágy | `1`, `1P`, `2`, `3`, `4` járatok hivatalos tanítási és tanszüneti naptárral |
| **KanyarGO** | Páty | `1A`, `1D`, `1J`, `1M`, `1MT`, `1MV`, `1T`, `1V` iskolai és normál menetrenddel |
| **HOMM** | Budaörs | `288`, `289` vonalak hitelesített önkormányzati megállókkal és útvonalakkal |
| **Volánbusz** | Országos | Helyközi és távolsági autóbuszok MÁV EMMA GraphQL adatokból |
| **BKK** | Budapest | Buszok, villamosok, trolik és metrók MÁV EMMA integrációval |
| **Vasút (EMMA)** | Országos | S/Z/G-vonalak, IC/IR és más vonatok, TramTrain, élő pozíciók és vágányadatok |
| **MÁV-HÉV** | Budapest és agglomeráció | H5, H6, H7, H8 és H9: élő pozíciók, állomások és indulások MÁVPlusz-adatokból |

A közös átszállópontok (pl. *Biatorbágy vasútállomás*) automatikusan egy közös, összevont indulási táblán jelennek meg.

---

## 🛠️ Architektúra és technológiák

- **UI keretrendszer**: Deklaratív felület [Jetpack Compose](https://developer.android.com/jetpack/compose) és [Material 3](https://m3.material.io/) alapokon
- **Térképmotor**: [MapLibre Native Android](https://maplibre.org/) + [OpenFreeMap](https://openfreemap.org/) vektoros csempék
- **Hálózat és adatfolyam**: OkHttp, Kotlinx Serialization, közvetlen GraphQL kliens a MÁV EMMA / MÁVPlusz végpontokhoz
- **Helykeresés**: Cím- és helymeghatározás a [Photon](https://photon.komoot.io/) / OpenStreetMap API segítségével
- **Állapotkezelés**: MVVM minta Kotlin Coroutines és StateFlow architektúrával, helyi gyorsítótárral

Az alkalmazás csomagazonosítója és Kotlin-névtere `hu.app.bmenetrend`. A felület, az állapotüzenetek és a dátumválasztó a telefon rendszernyelvétől függetlenül magyarul jelennek meg. Az alapértelmezett szövegek a `res/values/`, a megőrzött angol fordítások a `res/values-en/` könyvtárban találhatók.

---

## 🚀 Fordítás és futtatás

### Követelmények
- **JDK 21**
- **Android SDK Platform 36** (Build Tools 35.0.0+)
- **Android 8.0+** (API level 26+) készülék vagy emulátor

### Parancssori fordítás

```bash
# Repository klónozása
git clone https://github.com/micr0softstore/bmenetrend.git
cd bmenetrend

# Optimalizált debug csatornás APK fordítása (R8 + erőforrás-zsugorítás)
./gradlew :app:assembleDebug

# Release APK fordítása
./gradlew :app:assembleRelease

# Hagyományos fejlesztői változat: debugger, Compose-inspektor, gyors fordítás
./gradlew :app:assembleDevelopment

# Egységtesztek futtatása
./gradlew :app:testDebugUnitTest

# Telepítés csatlakoztatott eszközre
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A `debug` és a `release` ugyanazt az optimalizálást használja; a terjeszthető `debug` APK sem debuggolható. A `development` változat debuggolható, külön `hu.app.bmenetrend.dev` csomagnévvel települ. A nagy univerzális APK mind a négy CPU-architektúrához tartalmaz natív MapLibre-könyvtárakat.

### GitHub Actions CI

Az [Android builds](.github/workflows/android-build.yml) workflow minden push és pull request esetén, valamint kézzel indítva is lefordítja a `debug` és `release` APK-kat, külön feladatokban, Java 21 használatával. Az Actions-futásból a `bmenetrend-debug` és `bmenetrend-release` artifactok tölthetők le; a hozzájuk tartozó R8 mapping fájlok külön artifactokban érhetők el. Az artifactok megőrzési ideje 14 nap.

Mindkét változat a projekt jelenlegi debug aláírását használja; a CI a futtató által generált debug kulccsal írja alá az APK-kat. Meglévő, más kulccsal aláírt telepítésre ezek nem telepíthetők frissítésként. Állandó kiadási kulcsot és GitHub Release-publikálást ez a workflow nem állít be.

Váratlan Java/Kotlin-leállás után a következő indításkor a helyben mentett hibajelentés kimásolható. Android 11-től a natív összeomlás vagy memória miatti folyamatleállás rendszeradatai is megjelenhetnek. A jelentés nem kerül automatikusan elküldésre; másoláskor vagy bezáráskor a mentett példány törlődik.

> [!TIP]
> Android emulátorban a gördülékeny térképmegjelenítéshez ajánlott a hardveres gyorsítást (**Host GPU**) bekapcsolni.

---

## 📖 Részletes dokumentáció

A háttérrendszerek működése és a specifikációk a [`docs/`](docs/) mappában találhatók:
- [`docs/MAP.md`](docs/MAP.md) – Kulcsmentes térkép és saját éjszakai stílus
- [`docs/ADATFORRAS.md`](docs/ADATFORRAS.md) – Nyilvános adatforrások és frissítési folyamat
- [`docs/VOLAN.md`](docs/VOLAN.md) – MÁV EMMA GraphQL integráció és peronelrendezés
- [`docs/HEV.md`](docs/HEV.md) – HÉV-adatforrások és vonalemblémák
- [`docs/KOZOS-MEGALLOK.md`](docs/KOZOS-MEGALLOK.md) – Több szolgáltató közös megállóinak összerendelése
- [`docs/VIABUSZ.md`](docs/VIABUSZ.md) & [`docs/KANYARGO.md`](docs/KANYARGO.md) – Helyi menetrendi és naptárszabályok

---

## 🔒 Adatvédelem és filozófia

A BMenetrend független, nyílt közösségi segédprogram.
- A helyjelölő és a legközelebbi megálló kiválasztása helyben használja a GPS-helyzetet. Az útvonaltervezéshez az EMMA megkapja a kiindulópont és a cél koordinátáit, valamint az indulási időt. Ha a jelenlegi helyzetedről tervezel, ez a GPS-koordinátáidat is jelenti. A Photon a keresőszöveget kapja meg, a GPS-helyzetet nem.
- Nincsenek beépített analitikák, követőkódok vagy háttérben futó adatgyűjtők.
- A térképhez, helykereséshez és menetrendekhez nincs szükség fiókra vagy privát API-kulcsokra. A térképcsempék és keresési találatok letöltése internetkapcsolatot igényel.
