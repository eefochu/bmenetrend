# Comprehensive Project & Bug Handoff Document

> **Target Audience**: Next AI assistant / engineer continuing this session.  
> **Repository**: `stuffs/viabusz` (branch: `master`, commit: `7904014`)  
> **Current Version**: `versionCode = 18`, `versionName = "0.5.9"`

---

## 1. Project Overview & Architecture

### What is this app?
`viabusz` (package `hu.app.bmenetrend`) is an Android Kotlin Compose application designed for Budapest / Hungarian public transit navigation, GTFS timetable tracking, offline routing, and integration with the Hungarian State Railways (**MÁV / MÁV-START**) ticketing system.

### Key Technologies:
- **UI Framework**: Jetpack Compose (BOM `2025.05.01`), Material 3.
- **Architecture**: MVVM with Kotlin Coroutines (`StateFlow`).
- **HTTP / Networking**: OkHttp 4.12.0.
- **Image / Media Loading**: Coil Compose 2.7.0 (`coil-gif` for animated ticket validation badges).
- **Target / Compile SDK**: Android 36, minSdk 26.

---

## 2. The MÁV Module Architecture

The MÁV subsystem is located under `app/src/main/java/hu/app/bmenetrend/mav/` and `app/src/main/java/hu/app/bmenetrend/ui/mav/`:

1. **`MavApiClient.kt`**:
   - Communicates with two MÁV backends:
     - **IK_API_PROD** (`https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/...`): Modern JSON REST API. Provides `GetUserPersonalDatas`, `GetUserHPTDatas`, `GetPreviousPurchases`, `GetPreviousPurchaseDetails`.
     - **VIM API** (`https://jegy-a.mav.hu/VIM_API_PROD/api/UgyfelApi/...`): Legacy RPC JSON API. Provides `Bejelentkezes`, `GetJegykep`, `GetFelhasznaloAdat`.
   - Returns authentication sessions containing:
     - `token`: SAML 1.1 bearer XML assertion string (or Base64 thereof) used in the `UserTokenXml` header.
     - `vimToken`: Session token returned by VIM `Bejelentkezes` (used in JSON bodies for `GetJegykep`).
     - `uaid`: Unique Android client identifier (default: `2Juija1mabqr24Blkx1qkXxJ105j`).

2. **`MavModels.kt`**:
   - `MavSession`: User email, tokens, expiry, and `MavUser`.
   - `MavUser`: `email`, `firstName`, `lastName`, `regId`, `photoBase64` (the account profile picture).
   - `MavPassenger`: MÁV passenger profile with `id`, `name`, `birthDate`, `photoBase64` (the passenger selfie).
   - `MavPassHolder`: Passholder data with `id`, `hptId`, `photoBase64`, `idCardNumber`.
   - `MavOrder` & `MavTicketReceipt`: Active and past tickets/passes.
   - `MavTicketImage`: Aztec 2D barcode image in PNG Base64 format (specifically ignores PDF bills / certificates).

3. **`MavCacheStore.kt`**:
   - Persists data to disk (`context.filesDir/mav_tickets_cache.json` and `context.filesDir/mav_user_photo.b64`).
   - Caches active orders, past orders, passengers, pass holders, and ticket barcode images.

4. **`MavRepository.kt`**:
   - `MavSessionStore`: Saves and restores user session and `photo_base64` using `SharedPreferences` (`mav_session_prefs`) and `mav_user_photo.b64`.
   - `MavRepository`: Coordinates network sync via `syncTicketsAndPasses()` and deduplication via `deduplicateOrders()`.

5. **`MavViewModel.kt`**:
   - Exposes `session`, `ticketsState`, `selectedTab`, `barcodeData`, `isRefreshing`.
   - Orchestrates `login()`, `loadTickets()`, `refreshTickets()`, `showBarcode()`.

6. **`MavTicketsScreen.kt`**:
   - Top header (`MavHeader`): Shows user avatar (`photoBase64`), full name, email, offline cloud badge, refresh button, and logout.
   - Ticket tabs (`TabRow`): `Aktív` and `Korábbiak`.
   - Ticket cards (`OrderTicketCard` / `ReceiptItemView`): Shows order number, purchase date, ticket title, discount badge (`[BÉRLET]` / `[JEGY]`), and the "Jegy / Bérlet felmutatása" button.
   - Bottom sheet (`TicketBarcodeSheet`): Displays Aztec barcode, passenger photo, passenger name, birth date, and animated `ticket_anim.gif`.

