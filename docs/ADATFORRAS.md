# BMenetrend adatforrások és tényleges korlátok

A helyi hálózat **183 térképi megállót és 15 járatjelzést** tartalmaz; ehhez adódik az EMMA országos Volán/BKK-megállólistája. A ViaBusz/KanyarGO 111 forrásazonosítójából az ellenőrzött közös megállók összevonása után 105 térképi pont marad; ehhez adódik a HOMM 78 megállója. A szolgáltatói menetek és az indulási/érkezési szerepek megmaradnak.

| Szolgáltató | Tanítási menetek | Szüneti menetek | Élő adat |
| --- | --- | --- | --- |
| ViaBusz | 49 | 30, csak munkanap | GPS, járatazonosító nélkül |
| KanyarGO | 37 | 19, csak munkanap | GPS, járatazonosító nélkül |
| HOMM | 93 | 56, szünet/hétvége/ünnepnap | Nincs |

A menetrendeket a hivatalos PDF-ek vizuálisan ellenőrzött, reprodukálható importja adja. Az app naponta ellenőrzi az aktív dokumentumhivatkozásokat és a PDF-ek lenyomatát. Új forrásnál az adott szolgáltató indulási visszaszámlálása felülvizsgálatig leáll; a megállók és útvonalak megmaradnak. A szolgáltatók egymástól függetlenül érvényesek és frissülnek. A `network-v3` cache név megakadályozza a korábbi ViaBusz munkanapi tábla téves újrafelhasználását.

Részletek és források: [ViaBusz](VIABUSZ.md), [KanyarGO](KANYARGO.md), [HOMM](HOMM.md), [közös megállók](KOZOS-MEGALLOK.md).

A KanyarGO hat szerződéses iskolai szakaszát a felület megjelöli. A friss GPS-pozíciók élők, a járatszámos indulások a helyi ViaBusz/KanyarGO/HOMM-hálózaton menetrendiek. Nincs kitalált GPS→járat összerendelés vagy HOMM-járműfeed.

Az alábbi technikai rész a ViaBusz eredeti HTML- és élő forrásának feldolgozását dokumentálja. A HTML menettáblája összehasonlítási referencia maradt; a 0.3-as kliens **a PDF-menetrendeket használja** a tanítási és tanszüneti különbség miatt.

## Hálózat és menetrend

- Forrás: https://viabusz.biatorbagy.hu/
- `POST /`, `application/x-www-form-urlencoded`, `routeSelect=10` … `14`.
- Route IDs: `10=1`, `11=1P`, `12=2`, `13=3`, `14=4`.
- A `#routeSelect` opcióiból származnak az útvonalnevek.
- A `#timeTableData tr[routeId]` sorai tartalmazzák az adott járat sorrendezett megállóit. Megállóazonosító: `.digitalClock#bsClock<ID>`; név a második cellában; koordináták az `img[onclick="...zoomMapToPoint(lon,lat)..."]` attribútumban.
- FIGYELEM: a `busStopPopupBoxMP<szám>_<irány>` popup ID első száma **sorszám**, nem megállóazonosító. Különösen a 3-as/4-es útvonal esetén nem szabad ezt valódi megálló-ID-nak tekinteni.
- `div#busStopPopupBoxMPST<ID>` menetek: h3=járatszám, minden td pár=megállónév és idő. A teljes 49 menet mindegyik lapon megtalálható; a parser járatonként szűr.
- Mind az öt járat a Vasútállomástól a Vasútállomásig közlekedő körjárat. Az 1,1P,3,4 irányazonosítója 1, a 2-esé 2. Nincs adat két külön választható ellenirányról ugyanazon a járatszámon. Az irányválasztó a forrás egyetlen tényleges körirányát kínálja.
- Ismétlődő megállóelőfordulások megőrzendők: 1P Kálvin tér és Autóbusz-forduló kétszer; Vasútállomás minden út elején/végén. A végállomási érkezés nem további felszállási indulás.
- GPX-vonalak is publikusak: `/gpx/viabusz-1.gpx`, `/gpx/viabusz-1P.gpx`, `/gpx/viabusz-2.gpx`, `/gpx/viabusz-3.gpx`, `/gpx/viabusz-4.gpx`. Az app megállókoordinátákat használ; menetirány nem következtethető pusztán a GPX-ből.

A `#BusStopsDiv h3` címke csak **Munkanapokon**; ebből önmagában nem állapítható meg a tanítási/tanszüneti közlekedés. A 0.3-as változat ezt a korábbi hiányosságot a két hivatalos PDF külön feldolgozásával javítja. A 49 tanítási menet minden időpontját összevetettük az eredeti HTML-lel. A 30 tanszüneti menet külön adatállomány, benne a 3-as nem közlekedik.

## Élő járműpozíciók

`POST https://viabusz.biatorbagy.hu/WebServiceConfigurator.php`

Form mezők: `caller=Viabusz1012`, `serviceName=getWehicleDatas`, `ct=data`.

Válasz: `#` választja el a járműveket, `;` a mezőket:

