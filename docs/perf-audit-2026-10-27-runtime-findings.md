# Performance Audit: Runtime, Compose & Data Findings (2026-10-27)

**Target Repository**: `/Users/imbence/Stuff/Code/Projects/bmenetrend`  
**Branch**: `design/expressive-transit-ui`  
**Audit Scope**: Startup, State Churn, Compose Recomposition, Main-Thread Computations, Map Rendering, Offline Timetable Engine (SQLite/Frontier), and Memory Allocations.  
**Operating Constraints**:
- **R1 IMPLEMENTED**: Indexed per-service-day departure bounds in `OfflineTripPlanner.kt`.
- **R2 (Query Caching)**: Deferred until R1 measurement and parent checkpoint acceptance.
- **NO** changes to other Compose files yet.
- **NO** Gradle invocations, and **NO** test suite edits by this agent (the independent measurement agent coordinated via parent thread `a41dcbad-1972-46b6-b653-3499ce92813d` owns regressions and benchmarks).
- **Frozen Baselines**:
  - Host reverse routing baseline: `18.224687125` seconds (journeys: 1).
  - Host forward routing baseline: `~2.5` seconds.
- Preserves all recent uncommitted work:
  - Grouped offline platform endpoints (`e60a444`)
  - Offline vector basemap (`OfflineBasemap.kt`, PMTiles)
  - Delayed Expected + Scheduled departure times
  - Optional offline Hungary packages and license notices.

---

## Executive Summary

The audit surveyed seven architectural domains to diagnose runtime, data, and Compose performance.
The primary bottleneck is an algorithmic and query-level issue in `OfflineTripPlanner.kt` causing 12–18s query latencies on large transit hubs (e.g., Kelenföld) due to unconstrained SQLite boarding queries across 4 service days and un-cached intermediate point queries.

Crucially, static suspicions regarding UI state allocations and map clustering were **ground-truthed against actual code**:
- `routesById` is **already cached** in `ViaBuszScreen.kt` using `remember(state.network?.routes, state.emmaRoutes) { state.routesById }`, so it does *not* reallocate HashMaps every 5 seconds during departure browsing.
- `stationScene` in `NativeTransitMap.kt` is **already deferred** to a background coroutine (`produceState`), and marker updates **already filter by viewport bounds** on camera moves.
- Memory footprint claims (e.g., 30–50 MB) are marked **speculative** pending formal release profiler measurements.

---

## Top 5 Ranked Findings (Frequency × Cost)

| Rank | Issue | Frequency | Cost per Occurrence | Combined Severity | Actionable Files | Status |
|:---:|:---|:---|:---|:---:|:---|:---:|
| **1** | **Offline Trip Planner SQLite Departure Scan & Repeated Invariant Lookups** | On every offline route query from multimodal hubs | 10–18 seconds CPU & SQLite lock | **CRITICAL** | `app/src/main/java/hu/app/bmenetrend/offline/OfflineTripPlanner.kt` | R1 Implemented (Indexed Bounds); R2 Planned (Caching) |
| **2** | **Main-Thread Lock Contention in `OfflinePackStore.metadata()` during Routing** | On offline mode toggle or network check while routing runs | Up to 18 seconds Main-Thread UI freeze (**Input ANR**) | **CRITICAL / ANR** | `app/src/main/java/hu/app/bmenetrend/offline/OfflinePackStore.kt`<br>`app/src/main/java/hu/app/bmenetrend/TransitViewModel.kt`<br>`app/src/main/java/hu/app/bmenetrend/offline/NetworkAccessPolicy.kt` | Confirmed ANR Vector (R2 Candidate) |
| **3** | **5-Second Row Recomposition via Direct `UiState.now` Reads in Departure Items** | Every 5 seconds continuously while active | Recomposes all visible departure rows 12 times per minute instead of once | **HIGH** | `app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt`<br>`app/src/main/java/hu/app/bmenetrend/TransitViewModel.kt` | Confirmed Measured Root Cause |
| **4** | **Main-Thread Spherical Geometry in `StopDirections.collect` on Network Load** | On network/stop updates and line selection | Synchronous trigonometric calculation on UI thread inside `remember` | **MEDIUM** | `app/src/main/java/hu/app/bmenetrend/MainActivity.kt`<br>`app/src/main/java/hu/app/bmenetrend/map/StopDirections.kt` | Confirmed Root Cause (Burst, not 5s tick) |
| **5** | **National Offline Catalogue In-Memory Ingestion Footprint** | On offline mode toggle / pack activation | Ingests all stops/routes nationwide into heap | **LOW-MEDIUM** | `app/src/main/java/hu/app/bmenetrend/offline/OfflineTransitData.kt`<br>`app/src/main/java/hu/app/bmenetrend/TransitViewModel.kt` | Speculative Memory Impact (Needs Profiler) |

