# Optional nationwide offline mode — implementation plan

Status: original design. Implementation details, measured sizes and explicit remaining limitations are recorded in [OFFLINE_MODE.md](OFFLINE_MODE.md). The current implementation downloads official GTFS directly and compiles it on-device; it does not ship a precompiled national street graph or an authorized mainline rail feed. Updated 2026-10-26.

## Product requirements

Users can optionally download a Hungary offline pack from Settings, then enable an app-wide Offline mode. Online mode continues to use EMMA and the existing hybrid planner. Downloading a pack does not switch modes. The pack is separate from the APK and never required for ordinary online use.

The complete compressed download must be **at most 500,000,000 bytes (500 MB)**. Aim for an installed active pack below 500 MB too, but measure this separately and show both sizes before download. Atomic updates temporarily need the active pack, downloaded archive and staged replacement; compute and display the actual required free space rather than promising a 500 MB total storage footprint. The earlier 50–150 MB figure was an unmeasured estimate, not a requirement.

Offline mode supports nationwide scheduled transit journeys, stop/line search, timetables, saved places and device location. It never presents scheduled results as live predictions. Full coverage is conditional on obtaining and validating the required operator feeds; a limited pack must list its omissions and must not be advertised as complete.

Initial scope is domestic scheduled public transport, including rail, regional buses, Budapest transit and available local networks. Live vehicles, delays, cancellations received after download, tickets, live disruption notices, complete address/POI search and turn-by-turn navigation are outside the initial offline feature. These limitations appear in the download description, not only in documentation.

## Settings and interaction

Add an “Offline használat” / “Offline use” section to Settings:

1. “Magyarország offline csomag” / “Hungary offline pack”: actual download size, installed size, included networks, exclusions, timetable validity, build date and last update check.
2. Download action with progress, downloaded bytes, cancel, retry and resumable transfer. Default to Wi-Fi; allow a user to explicitly start on mobile data. Persist progress through process death.
3. “Offline mód” / “Offline mode” switch, initially off. Enable only after the pack is verified and installed. If missing, explain that a download is needed and provide the download action; never silently download hundreds of MB when toggling.
4. Update and delete actions; optional automatic Wi-Fi updates are off initially. Download and update controls require online mode. Returning online preserves the installed pack.
5. A persistent, compact Offline badge throughout relevant screens, with a direct route back to Settings. Offline results say “Menetrend szerinti adatok” / “Scheduled data” and show the pack validity.

When enabling offline mode, persist the choice, cancel outstanding online work and immediately rebind screens to local data. Clear live vehicle markers and live badges rather than leaving frozen vehicles on the map. Preserve endpoints, favorites, selected date and navigation state where their IDs remain resolvable. Cancel/recompute a pending plan using the selected mode; discard late online responses.

When deleting the active pack, explicitly explain that offline mode will be disabled. Cancel readers or wait for their leases, switch mode, then remove files. A restart must never leave a persisted offline choice pointing at deleted data.

No automatic switch to offline just because EMMA fails in the first release. If a usable pack is installed, offer “Use offline timetable” on failure without silently changing the saved setting. A disconnected device can enable offline mode without first attempting an online request.

## Current code and integration points

- `PlannerViewModel.kt` constructs `HybridTripPlanner` and calls Photon through `PlacesRepository` for endpoint suggestions. Both need mode-aware dispatch.
- `HybridTripPlanner.kt` deliberately depends on EMMA for national routing and walking. Keep it as the online implementation.
- `LocalTimetableRouter.kt` is a small reviewed-local-network router. Its provider-specific calendars and object-heavy search are not the nationwide GTFS engine.
- `TripPlanner.kt` provides reusable endpoint, leg and itinerary models, ranking and timing rules. Add explicit result provenance and pack validity without abusing `partial` to mean offline.
- `TransitRepository.kt` can perform source verification over HTTP even while loading schedules. Offline reads must use installed verified snapshots without triggering these checks.
- `StationTimetableRepository.kt` stores 24-hour departure snapshots, insufficient for nationwide routing. Preserve it as a separate user feature; it is not the routing pack format.
- `TransitViewModel.kt`, `UiState.kt`, `SettingsPage.kt`, `MainActivity.kt` and `BMenetrendApplication.kt` are the policy, state and wiring points.
- `map/NativeTransitMap.kt` uses MapLibre; its requests need separate treatment from OkHttp repositories.
- Current debug distribution builds are already minified and non-debuggable; use these or release builds for device measurements.