1. járműazonosító/rendszám
2. helyi pozícióidő `yyyy-MM-dd HH:mm:ss`, Europe/Budapest
3. szélesség
4. hosszúság
5. adatcsomag-sorszám
6. `|` jellel elválasztott megállóazonosítók (a mintákban üres)
7. `|` jellel elválasztott becsült percek, pl. `5'` (a mintákban kizárólag `0'`)
8. következő megálló neve (néha üres)

A honlap 13 másodpercenként frissít. Az alkalmazás 15 másodpercenként, kizárólag aktív képernyőn frissítsen. A parser 180 másodpercnél régebbi, több mint 60 másodperccel jövőbeli, illetve Biatorbágy térségén kívüli járműpozíciót nem közöl élőként.

**A feed nem tartalmaz járat-/menetazonosítót.** Rendszám vagy térképi pozíció alapján nem szabad 1/1P/2/3/4 jelölést kitalálni. A natív térkép a publikus helyi feed friss járműveit rendszámmal jelzi. Üres stopIds és `0'` nem élő érkezés. Ha később megjelennek érvényes pozitív stopIds/ETA párok, azok járműhöz köthető megállói jelzésként olvashatók, továbbra is járatszám nélkül.

## Összesített megállói visszaszámlálás

Azonos végpont, form: `caller=Viabusz1012`, `serviceName=getStartTimesMP`, `ct=data`, `selectedRoute=10`.

Válasz: `stopId;minutes;directionId#...`.

**Minden selectedRoute=10,11,12,13,14 paraméterre byte-ra azonos választ kaptunk.** A következő megállói idő több járat között keveredhet; nincs trip/route/vehicle azonosító, nincs készítési idő, és nincs jelölés arról, hogy GPS-becslés vagy menetrendi adat. Emiatt a parser megőrzi tájékoztató forrásadatként (`isVerifiedRealtime=false`), de a számozott járatindulásokat nem írja felül vele, és nem állítja róla, hogy élő GPS-előrejelzés. Nem elegendő „a menetrendhez legközelebbi” járatra ráilleszteni.

## Következmény az alkalmazás számára

A jelenlegi publikus forrással hitelesen megvalósítható a natív térkép, 74 megálló, keresés, öt körjárat iránya és sorrendezett megállói, az adott napra ellenőrzött, tanítási vagy tanszüneti menetrend szerinti indulások, valamint a friss helyi GPS-pozíciók. BudapestGO-szintű, **járatszámhoz biztosan kötött élő érkezéshez** szolgáltatói trip/route kapcsolat és dátumozott előrejelzés kell (pl. dokumentált API vagy GTFS-RT TripUpdates/VehiclePositions). Ez adatforrásbeli korlát, nem egy rejtett mock mód.

A repository naponta ellenőrzi a három szolgáltató menetrendoldalait és PDF-jeit, atomikusan helyi cache-be menti a hálózatot, hiba esetén a dátumot ellenőrizve használ cache/bundled adatot. Egy szolgáltató hibája nem törli a másik szolgáltató adatait. Ismert forrásváltozás esetén az érintett menetrendi dátumot érvényteleníti, a megállók és útvonalak megőrzése mellett.

## Naptár karbantartása

A `ServiceCalendar.kt` a fix és húsvéthoz kötött munkaszüneti napokat számolja; csak a ténylegesen ellenőrzött évek éves transzfereit tárolja. A 2027. évi munkarendről szóló kihirdetett rendeletet 2026-10-22-én az NJT, kormany.hu és Magyar Közlöny forrásain végzett kereséssel nem sikerült ellenőrizni. Nem tételezzük fel, hogy a 2026-os áthelyezések ismétlődnek. A 2027-es húsvéti ünnepek számítása tesztelt, de az indulási visszaszámlálás addig nem aktív arra az évre, amíg az éves rendelet alapján a `reviewedYears` táblát nem bővítik. Az app ezt külön, az évszámmal jelzi. Ez kiadás előtti karbantartási feladat; a megállókeresés és GPS-követés ettől függetlenül működik.

## Országos EMMA/MÁVPlusz járműpozíciók

A Volán és a BKK élő járműpozícióinak forrása az EMMA/MÁVPlusz GraphQL rendszere. Közvetlen BKK API-t és menetrendek.hu pozíciófeedet nem használunk. Az országos lekérés alapból bekapcsolt; a frissebb megfigyeléseket, a hibatűrést és az adatkorlátokat a [VOLAN.md](VOLAN.md) írja le. Ez nem változtatja meg a ViaBusz, KanyarGO és HOMM meglévő megállóhelyi menetrendi forrásait.

Az EMMA országos megállólistája helyben gyorsítótárazott. A kiválasztott megálló indulási táblája ugyanebből a GraphQL API-ból érkezik; az élő és menetrendi indulásokat a forrás valós idejű jelölése alapján különböztetjük meg. A megállókat számozatlan pontok, a távolabbról is látható járműveket közlekedési ikonok jelzik. A kiválasztott járműhöz az EMMA trip-azonosítója alapján tölti be az app a menet tényleges megállólistáját.