---

## Detailed Investigation & Ground-Truthed Technical Analysis

### Finding 1: Offline Trip Planner SQLite Scan & Invariant Query Churn

#### Confirmed Root Causes:
1. **Unbounded SQL Departure Range on `boarding` Index**:
   In `OfflineTripPlanner.kt`:
   ```kotlin
   db.rawQuery("""SELECT s.trip,s.seq,s.departure,t.route,t.headsign,t.restricted,d.day
       FROM stop_times s JOIN trips t ON t.id=s.trip JOIN service_days d ON d.service=t.service
       WHERE s.stop=? AND s.pickup!=1 AND d.day BETWEEN ? AND ?""",
       arrayOf(label.stop.toString(), date.minusDays(2).toEpochDay().toString(), date.plusDays(1).toEpochDay().toString()))
   ```
   The index in `GtfsImporter.kt` is `boarding(stop, departure, trip)`.
   Because the query contained **no `s.departure` filter**, SQLite was forced to scan every single departure row for that stop across the entire 4-day window. For large multimodal hubs (e.g. Kelenföld), this deserialized hundreds to thousands of past or far-future trips over SQLite IPC. Kotlin then discarded almost all of them with:
   `if (departure < label.time.plusSeconds(allowance) || departure > horizon) continue`
2. **Point-by-Point Sequential Stop Lookups**:
   During downstream trip expansion, intermediate stop positions and names are resolved one by one:
   `intermediate += point(to)` -> executes `SELECT ... FROM stops WHERE id=?` for every intermediate stop on every traversed trip if not in the local map.
3. **Repeated Invariant Queries**:
   - `transfers` table queries for `(from_stop, to_stop)` are repeated across labels.
   - Downstream `stop_times` for identical `(trip, seq)` are re-queried across rounds.

#### Transfer Correctness & R1 Departure Bound Mathematical Proof:
> [!IMPORTANT]
> **Preservation of Transfer Semantics and Proof of Non-Exclusion**  
> 1. **Lower Bound Proof**:
>    - At any point in the search, a passenger arrives at `label.stop` at instant `label.time`.
>    - For round 0 (origin): The passenger is ready at `ready` (`label.time = ready`), and boarding allowance is `TripPlanner.BOARD_SECONDS` (60s). Thus, the earliest possible departure is `minDeparture = label.time.plusSeconds(TripPlanner.BOARD_SECONDS)`.
>    - For round > 0 (transfer): The existing Kotlin check computes:
>      `allowance = maxOf(TripPlanner.TRANSFER_SECONDS, remaining)` where `remaining = rules.getLong(0) - Duration.between(label.arrival, label.time).seconds`.
>      Even if walking time exceeds GTFS `min_transfer_time` such that `remaining < 0`, `allowance >= TripPlanner.TRANSFER_SECONDS = 180 > 0`.
>      Furthermore, physical causality mandates that a passenger cannot board a vehicle departing before they arrive at the station: `departure >= label.time`.
>      By setting `minDeparture = if (round == 0) label.time.plusSeconds(TripPlanner.BOARD_SECONDS) else label.time`, any trip departing before `minDeparture` would have `departure < label.time.plusSeconds(allowance)` and is provably rejected by the existing Kotlin filter.
> 2. **Upper Bound Proof**:
>    - Any trip departing after `horizon` is immediately rejected by the existing Kotlin filter `if (departure > horizon) continue`. Thus `maxDeparture = horizon`.
> 3. **Per-Service-Day Monotonic Inverse**:
>    - GTFS `serviceInstant(serviceDay, s)` is defined as:
>      `serviceBase(serviceDay) + s.toLong()` where `serviceBase(serviceDay) = serviceDay.atTime(12, 0).atZone(TripPlanner.zone).toInstant().minusSeconds(43_200)`.
>    - This noon-minus-12h formula is strictly monotonic and accounts for DST transitions.
>    - For each candidate service day $d \in [\text{date}-2, \text{date}+1]$:
>      $s_{\min}(d) = \max(0L, \text{Duration.between}(serviceBase(d), minDeparture).\text{seconds})$
>      $s_{\max}(d) = \text{Duration.between}(serviceBase(d), horizon).\text{seconds}$
>    - If $s_{\min}(d) > s_{\max}(d)$, day $d$ has no feasible departures and is safely skipped without executing a query.
>    - When $s_{\min}(d) \le s_{\max}(d)$, querying `WHERE s.stop=? AND s.pickup!=1 AND d.day=? AND s.departure BETWEEN ? AND ?` queries strictly the feasible interval $[s_{\min}, s_{\max}]$, leveraging the `boarding(stop, departure, trip)` index.
> 4. **No Label Collapsing**:
>    - `LabelKey(label.stop, if (specificTransfers) label.trip else 0, if (specificTransfers) label.alighted else 0, label.legs.any { it.restrictedService })` is preserved verbatim.
>    - Downstream trip boarding, dropoff restrictions (`times.getInt(2) != 1`), intermediate stops, and overtaking evaluation remain 100% intact.

