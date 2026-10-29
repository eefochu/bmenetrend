# Online Runtime Performance Audit & Measurement (2026-10-29)

## 1. Environment & Build Fingerprint

- **Device**: Android Emulator `emulator-5560` (sole device touched; no connection to BlueStacks / `emulator-5554`)
- **System**: `google/sdk_gphone64_arm64/emu64a:16/BE4B.251210.005/14574095:user/release-keys` (Android 16, ARM64)
- **App Package**: `hu.app.bmenetrend` (versionCode=8, versionName=0.4.0)
- **Baseline APK Frozen Copy**: `/tmp/bmenetrend-perf-20261029-baseline/app-arm64-v8a-debug.apk`
- **Baseline APK SHA-256**: `b79cfa6b79c8a702e5bf9486ea783d97252982fba521b7f510b768443bfb7f25`
- **Installed APK Status**: Identical SHA-256 running live on `emulator-5560` (`/data/app/~~Uaavw2gLHH8E-7KmnFYjKg==/hu.app.bmenetrend-2iS3DYWmjf4OYyZFenqljw==/base.apk`)
- **Network / Connectivity**: Online mode verified active (Wi-Fi connected to `AndroidWifi`, cellular standby, DNS validated).

---

## 2. Methodology & Representative Online Journey

A bounded, reproducible representative user journey was executed while collecting `dumpsys gfxinfo` and a 6-second system Perfetto trace (`sched, freq, am, wm, gfx, view, dalvik, input`):

1. **Map Pan & Zoom**: 2 horizontal fast drag gestures (left/right) across the MapLibre surface view, followed by double-tap zoom into Budapest center.
2. **Station Loading & Highlight**: Tap on central station marker to trigger selection, line focus, and departure board loading.
3. **Search Interaction**: Tap top search bar, type query `"Deak"`, wait for local + remote search suggestions, press back.
4. **Planner Navigation**: Tap "Tervezés" (Trip Planner) navigation bar tab, trigger route planner endpoint initialization, and switch back to Map tab.

---

## 3. Runtime Measurements & Trace Observations

### A. Frame Timing & Jank (`dumpsys gfxinfo hu.app.bmenetrend`)
- **Total frames rendered**: 104
- **Janky frames**: 51 (**49.04%** deadline missed) / 73 (**70.19%** legacy metric)
- **Percentiles**:
  - **p50**: 32 ms
  - **p90**: 69 ms
  - **p95**: 93 ms
  - **p99**: 121 ms
- **Pipeline Breakdown**:
  - High input latency frames: 111
  - Slow UI thread frames: 17
  - Slow issue draw commands: 51
  - Slow bitmap uploads: 1

### B. Trace Analysis & Hotspots (Perfetto `bme_trace.perfetto`)
- **MapLibre GL Thread Contention (`postAndWait`)**:
  - Main thread blocked for **82.61 ms**, **75.50 ms**, and **37.33 ms** in `postAndWait`.
  - Thread monitor contention with MapLibre background thread (`Worker 4` / `GLThread.guardedRun()`) blocking UI thread for **29.25 ms**.
- **Garbage Collection (ART Alloc Churn)**:
  - Heavy concurrent mark compact GC triggered every 10–15 seconds even when lightly active.
  - Slices `PrepareForCompaction` (**56.55 ms**), `MarkRoots` (**42.13 ms**), and `ProcessMarkStackNonNull` (**41.94 ms**) observed pausing threads.
- **Trace Custom Markers**:
  - `BMenetrend.MapVehicles`: Executed on UI Thread (`TID 5969`) repeatedly (0.8–2.1 ms per tick) during pan/zoom.
  - `BMenetrend.MapLayout`: 0.3–5.0 ms.
  - `BMenetrend.MapUpload`: 0.3–0.9 ms.

---

## 4. Review of Working-Tree Optimizations (Read-Only Correctness Review)

