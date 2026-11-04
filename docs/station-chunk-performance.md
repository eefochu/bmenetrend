# Restored map appearance with progressive loading

The appearance baseline remains commit `25d7aca` from remote `origin/master`: vehicle-dot sprites, icon scaling and station thresholds are preserved. `VehicleMapScene.kt` still matches that revision. Non-train vehicles now start at zoom 11.5 rather than 9, so the wide Budapest view shows trains only; train dots start at 7 and full vehicle icons at 12.

## Railway stations

`RailStationMarkers` aggregates map groups for railway platforms sharing a parent station, with a 500m separation guard. Biatorbágy's rail records also merge by location/name when parent IDs differ between feeds. Nearby bus stops and opposite road platforms remain separate. This runs after ordinary platform matching, so the existing platform matching rules are not changed.

Every source platform stays in the marker's member list. Tapping an aggregated railway marker passes all its member IDs to existing group selection, preserving platform departures and route-planning data. Explicit route/vehicle focus can still choose its matching boarding platform.

## Chunk loading

- A per-scene spatial index uses 0.005-degree geographic cells. The loaded native marker source covers the viewport plus two cells on each side.
- The camera samples loading boundaries at most every 120ms during movement, with an immediate idle update. A StateFlow feeds the render worker directly, so chunk events do not invalidate Compose state.
- Buffer hysteresis avoids rebuilding the source for each small camera movement. Zooming in discards oversized windows.
- Local queries visit spatial buckets; broad queries visit occupied cells. Feature serialization and sprite preparation happen on Dispatchers.Default. Texture upload yields every eight new images.
- Loading tiers exactly preserve the old station behavior: rail from zoom 9, road stops from 12, direction arrows from 14. Native icon sizing and all vehicle appearance/zoom behavior are preserved.
- When the global catalogue is incomplete, `prefetchMapArea` warms downloaded/network map data during panning. A pending-bounds check prevents cancelling and restarting a request that already covers the requested area. Departure boards still update on idle.

The compact accent-colored Térképinfó and its sheet-following movement remain unchanged.

## Checks

Regression coverage includes one Biatorbágy railway marker with every platform retained; separate bus platforms; station separation; preload margins; edge crossing/revisit; broad/negative-coordinate chunk queries; matching every restored zoom threshold; and network map prefetch without waiting for camera idle/global index.

Physical device: Huawei P20 Pro / CLT-L09, Android 10, serial LCL0218325001083. Build an R8-minified non-debuggable release for on-device checks; unit tests use development variant. No measured FPS improvement is claimed from these implementation tests.

Final validation: full suite 610 tests, 605 passed, 5 skipped; R8 release build successful and installed on P20 Pro. Overview inspection showed one railway marker at Biatorbágy. A closer gesture check failed twice with `injectInputEvent returned false`, including the advised debug retry; device input checks stopped there. No on-device smoothness/FPS claim is made. The full suite also exposed a startup race in an existing planner test; a Main-dispatcher barrier now lets its initial policy collector initialize before exercising plan invalidation.