#### R1 Implementation Status:
- Implemented per-service-day indexed departure bounds in `OfflineTripPlanner.kt`.
- Precomputes the 4 candidate service day base instants once per plan.
- Evaluates candidate days with `s.departure BETWEEN ? AND ?`.
- No caching added yet (R2 candidate).

---

### Finding 2: ANR / Threading / Cancellation Audit (`android-performance-anr-and-jank-prevention`)

#### Confirmed Root Causes & Audit Findings:
1. **Lock Contention in `OfflinePackStore.metadata()` (Direct ANR Vector)**:
   - `OfflinePackStore.kt` protects both `metadata()` and `read()` with `synchronized(lock)`:
     ```kotlin
     fun metadata(): OfflinePackMetadata? = synchronized(lock) { ... }
     fun <T> read(block: (SQLiteDatabase, OfflinePackMetadata) -> T): T = synchronized(lock) { ... }
     ```
   - When `OfflineTripPlanner.plan` executes, it runs inside `store.read { db, meta -> ... }`, holding `synchronized(lock)` continuously for the entire duration of the route computation (12–18 seconds in baseline!).
   - In `TransitViewModel.kt` (line 625):
     ```kotlin
     if (!enabled || !offlinePacks.state.value.busy && offlinePacks.store.metadata() != null)
         NetworkAccessPolicy.setOffline(getApplication(), enabled)
     ```
     And in `NetworkAccessPolicy.kt` (line 24):
     ```kotlin
     val installed = OfflinePackStore(context).metadata() != null
     ```
   - **ANR Trigger**: If the user interacts with the UI (e.g. toggles offline mode, triggers network sync, or opens pack settings) while an offline route search is in progress, the Main Thread invokes `store.metadata()`, which blocks on `synchronized(lock)` waiting for `store.read` to complete. This blocks the UI thread for > 5 seconds, directly triggering an **Input ANR**.
   - **Recommended R2 Fix**: Decouple `metadata()` reading from the database query lock (e.g., using a separate pointer lock or atomic cached reference), and ensure UI callers never invoke `metadata()` synchronously on the Main Thread.

2. **Threading Guardrail in `OfflineDatabaseThread.kt`**:
   - `OfflineDatabaseThread.kt` uses a dedicated `SingleThreadExecutor` to ensure strict physical thread-affinity for Android SQLite transactions across coroutine suspension points:
     ```kotlin
     internal suspend fun <T> onOfflineDatabaseThread(block: suspend () -> T): T =
         Executors.newSingleThreadExecutor { task -> Thread(task, "OfflinePackDatabase").apply { isDaemon = true } }
             .asCoroutineDispatcher().use { databaseThread -> withContext(databaseThread) { block() } }
     ```
   - **Guardrail**: Do NOT replace this with `Dispatchers.IO.limitedParallelism(1)`. Although `limitedParallelism(1)` serializes task execution, it does *not* guarantee the same OS thread across suspension resumptions, which caused SQLite transaction strand / deadlock during GTFS import.

