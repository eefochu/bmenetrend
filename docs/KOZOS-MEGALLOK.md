# Közös megállók – BMenetrend 0.3

A két szolgáltató azonos fizikai megállóhelyei egy térképi pontot és keresési találatot kapnak. Az induláslista mindkét szolgáltató érvényes meneteit tartalmazza. A korábbi megállónevek keresési aliasok, a KanyarGO-megállókhoz mentett kedvencek a közös azonosítóra kerülnek.

A megfeleltetés ellenőrzött, rögzített lista, nem automatikus közelségi összevonás. Forrás: a ViaBusz és KanyarGO hivatalos térképeinek koordinátái, nevei és a felhasználó állomási azonosítása; ellenőrizve 2026-10-22.

| Közös megálló / megmaradó ViaBusz ID | KanyarGO forrásazonosító | Távolság a két forrás koordinátái között |
| --- | --- | --- |
| Vasútállomás (`1`) | `29` érkezés, `30` indulás | kb. 2,5 m / 7,7 m |
| Ritsmann Iskola (`2`) | `50` | kb. 3,5 m |
| Juhász Gyula utca (Ellenirány) (`67`) | `48`, BIT (Juhász Gy. u.) | kb. 4,3 m |
| Vendel tér (`26`) | `42` | azonos koordináta |
| Szentháromság tér (észak felé) (`10`) | `51` | azonos koordináta |

A ViaBusz szemközti `85`, `20`, `69`, `66` peronjai külön maradnak. A KanyarGO a Vendel teret és a Szentháromság teret egy-egy forrásazonosítóra vonja össze, eltérő útvonalváltozatokon is. Az app ezt a forrásbeli korlátot megőrzi; nem talál ki további peronazonosítókat. A listában az adott járat teljes iránya olvasható.

Az összevonás csak az utas számára látható megállóazonosságot változtatja meg. A nyers szolgáltatói hálózatok külön cache-ben maradnak. A menet- és irányazonosítók, minden megálló-előfordulás, az érkezés/indulás különbsége és a jogosultsági jelölés megmaradnak. Különösen: a KanyarGO állomási végponti érkezése nem válik felszállási indulássá attól, hogy az indulási peronnal közös ponton jelenik meg.

A program a szolgáltatónkénti menetrend-érvényességet az összevonás után is külön ellenőrzi. Egy kieső adatforrás nem érvényteleníti a közös megálló másik szolgáltatójának menetrendjét. A név nélküli vagy nem igazolt megállói előrejelzés továbbra sem rendelhető találgatással járatszámhoz.

Megvalósítás: `SharedStops.kt`, `NetworkMerger.kt`; regressziós ellenőrzések: `SharedStopsTest.kt`.
