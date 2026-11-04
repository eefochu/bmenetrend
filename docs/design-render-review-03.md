# Visual Design Review: Transit Route Planner (Candidate Render)

**Document:** `design-render-review-03.md`  
**Evaluation Target:** Candidate planner initial render (`02-planner-dark.png`)  
**Benchmarking References:** BudapestGO production captures (Results, Map Exploration, Destination Search)  
**Review Method:** Independent screenshot-only visual inspection (strictly visible evidence)

---

## 1. Visual Perception Confirmation

Direct visual inspection of candidate `02-planner-dark.png` confirms perception through the following two specific, concrete candidate details:
1. **Top Floating Card Structure:** A floating dark charcoal container (`Utazástervezés`) with a top-right `✕` dismiss icon, encapsulating an inner dark input capsule with two stacked rows:
   - Row 1: `"Honnan? / Jelenlegi pozícióm"` with a soft rose crosshair/target icon and right chevron (`>`).
   - Row 2: `"Hová? / Hova szeretnél menni?"` with a soft rose map-pin icon and right chevron (`>`).
   - Vertically centered to the right of the stacked fields sits an isolated, unbordered vertical swap icon (`⇅`) floating directly against the card background.
2. **Bottom Floating Primary Action Button:** A full-width floating pill CTA button at the screen bottom in deep wine/maroon rose, containing an S-curve route icon and centered white text `"Úti cél keresése"`, hovering directly above the OS gesture navigation indicator bar.

---

## 2. Evaluation & Rating by Dimension

### A. Hierarchy & Spacing (Weight: 25%) — Score: 6.2 / 10

* **Observed Layout Structure:**  
  The interface is split into three disjointed zones: a floating top card covering the upper third, an unutilized dark vector map occupying the middle half, and an isolated floating CTA pill at the bottom edge.
* **Flaws & Deductions:**
  - **Detached Top Card vs. Integrated Surface (-1.5):** Unlike production transit apps (e.g., BudapestGO References 1 & 2) where the header either anchors cleanly to the top status bar or lives in a grounded bottom sheet, this card floats with awkward, narrow side and top gutters over the map.
  - **Unanchored Swap Button Placement (-1.2):** The swap icon (`⇅`) sits stranded in the negative space between the input container and the right edge of the card. It lacks a dedicated bounding box, circle, or hit-target highlight, making it feel like an accidental floating glyph rather than an intentional interactive button.
  - **Cramped Sub-Controls (-0.7):** The secondary options (`Indulás most ∨` and `Helyi hálózatok`) are placed immediately beneath the input block with minimal vertical breathing room and no chip/capsule boundary, appearing visually unstructured.
  - **Imbalanced Top Header Padding (-0.4):** The dismiss `✕` in the top right sits uncomfortably close to the top-right corner radius compared to the generous inset of the `"Utazástervezés"` title.

### B. Typography & Readability (Weight: 20%) — Score: 6.8 / 10

* **Observed Typographic Structure:**  
  Clean sans-serif typeface with clear size distinction between screen titles, field labels, and placeholder values.
* **Flaws & Deductions:**
  - **Low-Luminance Rose Text Contrast (-1.8):** The muted rose tint applied to the secondary controls (`Indulás most`, `Helyi hálózatok`) and their accompanying icons (clock, sliders) suffers from noticeably low contrast against the dark charcoal surface (`#15171B` / `#1E2024`). Under outdoor daylight or low screen brightness, this text approaches unreadability and fails standard legibility thresholds.
  - **Subdued Field Meta-Labels (-0.8):** The `"Honnan?"` and `"Hová?"` micro-labels are rendered in an extremely subdued dark grey that fades into the card background, forcing the user's eye to rely entirely on the primary line text.
  - **Sentence Capitalization Polish (-0.6):** The bottom CTA uses `"Úti cél keresése"` in white on dark rose, which has good legibility, but the pairing with the thin S-curve route glyph feels slightly undersized relative to the massive height of the pill.

### C. Navigation & Action Clarity (Weight: 20%) — Score: 5.8 / 10

* **Observed Interaction Model:**  
  The user enters the planner with an origin preset to current location and an empty destination prompt.
* **Flaws & Deductions:**
  - **Severe Action Duplication & Mental Model Confusion (-2.2):** The second input row displays `"Hová? / Hova szeretnél menni?"` complete with a right chevron (`>`), signaling that tapping it opens the destination search picker (as seen in BudapestGO Reference 3). Simultaneously, the bottom of the screen features a prominent, full-width CTA button labeled `"Úti cél keresése"` (Search destination). Having two competing controls for the identical primary task creates unnecessary friction: does tapping the bottom button do something different than tapping the field? In a standard transit flow, setting the destination is initiated by tapping the field directly, while a bottom CTA is reserved for "Plan route" or "Show itineraries" *after* both points are set.
  - **Ambiguous Filter Trigger Affordance (-1.0):** `"Helyi hálózatok"` possesses no visual affordance (no chevron, no switch toggle, no check indicator). A user cannot determine whether tapping it toggles local vs. regional transit, opens a filter sheet, or opens a modal dialog.
  - **Unbounded Touch Target for Swap Action (-1.0):** The `⇅` swap control provides no tactile surface or visual boundary, raising uncertainty about its active touch hit-box.

### D. Visual Coherence & Polish (Weight: 20%) — Score: 6.5 / 10

* **Observed Aesthetic & Styling:**  
  Dark mode aesthetic utilizing an explicitly selected rose accent system across icons, filters, and CTA.
