# 🚏 BMenetrend

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![MapLibre](https://img.shields.io/badge/MapLibre-Native%20Android-blue?logo=maplibre&logoColor=white)](https://maplibre.org)

> Modern, natív Androidos menetrend és élő járatkövető a budapesti agglomerációhoz és Magyarországhoz. Valós idejű járműpozíciók, szinkronizált helyi menetrendek és egységesített átszállópontok — reklámok, követés és felesleges sallang nélkül.

---

## ✨ Főbb funkciók

- **Több hálózat egy helyen**: A biatorbágyi, pátyi és budaörsi helyi járatok, valamint az országos Volánbusz és budapesti BKK vonalak egyetlen letisztult felületen.
- **Élő járműradar**: Valós idejű GPS-pozíciók, útvonal-geometria, megállók közötti haladás, késési adatok és 15 másodpercenként frissülő járműkártyák.
- **Natív vektortérkép**: Teljes képernyős [MapLibre](https://maplibre.org/) motor [OpenFreeMap](https://openfreemap.org/) vektoros csempékkel, automatikus világos és sötét témával.
- **Kategorizált villámkereső**: Gyors keresés három kategóriában: **Helyek** (Photon / OpenStreetMap), **Megállók** (peronelrendezéssel) és **Járművek** (járatszám, rendszám vagy célállomás alapján).
- **Összekapcsolt peronok**: A közös megállók és nagyobb csomópontok peronjai részletes nézetben automatikusan összefüggő láncként, vizuálisan összekötve jelennek meg.
- **Material You & egyéni színek**: Finom animációk, edge-to-edge felület, rendszerhez igazodó dinamikus Monet színek (Android 12+) vagy egyéni RGB/HEX színválasztó.
- **100% privát, fiókmentes**: Minden adatfeldolgozás közvetlenül az eszközön történik. Nincs regisztráció, nincs analitika és nincs adatgyűjtés.

---

## 🚌 Támogatott közlekedési hálózatok

| Szolgáltató | Terület | Lefedettség és jellemzők |
|---|---|---|
| **ViaBusz** | Biatorbágy | `1`, `1P`, `2`, `3`, `4` járatok hivatalos tanítási és tanszüneti naptárral |
| **KanyarGO** | Páty | `1A`, `1D`, `1J`, `1M`, `1MT`, `1MV`, `1T`, `1V` iskolai és normál menetrenddel |
| **HOMM** | Budaörs | `288`, `289` vonalak hitelesített önkormányzati megállókkal és útvonalakkal |
| **Volánbusz** | Országos | Helyközi és távolsági autóbuszok MÁV EMMA GraphQL adatokból |
| **BKK** | Budapest | Buszok, villamosok, trolik és metrók MÁV EMMA integrációval |

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
- **JDK 17**
- **Android SDK Platform 36** (Build Tools 35.0.0+)
- **Android 8.0+** (API level 26+) készülék vagy emulátor

### Parancssori fordítás

```bash
# Repository klónozása
git clone https://github.com/micr0softstore/bmenetrend.git
cd bmenetrend

# Debug APK fordítása
./gradlew :app:assembleDebug

# Egységtesztek futtatása
./gradlew :app:testDebugUnitTest

# Telepítés csatlakoztatott eszközre
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> [!TIP]
> Android emulátorban a gördülékeny térképmegjelenítéshez ajánlott a hardveres gyorsítást (**Host GPU**) bekapcsolni.

---

## 📖 Részletes dokumentáció

A háttérrendszerek működése és a specifikációk a [`docs/`](docs/) mappában találhatók:
- [`docs/ADATFORRAS.md`](docs/ADATFORRAS.md) – Nyilvános adatforrások és frissítési folyamat
- [`docs/VOLAN.md`](docs/VOLAN.md) – MÁV EMMA GraphQL integráció és peronelrendezés
- [`docs/KOZOS-MEGALLOK.md`](docs/KOZOS-MEGALLOK.md) – Több szolgáltató közös megállóinak összerendelése
- [`docs/VIABUSZ.md`](docs/VIABUSZ.md) & [`docs/KANYARGO.md`](docs/KANYARGO.md) – Helyi menetrendi és naptárszabályok

---

## 🔒 Adatvédelem és filozófia

A BMenetrend független, nyílt közösségi segédprogram.
- A GPS-helyzet **kizárólag a készüléken** kerül felhasználásra a legközelebbi megálló megtalálásához.
- Nincsenek beépített analitikák, követőkódok vagy háttérben futó adatgyűjtők.
- Az alapvető böngészéshez és menetrendekhez nincs szükség fiókra vagy privát API-kulcsokra.
