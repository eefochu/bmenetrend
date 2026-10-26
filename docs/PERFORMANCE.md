# Highlighting performance investigation — 2026-10-26

This is a targeted release/R8 emulator investigation, following the Android performance skills. The user requested emulator checks. It is not a physical-device Macrobenchmark result or a claim that phone jank is solved.

## Reproduction

SDK `Medium_Phone` arm64 emulator, API 36 (`sdk_gphone64_arm64`), serial `emulator-5560`, on the same macOS host. Release build, R8 and resource shrinking enabled, not debuggable, shell-profileable. Each APK installed with `-r` and compiled with `cmd package compile -f -m speed hu.app.bmenetrend`. This uses full AOT for a matched comparison; it does not measure a shipped Baseline Profile or fresh-install startup.

One warm-up followed by five cached line 762 search → open/highlight → two map pans → close cycles. The replay includes search, keyboard and bottom-card frames, not just the highlighted map. Existing app data and online basemap/transit caches were retained. Runs were sequential; no Gradle builds ran during recording. UIAutomator waiting and emulator/host scheduling prevent treating the timings as phone frame-budget measurements.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleRelease
adb -s emulator-5560 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5560 shell cmd package compile -f -m speed hu.app.bmenetrend
adb -s emulator-5560 shell am start -n hu.app.bmenetrend/.MainActivity
python3 tools/perf/highlight_profile.py --serial emulator-5560 --label after --out /tmp/bmenetrend-perf-after
trace_processor query -f tools/perf/stages.sql /tmp/bmenetrend-perf-after/highlight.pftrace
```

The script records Perfetto, `gfxinfo framestats`, device metadata and replay parameters. It expects the app's map screen, no location permission modal, Hungarian app labels and the reference emulator viewport. Choose an explicit owned emulator serial. It does not start or wipe emulators. The app manifest's shell-profileable flag allows tracing the optimized release without enabling debugging.

## Findings and fixes

The original trace spent an average 135.329 ms (maximum 503.555 ms) per stop grouping call on the main thread. Latitude-only buckets compared unrelated stops across a wide city strip and repeatedly normalized names before checking physical distance. A two-dimensional geographic index plus an early distance check reduced that to 9.817 ms average / 37.044 ms maximum in the intermediate run. Group membership, nearest-candidate ambiguity rules, opposite-direction rejection and exact platform IDs are preserved.

The final renderer prepares groups, marker bitmaps and GeoJSON on `Dispatchers.Default`, then uploads on the main thread. Vehicle/location updates have a separate effect and no longer rebuild station groups or station layers. Line focus display collections are computed once per focus model. Existing lifecycle-aware Flow collection and strong skipping were already enabled; the trace did not justify a blanket Compose stability rewrite.

Stop bodies, split operator colours and direction pointers now share a cached bitmap instead of separate circle/fill layers and projected arrow geometry. Quantized sizes and bearings limit texture variants; unused images are evicted above the 256-entry soft limit (currently visible images are retained). Normal symbols use native viewport-Y ordering; the selected symbol draws above them. Close zoom (15+) permits at most a four-dp visual nudge and a little overlap; source stop coordinates and departure scope remain unchanged. Highlighted rings remain fixed at 16 dp and need no screen-projection/collision-layout pass.

The intermediate trace still spent 9.486 seconds in main-thread `postAndWait` across the replay, compared with 0.412 seconds in Compose `recompose`. This identifies native rendering/emulator GPU waiting as a remaining limitation; it does not prove a GPU bottleneck on the user's phone.

## Measurements

| Diagnostic | Before | Geographic index only | Final renderer |
| --- | ---: | ---: | ---: |
| Grouping calls | 70 | 68 | 31 |
| Grouping thread | Main | Main | Worker |
| Mean grouping span | 135.329 ms | 9.817 ms | 13.060 ms |
| Maximum grouping span | 503.555 ms | 37.044 ms | 33.909 ms |
| Total grouping span | 9473.1 ms | 667.5 ms | 404.9 ms |
| Frames recorded | 851 | 757 | 828 |
| Frame p50 | 34 ms | 36 ms | 34 ms |
| Frame p90 | 57 ms | 65 ms | 53 ms |
| Frame p95 | 69 ms | 77 ms | 61 ms |
| Frame p99 | 109 ms | 121 ms | 81 ms |
| Janky frames | 540 / 63.45% | 547 / 72.26% | 548 / 66.18% |

Final main-thread station layout: 28 calls, 0.206 ms mean / 0.409 ms maximum. Station upload: 28 calls, 0.075 ms mean / 0.372 ms maximum. Vehicle updates: 59 calls, 1.808 ms mean / 14.665 ms maximum. Original combined `MapRender`: 70 calls, 140.832 ms mean / 507.737 ms maximum.

The measured grouping mean fell about 90%, and its remaining work moved off the main thread. Fewer grouping calls demonstrate that station preparation no longer follows every vehicle update; cancelled obsolete preparations can also account for part of the count difference. The final replay's frame tail improved, but the **overall jank percentage did not improve versus the original run**. There is one run per revision, no confidence interval, and online/host/GPU variance. These results support the CPU and update-isolation fixes, not a universal frame-rate claim. `gfxinfo` GPU tail values hit an overflow-like 4950-ms bucket in all runs; they are retained in the raw summaries and not interpreted as physical GPU timing.

Raw diagnostic traces remain in `/tmp/bmenetrend-perf-before`, `/tmp/bmenetrend-perf-grid`, and `/tmp/bmenetrend-perf-after`; compact results are stored under `tools/perf/results/`. Stage durations are elapsed trace spans, not CPU cycles. Original `MapRender` includes `StopGroups`; do not add the nested durations together. Final layout/upload/vehicle sections are separate from background grouping and serialization.

## Validation

285 unit/Robolectric/Compose UI tests pass, including the spatial-grid boundary and city-strip cases, close-zoom nudge bounds/density scaling, unchanged highlighted sizing, and complete marker texture colours/direction bounds. Android lint reports zero errors (87 warnings); `:app:lintDebug`, `:app:assembleDebug`, and `:app:assembleRelease` pass. Both distributed variants use R8 and resource shrinking (about 45 MiB each).

The release emulator completed all five highlighted-line cycles without an app crash. Screenshots confirmed both-direction highlighted stop rings, native vehicle overlap, operator split colours and close-up platform rings. A Ritsmann Iskola selection retained its scoped ViaBusz/KanyarGO departure card; tapping a nearby marker opened the Vendel tér shared-platform board directly without a selection menu. No physical-device measurement was made.

## References

- [Profile an optimized build](https://developer.android.com/studio/profile/build-run-manually)
- [Shell-profileable manifest option](https://developer.android.com/guide/topics/manifest/profileable-element)
- [Android gfxinfo diagnostics](https://developer.android.com/tools/dumpsys)
- [Perfetto command-line trace analysis](https://perfetto.dev/docs/getting-started/command-line-analysis)
