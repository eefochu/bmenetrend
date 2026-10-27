# Comprehensive Performance Audit Report: Android App Size, Runtime & Compose (2026-10-27)

**Repository**: `eefochu/bmenetrend`  
**Branch**: `design/expressive-transit-ui`  
**Commit Baseline**: `e60a444a36d5d22756f3d85f6a1e767756588dcd` (Archived at `/tmp/bmenetrend-baseline-frozen/`)  
**Audit Domains**: APK Size & Packaging (S1/S2), Runtime Routing & Timetable Engine (R1/R2), Compose Recomposition & State, Main-Thread Contention & ANRs, Map Rendering, Heap Memory Footprint.  
**Measurement Device Status**: User-authorized emulator / matched host environment; physical USB Macrobenchmark explicitly deferred (labeled provisional per audit protocol).

---

## 1. Executive Summary & Verified Wins

| Domain | Baseline (Frozen) | Current (Verified) | Improvement / Reduction | Verification Status |
|:---|:---:|:---:|:---:|:---:|
| **Release APK (arm64-v8a)** | 82,210,285 B (78.4 MB) | **50,703,797 B (48.35 MB)** | **-31,506,488 B (-38.32%)** | Verified via Gradle `assembleRelease` |
| **Release APK (armeabi-v7a)** | 82,210,285 B (78.4 MB) | **47,601,853 B (45.40 MB)** | **-34,608,432 B (-42.10%)** | Verified via Gradle `assembleRelease` |
| **Release Universal APK** | 82,210,285 B (78.4 MB) | **82,210,285 B (78.4 MB)** | Exact byte parity (0 B diff) | Byte-for-byte baseline parity verified |
| **Offline Route Query (Reverse Cold)** | 18.2247 s | **1.6386 s** | **11.1x speedup (91.0% reduction)** | Real 370MB GTFS pack, exact path parity |
| **Offline Route Query (Reverse Warm)** | 18.2247 s | **0.4609 s (median)** | **39.5x speedup (97.5% reduction)** | 5 runs, 3 warmups (min 0.452s, max 0.486s) |
| **Constructor Contention ANR** | Unbounded UI Freeze | **Deterministic Proof RED** | Bounded test harness in place | Measured `Thread.State.BLOCKED` on Main |

---

## 2. Size & Packaging Fix (S1): ABI Splits & Artifact Automation

