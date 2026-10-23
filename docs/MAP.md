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

Road widths, zoom rules and label placement remain scale-aware. The existing transport marker colours stay unchanged. The system theme switches the basemap while retaining the current camera and selection. Low zoom hides stops below 12 and vehicles below 9; visible vehicles use coloured dots until zoom 12, then transport icons.

The bundled style can be edited directly or opened in [Maputnik](https://maplibre.org/maputnik/). It contains public resource URLs, not credentials. MapLibre keeps the attribution control above the bottom panel.

## Sources and licences

- [OpenFreeMap](https://openfreemap.org/) supplies the basemap resources without registration or API keys.
- Map data: [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).
- Night style: modified [OpenFreeMap Dark](https://github.com/hyperknot/openfreemap-styles), based on [OpenMapTiles Dark Matter](https://github.com/openmaptiles/dark-matter-gl-style), derived from CartoDB Basemaps by Stamen and Paul Norman for CartoDB Inc. Code and design licence notices are bundled in `app/src/main/assets/map/licenses/`.
- Place search remains Photon / OpenStreetMap; transit data remains EMMA and the local operators.

Tile services receive the requesting IP address and the requested map areas. This is an online map, not a claim of offline use or anonymity.
