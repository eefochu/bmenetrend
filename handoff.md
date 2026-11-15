# Comprehensive Project & Ticket/Pass Fetching Handoff Document

> **Target Audience**: Next AI assistant / engineer continuing this session.  
> **Repository**: `stuffs/viabusz`  
> **Branch**: `fix/ticket-fetching`  
> **Subsystem**: MÁV ticketing integration in `:mav-tickets` module (`hu.app.bmenetrend.mav`).  
> **Local Reference Path**: `/home/msstore/stuffs/viabusz`  

---

## 0. RESOLVED (2026-11-14): cause and fix of the barcode delay

Verified against the live MÁV API with both test accounts (email/password and Google OAuth), and on device by the user.
Sections 4–5 below are the earlier hypotheses; several turned out to be wrong.

**Cause 1: the barcode could not be resolved at all (regression from `5cbd80a`).**
`ProfileApi/GetPreviousPurchaseDetails` only accepts the *full* composite purchase id
(`ODAUTI_AJANLAT#CSCSAZON#…#0#1023744049`). Any part of it (`1023744049`, the GUID, `0`) is rejected with
HTTP 500 `Érvénytelen bemenet {VasarlasAzonosito::…}`, and the `{"requestModel": {...}}` body is always HTTP 400.
`5cbd80a` started sending only the parts, so `bizonylatTechnikaiAzonosito` was never found and `GetJegykep`
was tried with ids it rejects (`Nem regisztrált bizonylat` / `OSB-382505`).

**Cause 2: the 30–60 s wait.** The first `GetJegykep` for a ticket image MÁV hasn't prepared yet returns the
*notice* `[0/M] Kérjük, próbálja meg ismét fél perc múlva.`, but the image is ready ~1–2 s later
(observed: refusal, then success 2.3 s later). The app waited 32 s per attempt because of the literal text.

**Facts that disprove earlier hypotheses:**
- Google `SocialLogin` returns a SAML `userTokenXml` (top level) next to `refreshToken`, not only a JWT.
- Wrong ids passed to `GetJegykep` do **not** trigger a lockout; they just fail validation.
- No "registration" step is needed: with the right `bizonylatTechnikaiAzonosito`, `GetJegykep` works immediately after a fresh login.
- Back-to-back `GetJegykep` calls with the same token are fine.

**Fix:** full id first for `GetPreviousPurchaseDetails`; on the `fél perc` notice retry the same document after
1 s, 1 s, 1.5 s, 2 s, 3 s… (~30 s cap, `MavApiClient.DEFAULT_JEGYKEP_PREPARE_RETRY_DELAYS_MS`); the login prefetch
and the ticket view share one in-flight request in a repository-owned scope.
Live check: `MavLiveApiTest` (skipped unless `MAV_LIVE_EMAIL`/`MAV_LIVE_PASSWORD` or `MAV_LIVE_GOOGLE_SESSION` are set).

---

## 1. Executive Summary & The Problem

### The User Issue
When performing a clean install / reinstall of the app (or clearing application storage) and logging into a MÁV account via **either**:
1. **Google OAuth2** (`SocialLoginApi/SocialLogin`), OR
2. **Standard MÁV account** (email + password via `ProfileApi/GetUserToken`),

the application fetches the ticket/pass metadata (e.g. the pass card appears in the list), but **fails to fetch the Aztec barcode image promptly**. Instead:
- The user is forced to wait an unpredictable duration—typically **30 to 60+ seconds**—before the barcode is retrieved and displayed.
- The UI displays a progress indicator with the notice:
  > *"A jegykép még feldolgozás alatt van a MÁV rendszerében... Kérjük várj, a rendszer automatikusan újrapróbálja."*
- The barcode should ideally display **immediately or within 1–2 seconds** after opening the ticket.