3. **Cancellation Responsiveness**:
   - `OfflineTripPlanner.kt` includes `context.ensureActive()` at every iteration level:
     - Outer `walks()` priority queue processing
     - Frontier label loop
     - Per-service-day boarding loop
     - SQLite `boarding.moveToNext()` row consumption
     - `resolve()` endpoint resolution
   - Coroutine cancellation is fast and releases the SQLite handle without lingering or blocking threads.

---

### Finding 3: 5-Second Row Recomposition via Direct `UiState.now` Reads

#### Code Ground-Truthing:
- **Verified**: `ViaBuszScreen.kt` lines 187 & 600 **already cache** `routesById`:
  ```kotlin
  val routeById = remember(state.network?.routes, state.emmaRoutes) { state.routesById }
  ```
  Therefore, the lazy `routesById` property is **not** rebuilding HashMaps on every 5s tick.
- **Actual Hotspot**:
  Inside `ViaBuszScreen.kt` (lines 765–775):
  ```kotlin
  itemsIndexed(state.departures, ...) { index, departure ->
      val minutes = ceil(Duration.between(state.now, departure.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
      DepartureRow(..., minutes = minutes, ...)
  }
  ```
  Because `state.now` is read directly inside the `itemsIndexed` item lambda, and `state.now` updates every 5 seconds, **every visible departure row recomposes every 5 seconds**, even though `minutes` remains constant for 12 consecutive ticks!

#### Scoped Fix:
- Derive or key minute-level countdowns so `DepartureRow` only invalidates when the calculated minute integer actually changes, or pass a stable minute provider.

---

### Finding 4: Main-Thread Spherical Geometry in `StopDirections.collect`

#### Code Ground-Truthing:
- In `MainActivity.kt` (lines 200–208):
  ```kotlin
  val stopBearings = remember(state.network, state.emmaStops, state.lineDetails, state.vehicleJourney) {
      val routes = state.routes; val allStops = state.stops
      hu.app.bmenetrend.map.traceMapWork("BMenetrend.StopDirections") {
          hu.app.bmenetrend.map.StopDirections.collect(routes, allStops, state.lineDetails.values, state.vehicleJourney)
      }
  }
  ```
- **Finding**: This calculation does **not** run every 5 seconds (it depends on network/stops/details, which refresh every 15–30s or on user selection). However, when it does run, it executes synchronous trigonometric calculations (`atan2`, `distanceMeters`, `circularMean`) on the Android UI thread inside `remember`.
- **Scoped Fix**: Offload `StopDirections.collect` to `Dispatchers.Default` using `produceState` or compute in the ViewModel.

---

### Finding 5: Offline Catalogue Memory Footprint & Map Clustering

#### Code Ground-Truthing:
- **Memory Footprint**: Marked **SPECULATIVE**. Loading all stops/routes into heap on offline toggle exists in `OfflineTransitData.catalogue()`, but the exact heap footprint (previously estimated at 30–50 MB) must be measured via Android Studio Memory Profiler / heap dump before modifying data structures.
- **Map Clustering**: In `NativeTransitMap.kt`:
  - `stationScene` is already executed off the UI thread via `produceState<StationScene?>(null, sceneStops, stopBearings)`.\
  - Pan and zoom camera movements **do not** re-cluster: marker rendering in `LaunchedEffect` already filters pre-clustered `scene.groups` by `viewport`.
  - Severity downgraded to **LOW**.

---

## Scoped File Ownership Matrix

| File Path | Owner / Responsibility | Allowed Actions |
|:---|:---|:---|
| `hu/app/bmenetrend/offline/OfflineTripPlanner.kt` | Runtime Optimization Subagent | R1 Implemented (indexed departure bounds); R2 Candidate (query caching) |
| `docs/perf-audit-2026-10-27-runtime-findings.md` | Runtime Optimization Subagent | Maintain runtime findings, mathematical proofs, and audit logs |
| `hu/app/bmenetrend/ui/ViaBuszScreen.kt` | Runtime Optimization Subagent | Future scope: isolate `now` minute-tick recompositions |
| `hu/app/bmenetrend/MainActivity.kt` | Runtime Optimization Subagent | Future scope: offload `stopBearings` to `Dispatchers.Default` |
| `hu/app/bmenetrend/offline/OfflinePackStore.kt` | Runtime Optimization Subagent | Future scope: R2 candidate to decouple `metadata()` lock from `read()` |
| Any Test Files (`*Test.kt`, `Macrobenchmark*`) | **Measurement Agent ONLY** | **NO EDITS** by this subagent. All test runs dispatched via Parent. |
| Build Configs / Assets (`build.gradle.kts`, PMTiles, DB) | **Parent / Shared ONLY** | **NO EDITS** by this subagent. |