Proposed new responsibilities:

- `OfflineModeRepository`: persisted user preference plus effective mode and availability as StateFlow; initialize before any background networking.
- `OfflinePackRepository`: manifest, installed metadata, validation, active pack handle and reference-counted reader lifetime.
- `OfflinePackDownloadWorker`: persistent, cancellable download/install/update work with Android-compliant user-visible progress. Add WorkManager and use its appropriate long-running/user-initiated download support for the supported Android versions.
- `JourneyPlanner`: common online/offline planning interface. Existing hybrid implementation stays behind its online adapter; `OfflineTripPlanner` uses the installed graph and maps results to existing UI models.
- `OfflineSearchRepository`, `OfflineTimetableRepository` and `OfflineWalkingRouter`: local search, departure and walking access.
- `NetworkAccessPolicy`: one app-scoped policy used by repositories, polling, downloads, update checks and map resources.

## Phase 1 — prove feed coverage and pack feasibility

Before writing a nationwide router, create a source inventory containing feed owner, retrieval process, authentication requirement, redistribution terms, attribution, operator coverage, service dates and refresh cadence.

Verified starting points:

- BKK publishes a downloadable Budapest GTFS feed: https://bkk.hu/bkk-partnerek/egyeb/gtfs-menetrendi-adatok-programozoknak
- MÁV offers railway GTFS access through its application process: https://www.mavcsoport.hu/gtfs-igenybejelento
- The GTFS schedule reference defines timetable, calendar, transfer and pathway semantics: https://gtfs.org/documentation/schedule/reference/
- OpenStreetMap data can supply pedestrian connectivity subject to its attribution and ODbL requirements: https://www.openstreetmap.org/copyright

Still to verify: regional bus bulk feed access and redistribution, GYSEV coverage in the acquired rail data, HÉV ownership/overlap, independent city networks, ferries and local operator rights. EMMA coverage does not prove that a redistributable bulk feed exists. Do not build the pack by crawling every EMMA departure board.

Build an experimental national pack from acquired sources and measure archive bytes, installed bytes, build time, validity coverage, stop/trip counts and expected runtime memory. Assess compact pedestrian graph size separately. Missing major feeds or a pack above the cap are feasibility failures to resolve, not details to hide behind a nationwide label.

Exit: reproducible sample pack, documented coverage/rights, exact size report, chosen service horizon and working pack host. Start with a rolling 30-day timetable horizon where feeds permit it, but show actual source validity and never manufacture future service. Shorter horizons and optional visual geometry are size tradeoffs; silently dropping major operators is not.

## Phase 2 — build and publish versioned packs

Run preprocessing on a build machine/server, not on users' phones. Add a deterministic pipeline under `tools/offline_pack/`, source configuration, fixtures and CI validation. A maintained scheduled pipeline refreshes source feeds and publishes a new pack only when sources or derived content change.

Import routes, agencies, stops/platforms/entrances, trips, ordered arrival/departure times, calendars and exceptions, pickup/drop-off restrictions, transfer rules, frequencies where present and available accessibility metadata. Apply the current GTFS service-day/timezone semantics, including times beyond 24:00 and DST; do not reuse the provider-specific local calendar shortcuts. Unsupported feed features must cause an explicit coverage restriction or build failure, not silently invent service.

Namespace IDs by feed. Keep an explicit crosswalk to EMMA IDs for existing favorites and screen links where reliable; never assume matching IDs or names imply matching platforms. Resolve duplicate services with reviewed feed precedence. Add reviewed local timetables only with their actual validity and verification metadata.

Create compact route-pattern and trip-time arrays, service-date bitsets, stop-to-pattern indexes, search/spatial indexes, directed transfer edges and route metadata. Use SQLite for metadata/search and a versioned binary format with mapped or paged primitive arrays for the routing graph. Share repeated stop sequences and strings, delta-encode times and compress distribution files. Do not materialize every stop-time as a Kotlin data object.