### Test Credentials Provided by User
The user has provided a real test account with an active monthly county pass to reproduce and debug:
- **Google Account Email**: `bazsi.kokai@gmail.com`
- **Google Account Password**: `-2HeLLoworLdcaT1#`
- **Active Pass on Account**:
  - **Product**: `Kedvezményes megyebérlet - Pest megye` (Discounted Pest County Pass)
  - **Purchase / Order ID**: `1023744049` (in raw API responses, composite routing string is `ODAUTI_AJANLAT#CSCSAZON#CSAZON#1023744049`)
  - **Ticket Serial Number (`jegySorszam`)**: `5594003G000448895`
  - **Technical Document ID (`bizonylatTechnikaiAzonosito`)**: `1779935735`

---

## 2. Architecture & Request Flow

### Modules & Relevant Source Files
- [MavApiClient.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavApiClient.kt): HTTP client making requests to IK API and VIM API.
- [MavRepository.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavRepository.kt): Repository handling synchronization, caching, session persistence, and deferred in-flight barcode fetching.
- [MavViewModel.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavViewModel.kt): StateFlow management for UI (`ticketsState`, `barcodeData`, `showBarcode()`, retry timers).
- [MavTicketsScreen.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavTicketsScreen.kt): Jetpack Compose screens for tickets list, barcode bottom sheet (`TicketBarcodeSheet`), and Google OAuth WebView dialog.
- [MavModels.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavModels.kt): Data structures (`MavSession`, `MavUser`, `MavOrder`, `MavTicketReceipt`, `MavTicketImage`, etc.).
- [MavCacheStore.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavCacheStore.kt): Local JSON cache on disk (`filesDir/mav_tickets_cache.json`).

### The Two MÁV Backend APIs
The app integrates with two separate MÁV backends:

```
+-----------------------------------------------------------------------------------+
|                                  VIABUSZ APP                                      |
+-----------------------------------------------------------------------------------+
          |                                                       |
          | (1) REST / JSON                                       | (2) WCF REST / RPC
          v                                                       v
+--------------------------------------+  +-----------------------------------------+
|        IK_API_PROD (Modern)          |  |            VIM API (Legacy)             |
|   https://jegy-a.mav.hu/IK_API_PROD/ |  |    https://vim.mav-start.hu/VIM/PR/...  |
+--------------------------------------+  +-----------------------------------------+
| - ProfileApi/GetUserToken (Email/Pw) |  | - Bejelentkezes (Legacy email auth)     |
| - SocialLoginApi/SocialLogin (OAuth) |  | - GetJegykep (Generates Aztec barcode)  |
| - ProfileApi/GetUserPersonalDatas    |  | - BerletTokLekeres (Passholder tokens)  |
| - ProfileApi/GetUserHPTDatas         |  | - MegrendelesKereses (Order queries)    |
| - ProfileApi/GetPreviousPurchases    |  +-----------------------------------------+
| - ProfileApi/GetPreviousPurchaseDtl  |
| - OrderAndPaymentApi/...             |
+--------------------------------------+
```

1. **`IK_API_PROD` (`https://jegy-a.mav.hu/IK_API_PROD/api/`)**:
   - `ProfileApi/GetUserToken`: Returns a SAML 1.1 XML assertion (`userTokenXml`).
   - `SocialLoginApi/SocialLogin`: Accepts Google OAuth auth code, returns JSON with JWT bearer `token` and `refreshToken`.
   - `ProfileApi/GetPreviousPurchases`: Lists orders with active/inactive tickets.
   - `ProfileApi/GetPreviousPurchaseDetails`: Returns detailed items for an order, including ticket serial (`jegySorszam`), valid dates, passenger name, and `bizonylatTechnikaiAzonosito`.
