# Offline rebuild and verification — 2026-11-01

The fresh download completed in **50.067 seconds** on the Medium_Phone Android 16
arm64 emulator. This was the actual WorkManager chain, downloading official feeds,
checking local providers, compiling/indexing, verifying and activating the pack.
The development variant was debuggable and unminified. It is one observation on
this host/connection, not a guarantee for every phone, connection or later feed.
Building/installing the app and the initial connectivity check are excluded.

## What was broken

- Settings retained its `IMPORTING`/`DOWNLOADING` state even after a worker finished,
  keeping controls busy. Terminal work now transitions to Ready, Failed or Missing.
- Automatic offline fallback required an installed pack. No internet now activates
  offline mode in all cases; missing data has an explicit message and online work
  stops. Restoring connectivity returns online unless manual offline is enabled.
- Default-network callbacks raced synchronous capability queries and old network
  loss events. Callback capabilities and current-network identity now drive updates.
- Android validation can fail while the timetable providers remain reachable. The
  emulator reproduced this. Bounded TLS HEAD probes handle that case; manual
  offline suppresses them. WorkManager's `CONNECTED` constraint also requires that
  validation bit, so mobile-enabled downloads use the app's connectivity policy
  and retry paused work. Wi-Fi-only downloads retain their unmetered constraint.
- Network constraints applied to indexing, and compilation queried online local
  sources at its end. Downloading and indexing are now separate chained jobs.
  Downloading the two feeds and checking local sources run concurrently; all
  compilation inputs are local before indexing starts.
- Offline board queries loaded whole service-day schedules and repeatedly opened
  the database. Selected, nearby and favourite boards now share a snapshot and
  indexed departure windows. Parent stations resolve only their source-declared
  children, deduplicating explicitly selected parent/child combinations.
- Line details rebuilt identical stop patterns for every trip. SQLite now selects
  distinct ordered patterns before materializing stop objects; route lookup uses
  its indexed source ID.
- An active offline read dropped new favourite/selection/window changes. Reads now
  coalesce a pending refresh and rerun with the latest state. The Android view-model
  check reproduced the lost-favourite update before the fix.
- Planner exceptions for absent packs, unsupported endpoints and expired dates all
  became a generic service error. They now retain their specific messages. Pack
  replacement/deletion also updates the catalogue, trip results and validity label.

## Pack preparation

CSV uses block appends for unquoted fields; time parsing avoids per-time split/map
allocations. A bounded 32 MB SQLite cache helps bulk import and index construction.
Transactions remain on one dedicated database thread across suspending progress
callbacks. Combined input and installed-footprint limits remain enforced during
parallel transfers and import. Existing input archives can be reused after an
interruption; corrupt archives are discarded. Checksums, entry CRCs, SQLite checks,
a database flush and atomic activation protect the previous working pack.

| Fresh worker-chain stage | Observed duration |
| --- | ---: |
| Download feeds and verify local sources | 9.200 s |
| Compile, index, verify and activate | 40.867 s |
| Download request to Ready | **50.067 s** |

The pack downloaded 164,467,027 bytes, installed a 382,255,104-byte database, and
has a conservative 452,558,978-byte footprint including bundled map assets and
their native working copy. It contains 68,373 stops and 355,970 trips, with Volán,
BKK, ViaBusz, KanyarGO and HOMM sources and no source warnings. Validity is
2026-11-01 through 2026-11-30. Source versions and sizes can change.

A separate two-feed Android check imported/indexed/activated in 44.766 seconds.
With guarded HTTP blocked it verified catalogue, stop search, departure boards,
line details, a real Kelenföld → Biatorbágy bus journey and both offline map styles.
Its individual samples were 566 ms for catalogue loading, 2,049 ms for initial
search-index construction plus two queries, 1 ms for three board scopes, 6 ms for
line details and 34 ms for routing. These are single diagnostic measurements of
different operations, not frame timings or latency percentiles.

Raw measurements and source hashes:
[offline-emulator-2026-11-01.json](../tools/perf/results/offline-emulator-2026-11-01.json).

## Final checks

`testDevelopmentUnitTest`, `lintRelease` and `assembleRelease` passed in the shared
checkout: **547 tests, zero failures/errors, two existing opt-in skips**. Tests cover
missing-pack fallback, internet recovery, captive-network handover, verified-provider
recovery, manual-mode probe suppression, download constraints/budgets, terminal
worker states, parser boundaries, exact departure windows, parent/child station
boards, pattern reuse, offline error messages and existing online regressions.

Three opt-in Android scenarios passed separately: isolated real-feed import and
local features, a fresh full download through WorkManager, and final screen
view-model integration against that installed five-source pack. The last scenario
completed in 3.472 seconds and exercised the actual planner search/selection flow,
a Biatorbágy → Kelenföld journey and immediate favourite addition while offline.
It reproduced a dropped favourite refresh before the coalescing fix and passed
afterward. The physical phone was untouched.

## Reproduction

Use the development variant for instrumentation; the distributed release remains
R8-optimized. The first attempt to instrument the minified debug app failed in
the test runner's Kotlin-runtime linkage before pack code ran. It is excluded from
the measurements.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDevelopment :app:assembleDevelopmentAndroidTest
adb -s EMULATOR install -r app/build/outputs/apk/development/app-arm64-v8a-development.apk
adb -s EMULATOR install -r app/build/outputs/apk/androidTest/development/app-development-androidTest.apk

# Fresh development-app data only: downloads a real pack and leaves it installed.
adb -s EMULATOR shell am instrument -w -e fullOfflineDownload true \
  -e class hu.app.bmenetrend.offline.OfflineRealPackTest \
  hu.app.bmenetrend.dev.test/androidx.test.runner.AndroidJUnitRunner

# Exercise screen view models, offline endpoint selection, planning and favourites.
adb -s EMULATOR shell am instrument -w -e verifyInstalledOfflinePack true \
  -e class hu.app.bmenetrend.offline.OfflineRealPackTest \
  hu.app.bmenetrend.dev.test/androidx.test.runner.AndroidJUnitRunner
```

For an isolated local-input benchmark, place the official `volan.zip` and `bkk.zip`
in a directory readable by the development app and pass `-e realOfflinePack true`,
`-e feedDirectory /data/user/0/hu.app.bmenetrend.dev/files/offline-feeds` and an
optional pinned `-e feedDate YYYY-MM-DD`. Files can be copied through
`/data/local/tmp` and `run-as hu.app.bmenetrend.dev`; Android scoped storage can
prevent instrumentation from reading files pushed into external storage.
This check uses isolated cache storage and does not replace the installed pack.

No physical Huawei was used. The driver could not locate the expected download
button during its scroll attempt; UI automation was stopped. Validation continued
through Android instrumentation and the same repositories/view models used by
the screens.

## Coverage boundaries

Pack-backed map, stop/route search, scheduled departures, nearby/favourite boards,
line details and stop-to-stop planning work locally. Live vehicle positions,
delays and arbitrary-address walking are not provided by a static timetable pack.
Mainline rail is absent from the default sources; adding it requires an additional
GTFS feed. Source-provided transfer links and exact platform identities remain
authoritative; no speculative proximity-based walking connections were added.
