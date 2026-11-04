# Design Validation & Palette Regression Report

## Overview
This document records the validation, build results, and regression test execution for the latest design refinements in BMENetrend, focusing on the controlled blue transit palette (independent of Android wallpaper colors), compact theme selection, continuous full-size phone planner map with floating controls and single bottom CTA, navigation bar visibility controls, and touch target / accessibility compliance.

---

## 1. Environment & Build Validation

### 1.1 JDK & Build Variant
- **JDK**: OpenJDK 21 (`JAVA_HOME=/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`)
- **Variant**: `development` (`assembleDevelopment`)

### 1.2 APK Compilation
Command:
```bash
export JAVA_HOME=/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
./gradlew assembleDevelopment
```
- **Result**: `BUILD SUCCESSFUL` (38 actionable tasks, all executed or up-to-date)
- **Installable APK Artifacts**:
  - Universal APK: `app/build/outputs/apk/development/app-universal-development.apk` (96 MB)
  - Target APK: `app/build/outputs/apk/development/app-development.apk` (96 MB)
  - Architecture-specific APKs:
    - ARM64-v8a: `app/build/outputs/apk/development/app-arm64-v8a-development.apk` (66 MB)
    - ARMv7a: `app/build/outputs/apk/development/app-armeabi-v7a-development.apk` (63 MB)
    - x86_64: `app/build/outputs/apk/development/app-x86_64-development.apk` (66 MB)
    - x86: `app/build/outputs/apk/development/app-x86-development.apk` (66 MB)

---

## 2. Source Validation & Architectural Conformance

### 2.1 Controlled Blue Default & Wallpaper Independence
- **Implementation**: In `hu/app/bmenetrend/ui/Theme.kt`, `ViaBuszTheme` and `accentColorScheme(AccentColor.SYSTEM, ...)` supply explicit transit blue tonal roles (`#145FAD` light primary, `#E1EDFA` container, `#94C9FF` dark primary) with neutral reading backgrounds (`#F6F7F9` light, `#131519` dark charcoal). System dynamic wallpaper colors (`dynamicColorScheme`) are strictly bypassed.
- **Verification**: `AccentColorTest.kt` verifies exact hex values for light and dark surfaces, containers, borders, and variants without wallpaper pollution.

### 2.2 Compact SettingsPage Radio Selector
- **Implementation**: In `hu/app/bmenetrend/ui/SettingsPage.kt`, theme mode selection uses a compact horizontal segmented `selectableGroup` containing `Auto`, `Light`, and `Dark` items with `Role.RadioButton`.
- **Verification**: `SettingsPageThemeSelectorTest.kt` checks all 3 options, minimum 48 dp touch target heights, and callback dispatching.

### 2.3 Planner Phone Layout: Continuous Full-Size Map
- **Implementation**: In `hu/app/bmenetrend/ui/PlannerScreen.kt`, the phone planner layout (`PlannerMapOverlay`) is structured as a full-size `Box`:
  - `mapContent(Modifier.fillMaxSize())`: Continuous basemap spanning the entire viewport.
  - Floating top controls: `Surface(Modifier.align(Alignment.TopCenter)...) { controls() }` containing the origin/destination inputs and swap button.
  - Floating bottom CTA: Single primary CTA `PlannerFindButton(Modifier.align(Alignment.BottomCenter)...)` without any obsolete helper panel.
  - Map attribution: Positioned at `Modifier.align(Alignment.BottomStart)` with bottom padding (88 dp) to float clearly above the bottom CTA and remain interactive.
- **Verification**: `PlannerMapOverlayPhoneTest` and `MapAttributionTest` confirm bounds, layering, and touch reachability.

### 2.4 Tablet Planner & Bottom Bar Navigation
- **Implementation**:
  - `MainActivity.kt` sets `hideBottomBarOnPlanner = true` on `MainNavigation`.
  - In `MainNavigation.kt`, the phone bottom navigation bar is conditionally omitted when `tab == MainTab.PLANNER && hideBottomBarOnPlanner`.
  - Wide screens (`transitWindowWidth() >= 840.dp`) show the side navigation rail (`MainTab` entries), and wide tablet planner (`>= 600.dp`) displays the controls column (360 dp) and map side by side.
- **Verification**: `ExpressiveNavigationTest`, `TabletNavigationRailTest`, and `PlannerMapOverlayTabletTest` verify phone bottom bar hiding, tablet rail persistence, and side-by-side layout bounds.

### 2.5 Text Filter Actions
- **Implementation**: In `PlannerMapOverlay`, departure time selection and local networks dialog triggers use borderless `TextButton` controls with transit icons (`Icons.Rounded.Schedule`, `Icons.Rounded.Tune`) rather than bulky outlined chips.

