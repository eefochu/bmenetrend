# EMMA/MÁVPlusz: nationwide Volán and BKK

Vehicle positions now use the **EMMA/MÁVPlusz OTP GraphQL API**, following `~/Stuff/code/vonatpervolan/lib/mav.js` and `lib/feed.js`. There are no direct BKK API requests and no menetrendek.hu position requests or fallback. ViaBusz, KanyarGO and HOMM keep their own existing integrations.

## Source and coverage

`POST https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql`

```graphql
query LiveVehicles {
  vehicles: vehiclePositions(
    swLat: 45.7, swLon: 16.1, neLat: 48.6, neLon: 22.9,
    modes: [BUS, COACH, TRAM, TROLLEYBUS, SUBWAY]
  ) {
    vehicleId lat lon heading label lastUpdated speed
    trip {
      gtfsId tripShortName tripHeadsign
      route { gtfsId shortName longName mode color textColor }
    }
  }
}
```

One request covers Hungary, including Budapest. The old per-map-area requests, cell limits and zoom-in restriction are gone. Data is fetched nationwide regardless of where the map is centred. The map creates native annotations for the visible area; the full snapshot remains available for searching route details. Vehicles remain visible at every supported zoom. Stops are small unnumbered dots. At wide zooms a fine geographic grid combines nearby platforms into an approximate dot; tapping it zooms in. At zoom 14 and above each source platform has its own selectable dot. This keeps national views readable without rendering tens of thousands of native stop annotations. Source platform identities remain separate in search and departure boards.

Only the source namespaces `hkir:` (Volán BUS/COACH) and `BKK:` (BUS/TRAM/TROLLEYBUS/SUBWAY) are accepted. This is necessary because even the BUS/COACH response includes other operators. Rail and unrelated city operators are excluded. BKK data is received **through the MÁVPlusz endpoint**, never a separate BKK/FUTÁR endpoint. The user-supplied circular transport icons identify vehicles: yellow Volán bus, light-blue BKK bus, red trolleybus, yellow tram and dark-blue train/metro. Mode takes priority over individual route colours. Route numbers and destination details appear on selection. Train-style markers cover metro and supported train modes; national rail tracking is not added by this visual change.

A live source probe on 2026-10-22 returned 1,116 Volán reports, including 113 within the Budapest region, before freshness filtering. Availability and counts vary; the app cannot supply positions absent from the source.

## Settings and route identity

**Nationwide network** is now **on by default**, including existing installations migrating from the previous experimental feed (`emma_nationwide`, default true; the old `nationwide_volan` preference does not suppress the new nationwide integration).

Users can turn it off to display only the reviewed Volán routes serving Biatorbágy, Páty and Budaörs, plus BKK vehicles within the Budapest region. This is an immediate local filter over the shared feed, so toggling scope does not trigger redundant nationwide requests and old requests cannot restore the wrong scope.

The 36-route official local catalogue remains available, with official timetable links. All local labels were checked against `vonatpervolan/data/gtfs/routes.txt`: their source identities are `hkir:hkir_V-R-200-<number>`. Scope matching uses these GTFS identities. Vehicles and routes preserve their full source IDs, so identical short route numbers from different operators/places are not merged. National Volán and BKK route entries are built from live source trip/route metadata and are searchable. Routes without active fresh tracked vehicles may not appear unless they are in the bundled local catalogue or a selected stop board has supplied them.

## Freshness, polling and failures

The adapter preserves source vehicle ID, trip ID, full route ID, route number/name, destination, plate/label, heading, speed, transport mode and route colour. `lastUpdated` is **Unix seconds**; `speed` is metres per second and converted to km/h. It never replaces a missing GPS timestamp with fetch time. Rows with missing/invalid timestamps, coordinates outside Hungary, reports over 180 seconds old or more than 30 seconds in the future are omitted. Duplicate vehicles use the newest observation. Valid Volán positions with no trip metadata are retained without an invented route.

Foreground polling runs every five seconds. A shared mutex and one-second cache prevent concurrent callers from duplicating a request. HTTP calls are cancelled when their coroutine is cancelled. The client retries once, matching the reference integration. GraphQL errors (including partial data with errors), missing data arrays and HTTP failures are failures, not empty successes.

On failure, a successful snapshot is retained for at most 120 seconds with an explicit stale-data message. Its original fetch and observation times remain unchanged; per-vehicle freshness also continues expiring. After that, old vehicles disappear. EMMA polling is independent of the existing local operators and owned by the activity's foreground lifecycle.