2. **`VIM API` (`https://vim.mav-start.hu/VIM/PR/20250529/MobileServiceS.svc/rest/`)**:
   - `GetJegykep`: Windows Communication Foundation (WCF) RPC endpoint. Expects a JSON payload:
     ```json
     {
       "MegrendelesSzam": "<CandidateID>",
       "Token": "<SessionToken>",
       "UserTokenXml": "<SAML XML or omitted>"
     }
     ```
     Returns `{ "Bizonylatok": [ { "Jegykep": "<Base64 PNG Aztec barcode>" } ], "Uzenetek": [ ... ] }`.
   - **Anti-Hammering Mechanism**: If `GetJegykep` is called with invalid parameters, rapid successive calls, or an unpropagated token, it returns:
     `"Kérjük, próbálja meg ismét fél perc múlva."` (Please try again in half a minute), triggering a 30-second lockout.

---

## 3. History of Fix Attempts & Outcomes

### Commit `4f983d5`
- **Attempt**: Tried extracting a SAML XML token from Google `SocialLogin` response (`userData`).
- **Outcome**: Failed because Google OAuth exclusively returns a standard JWT bearer string, not SAML XML.

### Commit `03534f6` & `f807626`
- **Attempt**: Sanitized display strings in the UI. Formatted raw composite routing strings (e.g. `ODAUTI_AJANLAT#CSCSAZON#CSAZON#1023744049` -> `1023744049`) and hid order IDs when no electronic passholder was assigned.
- **Outcome**: UI display issues were resolved, but the underlying barcode fetching delay remained.

### Commit `5cbd80a`
- **Attempt**:
  1. Only attached `UserTokenXml` header to IK API calls if `isSamlToken(session.token)` is true (preventing XML parser failure on JWT tokens).
  2. Sanitized candidate IDs passed to VIM `GetJegykep` by stripping out composite keys (`ODAUTI_AJANLAT#...`).
  3. Bypassed redundant VIM `BerletTokLekeres` and `MegrendelesKereses` RPCs during initial sync when IK API succeeds.
- **Outcome**: The XML crash was resolved, but VIM still returned 30-second throttling responses on fresh logins.

### Commit `e8af5c5`
- **Attempt**:
  1. In `MavRepository.syncTicketsAndPasses()`, reordered operations so that active tickets are parsed and their barcode image pre-fetch is initiated immediately, before syncing passenger lists or past history.
  2. Deduplicated in-flight requests using a `ConcurrentHashMap<String, Deferred<MavTicketImage?>>` so background prefetch and UI requests share the same coroutine.
  3. Removed duplicate pre-fetch triggers in `login()` / `loginWithGoogle()`.
  4. In `MavViewModel.showBarcode()`, added an immediate `break` from the candidate ID loop upon receiving `MavTicketImageThrottledException`, setting `TICKET_IMAGE_RETRY_DELAY_MS = 32_000L` to prevent immediate back-to-back failures.
- **Outcome**: Even with these fixes, a fresh login on either Google OAuth2 or email+password still incurs a 30–60+ second delay before the barcode is fetched.

---

## 4. Root Cause Analysis & Diagnostic Hypotheses

Why is the delay still happening on fresh logins across both account types? Here are the critical hypotheses and avenues for the next engineer:

