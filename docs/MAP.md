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

Selecting a vehicle loads its line patterns as well as its actual trip. A line overview shows its available patterns as separate paths and keeps source platforms independent. Selecting a direction uses that pattern's stops and geometry. A vehicle uses its actual trip or a matching direction when one can be verified; the actual trip is retained even when its pattern is absent from that day's line catalogue. Local lines use their known stop sequences for the view bounds; the app does not fabricate road geometry when no shape is supplied.

Other lines' vehicles and stops are hidden during selection. Vehicles match the provider-scoped route ID, rather than colour or route number alone. The focused view automatically fits all available paths and platforms above the bottom card and below the search bar. The Back arrow in the vehicle card, or Android Back, exits focus and restores the previous camera. A boarding selection has the same Back action.

The draw order is basemap → route casing/stroke → station halo/ring/centre → vehicle annotations. Stations use a separate GeoJSON circle layer, so vehicles stay above stations regardless of which arrived first in a live poll. Selected-line stops remain visible at the zoom needed for a complete line overview. Outside focus, the normal zoom visibility rules still apply.


## Overlapping stops and compact route cards

Every platform stays at its source coordinate, including platforms that share the same point. Markers may overlap visually. Taps resolve all stops within a minimum 48-dp touch area; when several qualify, a compact bottom picker lists their provider and platform or source identifier. A vehicle covering those stops opens the same picker with an additional vehicle option. Zooming is not required to select a hidden platform.

Focused-line stops use hollow rings in the line colour with a contrasting centre; the selected stop is filled and drawn last. Vehicle icons stay above the stop layers.

Selecting a line from search closes the search overlay and opens a bottom card with its service type and direction choices. The map remains visible above it. Selecting a direction highlights only that pattern's stops and geometry; the overview shows the available directions together. Local stop sequences retain their actual coordinates without invented road geometry. Long direction names wrap beside a route badge and arrow. The same direction row is used by local and national line browsers, and vehicle cards show the service type as well.

Hiding a station or vehicle card, including by dragging it down, clears route focus. Clearing boarding selection does not reopen a dismissed sheet. The line card has Back and Close actions; Android Back returns from a direction to its overview, then closes the line card. Exit route view remains available directly below search for vehicle and boarding selections.