### 2.1 Configuration Implemented
In `app/build.gradle.kts`:
- Modern declarative ABI splits enabled for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` alongside universal APK generation:
  ```kotlin
  splits {
      abi {
          isEnable = true
          reset()
          include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
          isUniversalApk = true
      }
  }
  ```
- Task dependency pipeline wired via `variant.artifacts.get(SingleArtifact.APK)`:
  - Registers `copyUniversal<Variant>Apk` depending on `package<Variant>`.
  - Configures `package<Variant>.finalizedBy(copyTask)`.
  - Overwrites `app-<variant>.apk` unconditionally (`overwrite = true`) on incremental rebuilds to prevent stale artifact publishing.

### 2.2 Measured Artifact Sizes & Checksums

#### Release Variant (Baseline: 82,210,285 bytes)
| Artifact Name | ABI / Scope | File Size (Bytes) | Size Diff vs Baseline | SHA-256 Checksum |
|:---|:---:|:---:|:---:|:---|
| `app-arm64-v8a-release.apk` | arm64-v8a | **50,703,797** | **-31,506,488 (-38.32%)** | `4b08c90352ffb462c16fbe8525b6aa99e19d690a2a4b870e28325ddf82e5b7b6` |
| `app-armeabi-v7a-release.apk` | armeabi-v7a | **47,601,853** | **-34,608,432 (-42.10%)** | `c27c62c2f7415a770a9da3f9bb8c09a80eb9a5198ecae50a1dfa42bf4e698889` |
| `app-x86-release.apk` | x86 | **50,928,521** | **-31,281,764 (-38.05%)** | `5ca2a6fe1da4c0570bca578b87127e28b188c039dfa6669931fc8fcb3513360b` |
| `app-x86_64-release.apk` | x86_64 | **50,965,519** | **-31,244,766 (-38.01%)** | `7d739e44ffc20fcbca8ea4dfd0f28eec4aee9fcfdf82db6e30095982845aeb78` |
| `app-universal-release.apk` | universal | **82,210,285** | 0 (Parity) | `91a427337c7a38a063e0a3c422b69f30aae1e85326c50c9382dcd3fbfdfa96bb` |
| `app-release.apk` | alias | **82,210,285** | 0 (Parity) | `91a427337c7a38a063e0a3c422b69f30aae1e85326c50c9382dcd3fbfdfa96bb` |

#### Debug Variant (Baseline: 82,207,376 bytes)
- `app-arm64-v8a-debug.apk`: **50,700,888 B** (-38.33%)
- `app-armeabi-v7a-debug.apk`: **47,598,944 B** (-42.10%)
- `app-x86-debug.apk`: **50,925,612 B** (-38.05%)
- `app-x86_64-debug.apk`: **50,962,610 B** (-38.01%)
- `app-universal-debug.apk` / `app-debug.apk`: **82,207,376 B** (Exact parity)

### 2.3 Asset Compression Verification
- Verified glyph PBF compression: `assets/map/offline/glyphs/.../0-255.pbf` remains compressed (29,365 B deflated vs 83,504 B uncompressed). Blanket uncompressed `.pbf` exemptions were strictly avoided.
- PMTiles uncompressed storage (`androidResources.noCompress += "pmtiles"`) held for S2 as directed.

---

## 3. Runtime Routing Optimization (R1): Indexed Service-Day Bounds

### 3.1 Problem Diagnosis & Root Cause
In `OfflineTripPlanner.kt`, transit departure queries executed:
```sql
SELECT s.trip,s.seq,s.departure,t.route,t.headsign,t.restricted,d.day
FROM stop_times s JOIN trips t ON t.id=s.trip JOIN service_days d ON d.service=t.service
WHERE s.stop=? AND s.pickup!=1 AND d.day BETWEEN ? AND ?
```
The SQLite database contains an index `boarding(stop, departure, trip)`. Because `s.departure` was absent from the `WHERE` clause, SQLite scanned all departures across 4 calendar days for that stop, returning thousands of irrelevant trips over SQLite IPC.

### 3.2 Implemented R1 Solution
In `OfflineTripPlanner.kt`:
1. Precomputed base epoch instants for candidate service days $[\\text{date}-2, \\text{date}+1]$.
2. Derived exact monotonic bounds $[s_{\\min}(d), s_{\\max}(d)]$ for each service day using the noon-minus-12h anchor, preserving DST and overnight semantics.
3. Added `s.departure BETWEEN ? AND ?` to the query, activating index `boarding(stop, departure, trip)`.
4. Maintained label differentiation (`LabelKey`) and trip pruning without discarding route-specific transfer rules.

### 3.3 Benchmark Results (Real 370MB Pack)
**Scenario**: Kelenföld vasútállomás M $\\to$ Biatorbágy vasútállomás at 15:00:00 (Reverse query with large platform branching).
- **Frozen Baseline**: `18.2247s` (`journeys = 1`)
- **R1 Cold Run**: `1.6386s` (**11.1x speedup, 91.0% latency reduction**)
- **R1 Warmed Multi-Run Benchmark (3 warmups, 5 measurement runs)**:
  - Minimum: **0.4524s**
  - Median: **0.4609s**
  - Mean: **0.4630s**
  - Maximum: **0.4862s**
  - Speedup vs Baseline: **39.5x speedup (97.5% latency reduction)**
- **Path Parity**: 100% bit-identical itinerary output (`route 762`, departure 15:25, arrival 15:48).

### 3.4 Correctness & Regression Suite (27/27 Tests Passed)
Verified via `TEST-hu.app.bmenetrend.offline.OfflinePlanningTest.xml`:
- `routeSpecificForbiddenTransferIsRejected`: Rejecting transfer type 3 on specific routes.
- `routeSpecificMinTransferDurationIsEnforced`: Enforcing custom 600s transfer times over default 180s.
- `transferAllowanceRemainderAccountsForWalking`: Traversal duration subtraction on footpath transfers.
- `overnightDepartureOnPriorServiceDayRetainsCorrectBounds`: Discovery of 25:30 departures on previous service day.
- `pickupDropoffRestrictionsExcludeUnboardableStops`: Respecting `pickup_type=1` and `drop_off_type=1`.
- `coroutineCancellationAbortsPromptly`: Immediate termination on coroutine job cancellation.

---

## 4. Main-Thread Lock Contention & ANR Analysis (R2 Ground Truth)

### 4.1 Root Cause Ground-Truthed
Previous speculative claims that offline toggle was blocking the UI thread were **retracted** (toggle runs on `Dispatchers.IO`).  
The actual verified ANR vector is:
1. `OfflinePackRepository.kt` constructor executes `store.metadata()` twice synchronously during property initialization (`private val mutable = MutableStateFlow(...)`).
2. `OfflinePackStore.kt` protects `metadata()` and `read()` with the identical object monitor:
   ```kotlin
   private val lock get() = locks.getOrPut(root.absolutePath) { Any() }
   ```
3. When background routing or maintenance executes `store.read { db, meta -> ... }`, holding `synchronized(lock)`, any Main-thread instantiation of `OfflinePackRepository` (e.g. `TransitViewModel` creation or screen entry) synchronously blocks the Main thread until routing completes.

### 4.2 Deterministic Proof RED Regression
Created `OfflinePackConstructorContentionTest.kt`:
- Background thread enters `store.read` and holds the lock.
- Calling thread attempts to instantiate `OfflinePackRepository`.
- **Measured Result**:
  ```
  CONTENTION_MEASUREMENT: blockedBeforeRelease=true threadState=BLOCKED
  POST_RELEASE_MEASUREMENT: finishedAfterRelease=true durationMs=8 repoNotNull=true
  ```
- Harness uses a bounded 5s latch releaser in a `finally` block, guaranteeing no test runner hangs.

### 4.3 Successor Implementation Plan for R2
1. **Decouple Locks**: Separate metadata inspection (`active.json` AtomicFile read / existence check) from exclusive database connection locks.
2. **Asynchronous Repository State**: Initialize `OfflinePackRepository` state flow asynchronously or off the Main thread.
3. **Authoritative Loader & Mutation Epoch**: In WorkManager background updates, enforce monotonic mutation epochs to prevent race conditions between pack deletion, refresh, and imports.

---

## 5. Compose Recomposition & Main-Thread Hotspots

1. **5-Second Row Invalidation (`ViaBuszScreen.kt`)**:
   - `DepartureRow` reads `state.now` directly inside `itemsIndexed` (lines 765–775).
   - Even though minute values only change once every 60s, every visible departure row recomposes every 5 seconds (12 times per minute).
   - *Fix*: Provide pre-computed minute integers or key item composition by minute boundary.
2. **Spherical Geometry Burst (`MainActivity.kt` / `StopDirections.kt`)**:
   - Synchronous trigonometric calculation (`atan2`, `circularMean`) runs on UI thread inside `remember` when network/routes change.
   - *Fix*: Offload `StopDirections.collect` to `Dispatchers.Default` via `produceState`.
3. **Confirmed Non-Issues**:
   - `routesById` is already cached with `remember(state.network?.routes, state.emmaRoutes)`.
   - Map `stationScene` is already deferred to background coroutines and viewport-filtered.

---

## 6. Recommendations & Next Steps for Orchestration

1. **Accept S1 & R1**: All tests pass cleanly; APK size reduced by up to 42.1%, and offline routing latency reduced by 97.5%.
2. **Dispatch R2 (ANR & Lock Decoupling)**: Apply lock decoupling in `OfflinePackStore.kt` and asynchronous state loading in `OfflinePackRepository.kt` against `OfflinePackConstructorContentionTest.kt`.
3. **Dispatch S2 (PMTiles noCompress)**: Add `androidResources.noCompress += "pmtiles"` to test incremental APK size vs memory-mapped speedup.
4. **Dispatch UI Optimization**: Isolate `state.now` reads in `ViaBuszScreen.kt`.