---

## Detailed Test Requests for Independent Measurement Agent (R1 Evaluation)

We request the independent measurement agent to validate the R1 implementation against `OfflinePlanningTest` and real packs (`OFFLINE_INSTALLED_PACK=1`):

1. **Benchmark Execution**:
   - Compare reverse query latency against baseline: `18.224687125` seconds (target: significant latency reduction).
   - Compare forward query latency against baseline: `~2.5` seconds.
   - Verify journey count: Exactly matches baseline (e.g. journeys: 1 for reverse test).
2. **Route-Specific Forbidden Transfers**:
   - Verify cases where `transfers.txt` specifies `from_route` $\to$ `to_route` with `forbidden == 1`. Ensure such transfers are strictly rejected while general transfers at the same stop remain viable.
3. **Route-Specific Minimum Transfer Times**:
   - Verify cases where `transfers.txt` defines a custom `min_transfer_time` for specific route pairs (e.g. 300s vs standard 180s). Ensure connections with insufficient slack are rejected.
4. **Trip-Specific Transfer Rules**:
   - Verify cases where `from_trip != 0` or `to_trip != 0` enforce guaranteed or timed connections.
5. **Walked Labels & Remainder Allowances**:
   - Verify multi-leg journeys involving footpaths (`paths` table) where walking elapsed time is correctly subtracted from the total transfer allowance (`rules.seconds - Duration.between(alighted, now)`).
6. **Overnight & DST Transitions**:
   - Trips with departures past midnight ($24:00$–$28:00$) across service day boundaries.
   - Verification across Daylight Saving Time shifts where `serviceInstant` must resolve correct epoch timestamps.
7. **Pickup & Dropoff Restrictions**:
   - Confirm that trips with `pickup_type == 1` are never boarded at that stop.
   - Confirm that trips with `drop_off_type == 1` are never alighted at that stop.
   - Confirm handling of `restricted == 1` services.
8. **Overtaking Trips**:
   - A faster trip departing later on the same or parallel corridor overtaking an earlier slower trip. Confirm the faster arrival is preserved without discarding the earlier arrival if walking or transfer differences exist.
9. **Platform Group Endpoint Resolution**:
   - Verify that grouped platform origins and destinations (e.g., Kelenföld multi-platform hub from commit `e60a444`) expand all valid platforms and return complete, non-empty itineraries in both directions.

---

## Measured Benchmark Results & Verified Regressions (2026-10-27)

### 1. R1 Routing Engine Latency Measurements (Real 370MB Pack)

**Hardware / Environment**: Matched macOS host JVM (Robolectric / Native SQLite), real 370MB offline pack `/tmp/bmenetrend-offline-pack.db` (68,395 stops, 368,235 trips).  
**Test Case**: Kelenföld vasútállomás M (multi-platform hub) -> Biatorbágy vasútállomás at 15:00:00 (Reverse route query with high branching factor).

| Metric | Pre-R1 Frozen Baseline | Post-R1 Cold First Run | Post-R1 Matched Multi-Run (3 Warmup, 5 Measured) | Speedup / Reduction |
|:---|:---:|:---:|:---:|:---:|
| **Latency (s)** | **18.2247s** | **1.6386s** | **Min: 0.4524s<br>Median: 0.4609s<br>Mean: 0.4630s<br>Max: 0.4862s** | **39.5x speedup<br>(97.5% reduction)** |
| **Itinerary Count** | 1 | 1 | 1 | Exact match |
| **Route / Vehicle** | Route 762 | Route 762 | Route 762 | Exact match |
| **Departure / Arrival** | 15:25:00 / 15:48:00 | 15:25:00 / 15:48:00 | 15:25:00 / 15:48:00 | 100% path parity |