Build pedestrian connections from licensed street data and station pathways. Precompute feasible directed stop-to-stop transfers; preserve forbidden transfers, physical barriers, minimum durations and known access restrictions. Include a compact national pedestrian graph for endpoint access/egress; measure it against the size cap. It must not assume that nearby platforms can be reached across tracks or rivers. If this graph cannot fit, resolve a documented scope/format tradeoff before promising arbitrary-coordinate offline planning. Stop-to-stop-only routing is a possible reduced product, not equivalent to full endpoint support.

The pack manifest includes format version, minimum reader/app version, pack ID, source versions/checksums, per-source coverage and validity, timezone, build time, compressed/installed component sizes, file hashes, attribution and integrity signature. Keep map-derived and timetable components separately identified with their respective licenses. Production hosting needs HTTPS, immutable version URLs, range requests, cache headers and a small latest-manifest endpoint. A trusted key in the app verifies published manifests; checksums validate downloaded content.

Enforce the 500 MB archive ceiling in CI. Retain the last good published pack if an import fails, feeds unexpectedly disappear, calendars shrink or validation detects major regressions. Show increasing source age; never relabel an old pack as freshly sourced.

Exit: a hosted, verified artifact that a clean build can reproduce; feed failures cannot replace the good release with a broken one.

## Phase 3 — download, install and lifecycle

Store installed packs in app-private persistent storage, excluded from automatic backup. Keep partial downloads separate. Check manifest compatibility and available disk space before starting. Support HTTP resume with ETag/version checks so different pack versions cannot be spliced together.

Use states Missing, Downloading, Verifying, Installing, Ready, Updating, Failed and Incompatible. Keep freshness and per-feed date availability separate from install state; a Ready pack can become stale. A failed update leaves the previous active pack usable.

Verify signature/hashes, bounded archive contents and database/graph invariants before activation. Prevent archive path traversal and unexpectedly oversized extraction. Install into a staging directory, open and smoke-test it, then atomically replace the active-pack pointer. Persist installation progress for crash recovery. Pin the old version while active queries still read it; delete it after readers finish and the new pack passes activation. Clean abandoned partial/staged data safely on startup.

Refresh checks run only online and obey user update/Wi-Fi preferences. While offline, no update checks or downloads run. If the installed timetable has no service data for the requested date, show “Date outside downloaded timetable” rather than “No route”; if only some feeds cover it, show explicit partial coverage. Past the last supported date, do not invent recurring schedules. Staleness within validity gets a visible warning, not an arbitrary expiry unrelated to source dates.

Exit: restart-safe download/update/delete flow tested with interruption, low storage and corrupted files.

## Phase 4 — nationwide on-device planning

Implement a Kotlin RAPTOR-family router over precomputed route patterns with Pareto labels for arrival time and transfers, plus bounded walking preferences. Handle overtaking trips by correct pattern/trip ordering or separate patterns; never assume later departures always arrive later. Use the street graph for start/end access and directed transfer edges between rounds. Walking-only results should also use routable graph paths.

Run graph reads and computation off the main thread with cooperative cancellation and bounded memory. Pin one pack version for each query. Reuse the existing initial boarding, transfer and preparation policies where appropriate, applying explicit GTFS restrictions/minimums without double-counting them. Honor pickup/drop-off restrictions and distinguish ordinary from registration-only service. Initially retain the existing UI's maximum five boardings and up to three useful alternatives; document that search limits constrain results.

Support leave-now and chosen departure dates, overnight journeys across service dates, source timezone rules, multiple transport modes and valid through services. Generate alternative departures within a bounded search window, reconstruct complete legs and run the common feasibility/ranking checks. Represent scheduled results as scheduled, with pack provenance and coverage information.

Compare correctness against a simple exhaustive reference solver on small fixtures. EMMA is useful for manual comparisons, but its live data and source versions differ and do not make it an exact offline oracle.

Exit: real national rail/bus/local mixed journeys computed on a phone without network access, including transfers and correct service dates.

## Phase 5 — app-wide offline behavior

Gate operations at both the repository/use-case layer and the transport layer. The policy cancels active HTTP calls and stops pollers when offline is enabled. Every network client must be registered; separate clients in transit repositories, source validation, Photon and OTA must not bypass the policy. Stop MapLibre remote style/tile/glyph/sprite/terrain loads through its own resource configuration, not merely an OkHttp interceptor. Do not launch network-backed external views from offline screens without an explicit return-online action.

