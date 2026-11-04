# Independent UI/UX Review: bmenetrend vs. BudapestGO Reference

**Date:** 2026-11-04  
**Author:** Review Sub-Agent (Independent UI/UX Audit)  
**Target Repository:** [`/Users/imbence/Stuff/Code/Projects/bmenetrend`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend)  
**Reference Assets:** Ten BudapestGO reference screenshots matching `/Users/imbence/.t3/userdata/attachments/2c5ac9df-93e4-4b4e-bad1-b4af29b7fa34-*.jpg`  
**Output Path:** [`docs/design-independent-baseline.md`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/docs/design-independent-baseline.md)

---

## 1. Executive Summary & Review Scope

This report provides an independent, objective UI/UX design and architecture evaluation comparing the open-source transit application **bmenetrend** against the production **BudapestGO** Android mobile transit application across ten reference screenshots.

### Strict Review Constraints & Independence Declaration
1. **Zero Contamination:** No prior design reviews (e.g., `REDESIGN_REPORT.md`), internal audit memos, or past agent messages were read, referenced, or consulted. All evaluations, rubric weights, and scores originate exclusively from the current code audit and reference image analysis.
2. **Read-Only / No Execution:** No app modifications, Gradle builds, device emulations, or delegated agents were executed.
3. **Separation of Evidence Types:**
   - **BudapestGO:** Evaluated via **direct visual screenshot evidence** (ten 964×2000 px raster screenshots capturing production states: trip detail, map view, empty state, disruption card, planner query form, favorites list, search autocomplete, itinerary comparison, and hub departure boards).
   - **bmenetrend:** Evaluated via **source-based architectural and component code inspection** across its Jetpack Compose implementation (`hu.app.bmenetrend.ui`, `hu.app.bmenetrend.map`), design system tokens, typography scales, accessibility semantics, and navigation graph.
   - **Verified vs. Inferred Scores:** In accordance with instructions, bmenetrend is **not** assigned an unverified visual score for runtime-rendered frames that were not directly screenshotted in this session. Scores reflect verified structural code quality, layout ergonomics, semantic accessibility, information density, and algorithmic design.

---

## 2. Detailed Inspection of the Ten BudapestGO Reference Screenshots

Each reference image was analyzed via computer vision inspection, text recognition, coordinate mapping, and visual hierarchy assessment.

