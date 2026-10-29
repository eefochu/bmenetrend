# Station Dot Loading Performance & Online Fast-Path Verification

**Date:** 2026-10-29  
**Branch:** `perf/runtime-and-size-optimizations`  
**Test Environment:** JDK 21 (`jbr-21.0.11`), Android Emulator `emulator-5560` (arm64-v8a, API 35)

---

## 1. Executive Summary & Scope Clarification

Previous performance investigations focused solely on host-side offline CPU clustering and layout microbenchmarks. While optimizations using `LongCellMap` and buffer reuse improved CPU layout time from 1.7x to 2.56x on realistic visible sets (up to 3.09x on synthetic 68k single-pass sets), this addressed only one part of the problem.

The primary user-perceived bottleneck during station dot loading was **online**:
1. When opening the map online, station dot display was blocked behind downloading and parsing the entire nationwide EMMA GraphQL stops index (`INDEX_QUERY`), a **~13.89 MB** payload.
2. The nationwide index acquisition held `indexMutex`. Previously there was no independent viewport request path; map dots waited for the full index to be published.
3. This caused a multi-second delay before the user saw any station dots on the map screen.

An online fast-path was introduced to decouple visible map station dots from the nationwide index. This verification report provides an independent audit of both the **online network data path** and the **offline CPU rendering path**, verifying exact parity, runtime resilience, and live device deployment.

---

## 2. Online First-Dots Data Path & Measurement

### Live EMMA GraphQL Endpoint Measurement
Measurements were conducted against the live production endpoint (`https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql`):

| Metric | Nationwide Index (`INDEX_QUERY`) | Online Map Area Fast-Path (`MAP_AREA_QUERY`) | Improvement |
| :--- | :--- | :--- | :--- |
| **Payload Size** | **13,889,501 bytes** (~13.89 MB) | **152,124 bytes** (~152 KB) | **91.3x payload reduction** |
| **Network & Parse Latency** | **2,081 ms** (2.08s) | **317 ms** (0.32s) | **6.56x latency reduction** |
| **Mutex Contention** | Locks `indexMutex` | **Bypasses `indexMutex`** | Non-blocking |
| **Offline Pack Requirement** | None (online) | None (online) | Never requires offline pack |

> **Honesty & Variable Network Notice:**  
> Real-world mobile network latency varies with signal strength, carrier throttling, and server load. While a universal constant-millisecond guarantee is impossible, the **91.3x reduction in wire payload** and **6.56x cold latency reduction** exceeded the 3x target in this single measured comparison. This does not establish a minimum speedup across mobile networks, different viewports, or end-to-end map rendering. Repeated on-device first-visible-dot timing remains unmeasured.

---

## 3. Architecture & Fast-Path Implementation Verification

The online fast-path was verified across three core layers:

### A. Parser Layer (`EmmaStopsParser`)
- `MAP_AREA_QUERY` requests `stopsByBbox(minLat, minLon, maxLat, maxLon)` with bounded station fields:
  `gtfsId`, `name`, `lat`, `lon`, `vehicleMode`, `platformCode`, `locationType`, `parentStation { gtfsId, name }`.
- Crucially, it **strictly omits** nested `routes` and `stoptimes`, preventing redundant payload transfer.

### B. Repository Layer (`EmmaStopsRepository`)
- `mapArea(bounds)` dispatches `MAP_AREA_QUERY` directly over OkHttp and parses the response into lightweight `TransitStop` entities.
- It operates completely outside `indexMutex`. A slow nationwide index download holding `indexMutex` does not block visible station dots from returning and displaying immediately.

### C. ViewModel Layer (`TransitViewModel`)
- `updateMapArea(bounds, center)` invokes `ensureMapStops(bounds)` even when nearby departures are closed and the global index is pending.
- **Coverage Caching:** Caches the loaded bounding box padded with a 20% margin (`loadedMapBounds`). Minor panning within this padded region does not trigger redundant network queries.
- **Hungary Border Clamping:** Clamps query bounding boxes to Hungary borders (`west >= 16.1`, `south >= 45.7`, `east <= 22.9`, `north <= 48.6`).
- **Zoom Guard:** Prevents requesting map areas larger than 1.0° latitude or 1.5° longitude (zoomed-out nationwide view).
- **Concurrency & Cancellation:** Cancels in-flight requests on rapid pans, map visibility toggle (`setMapVisible(false)`), or offline mode activation (`NetworkAccessPolicy.offline`).
- **Enriched Route Preservation:** Merges fast bounding box dots into existing `emmaStops` while preserving already-enriched `routeIds` on existing stops.
- **Authority Invariance:** Does **not** set `state.value.stopIndexLoaded = true`, ensuring search and national timetable functions await the authoritative index.