---

## 3. The Bug Description & Exact User Symptoms

### User's Report:
> *"I open the app. I login with my credentials. It loads my data including profile picture, passes / tickets, passenger details, selfies it all works fine, but the moment I turn off Wi-Fi and hit the reload button it all disappears into the void. In logcat I see no clues (at least with the release build)."*
> *"If I close the app, turn off all networks and try to view my offline synced passes/tickets, nothing shows. The only thing which is visible of our account is the Name and email address."*

### Breakdown of the Symptoms:
1. **Online (Happy Path)**:
   - User logs in with `ilikehacking.py@gmail.com`.
   - Account profile picture loads in `MavHeader`.
   - Active pass (`Kedvezményes megyebérlet - Pest megye`, Order `1023744049`) renders as a single card.
   - Clicking "Jegy / Bérlet felmutatása" renders the Aztec barcode, passenger selfie photo (Kókai Balázs), birth date, and the official `ticket_anim.gif`.
2. **Offline (Failure Path)**:
   - When Wi-Fi/cellular network is turned off (or the app is restarted in airplane mode) and the user hits the reload button or opens the tickets tab:
     - **Profile picture disappears**: Only the fallback icon `Icons.Rounded.AccountCircle` shows.
     - **Name and email remain**: "Kókai Balázs" and "ilikehacking.py@gmail.com" are shown in `MavHeader`.
     - **Passes / tickets disappear**: The ticket list becomes empty (or errors out / disappears), showing no active passes or tickets.

---

## 4. Test Credentials & Account Data

The user provided the following test account:
- **Email**: `ilikehacking.py@gmail.com`
- **Password**: `-2HeLLoworLdcaT1,`

### Verified Server Data for this Account:
- **Account User**: `Kókai Balázs` (Reg ID: `7263595`)
- **Account Profile Picture (`IK_PROFIL`)**: HPT item ID `7270388`, size 136,616 chars base64 (JPEG format, begins with `/9j/4AAQSkZJRg...`).
- **Passenger Profile (`MENTETT_UTAS`)**: `Kókai Balázs`, birth date `1990.01.01` (`631148400000L`), selfie photo HPT ID `7265084`, size 109,864 chars base64.
- **Passholder Record (`BERLETTOK`)**: ID `7269605`, size 93,508 chars base64.
- **Active Pass**:
  - Order ID: `1023744049`
  - Name: `Kedvezményes megyebérlet - Pest megye` (OrszagMegyeBerlet, `isPass = true`)
  - Ticket Serial: `5594003G000448895`
  - Valid: `2026-11-06` to `2026-12-06`
  - Barcode Image: Aztec barcode PNG Base64 (size 114,632 chars).

---

## 5. What Was Attempted in Recent Commits

### 1. Multi-Key Cache Indexing in `MavCacheStore.kt`:
- **Change**: Barcodes and pass holders are saved and indexed under multiple keys:
  - Exact composite receipt ID (`ODAUTI_AJANLAT#CSCSAZON#...#1023744049`)
  - Trailing order ID (`1023744049`)
  - Ticket serial number (`5594003G000448895`)
  - Passholder ID (`7269605`)
- **Commit**: `f136475`

### 2. Elimination of `renameTo()` Cache Wiping:
- **Diagnosis**: `writeRoot()` previously wrote to `mav_tickets_cache.json.tmp` and called `tempFile.renameTo(cacheFile)`. If `renameTo()` returned false (a known issue on Android POSIX filesystems when destination exists), the fallback executed `cacheFile.delete()`.
- **Change**: Replaced with direct `cacheFile.writeText(root.toString(), Charsets.UTF_8)` to prevent cache file deletion.
- **Commit**: `7904014`