## Vehicle cards

Tapping a vehicle replaces the bottom stop panel with its route number, transport icon, operator, destination, source label/registration, speed and observation time. The map centres the selected vehicle and highlights its icon. Closing the card returns to the previous stop panel. The vehicle snapshot updates with polling; an old observation receives an explicit stale-position warning.

For EMMA vehicles with a source trip ID, `trip(id:)` supplies the actual ordered stop list, route identity and headsign. Repeated stops on loop routes remain in source order. Selecting a listed stop opens its departure board. No route variant is inferred from position or proximity. Missing trip metadata and failed requests show separate states and an explicit refresh action. Selection/trip checks plus a request generation guard keep late responses from replacing a different card. The query requests no stop times because the trip-level response may carry `serviceDay: -1`; it must not be treated as a dated live prediction.

## Stops and departure boards

The same GraphQL endpoint supplies the national `stops` index and `stop(id:)` departure boards. The index requests identifiers, names, coordinates, platform codes and parent station identities without expensive per-stop routes. The 2026-10-22 probe supplied 46,902 supported Volán/BKK stops. They are searchable, selectable on the map, usable as nearest stops and can be favourited. Full `emma:<source GTFS ID>` keys preserve opposite platforms and operator identity; unreviewed stops are never proximity-merged. Platform codes are shown where supplied; source stop codes distinguish otherwise identical names.

The index is parsed one record at a time in the background, cached atomically in app-private storage, and refreshed after 24 hours. An older cache remains usable if EMMA is unavailable. Search filtering also runs off the UI thread. All source stops remain searchable regardless of the vehicle scope setting.

Selecting a stop queries `stoptimesWithoutPatterns(startTime:,timeRange:,numberOfDepartures:100,omitNonPickups:true)` for the chosen 30/60/120-minute window. The board refreshes every 20 seconds in the foreground. Times use the source `serviceDay` epoch plus scheduled/realtime seconds, including departures after midnight. Only rows flagged realtime by EMMA are marked live. Cancelled and non-boarding rows are excluded. Full trip IDs keep simultaneous departures distinct. Selection/window checks prevent an old request from populating another stop. A failed refresh retains a recent board for at most 60 seconds and shows a warning, then removes stale predictions; unavailable boards do not masquerade as empty successful timetables. Empty valid boards show the normal no-departures state.

Vehicle, stop-index, board and legacy-provider failures are independent. A ViaBusz/KanyarGO outage is reported for the relevant local stop rather than showing an app-wide connection error while EMMA is working. Automatic retries remain enabled.

This integration does not add rail services, trip geometry or route shapes. Vehicle positions are not used to invent stop arrival predictions or scheduled movement.

Raw device GPS and the user's current viewport are not sent to EMMA. Position requests use fixed national bounds; the stop index request is global. Departure-board requests send the selected source stop ID and time window. Location remains local for nearest-stop selection. The source sees the network request and IP address.

## Validation

`EmmaTest` covers source identities, epoch units, speed conversion, BKK mode/colour handling, other-operator exclusion, freshness, missing metadata, newest duplicate selection, local/national scope, GraphQL errors, and route number collisions. `EmmaFeedClientTest` covers shared requests, caching, manual refresh, retries, stale snapshot expiry and GraphQL error fallback. `EmmaJourneyTest` covers trip identity, ordered/repeated stops, source errors, operator isolation and transport-icon selection. `EmmaStopsTest` covers streaming stop-index parsing, source/platform identity, partial errors, scheduled versus realtime departures, service-day arithmetic after midnight, cancelled/non-boarding rows, empty boards, expiry, selection isolation, viewport filtering and stop-count conservation.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The local route catalogue can still be refreshed with `python3 scripts/import_volan_routes.py`. It imports official route identities, not live positions or calendars.

Verified with 73 passing unit tests, debug assembly and lint (zero errors). A live request through the production query/parser returned 1,055 fresh Volán vehicles and 765 fresh BKK vehicles during validation. Emulator checks confirmed national mode starts enabled, transport icons, unnumbered stop dots, stop search, departure boards, vehicle cards and tap-to-zoom behavior. The cached stop index remained searchable after an offline restart, while the unavailable departure board showed a scoped retry message instead of an empty timetable. Screenshots are under `app/build/reports/emma/` (generated, not source-controlled). Counts vary with service and GPS availability.
