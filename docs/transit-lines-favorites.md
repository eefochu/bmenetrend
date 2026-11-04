# Persistent rail lines and compact favorites

`MetroNetworkOverlay` now installs two line layers (colored track and casing), with no circle/symbol station layers. It is loaded once per map style and stays present when selecting a stop or vehicle. Its placement anchor is the lowest existing selected-route, station, vehicle or annotation layer, so the persistent context stays underneath all interactive markers regardless of effect creation order.

Bundled lines: M1, M2, M3, M4, H5, H6, H7, H8, H9. Geometry comes from the official BKK GTFS snapshot downloaded from https://go.bkk.hu/api/static/v1/public-gtfs/budapest_gtfs.zip. `scripts/build_metro_map.py` records the ZIP hash, keeps the full-length shape in each direction, excludes replacement buses, and emits no station points. The asset contains 18 LineStrings. HÉV colors use the existing `HevSupport` palette; metro colors use `MetroLineStyle`. The offline asset footprint and map credits were updated.

Favorite cards have tighter spacing, a combined compact route-badge grid and provider label, and no duplicate 48dp All Departures footer. Tapping the card or its route badges still opens departures; the overflow menu retains All Departures and Remove. Existing next-departure data remains visible when available.

Focused tests cover the exact nine lines, both directions, nontrivial track geometry, colors, rejection of station points/unrequested routes, placement below each marker layer, exact bundled asset footprint, compact favorite-card height and card/delete callbacks. Restored station/vehicle zoom rules and the chunk prefetch logic are unchanged.

Validation complete: R8 release build successful; full suite 611 tests (606 passed, 5 skipped, zero failures). Installed on the physical P20 Pro. The favorites screen accessibility snapshot showed station titles and complete route badges. The user was actively navigating during the attempted screenshot check, so further device input stopped. No claim is made that all nine lines were manually inspected on the device.

Wide-view vehicle correction: non-train vehicle dots now start at zoom 11.5 rather than 9; train dots stay at zoom 7 and full icons at zoom 12. The native layer thresholds and visibility helper share constants. Boundary tests cover the wide Budapest view and the return of other vehicles at closer zoom, plus the existing focused-route exception. Station/track rendering and chunk loading are unchanged.

Wide-view correction validated with 13 targeted tests, zero failures. R8 release built successfully and installed on the P20 Pro. The phone was in another foreground app after installation, so it was left undisturbed and no additional UI-input verification was performed.