### 3. Dedicated User Photo File & Synchronous Commit:
- **Diagnosis**: The account profile picture base64 string is 136 KB. Android `SharedPreferences` with `apply()` (asynchronous queued write) struggled to persist large XML strings when closed quickly.
- **Change**: Saved `photoBase64` to `context.filesDir/mav_user_photo.b64`, read from it in `getStoredSession()`, and switched `prefs.edit()...apply()` to `.commit()`.
- **Commit**: `7904014`

---

## 6. Crucial Insights & Suspected Root Causes for the Next Assistant

Despite the above commits, the user reported that turning off Wi-Fi and hitting reload still makes everything disappear into the void. Here are the exact areas that the next session should inspect:

### Suspect A: `loadTickets(forceRefresh = true)` Flow in `MavViewModel.kt`
Look at `loadTickets()` in `MavViewModel.kt`:
```kotlin
fun loadTickets(forceRefresh: Boolean = false) {
    loadTicketsJob?.cancel()
    loadTicketsJob = viewModelScope.launch {
        if (forceRefresh) {
            _isRefreshing.value = true
        } else if (_ticketsState.value !is TicketsUiState.Success) {
            _ticketsState.value = TicketsUiState.Loading
        }

        // Step 1: Immediately emit cached tickets if available
        val cached = repository.getCachedOrders()
        val lastSync = repository.getLastSyncTime()
        if (cached != null && (cached.first.isNotEmpty() || cached.second.isNotEmpty())) {
            ...
            _ticketsState.value = TicketsUiState.Success(
                activeOrders = cached.first,
                pastOrders = filteredPast,
                isOffline = false,
                lastSyncTime = lastSync
            )
        }

        // Step 2: Sync with online server
        try {
            val (active, past) = repository.syncTicketsAndPasses()
            _ticketsState.value = TicketsUiState.Success(
                activeOrders = active,
                pastOrders = past,
                isOffline = false,
                lastSyncTime = repository.getLastSyncTime()
            )
        } catch (e: Exception) {
            val fallbackCache = repository.getCachedOrders()
            if (fallbackCache != null && (fallbackCache.first.isNotEmpty() || fallbackCache.second.isNotEmpty())) {
                ...
                _ticketsState.value = TicketsUiState.Success(
                    activeOrders = fallbackCache.first,
                    pastOrders = filteredPast,
                    isOffline = true,
                    lastSyncTime = repository.getLastSyncTime()
                )
            } else {
                _ticketsState.value = TicketsUiState.Error(e.message ?: "Nem sikerült a jegyek betöltése")
            }
        } finally {
            _isRefreshing.value = false
        }
    }
}
```
**CRITICAL QUESTIONS**:
1. When `syncTicketsAndPasses()` throws an exception offline (e.g. `java.net.UnknownHostException`), why is `fallbackCache` null or empty?
2. Did `saveOrders` actually save the orders to `cacheStore` during the initial online sync, or was `active` empty?
3. In `syncTicketsAndPasses()`, look at line 280:
   ```kotlin
   // 3. Fetch active orders (valid tickets and passes)
   val active = deduplicateOrders(apiClient.getOrders(currentSession, validOnly = true))
   ```
   What if `active` was filtered out or `saveOrders` in `syncTicketsAndPasses()` had `active` as empty?

### Suspect B: Why did the Profile Picture in `MavHeader` disappear on reload?
Look at `MavHeader`:
```kotlin
MavHeader(
    user = session?.user,
    email = session?.email.orEmpty(),
    ...
)
```
In `MavTicketsScreen.kt`:
`session` comes from `viewModel.session` (`repository.session`).
In `MavRepository`:
Where is `_session` updated?
Only in:
- `login()`
- `refreshUserData()`
- `syncTicketsAndPasses()`:
  ```kotlin
  // 1. Refresh user data (name and profile picture)
  runCatching {
      val user = apiClient.getUserData(currentSession)
      val updatedSession = currentSession.copy(user = user)
      sessionStore.saveSession(updatedSession)
      cacheStore.saveUser(user)
      _session.value = updatedSession
  }
  ```