| # | Image File Identifier | Screen Title / Context | Primary Components & Visual Layout |
|---|---|---|---|
| **1** | `...00596eb2-d05f-45c4-89e2-01f00053868e.jpg` | **Trip Detail / Itinerary Step-by-Step** | • **Header:** Large bold duration `"46p"`, rail line badge `[S10]` (`#00AFEF`), summary tags (`"gyors"`, `"27 p séta"`), and header action `"Jegyeim"`.<br>• **Vertical Stepper:** Left time column (`16:49`, `17:16`, `17:33`), solid colored nodes for transit and dashed lines for walking.<br>• **Tariff Warning:** Bordered callout container: *"A BKK-bérletek és -jegyek kizárólag a budapesti szakaszon érvényesek."*<br>• **Leg Expansion:** Expandable stop counter (`"16 p, 3 megálló v"`). |
| **2** | `...1d30e2cd-684b-4159-91ef-b4bcef4833ac.jpg` | **Home Map / Main Landing Screen** | • **Floating Search Bar:** Top rounded card *"Hová szeretnél menni?"* with search glyph.<br>• **Floating Controls:** Right-aligned vehicle toggle button (`"Járművek"` switch), recenter FAB, zoom controls.<br>• **Bottom Sheet (Collapsed):** Title `"Tervezés"`, horizontal category tabs (*Indulások, Járatok, Megállók, Szűrő*).<br>• **Promotional Card:** Ticket validation banner (*"KINT IS. BENT IS. KÉNYELMESEN."*).<br>• **Recent Item:** Line badge `[19]` to *"Móricz Zsigmond körtér M"*.<br>• **Bottom Bar:** 4 tabs (*Tervezés, Kedvenceim, Jegyeim, Menü*). |
| **3** | `...45683f4c-9c4d-47c4-8f55-0dfd4ab330b2.jpg` | **Suburban Map / Empty Departures State** | • **Context:** Centered on Biatorbágy suburban area (*Dévay Gyula utca*).<br>• **Empty State Design:** In the bottom sheet under *Indulások*: polite, concise Hungarian message: *"Nincsenek járatok a térképen látható területen. Próbáld meg egy másik környéken."*<br>• Keeps map context active without abrupt modal barriers. |
| **4** | `...7549e32c-bf1b-42f2-a266-2cf374db36d6.jpg` | **Route Preview & Disruption Warning** | • **Header:** `< Utazástervezés` with back navigation and `Jegyeim`.<br>• **Map Polyline:** Full geographic journey rendered with dashed walking leg and solid transit route line.<br>• **Itinerary Summary Card:** Duration `46p`, line `[S10]`, walk time.<br>• **Time Navigation:** Two quick step buttons: `"Korábban"` (Earlier) and `"Később (17:11)"` with preview of next departure time.<br>• **Disruption Banner:** Prominent orange/yellow card: *"Más vonat által okozott késés"* with valid time window and `"Részletek >"` chevron. |
| **5** | `...78ac99f9-32c1-4056-a013-12ba526157e6.jpg` | **Trip Planner Query Form (Origin/Destination)** | • **Header Action:** `"Járműről"` (Plan from current on-board vehicle).<br>• **Inputs:** Stacked card with origin (*"Jelenlegi pozícióm"*) and destination (*"Kelenföld vasútállomás M"*).<br>• **Options Chips:** Dropdown chips (*"Indulás most v"*, *"Legjobb útvonal v"*, *"Beállítások"*).<br>• **Action CTA:** Prominent bottom pill button *"Mutasd az útvonalakat"*. |
| **6** | `...8dc2e26c-eb52-426f-bcbe-2ea0b7051e38.jpg` | **Favorites Screen ("Kedvenceim")** | • **Segmented Control:** `Megállók` (active) vs. `Járatok`.<br>• **Station Cards:** Card for *Budapest-Kelenföld*, *Biatorbágy*, *Kelenföld vasútállomás M*.<br>• **Badge Grid:** Each card displays a compact 4-column badge grid of lines serving the stop (MÁV rail lines `EC, EN, Ex, G10, G40, G43, IR, S, S10...` with authentic MÁV brand colors, plus urban bus lines `8E, 108E`).<br>• **Footer CTA:** `"Új megálló hozzáadása"` (+ Add stop). |
| **7** | `...a4981e0e-012f-4916-abb1-4c8d4217919c.jpg` | **Search Autocomplete (Query: "déli pályau")** | • **Search Field:** Text input with placeholder *"Keress járatra, címre, megállóra"*.<br>• **Result Hierarchy:** General address match at top (*Déli pályaudvar, 1012, 16 km*), followed by section title `"Megállók"`.<br>• **Rich Stop Platform Items:** Displays stop name, orange platform badge (`[B1]`, `[A1]`), distance (`16 km`), and **a horizontal wrap of colored line badges** (`[21]`, `[21A]`, `[39]`, `[102]`, `[139]`, `[140]`, `[140A]`, `[221]`, `[912]`, `[917]`). |
| **8** | `...c70374cf-a103-48c9-add3-7bc57d9003b0.jpg` | **Search Autocomplete (Platform Scoping)** | • Continuation of "déli pályau" search.<br>• Shows tram platforms: `[E1]` with yellow tram badges `[17] [59] [59A] [61]`, `[G1]` with tram badges and red metro badge `[M2]`, and rail station with train line `[2]`. Allows users to identify the exact physical platform for their target route. |
| **9** | `...c94eac2e-2818-4192-8a56-866f002f90b4.jpg` | **Trip Planner Results List ("Útvonaltervek")** | • **View Switcher:** Segmented tabs `Útvonaltervek` (active) vs. `Térkép`.<br>• **Card 1:** `16:49` — `17:35` (`46p`), walking badge `[25]` -> rail badge `[S10]`, subtitle `"27 p séta • gyors"`, BKK regional tariff disclaimer box.<br>• **Card 2:** `16:51` — `17:39` (`48p`), walk `[15]` -> Volán yellow bus `[760]` -> BKK blue bus `[172]`, subtitle `"16 p séta • kevesebb gyaloglás"`.<br>• **Floating Bottom CTA:** `"Jegy- és bérletvásárlás"`. |
| **10** | `...e1f8e56a-ece5-4758-901f-d3ddd616ae09.jpg` | **Central Hub Stop Board (Deák Ferenc tér)** | • Bottom sheet over map at major transit node.<br>• Segmented tabs: `Indulások` (active), `Járatok`, `Megállók`, `Szűrő`.<br>• High-density grid of line badges serving the hub (e.g., night buses `909, 914, 922`, airport bus `[100E]` in signature purple/cyan). |

