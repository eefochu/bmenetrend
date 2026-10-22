# HOMM 288 / 289 – adatforrás és ellenőrzés

Ellenőrzés: 2026. október 22. A HOMM-adapter kizárólag menetrendi adatot szolgáltat, nem készít valós idejű pozíciót vagy becsült érkezést.

## Hivatalos források

- [HOMM helyi közlekedés](https://homm.hu/helyi-kozlekedes/).
- [Tanítási napok teljes menetrendje](https://homm.hu/www/menetrend-budaors/K%C3%B6zleked%C3%A9si%20rend%20tan%C3%ADt%C3%A1si%20napokon.pdf), SHA-256: `c7fb61b7abbc6fbf1fc65b828870c8fe2d805dc3e6b2f03f4bdb05a505b0ff82`.
- [Tanítási szünet, hétvége és munkaszüneti napok teljes menetrendje](https://homm.hu/www/menetrend-budaors/K%C3%B6zleked%C3%A9si%20rend%20tan%C3%ADt%C3%A1si%20sz%C3%BCnetben.pdf), SHA-256: `792cc9db653af9c453ade424075568f55098ca5a2a6fac8028374e66d902d593`.
- Budaörs önkormányzatának nyilvános térképi megállóadatai: `https://budaors.hu/api/terkep/busz/megallok/{járat}/{irány}`. A használt párok: `288/4`, `288/3`, `289/2`, `289/1`. A koordináták ebből a forrásból származnak; nem geokódolással vagy kézi becsléssel készültek.
- [A 2026/2027-es tanév rendje](https://njt.jog.gov.hu/jogszabaly/2026-1-20-9M), 2. és 7. §. A munkanap-áthelyezéseket a közös `ServiceCalendar` kezeli.

## Tartalom és értelmezés

Az adatállomány 78 megállóazonosítót, a 288-as és a 289-es járatot, öt útvonalváltozatot és 149 teljes menetet tartalmaz. Ebből 93 tanítási napi és 56 szüneti menet. Az összesített PDF-ek hét oldala vizuálisan is ellenőrizve lett.

A négy teljes irány 39, 20, 26 és 18 megállót tartalmaz. A PDF alján szereplő „38 mh”, „19 mh”, „17 mh” összesítés nem indokol megálló elhagyását: a táblázatok és a városi térkép egyező, végállomást is tartalmazó sorai az irányadók. A 289-es tanítási napi 06:59-es Rózsa utca → Ötvös utca menete külön, négyelemű útvonalváltozat. Az üres korábbi cellákhoz nem készültek kitalált időpontok.

A két járat egyes közös megállóit a városi API külön rekordokkal, azonos névvel és egy méteren belüli koordinátával adja vissza. Ezek 25, kifejezetten ellenőrzött párosítás alapján egy megállóként szerepelnek. A párosítások rögzített listája az importáló script `ALIASES` konstansa. Az ellenkező oldali peronokat és az eltérő koordinátájú végállomási állásokat nem olvasztjuk össze távolság alapján.

A „289 - re” és „288 - ra” sorok átszállási segítségek, ezért nem megállók és nem külön indulások. A szüneti táblában 06:55, 07:55, 15:55 mellett álló csillag a **vonatcsatlakozás** munkanapi elérhetőségét jelöli, nem a busz közlekedési napjait. Az utolsó sor végállomási érkezés; a közös indulásszámítás ezt nem mutatja felszállási indulásnak.

## Naptár

Ellenőrzött időszak: **2026. szeptember 1. – december 31.** Az őszi szünet október 23. – november 1., a téli szünet december 19. – január 3. Az ellenőrzött időszakon kívül az app megőrzi a megállókat és az útvonalakat, de nem állítja, hogy az importált indulások az adott napon érvényesek.

A HOMM második PDF-je kifejezetten szombati, vasárnapi, munkaszüneti és tanítási szüneti rendet közöl. Ezért minden szombaton és vasárnapon ez a rend aktív; a december 12-i áthelyezett munkanap-szombaton is. Hétköznap a nem munkanapnak minősülő pihenőnapok/ünnepnapok és az iskolai szünetek szintén ezt a táblát választják. Más ellenőrzött hétköznapon a tanítási rend működik. A két táblázat indulásai egy napon sosem keverednek. Ez eltér a KanyarGO munkanapokra korlátozott működésétől.

## A szolgáltató forrásainak eltérései

Az összesített táblázatot 3664 felszállási időnél összevetettük a HOMM főoldaláról elérhető megállónkénti PDF-ekkel. Kilenc megálló/naptár sorban eltérés van. Az import következetesen az elsődleges, teljes menetrendi mátrixot használja, amelynek minden menete időrendben halad. Nem keverjük össze a kétféle kiadványt, és nem javítjuk feltételezéssel a szolgáltatói értékeket.

| Járat, irány, megálló | Naptár | Megállólap | Importált teljes mátrix |
|---|---|---|---|
| 288 → Iglói köz, Holló utca | tanítási | 16:06, 16:39 | 16:08, 16:38 |
| 288 → lakótelep, Franktanya | tanítási | második 06:51 | 07:51 |
| 288 → lakótelep, Hársfa utca | szüneti | 19:00 | 19:01 |
| 288 → lakótelep, Széles utca | szüneti | 09:08, 20:08 | 09:09, 20:07 |
| 289 → Ötvös utca, Egészségügyi Központ | tanítási | második 15:57 | 16:57 |
| 289 → Ötvös utca, Arany János utca | tanítási | 18:00 hiányzik | 18:00 szerepel |
| 289 → Ötvös utca, Rózsa utca | szüneti | 09:07 | 09:08 |
| 289 → lakótelep, Budafoki utca | tanítási | 06:57, 13:52 | 06:56, 13:53 |
| 289 → lakótelep, Szabadság út | tanítási | 16:32 | 16:31 |

A régi megállólapokon 2015. szeptember 1-i érvényességi felirat látható; ettől függetlenül jelenleg is ezeket hivatkozza a szolgáltató. A fenti elsődleges menetrendi fájlok forrásazonosságát az alkalmazás napi frissítéskor ellenőrzi. Megváltozott PDF vagy új menetrendi hivatkozás esetén letiltja az ellenőrizetlen import időpontjait, és figyelmeztetést mutat.

## Újraépítés és tesztelés

Python 3.10+ és `pdfplumber` szükséges. A projekt gyökeréből:

```bash
python3 scripts/import_homm.py
cp app/src/main/assets/homm-network.json app/src/test/resources/homm-network.json
```

A script ideiglenes könyvtárba letölti a hivatalos két PDF-et és négy térképi JSON-t, ellenőrzi a PDF-ujjlenyomatokat, majd cellánként importál. Elmentett források használata: `python3 scripts/import_homm.py --source-dir research/homm`. `--output` más célfájlt, `--fetched-at` ellenőrzési időbélyeget állít be. A jelenlegi alapértelmezett időbélyeg az ellenőrzött import reprodukálhatóságát szolgálja, nem egy telefonos frissítés idejét jelenti.

Minden megállósornál ellenőrzi a térképi sorrendet és a nevet, minden időcellánál az érvényes órát/percet, minden menetnél a folytonos megállósort és a nem csökkenő időt. A sor- és oszlopszám, 149 menet és öt útvonalváltozat szintén ellenőrzött. Megváltozott forrás vagy elrendezés hiba, nem csendes részleges import.

A `HommTest` az időszakválasztást, hétvégi és ünnepnapi szolgáltatást, a rövid Rózsa utcai menetet, a közös és ellenirányú megállók kezelését, a terminális érkezések kizárását, a csak menetrendi státuszt és a forrásváltozás miatti érvénytelenítést ellenőrzi.
