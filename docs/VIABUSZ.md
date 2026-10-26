# ViaBusz tanítási és tanszüneti menetrend

Ellenőrizve: 2026. október 22.

A ViaBusz webes járatlekérdezése a jelenlegi tanítási táblát adja vissza, általános „Munkanapokon” megjelöléssel. Ez önmagában nem elegendő a tanítási szünet eldöntéséhez. A BMenetrend 0.3 ezért két, a szolgáltató által közzétett PDF-et importál, és minden járatmenetnél külön tárolja a `school_term` vagy `school_break` naptárt.

Források:

- [ViaBusz hivatalos oldal](https://viabusz.biatorbagy.hu/)
- [Tanítási időszak, 2026. október 1-től](https://viabusz.biatorbagy.hu/documents/viabusz-menetrend-tanitas-261001.pdf)
- [Tanítási szünet, 2026. szeptember 1-től](https://viabusz.biatorbagy.hu/documents/viabusz-menetrend-szunet-260901.pdf)
- [A 2026/2027. tanév rendje](https://njt.jog.gov.hu/jogszabaly/2026-1-20-9M)

| Járat | Tanítási munkanap | Tanszüneti munkanap |
| --- | ---: | ---: |
| 1 | 12 | 8 |
| 1P | 6 | 5 |
| 2 | 14 | 10 |
| 3 | 9 | 0 |
| 4 | 8 | 7 |
| Összesen | 49 | 30 |

A 3-as a tanszüneti PDF-ben nem szerepel, ezért ilyen napokon nem mutatunk rá indulást. Az egyes táblázatokban csillaggal jelölt továbbközlekedések külön járatmenetként szerepelnek az új járatszámuk alatt. A körjáratok utolsó vasútállomási időpontja érkezés; nem keletkezik belőle új felszállási indulás. A vonatkéséshez kötött legfeljebb háromperces várakozás lehetőség, nem mért késés, így a kijelzett menetrendi időhöz nem adunk automatikusan három percet.

A tanítási PDF mind a négy oldalát és a tanszüneti PDF mindhárom oldalát képként is ellenőriztük. Az összes 49 tanítási menet minden megállói időadata egyezik a korábban mentett hivatalos HTML-járatadatokkal. A 74 eredeti ViaBusz-megállóazonosító, a koordináták és az öt útvonal megállósorrendje változatlan. A közös szolgáltatói megállók összevonását a hálózat egyesítése végzi.

Az érvényes tanítási menetrend kezdete miatt a felülvizsgált tartomány 2026. október 1. – december 31. A tanév országos őszi szünete október 23. – november 1., téli szünete december 19. – január 3. A munkaszüneti napokat és a 2026-os áthelyezett munkanapokat ezen felül a közös `ServiceCalendar` ellenőrzi. 2027-re nincs automatikusan feltételezett munkanapnaptár. A KanyarGO is ugyanezeket a tanítási/tanszüneti időszakokat használja, a saját külön menetrendi tábláival.

Indításkor és napi frissítéskor a program ellenőrzi a hivatalos menüben szereplő mindkét PDF-linket és mindkét dokumentum SHA-256 lenyomatát. A régi hírekben megmaradt dokumentumlinkek nem határozzák meg az aktuális menetrendet. Ismeretlen új menetrend esetén a korábbi import nem lesz aznapi indulási előrejelzésként felhasználva. A megállók és az elérhető élő GPS-adatok ettől továbbra is megmaradnak.

Ellenőrzött lenyomatok:

```text
546466bd8d5a0afe0396015dc43252979995a7bce98346a9c9b063e63e65deb0  viabusz-menetrend-tanitas-261001.pdf
e18278f78baf63477af6737debf9352a5e3feecd869c354d5325299a0224a846  viabusz-menetrend-szunet-260901.pdf
```

## Reprodukálható import

Python 3 és Poppler `pdftotext` szükséges. A következő parancs letölti a két hivatalos PDF-et, ellenőrzi a lenyomatokat, az oszlopok számát, az összes megállónevet, a megállósorrendet és az időpontok monotonitását:

```bash
python3 scripts/build_viabusz_calendar_snapshot.py
```

Mentett forrásfájlokkal:

```bash
python3 scripts/build_viabusz_calendar_snapshot.py --source-dir research
```

A mappában a `calendar-via-school.pdf` és `calendar-via-break.pdf` fájlok kellenek. Az eredmény `app/src/main/assets/viabusz-calendar-network.json`; az eredeti `network.json` a régi HTML-parser teszteléséhez változatlanul megmarad. Új PDF esetén előbb teljes forrásellenőrzés és vizuális áttekintés szükséges, nem elegendő csak a lenyomatot kicserélni. A tesztpéldány frissítése is szükséges az `app/src/test/resources/viabusz-calendar-network.json` helyen.

## Becsült élő járatszám

A GPS-forrás továbbra sem ad megerősített járat-/menetazonosítót. A kliens most külön, **becsült** járatszámot is megjeleníthet a ViaBusz járműveinél. A normál zöld járatjelzés mellett, azon kívül sárga `Becsült` szöveg jelenik meg a járműpanelen és a keresőben. A becslés nem változtatja meg a forrás `routeId`, `routeNumber`, `tripId` mezőit, nem készít élő indulási előrejelzést, és nem érinti a többi szolgáltatót.

A `ViabuszRouteEstimator` több, különböző időpontú GPS-fixet illeszt a hivatalos GPX-nyomvonalakra. A `scripts/build_viabusz_geometry.py` az öt GPX-et a menetrend sorrendezett megállói mentén bontja útszakaszokra. Az 1P GPX csak a kitérőt tartalmazza, ezért a közös szakaszokhoz az 1-es nyomvonalát is használjuk. A GPX-fájlok legfeljebb 15 méteres felmérési hézagai összeköthetők; hosszú hiányzó utcákat nem egészítünk ki egyenes vonallal. A letöltési URL-ek és SHA-256-lenyomatok az `app/src/main/assets/viabusz-route-geometry.json` fájlban vannak. Az import újrafuttatható a fenti Python-szkripttel; frissítés után a geometriai és visszajátszási teszteket is futtatni kell.

A becslés feltételei:

- Aznap ellenőrzött PDF-forrás és aktív tanítási/tanszüneti naptár.
- Legalább három külön GPS-időpont, legalább 20 másodpercnyi megfigyelés és 60 méter előrehaladás; az első egyértelmű találat után még egy friss csomag megerősítése szükséges. A 15 másodperces frissítésnél ez kedvező esetben kb. 45 másodperc.
- Az úttól legfeljebb 110 méteres egyedi eltérés, legfeljebb 55 méteres átlagos négyzetes hiba; irányban és sebességben is következetes haladás a megállók sorrendjében.
- A haladáshoz illő, legfeljebb öt perccel korábbi vagy harminc perccel későbbi menetrendi pozíció. A konstans késést megengedjük; a változó időeltérés rontja a pontszámot.
- A legjobb másik járat legalább 25 ponttal gyengébb összesített illeszkedése. Közös útszakasz, bizonytalan irány vagy közeli pontszám esetén a szám rejtve marad.

A legfeljebb ötperces, tizenkét csomagos előzmény csak a memóriában él. Ismételt csomag nem erősíti a találatot. 90 másodpercnél régebbi GPS, hosszú adathiány, valószínűtlen ugrás, végállomási tartózkodás vagy egyperces állás törli a becslést; járatváltás után új megerősítés kell. Azonos időbélyeghez eltérő helyadat szintén érvényteleníti az előzményt.

A tesztek mesterséges, ismert irányú és idejű mozgásokat, valamint az öt hivatalos nyomvonal zajjal kiegészített menetrendi visszajátszását használják. Ezek regressziós ellenőrzések; hitelesített, valódi jármű–járat párosítás hiányában nem állítunk mért valós pontossági százalékot. Nagy késés, terelés, GPS-hiba és közös nyomvonal továbbra is korlátozhatja vagy elnémíthatja a becslést.
