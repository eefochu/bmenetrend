# Performance investigation — Huawei P20, 2026-11-01

## Scope and measurement

This pass optimizes the existing native Android app. It does not migrate it to React Native.
The target is prompt local interaction and immediate feedback during asynchronous work;
live departures, map tiles and route planning still depend on external services.

Device: Huawei CLT-L09, Android 10, physical device connected over USB.
Build: source-built `release`, R8 and resource shrinking enabled, debug signing,
application ID `hu.app.bmenetrend`. User settings/data are preserved.

`scripts/measure_android_startup.py` launches through the command returned by T3
`device_open` and reads new `am_activity_launch_time` events. It refuses to reuse a
previous event. Each sample restarts the process; OS caches remain warm. Android's
installed compilation state is left unchanged. These are diagnostic TTID measurements,
not Macrobenchmark results, fully drawn times, or proof of a 100 ms interaction budget.

Ten baseline samples (ms): 1057, 932, 920, 922, 957, 921, 982, 948, 953, 968.
Baseline median: **950.5 ms**. The previously installed APK was replaced with the
source-built baseline before collecting these samples.

Ten **final shared-checkout APK** samples (ms): 972, 916, 894, 900, 906, 905,
881, 893, 855, 894. Final median: **897 ms**, a **5.6%** reduction against the
950.5 ms baseline under the default installed compilation state.

The earlier diagnostic-snapshot APK produced a 704.5 ms median (1083, 792, 757,
686, 605, 709, 582, 700, 718, 590), despite matching production source files.
Its APK differed in DEX and packaged profile bytes. That earlier apparent 25.9%
gain did not reproduce with the final artifact and is **not the final claim**.
Both results are retained in the compact JSON rather than selecting the faster run.

A second comparison explicitly compiled **both** baseline and final APKs with
`cmd package compile -f -m speed hu.app.bmenetrend` before ten cold-process
launches apiece. Baseline: 998, 720, 696, 771, 722, 776, 758, 764, 744, 767
(median **761 ms**). Final: 792, 698, 727, 723, 730, 722, 731, 734, 724, 704
(median **725.5 ms**, **4.7%** lower). This is a matched full-AOT diagnostic,
not a shipped app-specific Baseline Profile or a fresh-install measurement.
The final app remains installed with this per-package compilation optimization.
Future updates or Android's compilation policy can change that state.

Runs were sequential, with OS caches retained, USB power and a reported battery
temperature of 30°C. Network, scheduler, profile and JIT variability remain;
these samples do not establish an app-wide response-time guarantee.

The initial exploratory snapshot comparison used five empty map/search open-and-close cycles per build, using the same Device-panel
commands and SwiftKey keyboard, produced the following exploratory `gfxinfo` results:

| Diagnostic | Baseline | Diagnostic snapshot |
| --- | ---: | ---: |
| Frames recorded | 114 | 120 |
| Frame p50 | 13 ms | 13 ms |
| Frame p90 | 69 ms | 48 ms |
| Frame p95 | 93 ms | 61 ms |
| Frame p99 | 150 ms | 73 ms |
| Janky frames | 47 (41.23%) | 44 (36.67%) |

There is one navigation capture per build, with live map/network/keyboard state;
these results are suggestive rather than a controlled causal frame benchmark.
Slow frames remain. Compact samples and provenance are retained in
[`tools/perf/results/huawei-2026-11-01.json`](../tools/perf/results/huawei-2026-11-01.json).

An additional matched full-AOT navigation comparison did not complete: the Device
automation daemon timed out opening the final APK after installation/compilation.
Only its baseline capture completed, so no final frame-rate improvement is claimed
from that attempt. The final shared-checkout APK remains installed. Startup samples
above were completed successfully before that separate navigation attempt.

Early `dumpsys gfxinfo` readings showed slow UI frames, but were collected over
different UI histories and are not a controlled before/after frame benchmark.
System `atrace` capture failed with `Out of memory (12)` on this device; no trace
conclusions are inferred from that failed capture.

