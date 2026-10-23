# HÉV integration

H5, H6, H7, H8 and H9 use the same public MÁVPlusz OTP GraphQL endpoint as the existing live feeds. No separate BKK API or API key is used. The app requests `SUBURBAN_RAILWAY` vehicle positions and accepts only identified HÉV routes from the `1:` source namespace; general rail and foreign S-Bahn routes remain excluded.

## Source details

- Source route IDs are retained, for example `1:V3922_109` for H5; app route IDs are `hev:1:V3922_109`.
- HÉV route `shortName` contains MNR font markup. The passenger line number comes from `longName`, such as `H5`. Source variants such as H8A/H8E retain their suffix.
- Live vehicles preserve source GPS coordinates, next-stop references and observation timestamps. The existing age/future-time checks apply. Stops and trip/pattern geometry come from the source; no vehicle motion or route shape is fabricated.
- HÉV stop IDs also use `1:`. The static index accepts the verified `1:0036…` HÉV stop range in SUBURBAN_RAILWAY mode, H-suffixed station names, or stops serving an identified HÉV route. General rail and foreign suburban stations are excluded. Some source HÉV platforms have a null vehicle mode, so station identity and serving routes also matter.
- Existing raw catalogue caches are reparsed to include HÉV without a destructive cache reset. Line browsing, direction and boarding selection, source-marked realtime departures, route focus and both-direction geometry reuse the existing flow. HÉV remains available in the local-network setting as well as nationwide mode.

## Emblems

The user-supplied H5–H9 images are bundled as app resources. Badges, timeline markers and map markers display only the numbered circle, without the H or M symbol or a duplicate line label. H8 and H9 select the corresponding numeric panel from the combined source emblem; each remains visually distinct. Variant suffixes stay visible in line badges. Low-zoom vehicle dots use each line's emblem colour. HÉV station dots use green.

## Verification

A live probe on 2026-10-23 returned 21 HÉV vehicle reports, H5 patterns in both directions with source geometry, a 17-stop actual H5 trip and a 12-departure Aquincum H board. Counts vary with service and reporting availability. All supported HÉV route patterns supplied 82 distinct source platform IDs, including source replacement/variant stops.

Captured public source responses in `app/src/test/resources/hev/` verify the actual namespace, line-label normalization, mode isolation, GPS preservation, route directions, trip geometry and departure boards without network access during unit tests. Source timetable times are shown as realtime only when the source flags them accordingly.

MÁV describes HÉV tracking through MÁVPlusz in its [passenger information](https://www.mavcsoport.hu/mav-szemelyszallitas/gyik/utazastervezes-menetrendek-forgalmi-valtozasok).