* **Flaws & Deductions:**
  - **Clashing Rose Shades (-1.8):** The visual palette uses two disjointed versions of rose: a pale salmon/dusty rose on the input icons and text filters, and a heavy, dark wine/maroon (`#7A1C38`) on the bottom CTA. The two shades lack chromatic harmony; the dusty rose appears washed out against dark grey, while the bottom CTA appears dense and somber.
  - **Ungrounded Floating Surface Polish (-1.0):** Floating both the top card and the bottom button creates a "sandwich" over the map, leaving the map feeling like an accidental background wallpaper rather than an interactive component. In contrast, BudapestGO's clean sheet and full-bleed header establish clear spatial depth.
  - **Status Bar Bleed (-0.7):** The top status bar items (time, battery, wifi) sit over raw map tiles above the floating card, creating noisy visual overlap with the map's street labels.

### E. Transit Information Design (Weight: 15%) — Score: 5.5 / 10

* **Observed Transit Features:**  
  Map display showing transit stop locations around Clark Ádám tér and the Danube riverbank.
* **Flaws & Deductions:**
  - **Vacant Transit Utility in Initial State (-2.0):** An initial planner state is the most critical moment to accelerate user flow. In BudapestGO (Reference 2 & 3), this space provides immediate shortcuts to Home/Work, recent search history, favorite stops, or nearby departures. Leaving 60% of the screen as non-interactive map space forces unnecessary taps.
  - **Uninformative Generic Map Pins (-1.2):** The transit stops on the map are rendered as plain blue and yellow circles with white borders. They offer zero line numbers, vehicle glyphs (bus vs. tram vs. metro), or directional cues, unlike the rich badging (`17`, `59`, `61`, `M2`) standard in Budapest transit design.
  - **Map Label Occlusion (-0.8):** In the center of the visible map, multiple stop circles cluster directly over the city name label, resulting in `"Bud[●][●]est"`, showing lack of collision avoidance between transit layers and base map typography.
  - **Non-Standard Transit Terminology (-0.5):** The term `"Helyi hálózatok"` (Local networks) is ambiguous in Budapest public transit context. Commuters think in terms of agencies (BKK, MÁV, Volánbusz) or modes (Villamos, Metró, Busz), not "local networks".

---

## 3. Score Summary & Anchoring

| Evaluation Criterion | Weight | Score (/10) | Weighted Contribution |
| :--- | :---: | :---: | :---: |
| **Hierarchy & Spacing** | 25% | **6.2** | 1.550 |
| **Typography & Readability** | 20% | **6.8** | 1.360 |
| **Navigation & Action Clarity** | 20% | **5.8** | 1.160 |
| **Visual Coherence & Polish** | 20% | **6.5** | 1.300 |
| **Transit Information Design** | 15% | **5.5** | 0.825 |
| **Overall Weighted Score** | **100%** | **6.2** | **6.195 / 10** |

### Benchmark Anchoring Justification:
* **Anchor 5:** *Usable but visually unpolished.*
* **Anchor 7:** *Professionally consistent with notable shortcomings.*
* **Result (6.2):** Positioned between Anchor 5 and Anchor 7. While the candidate possesses a coherent dark-mode baseline, clean typography, and functioning input structure, it falls short of professional consistency due to the redundant destination CTA, uncontained swap icon, low-contrast rose secondary copy, uninformative map dots, and missing transit shortcuts/recents.

---

## 4. Highest-Impact Design Fixes (Tied to Visible Regions)

1. **Eliminate Action Duplication (Bottom CTA vs. Destination Field):**
   - *Visible Region:* Bottom floating button (`Úti cél keresése`) and top input row (`Hová? / Hova szeretnél menni?`).
   - *Fix:* Remove the bottom floating `"Úti cél keresése"` button on this initial screen. Tapping the `"Hová?"` field should directly transition to the search picker view. Reserve the bottom primary button strictly for `"Útvonaltervezés"` (Calculate route) once both origin and destination have valid selections, or remove it entirely in favor of automatic live itinerary calculation.
2. **Anchor the Planner as an Integrated Header or Sheet:**
   - *Visible Region:* Top floating card boundaries and status bar margin.
   - *Fix:* Extend the top container edge-to-edge with proper safe-area insets behind the status bar, or anchor the entire search interface into a bottom sheet that slides up over the map, eliminating awkward map gaps around the card perimeter.
3. **Encapsulate & Containerize the Swap Control (`⇅`):**
   - *Visible Region:* Space immediately to the right of the stacked inputs.
   - *Fix:* Place the swap icon inside a discrete circular button (`~36dp` diameter) with a subtle dark background and thin border, either vertically centered between the two rows or nested into the input container's right edge, establishing a clear touch target.
4. **Restructure Secondary Controls into Structured Pill Chips:**
   - *Visible Region:* Bottom row inside the top card (`Indulás most ∨` and `Helyi hálózatok`).
   - *Fix:* Wrap each control into an enclosed horizontal filter chip/pill with a subtle surface background (`#26282E`), increase the contrast of the label text from washed-out rose to off-white (`#EDEDED`), and reserve the rose accent strictly for active state highlights. Replace `"Helyi hálózatok"` with standard BKK terminology such as `"Járatok és járművek"` or an explicit filter icon.
5. **Populate the Mid-Screen Map Area with Recents & Quick Shortcuts:**
   - *Visible Region:* Empty map area between the top card and the bottom edge.
   - *Fix:* In the initial empty state, display a sheet or list beneath the inputs showing frequent destinations (`Otthon`, `Munkahely`) and recent search history, matching the standard BudapestGO exploration pattern.
6. **Refine Map Pin Clutter and Text Collision:**
   - *Visible Region:* Central Danube / Clark Ádám tér map view.
   - *Fix:* Apply label collision avoidance so transit markers do not obscure city/bridge names (`Budapest`, `Lánchíd utca`), and add vehicle mode glyphs or line numbers inside major stop markers.
