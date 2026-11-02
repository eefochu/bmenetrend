# EMMA rail offline validation — 2026-11-02

Status: passed live snapshot generation, full source import, scheduled rail boards/planning, regression tests, lint and debug APK build.

## Delivered behavior

The standard offline download now includes MÁV mainline trains, tram-trains and rail replacement buses from the existing EMMA GraphQL endpoint. No separate GTFS registration or user-supplied rail file is needed. HÉV continues to come from BKK.

EMMA feed `1:` is queried in serial batches of up to 256 routes, with a cancellable one-second pause before each batch. Station metadata is requested once per route; train calls contain station references and scheduled seconds only. The first implementation used 24-route batches and received HTTP 403 before completion; one-second pacing alone did not resolve it. The server did not identify the precise denial reason. The compact, larger-batch implementation completed successfully.

Snapshots preserve actual operating dates, overnight times, parent stations, platform codes and pickup/dropoff restrictions. Identical calendars share service IDs. Rail station and route IDs match existing EMMA identifiers. Invalid/partial responses abort installation, leaving the active package intact.

## Live snapshot

- Endpoint: `https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql`
- Downloaded service-day window: 2026-10-31 through 2026-12-02, inclusive; the app advertises at most 30 days, further limited by source validity.
- Rail/replacement/tram-train routes: 13,543; detail requests: 53, plus the catalogue request.
- Compressed snapshot: 12,525,959 bytes.
- Full download Gradle invocation: 1 minute 39 seconds, including compilation/test setup.
- Cache used only for subsequent integration checks: `/tmp/bmenetrend-offline-feeds/mav.zip`.
- JVM integration uses macOS trusted root certificates in an in-memory trust store. TLS and hostname verification remain enabled; no production trust configuration is changed.

## Full package verification

Imported the actual Volán ZIP (102,127,745 bytes), BKK ZIP (62,339,282 bytes), and EMMA rail snapshot, then all bundled ViaBusz, KanyarGO and HOMM timetables. Local providers were explicitly enabled in this storage test to measure the upper bound of the worker's verified-provider selection, without additional provider HTTP requests.

- Indexed database: **425,390,080 bytes**.
- Bundled basemap/supporting assets plus native map working copy: **70,303,874 bytes**.
- Total installed footprint: **495,693,954 bytes**, below **500,000,000 bytes**.
- Stops: 103,275; routes: 17,430; MÁV routes: 13,543, including 12,322 RAIL routes.
- Database import/index duration on this host: 17.28 seconds.
- Kelenföld station board at 2026-11-02 08:00 Budapest time: 91 scheduled departures in the requested window.
- Offline Kelenföld–Biatorbágy planning found an **S12 train**, departing 08:02 and arriving 08:14 Budapest time (12 minutes).
- Activation through OfflinePackStore succeeded; fixture tests also verify parent station/platform mapping, service calendars, overnight calls and CSV escaping.

The footprint has approximately 4.3 MB headroom. These are measured source versions, not a promise that later feeds fit. Existing runtime limits reject oversized replacements and preserve the previous installed package. User-imported additional archives consume the same budget. The rail feed also exposes international routes; the snapshot retains them as provided by EMMA. No cross-feed walking transfers are invented.

## Automated checks

- Offline suite plus OfflinePackSettingsTest and PlannerOfflineErrorsTest: **84 cases, 80 passed, 4 opt-in cases skipped, zero failures/errors**.
- The live download and full-source import cases were run separately with opt-in enabled and both passed; they were not counted as successful merely because they skipped in the regular suite.
- Added compact-response regressions for route-level station metadata and missing station records.
- `:app:lintDebug :app:assembleDebug`: passed.
- Final full-source test including all local providers: passed after the above suite/build. Only test code changed to add that final check.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Reproduction

Use JDK 21. The live download test exports macOS system root certificates for its JVM TLS client. The opt-in source files live outside the repository.

```sh
OFFLINE_REAL_RAIL=1 ./gradlew :app:testDebugUnitTest --tests '*downloadProductionEmmaRailSnapshot'
OFFLINE_REAL_RAIL=1 ./gradlew :app:testDebugUnitTest --tests '*completeThreeFeedsWithRailCanBeImportedWithinStorageCapAndPlanned'
./gradlew :app:testDebugUnitTest --tests 'hu.app.bmenetrend.offline.*' --tests '*OfflinePackSettingsTest' --tests '*PlannerOfflineErrorsTest' :app:lintDebug :app:assembleDebug
```

After installing the updated app, use **Settings → Offline use → Update pack** to add rail data to an existing offline installation. Merely updating the APK does not change the contents of an already downloaded package. No release was published as part of this change.
