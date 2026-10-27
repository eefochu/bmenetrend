# 🚏 BMenetrend

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![OpenFreeMap](https://img.shields.io/badge/OpenFreeMap-Keyless%20maps-207D92)](https://openfreemap.org/)
[![MapLibre](https://img.shields.io/badge/MapLibre-Native%20Android-blue?logo=maplibre&logoColor=white)](https://maplibre.org)

> Modern, natív Androidos menetrend és élő járatkövető a budapesti agglomerációhoz és Magyarországhoz. Valós idejű járműpozíciók, szinkronizált helyi menetrendek és egységesített átszállópontok — reklámok, követés és felesleges sallang nélkül.

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

---

## 🚀 Fordítás és futtatás

### Követelmények
- **JDK 21**
- **Android SDK Platform 36** (Build Tools 35.0.0+)
- **Android 8.0+** (API level 26+) készülék vagy emulátor

### Parancssori fordítás

```bash
# Repository klónozása
git clone https://github.com/eefochu/bmenetrend.git
cd bmenetrend

# Optimális debug csatornás APK fordítása (R8 + erőforrás-zsugorítás)
./gradlew :app:assembleDebug

# Release APK fordítása
./gradlew :app:assembleRelease

# Hagyományos fejlesztői változat: debugger, Compose-inspektor, gyors fordítás
./gradlew :app:assembleDevelopment

# Egységtesztek futtatása
./gradlew :app:testDebugUnitTest

# Telepítés csatlakoztatott eszközre (univerzális)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Vagy eszköz-specifikus ABI APK (kisebb méret, pl. arm64-v8a eszköz vagy x86_64 emulátor):
# adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

A `debug` és a `release` ugyanazt az optimalizálást használja; a terjeszthető `debug` APK sem debuggolható. A `development` változat debuggolható, külön `hu.app.bmenetrend.dev` csomagnévvel települ. Az alkalmazás ABI-bontást (ABI splits) használ: a rendszer architektúra-specifikus APK-kat (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) készít, valamint egy minden architektúrát tartalmazó univerzális változatot (`app-universal-*.apk`). Az univerzális APK automatikusan átmásolódik az `app-debug.apk` és `app-release.apk` névre is a meglévő OTA frissítések és külső eszközök teljes kompatibilitásáért.

### GitHub Actions CI

Az [Android builds](.github/workflows/android-build.yml) workflow minden push és pull request esetén, valamint kézzel indítva is lefordítja a `debug` és `release` APK-kat, külön feladatokban, Java 21 használatával.

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

### Frissítések közzététele

Az alkalmazás közvetlenül az [`eefochu/bmenetrend` GitHub-kiadásait](https://github.com/eefochu/bmenetrend/releases) ellenőrzi; nincs külön GitHub Pages OTA-JSON. Stabil csatornához egy nem előzetes, verziószámos kiadásba `app-release.apk`, a debug csatornához `app-debug.apk` fájlt kell feltölteni. Egy kiadás mindkét APK-t is tartalmazhat. A changelog a kiadás leírásából származik. Az új kiadás `versionName` és `versionCode` értékét növelni kell, az alkalmazásazonosítót és az aláírókulcsot meg kell tartani a meglévő telepítések frissíthetőségéhez.

## Optional offline mode

Settings now provides an optional offline timetable download and Offline mode switch. See [offline mode](docs/OFFLINE_MODE.md) for supported networks, the 500 MB size cap, source import, and current routing limitations.