---

## 3. Test Suites & Pass/Fail Execution Summary

### 3.1 Focused Regression Suites
Executed with:
```bash
export JAVA_HOME=/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
./gradlew testDevelopmentUnitTest \
  --tests "hu.app.bmenetrend.ui.PlannerMapOverlayTabletTest" \
  --tests "hu.app.bmenetrend.ui.PlannerMapOverlayPhoneTest" \
  --tests "hu.app.bmenetrend.ui.MapAttributionTest" \
  --tests "hu.app.bmenetrend.ui.SettingsPageThemeSelectorTest" \
  --tests "hu.app.bmenetrend.ui.AccentColorTest" \
  --tests "hu.app.bmenetrend.ui.ExpressiveNavigationTest" \
  --tests "hu.app.bmenetrend.ui.TabletNavigationRailTest" \
  --tests "hu.app.bmenetrend.PlannerLocationRegressionTest" \
  --tests "hu.app.bmenetrend.ui.PlannerRenderingTest" \
  --tests "hu.app.bmenetrend.ui.StationRecommendationSearchTest" \
  --tests "hu.app.bmenetrend.ui.SearchDistanceAndNavigationCallbackTest"
```

| Suite | Tests | Passed | Failed | Notes |
|---|---|---|---|---|
| `PlannerMapOverlayTabletTest` | 2 | 2 | 0 | Verifies side-by-side tablet controls and map bounds |
| `PlannerMapOverlayPhoneTest` | 4 | 4 | 0 | Verifies full-size map, floating top form, bottom CTA, 1.6x font scaling, and local services dialog |
| `MapAttributionTest` | 5 | 5 | 0 | Verifies attribution reachability, dialog invocation, and placement within map bounds |
| `SettingsPageThemeSelectorTest` | 8 | 8 | 0 | Verifies compact radio selector, 48dp targets, role semantics, and callbacks |
| `AccentColorTest` | 4 | 4 | 0 | Verifies controlled blue default, wallpaper independence, and custom hue generation |
| `ExpressiveNavigationTest` | 2 | 2 | 0 | Verifies 1.8x font scaling destinations and `hideBottomBarOnPlanner` behavior on phone |
| `TabletNavigationRailTest` | 1 | 1 | 0 | Verifies wide tablet navigation rail persists on planner tab |
| `PlannerLocationRegressionTest` | 8 | 8 | 0 | Verifies initial planner fields, location fallback, and dynamic CTA labels |
| `PlannerRenderingTest` | 8 | 8 | 0 | Verifies trip plan rendering, itinerary details, time shifts, and error handling |
| `StationRecommendationSearchTest` | 2 | 2 | 0 | Verifies recommendation chips and stop selection |
| `SearchDistanceAndNavigationCallbackTest` | 7 | 7 | 0 | Verifies distance formatting, browse callbacks, and fullscreen side-effects |
| **Total Focused** | **51** | **51** | **0** | **100% Pass** |

### 3.2 Full Project Unit Test Run
Command:
```bash
export JAVA_HOME=/Users/imbence/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
./gradlew testDevelopmentUnitTest
```
- **Total Tests**: **592**
- **Passed**: **587**
- **Skipped**: **5** (pre-existing mock/snapshot skips)
- **Failed**: **0**
- **Errors**: **0**

---

## 4. Test Adjustments Made

1. **Obsolete Map Sandwich Assertion in `PlannerMapOverlayTest.kt`**:
   - *Previous Assertion*: Tested obsolete vertical column ordering where `cardBounds.bottom <= mapBounds.top` and `mapBounds.bottom <= ctaBounds.top`.
   - *Updated Assertion*: Reflects the continuous full-size map (`mapBounds.height > 0f`), floating top card (`cardBounds.top >= mapBounds.top`), floating bottom CTA (`ctaBounds.bottom <= mapBounds.bottom`), non-overlapping card and CTA (`cardBounds.bottom <= ctaBounds.top`), minimum 48dp touch target on the CTA, and attribution placement above the bottom CTA (`attrBounds.bottom <= ctaBounds.top`).
2. **Navigation Suite Expansion in `ExpressiveNavigationTest.kt`**:
   - Added `phoneHidesBottomNavigationWhenPlannerActiveAndRequested` validating that the bottom bar disappears when navigating to Planner if `hideBottomBarOnPlanner` is active.
   - Added `TabletNavigationRailTest` validating that wide tablet navigation rail remains visible across all tabs including Planner.
