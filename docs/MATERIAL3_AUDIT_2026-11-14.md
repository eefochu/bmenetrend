# Material 3 / Expressive UI audit — 2026-11-14

## Scope and intent

BMenetrend's shared typography, route badges, favourites, navigation, planner, search and settings components. Phone-first, foundational Expressive styling, with dynamic colour and the existing navigation preserved. This is a source review plus targeted rendering/interaction checks, not an accessibility certification of every screen.

## Findings and changes

| Area | Finding | Result |
| --- | --- | --- |
| Typography | Body text used Google Sans Flex, but headings and explicit route labels used Open Sans. | Google Sans now supplies normal, medium, semibold and bold across the shared typography; route/platform labels retain Open Sans as requested. Fonts are bundled, with Hungarian accented characters and tabular-number features retained. |
| Refresh | Favourites had a labelled refresh button but no downward gesture. | Material 3 `PullToRefreshBox` wraps the existing lazy list and calls the same refresh action. Existing loading state controls its indicator and prevents another request during refresh. Empty lists support the gesture too. |
| Badges | Feed labels were custom rectangular surfaces; saved-stop count was absent. | The favourite count badge was subsequently removed at the user’s request. Feed-source badges were subsequently removed throughout the UI at the user’s request. |
| Train labels | Automatic contrast selected dark text on light blue S services and green G services. | Plain white labels requested by the user have no outline; original service colours remain unchanged. Other route badges still use automatic contrast. |
| Shape | Existing expressive controls mixed tight and larger corner radii. | Outer grouped-list corners use 28dp, inner group corners 8dp; shared panels retain their 20dp token and buttons retain their distinct pressed shape. Route badges retain their original 52×28dp compact cells, with an 18%-of-height corner radius and bold Open Sans labels. Text-heavy containers retain rectangular outlines. |
| Hierarchy | Shared screen introductions used the same weight as many secondary headings. | Intro headings use the emphasized headline role. Main actions retain text labels and their filled-primary emphasis. |
| Touch targets | Favourite overflow buttons explicitly occupied 40dp. | Raised to 48dp. Passive service badges remain compact; they are labels, not separate touch targets. |
| Navigation / colour | Standard navigation, dynamic colours, paired semantic roles and Expressive motion scheme were already present. | Retained. Custom service colours and supplied metro/HÉV logos remain identity exceptions. Map rendering and network policies are outside this change. |

## Contrast exception

White text directly against S blue (`#00AFEF`, about 2.5:1) and G green (`#A9CD45`, about 1.8:1) does not meet the normal-text 4.5:1 target. The requested plain white fill is kept without any outline. This is an explicit visual exception, not a claim of full text-contrast compliance. Yellow Z labels and other services retain automatically selected contrasting text. Colour is accompanied by a readable service name/number.

## Remaining review boundaries

- Existing custom accent palettes, platform badges and every map overlay have not been exhaustively measured for contrast.
- Existing menus, icon controls and navigation retain their semantics; every app screen has not been manually tested with TalkBack.
- Compose-managed motion remains governed by the platform animation scale. A manual reduced-motion walkthrough and full tablet/foldable walkthrough remain outside this pass.
- Fixed-width service grids can shrink long labels; large-font checks cover navigation, not every possible service identifier.

## References

- [Material 3 for Compose](https://m3.material.io/develop/android/jetpack-compose)
- [Android Material 3 theming](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Material pull-to-refresh](https://developer.android.com/develop/ui/compose/components/pull-to-refresh)
- [Expressive corner scale](https://m3.material.io/styles/shape/corner-radius-scale)
- [Google Sans upstream](https://github.com/googlefonts/googlesans) and [Google Fonts distribution](https://github.com/google/fonts/tree/main/ofl/googlesans)

Google Sans static instances were generated at optical size 18, grade 0, weights 400/500/600/700, and subset to Latin/Latin Extended, punctuation and currency characters. The accompanying OFL is bundled at `app/src/main/assets/fonts/GoogleSans-OFL.txt`. Original upstream font files and licences for other existing fonts are preserved.

## Validation

- Full application suite before the final removal of the badge outline: 1,089 tests, 0 failures/errors, 5 skipped.
- After switching to plain white labels: all 19 selected favourites, typography/colour, navigation and light/dark large-text rendering tests passed. Pull gestures cover loaded, empty and already-refreshing lists.
- Development and release APK assembly passed. Release universal and ARM64 signatures verified, version 0.7.0 (21), using the repository's existing debug signing configuration.
- Development lint: 0 errors, 190 warnings. Fixed one existing Google-map fallback string lookup to use configuration-aware Compose resources.
- Reviewed off-screen light/dark (1.6× text) renders and the actual Android 16 emulator favourites page. Installed the final development build and exercised the downward gesture. The first device connection failed; reopening after the emulator recovered succeeded.
- Screenshot files: `app/build/reports/ui/expressive-favorites-light.png` and `app/build/reports/ui/expressive-favorites-dark.png`. These are generated verification artifacts, not tracked product assets.

The earlier station/Volán/G10 edits already present in the checkout were preserved. No commits or publishing were performed.

### Badge reference revision

Route badges now use the existing bundled Open Sans bold font, a 2:1 compact size (64×32dp) and an 18%-of-height corner radius to match the supplied S10 reference. Label size is 22sp, with autosizing for fixed grid cells and long service names. General UI typography remains Google Sans. All 29 selected favourites, appearance, navigation/rendering tests and development APK assembly passed after this revision. The previously built release APK predates this final font/shape revision.

The badge size was subsequently restored to the original 52×28dp grid cells and 15sp labels at the user’s request. Open Sans, plain white S/G labels and the reference corner treatment remain.

Final badge label adjustment: 17sp Open Sans bold (20sp line height), with the original 52×28dp grid cells retained. Fixed-width labels still autosize for longer service names. Favourites and light/dark rendering checks and development assembly passed.

Feed-source badges (BKK API, EMMA and mixed-source labels) were removed from favourites, nearby stops, search, planning, line details and vehicle details. Feed selection, fallback, timestamps and stale/unavailable states remain in the data layer.

Favourites pull-to-refresh now uses Material 3 Expressive `PullToRefreshDefaults.LoadingIndicator` (the contained morphing-shapes variant), with the shared pull state and existing loading flag. The favourite count and duplicate wavy/header loading indicators were removed. The refresh button remains available as a non-gesture action.
