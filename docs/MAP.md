# Keyless map and Night style

The app uses MapLibre Native Android 11.8.0 with OpenFreeMap vector tiles, fonts and sprites. There is no map API key, billing setup or server of your own to configure. Build and run the app normally. Network access is needed for uncached tiles and fonts.

## Appearance

Light mode uses OpenFreeMap Positron. Dark mode loads `app/src/main/assets/map/night.json`, a local adaptation of OpenFreeMap Dark / OpenMapTiles Dark Matter. It changes cartography rather than tinting the screen:

| Feature | Colour |
| --- | --- |
| Land | Navy `#111D2C` |
| Water | Teal-blue `#15516B`, lighter cyan labels |
| Forests and parks | Emerald `#184838` / `#205443` |
| Local streets | Slate blue `#465E78` |
| Main roads | Pale blue-grey `#8195AA` |
| Motorways | Warm sand `#C39A73` |
| Railways | Lavender `#9C8DAF` |
| Place labels | Pale ice `#E1EAF3` with navy halos |

Road widths, zoom rules and label placement remain scale-aware. The existing transport marker colours stay unchanged. The system theme switches the basemap while retaining the current camera and selection. Outside route focus, low zoom hides stops below 12 and vehicles below 9; visible vehicles use coloured dots until zoom 12, then transport icons.

The bundled style can be edited directly or opened in [Maputnik](https://maplibre.org/maputnik/). It contains public resource URLs, not credentials. MapLibre keeps the attribution control above the bottom panel.

## Sources and licences

- [OpenFreeMap](https://openfreemap.org/) supplies the basemap resources without registration or API keys.
- Map data: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).
- Night style: modified [OpenFreeMap Dark](https://github.com/hyperknot/openfreemap-styles), based on [OpenMapTiles Dark Matter](https://github.com/openmaptiles/dark-matter-gl-style), derived from CartoDB Basemaps by Stamen and Paul Norman for CartoDB Inc. Code and design licence notices are bundled in `app/src/main/assets/map/licenses/`.
- Place search remains Photon / OpenStreetMap; transit data remains EMMA and the local operators.

Tile services receive the requesting IP address and the requested map areas. This is an online map, not a claim of offline use or anonymity.

## Selected line view

Selecting a vehicle loads its line patterns as well as its actual trip. A highlighted line shows both directions' available paths and platforms together. Selecting a direction keeps its boarding identity and departure details specific to that direction. A vehicle uses its actual trip or a matching direction when one can be verified; the actual trip is retained even when its pattern is absent from that day's line catalogue. Local lines use their known stop sequences for the view bounds; the app does not fabricate road geometry when no shape is supplied.

Other lines' vehicles and stops are hidden during selection. Vehicles match the provider-scoped route ID, rather than colour or route number alone. The focused view automatically fits all available paths and platforms above the bottom card and below the search bar. The Back arrow in the vehicle card, or Android Back, exits focus and restores the previous camera. A boarding selection has the same Back action.

The draw order is basemap → route casing/stroke → station halo/ring/centre → vehicle annotations. Stations use a separate GeoJSON circle layer, so vehicles stay above stations regardless of which arrived first in a live poll. Selected-line stops remain visible at the zoom needed for a complete line overview. Outside focus, the normal zoom visibility rules still apply.


## Shared platforms, direction pointers and close-zoom spacing

A physical platform shared by different operators has one marker. Compatible modes, matching names/aliases, coordinates within 30 metres, and a clearly closest match are required. Opposite known headings reject a match, same-provider platforms remain independent, and an ambiguous equal-distance match stays separate. Every member must match every other member, so proximity cannot chain platforms along a street. Reviewed `SharedStops` aliases remain authoritative. These are map presentation groups; source IDs, boarding directions and raw networks are retained.

Shared markers use fixed split-colour rings, with an equal segment for each distinct operator colour (including ViaBusz, KanyarGO, HOMM and Volán). All operator colours remain visible together. The departure card lists provider names as well, so colour is not the only identification. A shared marker opens the combined station departures directly.

Stop markers always stay on source coordinates; they are never displaced and have no connector lines. Shared groups use the Volán source location when present, otherwise an actual member's location. Isolated platforms retain large rings; nearby platforms shrink symmetrically into small dots as screen-space distance decreases. They naturally regain their full size when zoomed in far enough. Vehicle icons may cover a stop, without moving its position. Tapping an ambiguous overview overlap zooms in; close-up taps choose the nearest source dot directly without a platform menu.

Direction pointers use the ordered onward stops and, when available, the route shape tangent. This works for buses, trolleys, trams, metro and rail. Arrival-only endpoints and unknown directions do not get invented arrows; a platform used in two known directions can show two pointers. Opposite-direction paths and platforms are highlighted together with the rest of the line. The selected direction's boarding identity and departure details remain specific to that direction.

Selecting a line from search closes the search overlay and opens a bottom card with its service type and direction choices. The map remains visible above it. Long direction names wrap beside a route badge and arrow. The same direction presentation is used by local and national line browsers, vehicle cards, departures and journey steps.

Hiding a station or vehicle card, including by dragging it down, clears route focus. Clearing boarding selection does not reopen a dismissed sheet. The line card has Back and Close actions; Android Back returns from a direction to its overview, then closes the line card. Exit route view remains available directly below search for vehicle and boarding selections.
