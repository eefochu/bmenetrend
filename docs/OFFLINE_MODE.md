# Offline mode

The offline pack is optional. Open **Settings → Offline use**, download the pack, then enable **Offline mode**. Downloading does not enable the mode. Turn it off to restore normal EMMA/Photon/live operation. The app never switches modes silently after a failed network request.

## Included data and limits

The default download uses the official Volán/KTI and BKK GTFS endpoints directly. This requires no new server or API credentials in the app. The current download includes nationwide/regional/local Volán buses and Budapest services, including HÉV where present in the BKK feed. Verified ViaBusz, KanyarGO and HOMM snapshots are included when their online source checks pass. Settings lists the actual installed sources, source exclusions and date range.

**Nationwide mainline rail is not included by default.** MÁV/GYSEV bulk timetable access must be obtained through the provider. Settings can add an authorized GTFS ZIP using Android's document picker. The original document stays untouched. The copied additional feed participates in rebuilding the pack and future updates; replacing it replaces that additional feed. The importer rejects unsupported timetable features instead of treating them as ordinary service.

Planning is **stop-to-stop**, with explicit source transfer/pathway links. It does not infer walking paths from proximity. Coordinate/address/current-location walking access, a routable national street graph and through-vehicle GTFS transfer types are not implemented. A bundled simplified Hungary basemap supplies geographic context; it is not a routing graph. Choose boarding platforms from downloaded stop search. Stops with only a station parent or no active boardable service cannot substitute for a boarding platform. A no-route result can reflect missing transfer data or operator coverage.

Offline mode provides scheduled route planning (up to four transfers), stop and line search, departure boards, line stop lists and favorites. Live vehicles, delays and address search are unavailable. The map uses a bundled OpenStreetMap-derived Hungary vector basemap, local font glyphs and local stop icons, with separate light and dark styles. It shows real roads (including residential/service streets), street and settlement names, railways, water and broad land cover. No map HTTP requests or online fallback are used. Its dated extract, simplification and omitted detail are described below and in Settings. Device GPS remains available, but cannot create an unverified walking route to a stop. Results show their offline origin and validity; expired dates are reported separately from no route.

## Storage and download behavior

- Maximum combined downloaded source ZIP size: **500,000,000 bytes**.
- Maximum installed database plus retained additional source ZIP, bundled basemap/supporting assets and native basemap working copy: **500,000,000 bytes**. All map asset bytes are counted without subtracting APK compression savings.
- The national graph is compiled on-device from streaming GTFS, avoiding any dependency on an unpublished precompiled pack host. This can take several minutes on a phone; progress and cancellation remain available while processing runs off the UI thread.
- Installation needs at least **1.5 GB temporary free space** for the previous pack, input archives, staged database and indexes. File growth remains bounded throughout import.
- Default source archives are removed after successful activation. An additional user-imported source is retained for subsequent updates and included in the storage cap.
- Updates install into a separate database. Checksums, ZIP entry CRCs, schema checks and SQLite integrity checks run before an atomic active-pointer replacement. A failed update preserves the previous working pack.
- Data lives under `noBackupFilesDir/offline-pack`; the application already disables automatic backup.
- A foreground WorkManager task owns downloading and processing. Default network constraint is unmetered; users may opt into mobile data. The notification and Settings can cancel the task. Resume uses strong ETags and validated range offsets; servers without safe validators restart the partial transfer. Process death restarts processing safely without replacing the active database prematurely.
- Automatic daily updates on Wi-Fi are optional and initially disabled. They skip work in Offline mode and avoid rebuilding a pack less than 20 hours old. Manual updates are available while online. The pack compiles at most the next 30 days plus preceding overnight service days, subject to actual feed validity.

Measurements from the official feeds retrieved on 2026-10-26: 105,915,294 bytes Volán plus 61,966,998 bytes BKK, or 167,882,292 bytes compressed. The initial two-feed indexed database measured 383,832,064 bytes before the later stop-route catalogue index/local-source additions. The emulator installation including the verified local networks and stop-route catalogue index measured 386,695,168 bytes. These are measurements of those source versions, not fixed future pack sizes. Runtime checks reject oversized later feeds.

A full-data regression on the development host found the Kelenföld–Biatorbágy bus journey in approximately 0.5–1.0 seconds after removing equivalent search states and pruning arrivals outside the UI alternative window. This is a host measurement, not a phone latency guarantee.

## Routing and network policy

`OfflineTripPlanner` uses boarding-count rounds over indexed GTFS data, preserves service-day identities and all boardable trip alternatives (including overtaking), and reconstructs source times and stop sequences. It preserves transfer-specific labels where required, rejects forbidden/minimum-time transfers and retains arrival/walking Pareto alternatives. A common feasibility/ranking pass feeds the existing itinerary UI.

`NetworkAccessPolicy` initializes from the saved mode before screen repositories start. It guards the shared EMMA client, local provider HTTP clients, Photon, OTA and MapLibre's resource HTTP factory. Switching offline cancels registered HTTP dispatchers, stops remote use cases and invalidates pending screen/planner generations. Map views are recreated with a local style. Cached vehicle positions are cleared. Polling loops can continue local clock/board updates while their remote branches return immediately. Returning online restores the prior online catalogue and refreshes the online repositories.

GTFS and EMMA identifiers are mapped by exact provider namespace for Volán and BKK, preserving favorites where the source IDs match. Other imported feeds keep their own namespace. Names/coordinates are never used to merge unrelated platforms. Unknown saved stops remain saved and receive an offline-coverage notice.