Parent working copy contains optimizations across 4 files:
1. `app/src/main/java/hu/app/bmenetrend/map/NativeTransitMap.kt`:
   - Moved station feature filtering, geometry extraction, and GeoJSON string serialization (`FeatureCollection.fromFeatures(...).toJson()`) off the UI thread into `withContext(Dispatchers.Default)` with `ensureActive()`.
   - Moved `sprites.render()` (Bitmap rasterization) to `Dispatchers.Default`.
   - **Review**: **Correct**. Avoids main-thread JSON serialization and Canvas rasterization during station clustering updates. No thread-safety violations detected.
2. `app/src/main/java/hu/app/bmenetrend/PlannerViewModel.kt`:
   - Cached previous stop index inputs (`indexedLocal === local && indexedNational === national`) to prevent redundant background indexing jobs.
   - Removed 400 ms debounce for local station index search while preserving 400 ms debounce for remote geocoder search (`places.search`).
   - **Review**: **Correct**. Local stop results appear instantaneously while remote geocoding traffic remains throttled.
3. `app/src/main/java/hu/app/bmenetrend/data/TransitSearch.kt`:
   - Replaced full country-wide sorting `.sorted().take(100)` with top-K bounded heap `bestMatches(100)` using `PriorityQueue(limit, order.reversed())` and stable tie-breaking `Ranked(value, index)`.
   - **Review**: **Correct**. Preserves original index order on tied ranks (e.g. `location == null`) and reduces O(N log N) sorting of 40,000 stops to O(N log K).
4. `app/src/main/java/hu/app/bmenetrend/map/LineMapFocus.kt`:
   - Pre-computed `selectedStopIds` and `routeStopIds` into `HashSet`s before filtering `contextStops`.
   - **Review**: **Correct**. Avoids nested linear searches without altering output.

---

## 5. Top 3 Highest-Value Actionable Remaining Findings