Forward query (Biatorbágy -> Kelenföld at 08:58:00) also confirmed finding trip 10158 (Route 762) departing at 08:59:00, and querying at 08:59:00 with 60s boarding allowance correctly advancing to the next feasible departure >= 09:00:00.

### 2. Comprehensive R1 Correctness & Regression Suite

All 27 unit tests in `hu.app.bmenetrend.offline.OfflinePlanningTest` executed and passed cleanly:
- **`routeSpecificForbiddenTransferIsRejected`**: Verified that when `transfers.txt` specifies `from_route` -> `to_route` with `transfer_type = 3` (forbidden), the engine rejects the prohibited connection while keeping non-prohibited connections at the same hub viable.
- **`routeSpecificMinTransferDurationIsEnforced`**: Verified that route-specific `min_transfer_time` rules (e.g. 600s vs standard 180s) strictly reject connections with insufficient transfer windows.
- **`transferAllowanceRemainderAccountsForWalking`**: Verified multi-leg itineraries with intermediate footpaths (`pathways.txt`) correctly subtract walking traversal time from the transfer allowance.
- **`overnightDepartureOnPriorServiceDayRetainsCorrectBounds`**: Verified that trips departing after midnight (`25:30:00`) on day $N$ are discovered and properly bounded when queried early morning on day $N+1$.
- **`pickupDropoffRestrictionsExcludeUnboardableStops`**: Verified that `pickup_type = 1` and `drop_off_type = 1` correctly prevent invalid passenger boarding and alighting.
- **`coroutineCancellationAbortsPromptly`**: Verified that cancelling the planning coroutine immediately terminates search execution and releases database resources without thread starvation.
- **Grouped platform endpoints**: Both forward and reverse queries on multi-platform transit hubs (e.g. Kelenföld) resolve all candidate child platforms without fabricating cross-platform connections.

### 3. R2 Deterministic Constructor Contention Measurement (Proof RED)

**Harness**: `OfflinePackConstructorContentionTest.kt`  
**Mechanism**: Background thread holds SQLite `store.read { ... }` monitor `lock`. A separate thread simulates Main-thread instantiation of `OfflinePackRepository(context, scope)`. A bounded timeout releaser (5s) in a `finally` block guarantees the test driver never hangs.

```
CONTENTION_MEASUREMENT: blockedBeforeRelease=true threadState=BLOCKED
POST_RELEASE_MEASUREMENT: finishedAfterRelease=true durationMs=8 repoNotNull=true
```

**Findings & Ground Truth**:
1. **Direct ANR Hazard Confirmed**: During active offline route queries or background database reads, any Main-thread attempt to instantiate `OfflinePackRepository` (e.g. during `TransitViewModel` initialization) enters `Thread.State.BLOCKED` on `OfflinePackStore.lock`. On production devices, this produces a multi-second UI hang / Input ANR.
2. **Immediate Unblock**: As soon as the background lock is released, the constructor unblocks and completes in **8ms**.
3. **Refined R2 Scope**:
   - Decouple metadata access (`active.json` AtomicFile / existence checks) from the exclusive SQLite database reader lock.
   - Run repository state population asynchronously off the Main thread.
   - Enforce a single authoritative WorkManager loader with mutation epochs to prevent race conditions during pack deletion, refresh, and download.

### 4. R2 Implementation: Non-Blocking Repository Constructor & Cached State Mode Checking

**Operating Constraints & Ownership Adherence**:
- Touched ONLY the authorized files:
  1. `hu/app/bmenetrend/offline/OfflinePack.kt`
  2. `hu/app/bmenetrend/offline/OfflinePackRepository.kt`
  3. `hu/app/bmenetrend/TransitViewModel.kt`
  4. `docs/perf-audit-2026-10-27-runtime-findings.md`
- Preserved `NetworkAccessPolicy.kt` initialization synchronous contract intact.
- Preserved `OfflinePackStore.kt` storage lock `synchronized(lock)` intact.
- Preserved `OfflineDatabaseThread.kt` dedicated `SingleThreadExecutor` intact.
- No Gradle invocations and no test authoring by this subagent.

