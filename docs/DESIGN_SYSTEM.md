# BMenetrend interface

BMenetrend is a Hungarian, map-first commuter tool spanning local buses, regional services and urban/national rail. Its character comes from readable transit signage: routes, platforms, directions and departure times, rather than decorative dashboards.

## Direction

Forest #205C48, mint #9DDFC3, pale green paper #F3F7F5, ink #172D26, muted green #4C6257, and white #FFFFFF. Dark mode uses purpose-selected green-black surfaces and brighter mint controls. Existing system/custom accent preferences remain supported. Transport badges keep their established operator/service colours, train category deduplication and compact proportions.

Open Sans is retained as the existing branded family. Bold, slightly tight headlines orient the traveller; semibold destinations and tabular numerals make timetables easy to scan. Body copy remains regular and readable. Shared 4/8/12/16/20/24-dp spacing, 48-dp touch targets, 16-dp control corners, 24-dp grouped panels and 28-dp sheet corners establish hierarchy.

## Layout

Map: search control above the map, contextual board below it. Sheet headings get full-width names, with actions on a separate line at compact widths. Departures are aligned rows with route, destination, and a consistent time block. No additional modal flows.

Planner: page introduction → connected origin/destination group → departure selection → strong primary action → ranked itineraries. The first route gets emphasis; alternatives stay quieter. Journey details use the existing continuous route timeline.

Favourites: station sections containing service badges, with a direct departures action. Settings: compact colour choices, contextual custom colour editor, grouped network preferences. Secondary screens use the same header, tone and spacing.

```
Map                         Planner / favourites
[ search             gear ] [ identity   title       ]
[                          ] [ content group         ]
[      transit map         ] [ controls / rows       ]
[  stop name               ] [ results / station     ]
[  provider     actions    ] [                       ]
[  route → destination time] [                       ]
[ map   planning   saved   ] [ map   planning   saved ]
```

Wide screens use a navigation rail and bounded reading width. Motion is limited to selection feedback and user-opened expansions; Compose animation APIs honour the system duration scale. Native maps and whole screens are not duplicated for decorative transitions. Existing stable Material 3 1.3.2 APIs implement Expressive principles without adding an alpha dependency.

The UI/UX database's two design-system searches returned web-marketing layouts, which do not fit this product. These decisions instead follow the app's existing brand, native accessibility guidance and its actual commuting workflows.

## Departure board behaviour

A shared timetable row is used by stop and nearby boards only. Times occupy a distinct tonal column; destinations retain a consistent left edge and semibold hierarchy. At large text sizes or narrow window widths, badge/time and destination/details become separate rows. Late services have an explicit `+N perc` label, and cancelled services have `Kimarad`, a cancellation icon, a struck scheduled time, and a disabled boarding action. Cancellation rows are retained only when the feed explicitly reports `CANCELED`; no cancellation is inferred from absent GPS data. Non-pickup and out-of-window services remain excluded. Service colours are unchanged; bright cyan/lime badge foregrounds use dark text to meet small-text contrast.

## Verification

- JDK 21: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDevelopment`, `:app:assembleDebug`, `:app:assembleRelease` pass. 301 tests, zero failures; lint has zero errors and 90 warnings (including existing dependency, resource and API suggestions).
- New tests cover labelled/non-interactive cancellation rows, explicit delay text, large-text destination/platform visibility, accessible large-text navigation between all destinations, and rail-label contrast. The parser test verifies retained cancellations use scheduled time while non-pickups and out-of-window rows remain excluded. Existing route, favourite, platform scope, planner rendering and colour preference tests pass.
- API 36 arm64 emulator: map/search → Biatorbágy rail departure board → favourite station → favourite tab; light/dark themes; settings colour selection → About → Updates → back; planner departure picker. Actual source data populated the board and favourite service badges.
- UI inspected at the stock phone size, 320-dp width with 1.6× text, and a 1280-dp landscape window. Large text reflows board content and uses a labelled selected navigation destination with named icon tabs. Wide layouts use a rail and bounded reading width. The sheet handle has a 48-dp touch target and an explicit expand/collapse accessibility label.
- Animator duration scale zero was used during the large-text navigation check; no animation is needed to complete an action. Settings were restored afterward. This was emulator verification, not a physical-device accessibility certification or a new performance benchmark.

Material 3 Expressive describes the visual direction here. The implementation uses the Compose and Material 3 1.3.2 APIs already configured by the project, with custom theme tokens and composables; the dependency versions remain unchanged.