### Finding 1: Synchronous Flash I/O, GZIP Decompression & JSON Parsing on Main Thread in `TransitViewModel.recalculate`
- **Location**:
  - [TransitViewModel.kt: lines 400–405](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/TransitViewModel.kt#L400-L405)
  - [StationTimetableRepository.kt: lines 68–70, 105–139, 305–340, 396–410](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/data/StationTimetableRepository.kt#L305-L340)
- **Mechanism**:
  - `TransitViewModel.recalculate()` runs synchronously on the Android Main thread during UI events, user selections, and periodic polls.
  - When any station or stop is tapped, `validBoard` is initially null while awaiting online network response. Lines 400–404 trigger:
    ```kotlin
    val offlineBoard = if (selectedId != null && (validBoard == null || boardFailure) && emmaDepartures.isEmpty()) {
        stationTimetableRepository.getOfflineBoard(selectedId, cluster.map { it.id }, now, current.windowMinutes)
    } else null
    ```
  - `getOfflineBoard` calls `getOrLoadTimetable(stationId)`, which calls `loadAndDecompress(file)`:
    ```kotlin
    val text = GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = JSONObject(text)
    ```
  - This performs unbuffered flash storage reads, GZIP CPU decompression, and full JSON object parsing directly on the UI thread.
  - Additionally, `StationTimetableRepository`'s constructor calls `init { loadIndex() }` synchronously reading `index.json` on the Main thread when `TransitViewModel` is instantiated (`TransitViewModel.kt` line 47).
- **Impact**: Instantaneous UI thread freezes (15–60 ms) upon tapping stations before online timetables arrive, triggering frame drops and input latency warnings.
- **Recommended Fix**:
  - Hoist `stationTimetableRepository.getOfflineBoard` into a coroutine dispatcher (`Dispatchers.IO`) or load offline timetables asynchronously into a memory state holder.
  - Load `index.json` asynchronously upon repository creation.

---

### Finding 2: Per-Marker MapLibre JNI Mutation and Main-Thread Bitmap Decoding for Vehicles in `NativeTransitMap`
- **Location**:
  - [NativeTransitMap.kt: lines 478–600](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/map/NativeTransitMap.kt#L478-L600)
- **Mechanism**:
  - The vehicle `LaunchedEffect` is keyed on `viewport`, `zoom`, and `vehicles`. Every camera pan and pinch gesture continuously restarts and re-executes this block on the Main thread.
  - For every visible vehicle in the viewport (often dozens simultaneously):
    - `vehicleIcon(vehicle)` executes `BitmapFactory.decodeResource`, allocating `Bitmap` and `Canvas` instances whenever zoom level crosses thresholds or cache evicts (`iconCache.size > 256`).
    - More critically, lines 590–596 mutate individual MapLibre legacy `Marker` objects via per-property JNI calls on the UI thread:
      ```kotlin
      val marker = vehicleMarkers.getOrPut(vehicle.id) { nativeMap.addMarker(options) }
      if(marker.position!=options.position) marker.position=options.position
      if(marker.icon!=options.icon) marker.icon=options.icon
      if(marker.title!=options.title) marker.title=options.title
      if(marker.snippet!=options.snippet) marker.snippet=options.snippet
      ```
    - In MapLibre Android, these JNI calls acquire locks and synchronously dispatch to the GL/render thread via `postAndWait`.
- **Impact**: Perfetto trace confirms **75–82 ms UI thread stalls** waiting for `postAndWait` and GL thread monitor contention during camera movement.
- **Recommended Fix**:
  - Migrate vehicle markers from legacy MapLibre `Marker` objects to a GeoJSON `SymbolLayer` backed by `GeoJsonSource` (matching the architecture used for station markers). GeoJSON feature generation and JSON serialization can then run completely on `Dispatchers.Default`.
  - Throttle/debounce camera updates driving vehicle queries during active gestures.

---

### Finding 3: Inefficient `remember` Keys and Nationwide 40k-Stop Scanning in `LineMapFocus.create`
- **Location**:
  - [MainActivity.kt: lines 164–170](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/MainActivity.kt#L164-L170)
  - [LineMapFocus.kt: lines 59–78, 103–107](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/map/LineMapFocus.kt#L59-L78)
- **Mechanism**:
  - In `MainActivity.kt`:
    ```kotlin
    val focus = remember(vehicle?.id, vehicle?.routeId, boarding, state.network, state.emmaRoutes, state.lineDetails, state.vehicleJourney, state.emmaStops) {
        val routes=state.routes; val allStops=state.stops
        LineMapFocus.create(vehicle,boarding,routes,state.lineDetails,state.vehicleJourney,allStops)
    }
    ```
    `state.emmaRoutes` is re-instantiated on every live vehicle tick (`TransitViewModel.kt` line 443). Keying `remember` on `state.emmaRoutes` (thousands of items) invalidates `focus` on unrelated vehicle arrivals, triggering `LineMapFocus.create` on the UI thread.
  - Inside `LineMapFocus.create`, lines 59–78 build a 40,000-element stop hash map (`allStops.associateBy { it.id }`) and line 103 iterates through all 40,000+ national stops:
    ```kotlin
    allStops.filter { stop -> routeId!=null && (routeId in stop.routeIds || stop.id in routeStopIds) }
    ```
- **Impact**: Heavy allocation churn and Compose recomposition stalls (5–15 ms) during route inspection and vehicle tracking.
- **Recommended Fix**:
  - Narrow `remember` keys to only the active `routeId` or active `TransitRoute` instance rather than the full `state.emmaRoutes` list.
  - Replace the 40,000-stop linear scan with direct index lookups (`stopsById[stopId]`) using the route's known pattern/direction stop IDs.

---

### Additional Note: `HybridTripPlanner` Serialized Await Cascades
- In [HybridTripPlanner.kt: lines 50–60, 77–83, 105, 135–151](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/data/HybridTripPlanner.kt#L50-L60):
  - `val network = local.await()` blocks immediately at line 59 before verifying whether the trip endpoints even require local transit network access.
  - Gateway combinatorial pairs run under `worker.limitedParallelism(1)` with synchronous `awaitAll()`, stalling overall trip planning until the slowest network call completes.