---

## 3. Weighted Evaluation Rubric

To provide a rigorous, objective benchmark for public transit applications, we define six weighted criteria reflecting real-world commuter ergonomics:

| Category | Weight | Description & Focus |
|---|---|---|
| **1. Transit Information Scanning** | **25%** | Recognition of transit modes, line numbers, official brand colors, countdown timers, platform codes, and step-by-step transfer points. Essential for split-second commuter decisions. |
| **2. Clarity & Disruption Communication** | **20%** | Unambiguous labels, real-time delay presentation, cancellation alerts, disruption notices, and tariff boundary clarity across regional transit borders. |
| **3. Hierarchy & Layout Ergonomics** | **20%** | Sheet mechanics, progressive disclosure, density balance, visual clutter prevention, and interaction reachability on one-handed mobile devices. |
| **4. Navigation Architecture & Mental Model** | **15%** | Flow between Map, Planner, Search, and Favorites. Back-stack behavior, modal state preservation, and view switching. |
| **5. Accessibility & Adaptability** | **10%** | Touch target sizing (min 48dp), WCAG AA color contrast, dynamic font scaling (>1.3×), screen-reader semantics (`Role`, `contentDescription`), and dark mode integrity. |
| **6. Visual Consistency & Design System** | **10%** | Design tokens, surface elevations, corner radii, typography scale uniformity, and cohesive component reuse. |

---

## 4. Comparative Ratings & Scores

*Note: In accordance with review guidelines, BudapestGO scores reflect **direct visual inspection of rendered production screenshots**. bmenetrend scores reflect **source code inspection of Compose components and layout specifications**, separated from unrendered runtime visual fidelity.*