#### Detailed Architecture & Race Guards:
1. **`OfflinePack.kt`**:
   - Added `INITIALIZING` to `OfflinePackPhase` enum: `enum class OfflinePackPhase { INITIALIZING, MISSING, DOWNLOADING, IMPORTING, READY, FAILED }`.
   - In `OfflinePackState`: set default `phase = OfflinePackPhase.INITIALIZING`, added `val loaded: Boolean get() = phase != OfflinePackPhase.INITIALIZING`, and retained `val busy get() = phase == OfflinePackPhase.DOWNLOADING || phase == OfflinePackPhase.IMPORTING`.
   - `busy` is strictly true for active downloads/imports, preventing accidental cancellation UI while loading initial metadata.

2. **`OfflinePackRepository.kt`**:
   - Initialized `mutable = MutableStateFlow(OfflinePackState(phase = OfflinePackPhase.INITIALIZING))` without invoking `store.metadata()` on the constructor thread.
   - Authoritative WorkManager flow collection asynchronously reads `store.metadata()` via `withContext(Dispatchers.IO)`.
   - **Race Condition Protections**:
     - Synchronized state tracking via `stateLock`, `mutationEpoch: Long`, and `deleting: Boolean`.
     - Stale or in-flight emissions during deletion (`deleting == true`) or across mutations (`emissionEpoch != mutationEpoch`) are dropped immediately before publishing to `mutable`.
     - In `delete()`: sets `deleting = true` and bumps `mutationEpoch`, cancels work, resets offline mode, clears downloads and working copies, removes `work_id` preference, and in `finally` sets `deleting = false`, bumps `mutationEpoch`, and emits `OfflinePackState(phase = OfflinePackPhase.MISSING)`.
     - Stale completions post-delete cannot reassert `READY` because `active.json` is deleted (`meta == null`) and `work_id` preference is removed (`selectedId == null`).
     - Active `IMPORTING` and `DOWNLOADING` phases are protected against being overwritten by finished work entries remaining in WorkManager history.
     - `state.value.loaded` guard in `download()`, `delete()`, and `importFeed()`.

3. **`TransitViewModel.kt`**:
   - In `setOfflineMode(enabled)`: eliminated synchronous `offlinePacks.store.metadata()` lock acquisition. If `!enabled`, immediately sets offline mode to `false`. If `enabled`, validates `pack.loaded && !pack.busy && pack.metadata != null` purely against the in-memory StateFlow.
   - In `init`: flow collection checks `pack.loaded && pack.metadata?.id != null && pack.metadata.id != offlineLoadedId` before invoking `loadOfflineCatalogue()`.

4. **Honest Constructor File I/O Accounting**:
   - `OfflinePackStore(context)` constructor still calls `root.mkdirs()` and `downloads.mkdirs()`.
   - `context.getSharedPreferences("offline", 0)` still touches the shared preferences subsystem.
   - `WorkManager.getInstance(context)` still references the WorkManager database/singleton.
   - **R2 Improvement Specificity**: R2 specifically eliminates the **`store.lock` contention wait on concurrent `store.read` queries** (which previously blocked the UI thread up to 18 seconds) and the synchronous **`active.json` file read + DB stat syscalls** from the constructor. It is a precise lock-contention and metadata decoupling improvement, not an unfounded blanket "zero disk IO on Main" promise.

### U2 fix: Asynchronous Spherical Geometry Offload (STATIC-VERIFIED)

- **MainActivity.kt**: Replaced synchronous `remember(state.network, state.emmaStops, state.lineDetails, state.vehicleJourney)` computation of `stopBearings` with Compose `produceState` running asynchronously off the main thread.
- **StopDirections.kt**: Added `suspend fun collectAsync(...)` defaulting to `Dispatchers.Default` with `traceMapWork("BMenetrend.StopDirections")`, while preserving synchronous `fun collect(...)` intact for host JVM unit tests (`StopDirectionsTest.kt`).
- **UI Thread Protection**: Offloaded heavy spherical trigonometry bursts (`atan2`, `circularMean`, `bearing`, and k-means corridor clustering in `generalDirections`) off the main thread, keeping the composable API and state contract identical for `NativeTransitMap`.
- **Verification round + parent fix**: Combined verification (build + 369-test suite GREEN, static logic checks) confirmed the offload works but flagged staleness: `state.routes` derives from `network?.routes` AND `emmaRoutes`, so the `produceState` key set was missing `state.emmaRoutes` (route catalogue updates would not recompute bearings). Fixed in `MainActivity.kt` by adding `state.emmaRoutes` to the `produceState` keys; `:app:compileDebugKotlin` passes.

