# Design Validation & Test Execution Report

## Overview
This document summarizes the validation, compilation fixes, test suite execution, and focused regression tests added for the UI and design updates across `MainActivity`, `MainNavigation`, `ViaBuszScreen`, `NearbyPanel`, `PlannerScreen`, `FavoritesScreen`, `OfflinePackSettings`, and theme surfaces.

---

## 1. Build & Execution Environment
- **JDK Requirement**: Gradle execution requires JDK 21 (`JAVA_HOME="/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home"`).
- **Build Variant**: `development`

---

## 2. Commands & Results

### 2.1 APK Compilation (`assembleDevelopment`)
- **Command**:
  ```bash
  JAVA_HOME="/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home" ./gradlew assembleDevelopment
  ```
- **Result**: `BUILD SUCCESSFUL` (38 actionable tasks, all up-to-date or executed cleanly).
- **Assembled APK Paths**:
  - Universal: `app/build/outputs/apk/development/app-universal-development.apk` (96 MB)
  - ARM64-v8a: `app/build/outputs/apk/development/app-arm64-v8a-development.apk` (66 MB)
  - ARMv7a: `app/build/outputs/apk/development/app-armeabi-v7a-development.apk` (63 MB)
  - x86_64: `app/build/outputs/apk/development/app-x86_64-development.apk` (66 MB)
  - x86: `app/build/outputs/apk/development/app-x86-development.apk` (66 MB)

### 2.2 Unit Test Execution (`testDevelopmentUnitTest`)
- **Command**:
  ```bash
  JAVA_HOME="/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home" ./gradlew testDevelopmentUnitTest
  ```
- **Result**: `BUILD SUCCESSFUL`
  - Total tests: **588**
  - Passed: **583**
  - Skipped: **5** (pre-existing mock/snapshot skips)
  - Failed: **0**

---

## 3. Direct Fixes & Test Alignments

### 3.1 Compilation Fix
- **File**: `app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt`
- **Issue**: Missing import for `androidx.compose.material3.AssistChip` used in the new offline status indicator chip.
- **Fix**: Added `import androidx.compose.material3.AssistChip`.

### 3.2 Test Environment Adjustments (Robolectric Network State)
Robolectric's simulated `ConnectivityManager` does not provide an active validated network by default, causing `NetworkAccessPolicy` to initialize in offline mode (`internetAvailable = false`, `offline = true`).
- **Files Adjusted**:
  - `app/src/test/java/hu/app/bmenetrend/ui/OfflinePackSettingsTest.kt`: Added `@Before fun setUp() { NetworkAccessPolicy.onConnectivityChanged(true) }` to allow pack mode toggle tests to run with simulated connectivity.
  - `app/src/test/java/hu/app/bmenetrend/ui/PlannerRenderingTest.kt`: Added `NetworkAccessPolicy.setOffline(app, false)` and `NetworkAccessPolicy.onConnectivityChanged(true)` in `@Before fun setupMain()` to allow online planning tests to complete without falling back to `OFFLINE_PACK` error.
  - `app/src/test/java/hu/app/bmenetrend/ui/StationRecommendationSearchTest.kt`: Added `@Before fun setup()` ensuring online mode so place search recommendations are queried as expected.

### 3.3 Test Alignments for Intentional UI Changes
- **CTA Button Label (`R.string.design_choose_destination` vs `R.string.planner_find`)**:
  - In `PlannerScreen.kt`, when `destination == null`, `PlannerFindButton` intentionally renders `R.string.design_choose_destination` ("Úti cél keresése") rather than `R.string.planner_find` ("Keresés").
  - Updated assertions in:
    - `app/src/test/java/hu/app/bmenetrend/ui/PlannerMapOverlayTest.kt` (`largeFontFormScrollsAndKeepsFindReachable`, `phoneActualBoundsNeverOverlapMapWithCardOrCta`)
    - `app/src/test/java/hu/app/bmenetrend/PlannerLocationRegressionTest.kt` (`initialPlannerShowsEndpointsFormWithDefaultCurrentLocationAndFindButton`)
- **Map Attribution Placement**:
  - In the redesigned layout, `MapAttribution` was moved inside the map viewport container with `Modifier.align(Alignment.BottomStart)` rather than occupying a dedicated row between form and map or the left column on tablets.
  - Updated bounds assertions in `app/src/test/java/hu/app/bmenetrend/ui/MapAttributionTest.kt` (`plannerMapOverlayPhonePositionsAttributionBelowFormAndAboveMap` and `plannerMapOverlayTabletPositionsAttributionBelowControlsAndLeftOfMap`) to verify that the attribution is placed inside the map bounds below the controls card / to the right of tablet controls.

---

## 4. Focused Regression Tests Added

Added test suite in `app/src/test/java/hu/app/bmenetrend/ui/SearchDistanceAndNavigationCallbackTest.kt` (7 tests, all passing):

1. **`formatStopDistanceRespectsMeterAndKilometerThresholdsAndLocale`**:
   - Verifies formatting for distances under 1,000 m (renders integer with `" m"` suffix, e.g. `0 m`, `12 m`, `999 m`).
   - Verifies boundary at 1,000 m and above (converts to kilometers with 1 decimal place).
   - Validates locale decimal formatting (US dot `1.5 km` vs Hungarian comma `1,5 km`, `2,4 km`).
2. **`stopSearchRowRendersFormattedDistanceOnlyWhenUserLocationIsKnown`**:
   - Verifies that `StopSearchRow` displays the computed stop distance when `UiState.userLocation` is present.
3. **`stopSearchRowOmitsDistanceWhenUserLocationIsNull`**:
   - Verifies that distance labels (`" m"`, `" km"`) are omitted when user location is unavailable.
4. **`favoritesScreenEmptyStateBrowseButtonTriggersCallback`**:
   - Verifies that tapping the browse button on the empty favorites screen invokes `onBrowse()`.
5. **`favoritesScreenWithItemsBrowseActionTriggersCallback`**:
   - Verifies that tapping the add-stop button on a populated favorites screen invokes `onBrowse()`.
6. **`mainNavigationHidesNavigationBarWhenHideNavigationIsTrue`**:
   - Verifies that when `hideNavigation` is enabled (e.g. fullscreen map mode), bottom bar / rail navigation tabs are removed from composition, and reappear when `hideNavigation` is false.
7. **`viaBuszScreenFiresFullscreenAndSearchOpenWhenOpenSearchRequestIncrements`**:
   - Verifies that incrementing `openSearchRequest` opens the search overlay and reports `onFullscreenChanged(true)` via side effects.

---

## 5. Correctness Observations & Summary

| Scope | Status | Notes |
|---|---|---|
| Build / Compilation | PASS | `assembleDevelopment` builds without error. Produced universal APK at `app/build/outputs/apk/development/app-universal-development.apk`. |
| Unit Tests | PASS | 583 passed, 5 skipped, 0 failed out of 588 tests. |
| Distance Formatting | PASS | Boundary behavior and locale-aware decimal separators verified. |
| Callback State Behavior | PASS | Verified `onBrowse`, `onFullscreenChanged`, and `hideNavigation` reactivity. |
| Pre-existing vs Redesign | PASS | Distinguish Robolectric offline defaults from intentional UI/CTA string updates. |