### Hypothesis 1: Candidate ID Waterfall Triggers VIM Anti-Hammering Lockout
In [MavApiClient.kt](file:///home/msstore/stuffs/viabusz/mav-tickets/src/main/java/hu/app/bmenetrend/mav/MavApiClient.kt), `getTicketImage()` loops through candidate IDs:
```kotlin
val candidates = listOfNotNull(
    receiptId.takeIf { it.isNotBlank() },
    cleanId,
    ticketSerial.takeIf { !it.isNullOrBlank() },
    technicalId.takeIf { !it.isNullOrBlank() },
    orderId.takeIf { !it.isNullOrBlank() }
).distinct()
```
- **The Trap**: If the first candidate ID in this list (e.g. `receiptId` or order ID) is NOT the exact identifier VIM `GetJegykep` expects for a pass, VIM rejects it.
- In many MÁV gateway configurations, receiving an invalid or unmatched ID query causes VIM to reply with `"Kérjük, próbálja meg ismét fél perc múlva."` (or an error that triggers the 30-second penalty).
- **Key question**: Does VIM `GetJegykep` specifically require `bizonylatTechnikaiAzonosito` (e.g. `1779935735`) or `jegySorszam` (e.g. `5594003G000448895`)? If the code queries `receiptId` first, it may be triggering the 30-second lockout on call #1!

### Hypothesis 2: Does `IK_API_PROD` Have a Native Barcode / Image Endpoint?
- VIM is a legacy WCF service (`vim.mav-start.hu`).
- The modern MÁV web application (`jegy.mav.hu`) communicates primarily with `IK_API_PROD` (`jegy-a.mav.hu`).
- `IK_API_PROD` has endpoints under:
  - `OrderAndPaymentApi/GetCertificatesImages`
  - `ProfileApi/GetPreviousPurchaseDetails`
- **Crucial check**: Does `IK_API_PROD` return the Aztec barcode image (or barcode content) directly without ever needing to call VIM? If `jegy.mav.hu` gets tickets without VIM, switching barcode retrieval to IK API would completely bypass VIM's legacy WCF rate-limiting and session propagation lag!

### Hypothesis 3: Can the Aztec Barcode be Rendered Locally?
- An Aztec code is a standard 2D matrix barcode (supported natively by libraries like `com.google.zxing:core`).
- Check if `GetPreviousPurchaseDetails` or `GetPreviousPurchases` already contains the barcode payload string/hex/bytes.
- If the ticket payload (e.g. digital signature, serial, ticket data) is present in the IK API JSON response, the app can render the Aztec barcode bitmap locally in milliseconds, completely eliminating network latency, VIM rate-limits, and offline issues!

### Hypothesis 4: VIM Token Propagation Delay on Fresh Authentication
- When an account logs in, MÁV's authentication issues tokens via `IK_API_PROD`.
- VIM is a distinct legacy service. On fresh logins, VIM may experience a database replication or token propagation delay (30–45 seconds) before `GetJegykep` acknowledges the newly issued session.
- If Google OAuth is used, the token is a JWT; VIM `GetJegykep` may not accept JWTs at all, or may require a specific token exchange or header format.

---

## 5. Recommended Action Plan for Next Assistant

1. **Direct API Inspection**:
   - Use `curl` or a standalone test script with the user's test account credentials (`bazsi.kokai@gmail.com` / `-2HeLLoworLdcaT1#`) or inspection of API responses to test:
     1. What does `OrderAndPaymentApi/GetCertificatesImages` on `IK_API_PROD` return for order `1023744049` / doc `1779935735`?
     2. Does `GetPreviousPurchaseDetails` contain the raw Aztec barcode string?
     3. When calling VIM `GetJegykep`, which exact parameter (`bizonylatTechnikaiAzonosito` vs `jegySorszam` vs order ID) succeeds, and what happens if `bizonylatTechnikaiAzonosito` is passed as the first and only candidate?
2. **Optimize Candidate Selection in `MavApiClient.kt`**:
   - Instead of trying multiple IDs sequentially (which risks burning rate-limits on the wrong ID), prioritize the exact ID required (e.g. `bizonylatTechnikaiAzonosito`).
3. **Explore Local Aztec Rendering**:
   - If Aztec data is present in the order details, render the barcode locally using ZXing instead of relying on VIM server-side image rendering.
4. **Build & Test Verification**:
   - Run unit tests: `./gradlew :mav-tickets:test`
   - Build release APK: `./gradlew assembleRelease`
   - Test on device or inspect network logs to verify instantaneous ticket loading.

---

## 6. Build Commands

```bash
# Run unit tests
./gradlew :mav-tickets:test

# Build release APK
./gradlew assembleRelease

# Release APK path:
# app/build/outputs/apk/release/app-universal-release.apk
```