## Verification

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Deterministic fixtures cover CSV quoting/BOM/newlines, overtaking, transfer restrictions, overnight departures, conditional pickup, invalid dates, checksum rollback, disabled Settings controls, enabling without downloading and HTTP blocking before a socket opens. Existing online planner and search regressions also run.

For the opt-in real-feed integration, put the official archives at `/tmp/bmenetrend-offline-feeds/volan.zip` and `bkk.zip`, then run:

```sh
OFFLINE_REAL_FEEDS=1 JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest \
  --tests '*OfflinePlanningTest.completeRealFeedsCanBeImportedWithinStorageCap'
```

This integration currently fixes its date range to the source versions used above; adjust it deliberately when testing later feeds. It compiles the real archives, asserts the size cap and verifies that a real Kelenföld–Biatorbágy itinerary exists within the test timeout.

## Remaining work toward the original broader design

Full mainline rail coverage needs an authorized rail source. Arbitrary-coordinate planning needs a licensed/preprocessed pedestrian graph and a distribution solution that fits the same size cap. Frequency-based and in-seat-transfer feeds currently require preprocessing and are rejected by the importer. There is no precompiled/signed-pack publication service. These are explicit limitations; the Settings UI does not advertise them as delivered.

Sources: [BKK developer data](https://bkk.hu/bkk-partnerek/egyeb/gtfs-menetrendi-adatok-programozoknak), [MÁV GTFS access](https://www.mavcsoport.hu/gtfs-igenybejelento), [GTFS schedule specification](https://gtfs.org/documentation/schedule/reference/).

Final validation on 2026-10-26: 332 unit tests with zero failures (the opt-in real-feed test ran separately and passed); `lintDebug`, `assembleDebug` and `assembleDevelopment` succeeded. An Android emulator completed the actual download/install, retained Offline mode across a restart with Wi-Fi/mobile data disabled, and returned local stop-search results without connectivity.


## Processing progress and retry fix (2026-10-27)

The completed ZIP download size no longer appears as installation progress. Installation reports the current table, actual processed record count and file progress, followed by local-source checks, validation, catalogue construction, individual indexes, saving and activation. Cancellation checks occur during import; a new attempt waits for previous cleanup without WorkManager retry backoff.

CSV input uses block reads and indexed row views instead of per-character synchronized readers and per-row map/pair allocations. Ordered trip times are validated as they are imported; unordered trips receive an additional indexed check. This avoids a second full per-row SQLite cursor pass while preserving backwards-time rejection. The full-feed integration preserves the same database contents and size; host-only timings are not a substitute for the Android transaction-suspension regression.

Completed source ZIPs from an interrupted installation are reused for up to six hours and validated during import. Successful installation still removes the default archives. This preserves completed downloads when retrying a canceled installation.


The underlying processing hang was a SQLite transaction thread-affinity bug: coroutine suspension at progress publication could resume on another IO thread while the original thread retained the transaction connection. Device logs reproduced a staging-database connection wait lasting over three minutes. Database creation, import, commit/rollback and close now run on a dedicated single-thread dispatcher for each install. A regression forces caller-thread switching and checks transaction ownership and cancellation cleanup.

Validation of the thread-affinity fix: 341 unit tests with zero failures (real-feed integration also passed separately), lint and both APK builds passed. On an isolated Android emulator, the worker resumed from completed archives, imported both national feeds and verified local sources, and activated a 388,014,080-byte pack in 109 seconds. The new process emitted no SQLite connection-pool wait warnings.


## Offline basemap fix (2026-10-27)

The former local style contained only a background, which explained the blank map behind stop rings and GPS. It is replaced by a genuine, licensed OSM-derived Hungary context map. The Geofabrik snapshot contains OSM data through 2026-10-25; the raw 327,646,997-byte PBF is used only for development preprocessing and is not shipped or downloaded by the app. No map hosting or credentials are required. The map updates with the app, separately from timetable updates.

The shipped PMTiles archive is **34,857,842 bytes**. Bundled map data, glyphs, styles, manifest and licenses total **35,446,032 bytes**. Counting the native working copy reserves **70,303,874 bytes**, so the measured 388,014,080-byte timetable totals **458,317,954 bytes**, leaving 41,682,046 bytes below the installed cap. Settings includes this reservation even before the copy is first needed. Import growth, metadata acceptance and activation enforce the reduced timetable/custom-feed allowance. Optional feed downloads retain their separate 500,000,000-byte cap.

The map retains actual residential/service/pedestrian streets and their names throughout the Hungary extract, including small towns such as Biatorbágy and Budaörs. Tiles cover zooms 5–12 with 4096 coordinate precision (about 6.4 metres near Budapest); further zoom enlarges that geometry. Buildings, house numbers, POIs, dedicated footpaths/cycleways and walking routing are omitted. Coverage ends at the extract edge. This does not claim detailed worldwide coverage or complete/updated OSM data.

MapLibre 11.8 reads native tile ranges from a checked private file on an IO dispatcher. The app does not load a national GeoJSON or copy the archive into the Java heap. Both styles and all required Hungarian/other snapshot label glyph ranges are local. File/checksum failure reports a map error without using HTTP. Deleting the timetable pack also requests working-copy removal after the last offline MapView is destroyed; bundled map assets remain in the APK. Mode changes recreate the displayed AndroidView as well as its MapView.

Data is © OpenStreetMap contributors, ODbL 1.0; the derivative archive, full license, attribution and glyph license are distributed in APK assets. The map source exposes attribution in MapLibre, and Settings explains source, date and limits. See [reproducible preprocessing, source hashes, 11.8 protocol source evidence and runtime handoff](../tools/offline-map/README.md). Implementation testing/builds are delegated to the parent’s existing T3 testing child; earlier validation totals above predate this basemap change.
