# KanyarGO import és élő adapter

Ellenőrizve: 2026-10-22. A BMenetrend a [hivatalos térkép](https://kanyargo.paty.hu/Terkep), a [menetrendoldal](https://kanyargo.paty.hu/Menetrend) és a szolgáltató két kiadott PDF-menetrendjének adatait használja. Nem kerültek bele kitalált járat- vagy megállóidők.

## Hálózat és kiadott menetrendek

- [Tanítási időszak, 2026. szeptember 1-től](https://kanyargo.paty.hu/documents/menetrend-KanyarGO-2026-szept-1-tol.pdf): 24 oszlop, 37 külön menet-/szakaszrekord, ezekből 6 szerződéses iskolai szakasz.
- [Tanítási szüneti munkanapok, 2026. augusztus 10-től](https://kanyargo.paty.hu/documents/menetrend-KanyarGO-2026-aug-10-tanszuneti.pdf): 12 oszlop, 19 külön menet-/szakaszrekord. A [hírek](https://kanyargo.paty.hu/Tajekoztatas) augusztus 5-i közleménye ehhez a szünidei változathoz vezet; a PDF visszavonásig szól.
- Járatjelzések: `1A`, `1D`, `1J`, `1M`, `1MT`, `1MV`, `1T`, `1V`. A PDF `1MT` jelzését megőrizzük; a HTML megállótábla ugyanezt a reggeli menetet `1M`-ként rövidíti. A PDF az elsődleges kiadott menetrend.
- 37 megállóazonosító, 34 külön sorrendezett útvonalváltozat. A két naptártípus meneteit ugyanazon a napon nem keverjük.
- Az érkezési és indulási oldalt a biatorbágyi állomásnál külön `29` és `30` azonosítja. A várakozást külön menetekre bontjuk, az érkezésből nem lesz felszállási indulás. Más végpontokon az üzemeltető oszlopfolytatásai nem külön új indulások; a modell a megnevezett járatszakasz felszállási megállóit mutatja, végponti érkezését elhagyja. Az esetleges továbbközlekedő kör másik járatjelzését nem találgatjuk.
- A szerződéses szakaszok külön útvonalváltozatok `restrictedService=true` jelöléssel. A korlátozás minden hozzájuk tartozó induláson is megmarad. A helyi és vasútállomási közforgalmú szakaszok külön böngészhetők. A részvétel feltételeit az [üzemeltető közli](https://kanyargo.paty.hu/): a biatorbágyi iskolákhoz tartó szakaszokra jogosultság és előzetes regisztráció szükséges.

A PDF-eket `pdfplumber` segítségével a tényleges sor-/oszlopkoordináták alapján nyertük ki, majd a renderelt oldalakon ellenőriztük. Reprodukálható importálók:

```text
scripts/extract_kanyargo_school.py → kanyargo-school-import.json
scripts/extract_kanyargo_break.py → kanyargo-break-snapshot.json
scripts/build_kanyargo_snapshot.py → app/src/main/assets/kanyargo-network.json
```

A futtatás és a szükséges letöltések leírása: [scripts/README.md](../scripts/README.md).

Az importált időpontokat nem a GPS pozíciója vagy a legközelebbi menetrendi idő alapján párosítottuk.

## Megállók

A `/Terkep` HTML-ben az OpenLayers `busStopFeatures1` objektumok adják a pontos megállónevet és `[lon, lat]` koordinátát. A táblázat `timeThSpan-<ID>` elemei és a `/Menetrend` `#busStopSelector` opciói adják az ID-neveket. Az Iskola utca ismétlődő előfordulásait a szolgáltató a `2` azonosítón egyesíti. A Vendel tér mindkét irányú idejét ugyancsak a `42` azonosító alatt szolgáltatja, miközben a külön `53` opció üres. Ezt a forrásazonosságot megőrizzük; nem találunk ki peronfelosztást. A nyers forrásadatokban minden KanyarGO ID `kanyargo:` előtagot kap. A 0.3-as felület az ellenőrzött közös megállókat egy pontként mutatja, mindkét szolgáltató indulásaival; a megfeleltetés és korlátai a [KOZOS-MEGALLOK.md](KOZOS-MEGALLOK.md) fájlban vannak.

A Vitamin-domb a PDF megjegyzésében igény szerinti kitérésként szerepel, de a forrás térképén nem tartozik hozzá ellenőrzött koordináta/megálló-ID, és táblázatos idő sincs. Emiatt nem tettünk a térképre kitalált pontot, és nem gyártottunk hozzá érkezést. Ez a szolgáltatói adat hiányossága, nem egy rejtett mintapont.

## Napi frissítés és forrásváltozás

A honlapnak nincs felfedezett nyilvános, azonosított meneteket adó API-ja. A `/Menetrend` korábbi változata megállónként egyesített időket adott; a 2026-10-22-én később ellenőrzött változat már csak a két ismert PDF-et hivatkozza. A megállóválasztó opcionális webes megjelenítési elem, ezért nem feltétele a natív menetrend használatának. A natív adapter ezért naponta ellenőrzi a menetrendoldal aktív PDF-hivatkozását és mindkét importált PDF SHA-256 lenyomatát:

```text
school 866231b9e84d16f16cde34b11adc67682cc049a360ed7f41ae430e7e7f1cdd66
break  60f7e5db621e456fb63e747fe8ca204e0fd58b744d75ba2897ba3aaae6330e98
```

Ha a szolgáltató megváltoztatja valamelyik kiadott menetrendet, a KanyarGO menetrendi visszaszámlálás leáll, és ellenőrzött frissítést kérő üzenet jelenik meg. A megállók, útvonalak és élő GPS ettől még elérhetők. A megváltozott forrás érvénytelenítését cache-be is mentjük, így egy újraindítás nem kapcsolja vissza a már ismerten elavult menetrendet. Egy sima hálózathiba esetén az aznap már ellenőrzött menetrend tovább használható; más napi mentést nem vetítünk automatikusan a jelenre.

## Naptár

A [2026/2027. tanév rendjéről szóló 1/2026. (VII. 31.) OGYM rendelet](https://njt.jog.gov.hu/jogszabaly/2026-1-20-9M) 7. §-a alapján az őszi szünet 2026-10-23–2026-11-01, a téli szünet 2026-12-19–2027-01-03. A szünetek munkanapjaira a szünidei menetrend, a többi ellenőrzött tanítási időszaki munkanapra az iskolai menetrend érvényes. A naptár egyben ellenőrzi az ünnepnapokat és a 2026-os magyar munkanap-áthelyezéseket. Az adapter jelenlegi ellenőrzött lefedettsége 2026-09-01–2026-12-31. A 2027-es munkanap-áthelyezések felülvizsgálatáig arra az évre nem jelenít meg menetrendi visszaszámlálást.

## Élő GPS

```text
POST https://kanyargo.paty.hu/WebServiceConfigurator.php
caller=KanyarGo1012&serviceName=getWehicleDatas&ct=data
```

Azonos mezőszerkezet a ViaBusz forrásával: `vehicleId;localTimestamp;latitude;longitude;packetId;stopIds;minutes;nextStopName`, `#` rekordhatár. A megfigyelt válaszban két jármű volt, a stopIds és percek üresek. A járműazonosító is `kanyargo:` előtagot kap. 180 másodpercnél régebbi vagy a szolgáltatási térségen kívüli adat nem jelenik meg élő pozícióként.

Az összesített megállói szolgáltatás:

```text
caller=KanyarGo1012&serviceName=getStartTimes&ct=data
```

A válasz `stopId;minutes;directionId#...`, járat-, menet-, járműazonosító és GPS-eredetigazolás nélkül. Nem írjuk felül vele a járatszámos indulásokat. **Mindkét szolgáltatónál élő a friss járműpozíció, a számozott indulások menetrendiek.** Ehhez valós route/trip kapcsolattal rendelkező előrejelzési API kellene.

A két hálózat naponta, a két élő járműforrás aktív alkalmazás mellett 15 másodpercenként, egymástól függetlenül frissül. Egy szolgáltató kiesése nem tünteti el a másik működő adatait. Az összes felhasználói adatforrás-/naptárüzenet Android angol és magyar erőforrásban szerepel.
