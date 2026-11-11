# Google Maps in BMenetrend

The Settings map style selector offers Google Maps when a local Android SDK key is configured.
Street and Satellite continue to use MapLibre. Offline mode uses the bundled MapLibre map.
Vehicles, stops, predictions, and routing continue to use the existing transit services.

## Local setup

Enable **Maps SDK for Android** in a billing-enabled Google Cloud project. Create an API key,
restrict it to **Android apps**, and add `hu.app.bmenetrend` with the SHA-1 of the certificate
signing the APK. Add `hu.app.bmenetrend.dev` separately if you want development builds.
Restrict the key's APIs to **Maps SDK for Android**.

Store the key in the ignored root `secrets.properties` file:

```properties
MAPS_API_KEY=YOUR_ANDROID_MAPS_KEY
```

Alternatively set `GOOGLE_MAPS_API_KEY` in the build environment. The key is inserted into the
Android manifest; Android API keys are distributed with the APK, so package/certificate/API
restrictions are required. Never commit the local secrets file or log the key. Play App Signing
uses the Play app-signing certificate; add that certificate's SHA-1 before publishing via Play.

Build the optimized APK with `./gradlew :app:assembleDebug`, install it, then choose
**Settings → Map style → Google Maps**. Google Play services must be available on the device.

## No-charge map display

As checked on 2026-11-11, Google's Maps SDK SKU has unlimited no-charge usage. The implementation
uses a standard `GoogleMapOptions` with **no map ID**, classic markers, and local polylines.
It does not use Advanced Markers, cloud styling, Street View, Places, Routes, or Map Tiles APIs.
Using a map ID would move map loads into the billable Dynamic Maps SKU; do not add one to this
backend without reviewing billing and obtaining the owner's agreement.

- https://developers.google.com/maps/billing-and-pricing/pricing
- https://developers.google.com/maps/documentation/android-sdk/map-ids/get-map-id

## Performance and verification

Only a retained region around the viewport enters the Google marker pool. Existing marker
identities are reconciled in place and focus changes hide/show vehicles without clearing the pool.
Marker bitmap preparation and platform grouping run off the main thread. Camera/source listeners
and MapView lifecycle callbacks are cleaned up when switching backends or leaving the screen.
Google attribution stays visible through the SDK's own padded map controls.

Unit tests cover map selection persistence, offline fallback, scene retention, and focus matching.
SDK rendering and key authorization must also be verified on a Google Play-enabled device;
JVM payload tests alone cannot establish that map tiles load.