## Confirmed avoidable work

- `TransitViewModel` synchronously decoded and merged three bundled timetables during
  construction, before the first frame. It also constructed an EMMA asset catalogue.
- Every recalculation rebuilt the national route catalogue. Position-only live updates
  recreated route objects and list instances consumed by search and map catalogue indexes.
  A synthetic JVM baseline with 10,000 routes and 5,000 vehicles copied 5,000 route
  objects per call even with no metadata changes. This is an allocation diagnostic,
  not a device latency claim.
- Each repository requested a fresh default HTTP client instead of sharing its
  dispatcher, connection pool and TLS sessions.
- Camera-idle nearby selection deliberately waited 1,200 ms, and the subsequent
  board-fetch job waited another 600 ms before requesting departures.
- Favorite serving-line requests held one repository-wide mutex across network I/O,
  serializing requests despite the ViewModel's concurrency limit of three.
- Reopening a recently viewed departure board discarded the fetched result and
  always waited for another request.
- Both map and planner search waited for remote station search before publishing
  geocoder results, even when the geocoder had already returned or held a memory hit.

## Changes

- Load bundled timetables and route metadata on `Dispatchers.IO`; publish the result
  without overwriting a newer network, offline catalogue or user selection.
- Keep one generation of route metadata. Position/timestamp changes reuse the same
  catalogue; metadata and source changes are compared and published when necessary.
  Derived routes disappear when their vehicles disappear. There is no unbounded history.
- Preserve route objects and lists when enrichment does not change their values.
- Share the default OkHttp client. Custom timeout clients created with `newBuilder()`
  retain the same underlying connection pool and dispatcher. Offline policy remains active.
- Use a 150 ms camera-idle coalescing interval and remove the second 600 ms wait.
- Bound favorite locks to 64 stripes; protect the 64-entry LRU independently of HTTP.
  Same-station cache fills serialize; unrelated stations can proceed independently.
- Retain up to eight complete departure boards for less than 20 seconds, keyed by
  exact stop, explicit platform IDs and requested time window. Partial responses are
  not cached. Manual refresh bypasses the cache; changing network mode clears it.
- Show memory-cached places immediately and publish newly fetched places independently
  of station-search completion. Planner suggestions keep station entries above places
  regardless of which service finishes first. Query generation checks and cancellation
  continue to prevent old queries from replacing current results.

## Validation and remaining work

The baseline targeted regression run passed 98 tests. Final validation in the actual
shared checkout passed `:app:testDevelopmentUnitTest :app:lintRelease :app:assembleRelease`:
83 test classes, 524 tests, zero failures/errors, and two pre-existing skips.
The missing `ui-test-manifest` dependency was added to the development variant so its
Compose tests can resolve the test activity, as the debug variant already could.

Regression coverage includes route-enrichment parity, source precedence, stable
catalogue identity, asynchronous startup publication/cancellation, independent favorite
HTTP requests, same-station single-flight fills, exact board-cache keys, expiry,
forced-refresh bypass, network-mode invalidation, and immediate cached places in both
map and planner search while remote station HTTP is blocked. The TTL test ages the
board timestamp; platform-key distinction is covered structurally rather than by a
separate network round trip. New-query generation guards remain in production.

DeepSeek V4.1 Flash Free via OpenCode authored and ran the regression tests. The final
build log is `/tmp/bmenetrend-perf/final-validation.log`. The optimized release was
exercised on the Huawei through the Device panel: map/search navigation, station
search, live station departures, and place selection. No AndroidRuntime crash was
observed during those checks. The final shared-checkout release APK was installed
over the existing app with data preserved.

This pass does not establish that every screen meets a 100 ms target. A controlled
Macrobenchmark journey, Baseline Profile generation, fully drawn timing, and repeated
scroll/pan measurements remain necessary to enforce a whole-app latency budget.