| Rubric Category | Weight | BudapestGO (Rendered Evidence) | bmenetrend (Source Audit) | Analysis & Rationale |
|---|:---:|:---:|:---:|---|
| **1. Transit Information Scanning** | 25% | **9.2 / 10** | **8.4 / 10** | **BudapestGO:** Exceptional scanning in search autocomplete, where stop platform rows display full colored badge rows (`[21] [139] [M2]`), enabling immediate platform identification. Station boards and favorites use authentic MÁV/BKK palettes.<br>**bmenetrend:** Excellent badge styling in [`LineComponents.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/LineComponents.kt#L101-L150) and [`FavoritesScreen.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/FavoritesScreen.kt#L70-L115) (train badge classification and natural sorting). Outstanding proportional canvas `JourneyBar` in [`JourneyComponents.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt#L70-L100). However, **loses points because search results** in [`StopSearchRow`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt#L920-L935) print line numbers as plain-text dots (`"21 · 21A · 39"`) instead of colored badges, reducing scanning speed. |
| **2. Clarity & Disruption Communication** | 20% | **9.0 / 10** | **7.5 / 10** | **BudapestGO:** Features high-visibility disruption warning cards on route previews (Screenshot 4) with time validity and details. Displays vital regional tariff notices (*"A BKK-bérletek és -jegyek kizárólag a budapesti szakaszon érvényesek"*) and one-tap departure steppers (*"Korábban" / "Később (17:11)"*).<br>**bmenetrend:** Solid delay indicators (`delayMinutes > 2` triggers error color and strike-through scheduled times in [`LineComponents.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/LineComponents.kt#L180-L195)). However, lacks inline disruption alert cards on itineraries, lacks regional tariff boundary notices when planning interurban MÁV/Volán trips, and lacks one-tap earlier/later time buttons on results. |
| **3. Hierarchy & Layout Ergonomics** | 20% | **8.8 / 10** | **8.5 / 10** | **BudapestGO:** Well-balanced bottom sheets with discrete peek heights, search floating bar, and clear segmented controls (e.g., *Útvonaltervek vs. Térkép*).<br>**bmenetrend:** Highly engineered adaptive sheets in [`ViaBuszScreen.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt#L305-L330) with dynamic peek height calculated by screen height and route pattern count. However, [`PlannerScreen.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/PlannerScreen.kt#L180-L240) replaces the map entirely with a full-screen `Scaffold` during results view rather than offering a tabbed map/list split. |
| **4. Navigation Architecture & Mental Model** | 15% | **8.5 / 10** | **8.8 / 10** | **BudapestGO:** 4-tab model (*Tervezés, Kedvenceim, Jegyeim, Menü*). Strong commercial focus on ticketing.<br>**bmenetrend:** 3-tab model (*Térkép, Tervezés, Kedvencek*) in [`MainNavigation.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/MainNavigation.kt#L25-L75). Cleaner transit focus (no ticketing clutter). Superior wide-screen adaptation: automatically converts the bottom bar into a responsive Navigation Rail on screens $\ge 840\,\text{dp}$. Clean back-handler stack and saveable state providers. |
| **5. Accessibility & Adaptability** | 10% | **8.0 / 10** | **9.4 / 10** | **BudapestGO:** Standard consumer accessibility. Touch targets and fonts are standard.<br>**bmenetrend:** **Exceptional accessibility engineering in source.** Strict `heightIn(min = 48.dp)` on all interactive controls; automatic layout reflow when `fontScale > 1.3f` (navigation labels adapt, badge grid switches from 4 to 3 columns in [`FavoritesScreen.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/FavoritesScreen.kt#L125)); programmatic WCAG AA contrast calculation via `badgeTextColor()` in [`LineComponents.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/LineComponents.kt#L45-L55); and tabular font numbers (`tnum`) in [`Theme.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/Theme.kt#L60-L75) to prevent layout jitter during live countdown updates. |
| **6. Visual Consistency & Design System** | 10% | **8.8 / 10** | **8.7 / 10** | **BudapestGO:** Polished, recognizable BKK corporate identity with clean white surfaces, subtle drop shadows, and consistent 16–20dp radii.<br>**bmenetrend:** Rigorous Material 3 Design System in [`DesignSystem.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/DesignSystem.kt#L30-L50) using unified tokens (`TransitDesign.gutter`, `controlShape = 16.dp`, `panelShape = 24.dp`, `sheetShape = 28.dp`). Full dynamic accent color generation and dark mode support in [`Theme.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/Theme.kt#L80-L120). |
| **Weighted Total** | **100%** | **8.82 / 10** | **8.42 / 10** | *bmenetrend demonstrates superior accessibility, responsive layout engineering, and data integrity, but lags behind BudapestGO in search badge richness, disruption communication, and planner time-shift agility.* |

---

## 5. Detailed UI/UX Divergence Analysis

### A. Search & Autocomplete
- **BudapestGO (Screenshots 7 & 8):**
  - Searches differentiate stop platforms (e.g., *Déli pályaudvar M [A1]*, *[B1]*, *[E1]*, *[G1]*).
  - Each platform row renders an authentic wrap of colored line badges (`[21]`, `[39]`, `[102]`, `[17]`, `[M2]`). A rider looking for tram 61 instantly spots platform `[E1]`.
  - Displays distance right-aligned (`16 km`).
- **bmenetrend Implementation:**
  - Implements [`CategorizedSearch.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/CategorizedSearch.kt#L100-L140) with category filter chips (*All, Stations, Places, Vehicles*).
  - Uses [`PlatformBadge`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/DesignSystem.kt#L65-L95) (`#E67008`), matching BudapestGO’s orange badges.
  - **Divergence:** In [`StopSearchRow`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt#L920-L935), line numbers are joined as plain text strings (`"21 · 21A · 39 · 102 · 139 · 140"`) and capped at 6. The user cannot distinguish at a glance whether "21" is a bus, tram, or regional service. Distance in meters/km is also omitted from search rows.

### B. Trip Planning & Itinerary Presentation
- **BudapestGO (Screenshots 1, 4, 9):**
  - Result cards feature massive duration typography (`46p`), prominent mode badges (`[S10]`), and walking durations.
  - Offers a segmented view switcher between **"Útvonaltervek" (List)** and **"Térkép" (Map)** directly on the results screen (Screenshot 9).
  - Features quick departure-shift buttons: `"Korábban"` and `"Később (17:11)"`.
  - Prominently warns about cross-boundary ticket validity (BKK boundary).
  - Renders inline disruption alerts (Screenshot 4).
- **bmenetrend Implementation:**
  - In [`ItineraryCard`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt#L30-L65), bmenetrend includes an innovative proportional canvas `JourneyBar` illustrating the exact ratio of walking, riding, and waiting.
  - In [`JourneyDetail`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt#L110-L160), bmenetrend renders a clean vertical timeline stepper with dotted lines for walking, transfer wait durations, platform badges, and expandable intermediate stops.
  - **Divergence:** Once planning executes in [`PlannerScreen.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/PlannerScreen.kt#L180), bmenetrend swaps out the map overlay for a vertical `Scaffold` list. Users cannot easily toggle back and forth to inspect routes on the map without canceling or backing out. It also lacks one-tap earlier/later buttons and regional tariff disclaimers.

### C. Favorites Screen
- **BudapestGO (Screenshot 6):**
  - Header segmented control: `Megállók` vs. `Járatok`.
  - Displays stop cards with a 4-column badge grid showing all lines serving that stop, naturally ordered.
- **bmenetrend Implementation:**
  - [`FavoritesScreen.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/FavoritesScreen.kt#L125-L165) faithfully implements this exact pattern via `extractStationLines` and `StationBadgeGrid`, distinguishing train lines from other lines with natural sorting and MÁV/BKK colors.
  - **Divergence:** bmenetrend does not currently offer the `Járatok` (Line favorites) tab in the favorites view, focusing strictly on favorite stops.

### D. Accessibility & Device Responsiveness
- **BudapestGO:** Typical fixed-orientation consumer app layout.
- **bmenetrend:** Outstanding implementation:
  - Wide-screen navigation rail support in [`MainNavigation.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/MainNavigation.kt#L45-L60) (`rail = transitWindowWidth() >= 840.dp`).
  - Adaptive column reflow for accessibility text scaling (`fontScale > 1.3f`).
  - Strict adherence to 48dp touch targets.
  - Tabular numbers (`tnum`) in typography to prevent layout oscillation during live departure updates.

---

## 6. Prioritized Actionable Recommendations

The following actionable improvements are identified directly from this comparative analysis. Exact file locations and proposed architectural enhancements are provided.

### Priority 1 (High Impact - Quick Usability Wins)

#### 1. Rich Line Badges in Stop Search Results
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt:920-935`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt#L920-L935) (`StopSearchRow`) and [`CategorizedSearch.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/CategorizedSearch.kt#L125-L135).
- **Issue:** Stop search results display line numbers as raw text strings (`"21 · 21A · 39"`), whereas BudapestGO displays rich, colored `LineBadge` components (Screenshots 7 & 8).
- **Recommendation:** Replace the plain-text string formatting in `StopSearchRow` with a horizontal `FlowRow` of `LineBadge(number, provider, mode)` (or a compact variant) for the first 6–8 lines, allowing users to instantly recognize vehicle modes and colors.
- **Reference:** See BudapestGO Screenshots 7 and 8.

#### 2. One-Tap Earlier/Later ("Korábban" / "Később") Itinerary Shifting
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/PlannerScreen.kt:280-320`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/PlannerScreen.kt#L280-L320) and [`PlannerViewModel.kt`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/PlannerViewModel.kt).
- **Issue:** Changing departure times requires opening a modal `DeparturePicker` dialog. BudapestGO provides two direct buttons: `"Korábban"` and `"Később (time)"` (Screenshots 4 & 9).
- **Recommendation:** Add a two-button row above or below the itinerary cards in `PlannerScreen.kt` that shifts `departureTime` by $-15$ / $+15$ minutes (or to the next scheduled service) with a single tap, displaying the anticipated next departure time in the label.

#### 3. Distance Display in Stop Search Results
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt:920-935`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/ViaBuszScreen.kt#L920-L935) (`StopSearchRow`).
- **Issue:** When the user's location is known, search results do not display the distance to the station, making it difficult to distinguish between nearby stops and distant identically named stops across the country.
- **Recommendation:** Calculate distance from `state.userLocation` to `stop.position` and display it right-aligned in `SearchResultCard` (e.g. `"16 km"` or `"450 m"`), matching BudapestGO Screenshots 7 & 8.

---

### Priority 2 (Medium Impact - Commuter Clarity & Ergonomics)

#### 4. Dual View (List vs. Map) in Trip Planning Results
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/PlannerScreen.kt:170-220`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/PlannerScreen.kt#L170-L220).
- **Issue:** When results arrive (`showResults == true`), `PlannerScreen` enters a full-page scrollable list that hides the map entirely. BudapestGO maintains a segmented control (*"Útvonaltervek"* vs. *"Térkép"*, Screenshot 9) or a bottom sheet over the map (Screenshot 4).
- **Recommendation:** Introduce a segmented toggle in the `TopAppBar` or sheet header allowing users to view itineraries either as a list or rendered on the interactive `transitMapContent`.

#### 5. Regional Tariff & Zone Boundary Notices
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt:50-70`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt#L50-L70) (`ItineraryCard`) and `JourneyDetail`.
- **Issue:** Multi-operator journeys (e.g. Biatorbágy to Budapest-Kelenföld via MÁV S10 or Volán 760) cross the Budapest administrative tariff boundary. BudapestGO warns users prominently: *"A BKK-bérletek és -jegyek kizárólag a budapesti szakaszon érvényesek"* (Screenshots 1 & 9).
- **Recommendation:** When an itinerary contains regional legs (`mav`, `volan`) originating or terminating outside the Budapest tariff zone (or across county borders), display an informative callout banner.

#### 6. Inline Service Disruption Alerts on Planner Cards
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt:60-80`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/JourneyComponents.kt#L60-L80).
- **Issue:** Real-time disruptions and line delays are currently indicated via text/status in stop views, but not highlighted as alert banners on planned itineraries as in BudapestGO (Screenshot 4: *"Más vonat által okozott késés"*).
- **Recommendation:** Add a `DisruptionBanner` composable to `ItineraryCard` and `JourneyDetail` when any leg's route or vehicle carries active alerts or significant delays.

---

### Priority 3 (Refinements & Completeness)

#### 7. Line Favorites Tab ("Járatok")
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/FavoritesScreen.kt:180-220`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/FavoritesScreen.kt#L180-L220).
- **Issue:** BudapestGO allows bookmarking both stops and entire transit lines via a segmented header control (`Megállók` vs. `Járatok`, Screenshot 6). bmenetrend currently supports favorite stops only.
- **Recommendation:** Add a tab row to `FavoritesScreen` allowing users to switch between favorite stops and favorite lines, displaying route cards with quick direction shortcuts.

#### 8. Contextual Empty States for Suburban / Sparse Areas
- **Location:** [`app/src/main/java/hu/app/bmenetrend/ui/NearbyPanel.kt:60-75`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/app/src/main/java/hu/app/bmenetrend/ui/NearbyPanel.kt#L60-L75).
- **Issue:** When the map is panned to an area with no nearby stops, the empty state can be further refined to match BudapestGO Screenshot 3 (*"Nincsenek járatok a térképen látható területen. Próbáld meg egy másik környéken"*), clearly separating geographic absence from network error states.

---

## 7. Report Evidence Limitations & Objections

To maintain complete transparency and objective rigor, the following evidence limitations are recorded:

1. **Non-Rendered App Visuals:** bmenetrend’s visual evaluation was conducted strictly through static code analysis of its Jetpack Compose definitions, design system values, and algorithmic calculations. No runtime APK was built or executed on a live device or emulator during this audit. Consequently, runtime pixel-level antialiasing, GPU rasterization performance, and gesture physics could not be visually confirmed and are excluded from the verified visual score.
2. **Dynamic Data Variance:** The BudapestGO reference screenshots were captured at a specific time of day (around 16:47–16:49 on 2026-11-04) showing active live vehicle positions and disruptions on specific lines (S10, 19, 100E). Source inspection of bmenetrend confirms support for equivalent real-time feeds (GTFS-RT, EMMA, MÁV), but exact visual parity under identical live payload conditions remains dependent on external backend API responses.
3. **Ticketing Scope:** BudapestGO integrates a commercial ticketing ecosystem (*"Jegyeim"*, in-vehicle NFC validation, pass purchase CTAs). bmenetrend intentionally does not include mobile ticketing infrastructure. This architectural difference is reflected in the navigation rubric without penalizing bmenetrend's transit information utility.

---
*Report completed and saved independently to [`docs/design-independent-baseline.md`](file:///Users/imbence/Stuff/Code/Projects/bmenetrend/docs/design-independent-baseline.md).*