**WAIT!** When `syncTicketsAndPasses()` runs offline:
`apiClient.getUserData(currentSession)` throws `UnknownHostException` (caught by `runCatching`).
So `_session.value` is NOT updated there.
Then why would `session.user` lose its profile picture when Wi-Fi is turned off?
Could `MavRepository` have been re-instantiated?
Look at `MainActivity.kt`:
`val mav: MavViewModel = viewModel()`
When the activity recreates or process restarts offline:
`_session` initializes with:
```kotlin
sessionStore.getStoredSession(cacheStore.getUser()) ?: cacheStore.getUser()?.let { ... }
```
If `sessionStore.getStoredSession(cacheStore.getUser())` returned a `MavUser` where `photoBase64` was null, `_session.value` has `photoBase64 = null`.

### Suspect C: Check `cacheStore.getOrders()` / `saveOrders()` Serialization
Look closely at `MavCacheStore.kt`:
```kotlin
private fun orderToJson(order: MavOrder): JSONObject
private fun jsonToOrder(obj: JSONObject): MavOrder?
```
Notice:
When `jsonToOrder` parses:
```kotlin
val orderId = obj.optString("orderId", "")
if (orderId.isBlank()) return null
```
And in `jsonToReceipt`:
```kotlin
val receiptId = obj.optString("receiptId", "")
if (receiptId.isBlank()) return null
```
And in `jsonToItem`:
```kotlin
val id = obj.optString("id", "")
val name = obj.optString("name", "")
if (id.isBlank() && name.isBlank()) return null
```
Check if any of these fields (`orderId`, `receiptId`) could be empty when coming from `apiClient.getOrders()`, causing `jsonToOrder` or `jsonToReceipt` to return `null`, dropping the order from `cached.first`!

Also check in `MavViewModel.kt`:
```kotlin
val activeOrderIds = fallbackCache.first.map { it.orderId }.toSet()
val activeReceiptIds = fallbackCache.first.flatMap { it.receipts }.map { it.receiptId }.toSet()
val filteredPast = fallbackCache.second.filter { p ->
    p.orderId !in activeOrderIds &&
    p.receipts.none { r -> r.receiptId in activeReceiptIds } &&
    !p.isValidNow
}
```
Notice: `state.activeOrders` is set to `fallbackCache.first`.
If `fallbackCache.first` is empty, then `orders.isEmpty()` is true!
In `MavTicketsScreen.kt`:
```kotlin
val orders = if (selectedTab == MavTab.ACTIVE) state.activeOrders else state.pastOrders
if (orders.isEmpty()) {
    ...
    // Displays: "Nincsenek aktív jegyek vagy bérletek"
}
```
Is the UI showing "Nincsenek aktív jegyek vagy bérletek", or is it showing `TicketsUiState.Error`?
If `syncTicketsAndPasses()` failed and `fallbackCache.first` was empty, `loadTickets()` emitted:
`_ticketsState.value = TicketsUiState.Error(e.message ?: "Nem sikerült a jegyek betöltése")`
Which renders the red error card:
`"Unable to resolve host "jegy-a.mav.hu": No address associated with hostname"`!

---

## 7. Recommended Action Plan for the Next AI / Session

1. **Add explicit logging / diagnostics** to `MavViewModel.loadTickets()` and `MavCacheStore`:
   - Log the exact emitted state in `loadTickets()`:
     - `Log.d("MavDebug", "Step 1 cached: ${cached?.first?.size} active, ${cached?.second?.size} past")`
     - `Log.d("MavDebug", "Step 2 caught exception: ${e::class.java.simpleName}: ${e.message}")`
     - `Log.d("MavDebug", "Fallback cache: ${fallbackCache?.first?.size} active")`
     - `Log.d("MavDebug", "Emitted state: isOffline=$isOffline, activeOrders count=${state.activeOrders.size}")`
2. **Verify what `cacheStore.getOrders()` actually returns**:
   - Check if `apiClient.getOrders(..., validOnly = true)` produces receipts with valid `receiptId` that survive JSON round-trip.
3. **Verify profile photo persistence**:
   - Check why `user?.photoBase64` is null in `_session` on offline launch or reload. Log `photoFile.exists()` and `photoFile.length()`.
4. **Test offline flow end-to-end**:
   - Populate cache, disconnect network in test / emulator, call `loadTickets()`, and observe state transitions.
