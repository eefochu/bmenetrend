# Bundled Hungary context map

This is a real OSM-derived vector database, separate from transit data. It is
ODbL 1.0, including the generated PMTiles archive. Attribution is in both style
sources (MapLibre attribution control), Settings, and the bundled NOTICE/license.
No credentials, tile server or app-time map downloads are required.

The archive has mapped roads including residential, service and pedestrian
streets and their names throughout the Geofabrik Hungary extract. It also has
settlement/suburb names, railways, water, and broad green/built land cover.
Footways/cycleways first appearing above z12, neighbourhood labels, buildings,
house numbers, POIs and routing connectivity are omitted. OSM completeness
varies; the extract has a buffer around Hungary, not worldwide coverage.
Tiles are generated at z5–12, 4096 precision. Near Budapest this quantizes
coordinates to about 6.4 metres; zooms above 12 enlarge that stored geometry.
The real road network remains visible at bus-stop zooms. This is a context map,
not a walking-routing graph. The static snapshot updates with app releases,
independently of the 30-day timetable download.

## Source and exact packaging budget

Input: Geofabrik `hungary-261025.osm.pbf`, 327,646,997 bytes, OSM through
2026-10-25T21:21:21Z. SHA-256:
`95cee027cb022b35b6633c511119d72f19fb6a606e8c8eceba0de80709d66912`.
The raw PBF, intermediate GeoJSON and development environment stay outside the
repository. Data: https://download.geofabrik.de/europe/hungary.html
License: https://www.openstreetmap.org/copyright

The packaged archive is **34,857,842 bytes**. All bundled map data, glyphs,
styles, manifest and licensing files total **35,446,032 bytes**. Counting that
plus the native working copy reserves **70,303,874 bytes**. With the measured
388,014,080-byte timetable, total active footprint is **458,317,954 bytes**.
These totals deliberately count uncompressed APK asset bytes, even if ZIP
compression saves APK space. They exclude unrelated app code/Android libraries.
`package.py` generates constants; metadata loading, import growth and activation
reserve this footprint. Settings shows the complete reserved installed total,
including the working copy before its first use. Optional source ZIP downloads
still have their separate 500,000,000-byte combined cap. A retained custom feed
also counts against installed storage.

Deleting the optional pack requests working-copy cleanup after all offline
MapViews are destroyed; native range readers keep their file until then. The
bundled baseline map remains in the APK. A later offline use recreates the copy.
No nationwide GeoJSON is parsed at runtime: native code reads individual tile
ranges from a checked local file. Styles and all required glyphs are local.
A corrupt/missing local resource reports an error; there is no network fallback.

## Reproduce preprocessing (development only)

Dependencies: Python 3, `osmium==4.3.2`, `shapely==2.2.0`, and Felt tippecanoe
2.79.0. This is data preparation, not an Android build or test run.

From the repository root, with scratch space outside the repository:

```sh
python3 -m venv /tmp/bmenetrend-offline-map/venv
/tmp/bmenetrend-offline-map/venv/bin/pip install osmium==4.3.2 shapely==2.2.0
# On macOS, if not installed:
brew install tippecanoe
curl -fL -o /tmp/bmenetrend-offline-map/hungary-261025.osm.pbf \
  https://download.geofabrik.de/europe/hungary-261025.osm.pbf
# Compare SHA-256 with the pinned value above before processing.
shasum -a 256 /tmp/bmenetrend-offline-map/hungary-261025.osm.pbf
/tmp/bmenetrend-offline-map/venv/bin/python tools/offline-map/extract.py \
  /tmp/bmenetrend-offline-map/hungary-261025.osm.pbf \
  /tmp/bmenetrend-offline-map/hungary-z12.geojson
tippecanoe -o /tmp/bmenetrend-offline-map/hungary-z12.pmtiles \
  -Z5 -z12 -d12 -P -pf -pk --no-tile-stats \
  --name='Hungary offline context' \
  --attribution='© OpenStreetMap contributors · ODbL 1.0' \
  /tmp/bmenetrend-offline-map/hungary-z12.geojson
python3 tools/offline-map/styles.py
python3 tools/offline-map/package.py /tmp/bmenetrend-offline-map/hungary-z12.pmtiles
```

`-pf -pk` prevent automatic feature dropping and tile-size feature reduction.
Per-feature minimum zoom limits density at national scales; every selected
street appears at the stored maximum zoom. `extract.py` streams input and uses
libosmium for multipolygon assembly. It writes source feature counts and used
Unicode ranges next to the GeoJSON. Regenerating with another tool version may
change binary packing; the manifest records exact shipped file hashes.

The existing glyphs are Noto Sans Regular PBFs from
`https://tiles.openstreetmap.us/fonts/Noto%20Sans%20Regular/{range}.pbf`, licensed
under SIL OFL 1.1 (bundled `Noto-OFL.txt`). Six ranges from this snapshot are
bundled: 0–255, 256–511, 512–767, 1024–1279, 8192–8447 and 9472–9727. The
manifest records each hash. For a new input snapshot, fetch any additional
ranges reported by `extract.py` into `map/offline/glyphs/Noto Sans Regular/`
before packaging. Keep font licenses and source attribution with derived data.
If Geofabrik removes the dated source, obtain an archived copy with the pinned
hash or deliberately update the snapshot, notices and budget; do not silently
substitute `latest`.

## MapLibre 11.8 protocol evidence and runtime handoff

Verified against the **android-v11.8.0** native source, not current docs:

- [PMTiles source](https://github.com/maplibre/maplibre-native/blob/android-v11.8.0/platform/default/src/mbgl/storage/pmtiles_file_source.cpp)
  accepts `pmtiles://`, strips the prefix and requests header/directory/tile byte
  ranges. File URLs are explicitly handled in its error path.
- [Local file source](https://github.com/maplibre/maplibre-native/blob/android-v11.8.0/platform/default/src/mbgl/storage/local_file_source.cpp)
  forwards `Resource.dataRange` to `requestLocalFile`.
- [Local file request](https://github.com/maplibre/maplibre-native/blob/android-v11.8.0/platform/default/src/mbgl/storage/local_file_request.cpp)
  passes that range to `util::readFile`.

APK asset range support was added later, so the app uses `pmtiles://file:///…`
with a small checked copy on an IO dispatcher. Ordinary asset glyph/style reads
remain supported in 11.8. No SDK upgrade or HTTP tile-cache trick is involved.

All testing/builds are assigned by the parent to the existing less-powerful
T3 testing child. Check real runtime rendering with airplane mode/cold start,
Biatorbágy (47.473,18.825), Budaörs (47.46,18.96) and a rural settlement at
zooms 12–19; Hungarian road/place labels; light/dark; camera retention; switching
offline/online; PMTiles/file/glyph errors; HTTP blocking before sockets; and
DB + custom source + all map assets + working copy storage enforcement. Include
pack deletion while a map is open and confirm copy removal after its release.