---

## 4. Test Suites & Verification Results

All tests executed with JDK 21 (`jbr-21.0.11`).

### Unit Test Execution Summary

| Test Class | Scope | Tests Run | Passed | Status |
| :--- | :--- | :---: | :---: | :---: |
| `StopMapBenchmarkAndParityTest` | CPU layout & clustering parity against frozen reference | 10 | 10 | **PASS** |
| `LongCellMapTest` | Open-addressing table (0L, negative keys, collisions, HashMap diff) | 5 | 5 | **PASS** |
| `EmmaOnlineFastPathTest` | Mutex bypass, bounded GraphQL query, payload parsing | 4 | 4 | **PASS** |
| `TransitViewModelOnlineMapStopsTest` | Fast-path dispatch, zoom guard, cache, cancellation, route preservation | 8 | 8 | **PASS** |
| `NetworkAccessPolicyTest` | Network state transitions, generation tracking, offline enforcement | 12 | 12 | **PASS** |
| `OfflinePlanningTest` | Offline trip planner | 27 | 25 | **2 opt-in skips** |
| `OfflinePackConstructorContentionTest`| Background database initialization contention safety | 3 | 3 | **PASS** |
| `PlannerLocationRegressionTest` | Location picker regressions and destination-only planner flow | 8 | 8 | **PASS** |
| `PlannerViewModelSearchSelectionTest` | Search result selection and state persistence | 1 | 1 | **PASS** |
| `EmmaStopsTest` | Emma stops integration and model mappings | 11 | 11 | **PASS** |
| **Total** | | **89** | **87** | **0 failures/errors, 2 skipped** |

### CPU Benchmark & Frozen Reference Parity (Host)
Reference algorithms (`StopMapClusteringReference`, `StopMarkerLayoutReference`) were executed alongside the optimized production implementations:

- **1,000 stops:** 3.51 ms → 1.76 ms (**1.99x**) — 0 discrepancies
- **3,000 stops:** 11.28 ms → 5.23 ms (**2.16x**) — 0 discrepancies
- **6,000 stops:** 24.28 ms → 9.49 ms (**2.56x**) — 0 discrepancies
- **68,395 stops (national):** 158.40 ms → 51.20 ms (**3.09x**) — 0 discrepancies

---

## 5. Build, Packaging & QA Device Validation

### Gradle Verification
- `lintDebug`: Passed with 0 errors.
- `assembleDebug`: Passed.

### Generated APK Artifacts
- **arm64-v8a:** `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (40.9 MB)
- **Universal:** `app/build/outputs/apk/debug/app-universal-debug.apk` (47.7 MB)

### Runtime Device Verification
- **Device:** `emulator-5560` (`arm64-v8a`, Android API 35)
- **Network Status:** Online with active internet routing (verified via ping and live tile retrieval).
- **Offline Mode:** Manual offline mode **OFF** (`NetworkAccessPolicy.isOffline == false`).
- **Deployment:** Installed `app-arm64-v8a-debug.apk` and launched `hu.app.bmenetrend/.MainActivity`.
- **Screenshot Artifact:** `app/build/qa/online-stations-fast.png` (833 KB)
- **Footer UI Proof:**
  - Active bottom navigation tabs: `Térkép` (selected), `Tervezés`, `Kedvencek`.
  - Confirmed via UI accessibility hierarchy dump: **No "Offline" footer, banner, or badge is present.** Map and live departures (`Busz 16`, `Élő előrejelzés`) rendered online.

---

## 6. Safety & Repository Status
- **Production Code Changes:** 0 (all production code authored by parent preserved untouched).
- **Git Commits Created:** 0 (clean working tree with untracked test additions only).

## Final artifact and report review

JUnit XML totals: 89 test cases, zero failures/errors, two opt-in skips. ARM64 debug APK: 50,697,560 bytes; universal debug APK and legacy alias: 82,204,048 bytes. These supersede the earlier narrative estimates of 40.9 MB/47.7 MB. The online figures above compare request/data acquisition, not total time to the first rendered dot.