In offline mode:

- Planner dispatches directly to the offline engine; it never waits for an EMMA timeout.
- Search uses installed stops, lines and saved coordinates. Photon is not called. Complete address/POI search is not promised; support stop names, saved endpoints, coordinates/current location and a map point where available.
- Departures and route details read the national pack. Existing station snapshots remain separate fallback artifacts with their own date/staleness labels; cached realtime fields cannot become offline live predictions.
- The Járművek tab explains that live vehicles require online mode; it cannot display fabricated vehicle locations. Line browsing remains available locally.
- Favorites work from local IDs/crosswalks. An unresolved old favorite gets a useful unavailable state rather than a network fetch or a guessed match.
- GPS remains usable without internet, subject to location permission and signal availability.
- Show local stop markers and route/walking geometry on a bundled simple background. Full detailed offline basemap tiles are a separate later enhancement unless the measured full pack can include them below 500 MB. Missing remote maps never block planning or a readable itinerary.
- Disable live refresh, source fingerprint downloads, automatic station downloads, app update checks and remote map requests. About/license content needed offline is bundled.

Returning online resumes normal EMMA planning and appropriate screen polling. Do not reuse offline results as live results or resurrect requests canceled before the switch. Initialize the saved network policy before app startup refreshes, including when launching via deep links.

Exit: the entire app starts and works in airplane mode with an installed pack, and explicit Offline mode causes zero new app network requests even while Wi-Fi remains connected.

## Phase 6 — validation and release

Automated fixtures cover calendar exceptions, holidays supplied by feeds, working Saturdays, previous-day overnight trips, DST transitions, overtaking, loop routes, one-way pathways, prohibited/minimum transfers, boarding restrictions, accessibility unknowns, through trips, duplicate feeds and incomplete coverage. Unknown accessibility must never be advertised as guaranteed accessible.

Lifecycle tests cover canceled/resumed downloads, lost network, invalid signatures, corrupt/truncated files, insufficient disk, process death at each installation boundary, schema incompatibility, simultaneous routing/update/delete and rollback. App tests cover both mode directions, saved settings across restart, late HTTP responses, missing/expired packs, dates outside coverage and favorite migration.

Use request counters/test network transports plus device traffic inspection to verify zero outbound app requests in Offline mode, including startup, backgrounding, map screens and OTA. Airplane mode alone does not prove the policy works.

Benchmark the real national pack on a named mid-range Android device in a minified build. Initial acceptance targets: warm stop search below 100 ms, warm journey planning p95 below 2 seconds and cold planning below 5 seconds, with no main-thread graph parsing. Target additional routing memory below 150 MB and measure total app RSS with the map open. These are release targets to validate, not measured claims; revisit the storage layout/algorithm if they fail. Also record peak install disk usage, download recovery and battery behavior.

Manual route matrix includes Budapest-only, Budapest–regional town, rural bus transfer, rail–bus interchange, ViaBusz/KanyarGO/HOMM connections, overnight trips, opposite sides of a station/river and a long-distance cross-country journey. Run with networking blocked and verify that every leg is supported by the installed source data.

Roll out behind a feature flag/internal build first. Publish only after the pack host/refresh job is operational, nationwide claims match the coverage report, the complete download fits the cap and online planner regression checks pass. Keep pack format compatibility independent of routine app updates and provide a clear app-update requirement for incompatible future formats.

## Implementation sequence and review boundaries

1. Data feasibility: source inventory, rights/coverage report, sample pack and measured size. This determines whether the full promise is possible.
2. Pack pipeline: validated format, search/routing/walking indexes, manifest signing and publication CI.
3. Installation: repository, worker, resumable/atomic lifecycle and Settings download management; do not expose an operational offline switch until local features work.
4. Engine: RAPTOR, local walking, reference-solver tests and mapping into existing itinerary UI.
5. App integration: mode repository, shared network policy, local search/boards/map behavior and enabled Settings switch.
6. Release: national route matrix, interrupted-update tests, zero-network verification and measured device budgets.

Planning alone does not establish a reliable delivery date. Estimate effort after the first phase has acquired the feeds and measured a sample, because data access, street graph size and operator coverage dominate uncertainty. No paid hosting, provider account changes or app implementation are part of this planning change.