### Import speedup fix (VERIFIED)

- **Diagnosis**: the post-download install phase builds the ~388 MB SQLite pack row-by-row, previously under `PRAGMA journal_mode=DELETE` + `synchronous=FULL` (rollback-journal double-writes + hard fsyncs on a disposable staging file), and the finished file was SHA-256 hashed twice (worker metadata pass + recompute inside `activate()`).
- **Fix (parent-applied)**: `GtfsImporter.create()` now uses `journal_mode=MEMORY` + `synchronous=OFF` for staging (disposable file: crash → `staging()` purges it, activation only promotes a digest-matched, `quick_check`-passed DB, `active.json` flips last via AtomicFile). `OfflinePackStore.activate()` gained optional `verifiedDigest` param; worker computes one SHA-256 pass and passes it through. Other callers keep pure-recompute behavior (tamper test still enforces digest mismatch rejection).
- **Verification**: dedicated round 44/46 passed (2 env-gated assumption skips), contention 3/3, call-site audit clean, staging cleanup compatible with memory journaling. On-device magnitude is user-observable, not benchmarked locally.

### Planner search duplicate fix verification (2026-10-29, VERIFIED ON EMULATOR)

- **User report**: planner destination search for "kelenf" showed two Kelenföld entries ("Budapest-Kelenföld" + "Budapest, Kelenföld vasútállomás") and raw internal route-ID chips ("V2912_106", "hkir_V-R-200-686").
- **Fix (previous agent, uncommitted in this stack)**: `PlannerViewModel.consolidateEndpoints` (two-pass station merge: `cleanName` grouping + hub-distance) and `StopClusters.isCleanRouteNumber` route-chip filtering.
- **Investigation**: pulled the device's real EMMA stop index (71,535 stops, incl. MÁV European rail records like "Budapest-Kelenföld") and replicated the exact pipeline in a throwaway JVM test — `consolidateForSearch` → search → consolidateEndpoints yields 3 clean suggestions for the 47 real Kelenföld records: no duplicates survive.
- **Device verification**: `:app:assembleDevelopment` `app-arm64-v8a-development.apk` installed to emulator-5560; planner search "kelenf" → ONE "Kelenföld vasútállomás" entry with clean line badges; "vasut" → nearest stations distance-ranked. Raw-ID chips gone.
- **Build-type trap (root cause of a false-negative during testing)**: this project's `debug` buildType is the release-optimized *distributed debug-channel* package (`hu.app.bmenetrend`, R8, isDebuggable=false — see `app/build.gradle.kts` buildTypes comment); the locally installed `.dev` package comes from the `development` buildType. Installing `assembleDebug` APKs creates/updates the unintended second package `hu.app.bmenetrend` instead of the dev app. For emulator verification use `:app:assembleDevelopment` + `app-build/outputs/apk/development/app-<abi>-development.apk` and package `hu.app.bmenetrend.dev`. A stray `hu.app.bmenetrend` install on the emulator was removed via `pm uninstall`.

### U1 fix: Gated Departure Row Recomposition (STATIC-VERIFIED)

- **ViaBuszScreen.kt**: Hoisted `Duration.between(state.now, departure.expectedAt)` reads out of the per-item `LazyColumn` body into `departuresWithMinutes = remember(state.departures, nowMinuteEpoch)`, keyed on `nowMinuteEpoch = state.now.epochSecond / 60`.
- **Row Stability & Lambda Memoization**: Introduced immutable `DepartureWithMinutes(departure, minutes)` and remembered the per-departure `onClick` lambda (`remember(departure) { { openDeparture(departure) } }`) inside the item content scope.
- **Elimination of 5-Second Recomposition Storm**: Because all parameters (`departure`, `minutes`, `providerId`, `route`, `boardingInfo`, `onClick`) remain identical across 5-second `UiState.now` ticks, visible `DepartureRow` instances and their internal `DepartureBoardRow` layouts completely skip recomposition until the minute boundary flips or new departure data arrives.
