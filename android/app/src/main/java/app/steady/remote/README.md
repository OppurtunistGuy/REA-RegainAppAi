# remote/ (Phase 11)

Backend client and the remote-backed services, wired in `di/AppContainer.kt` (`DefaultAppContainer`
implements `RemoteHost`; screens use `container.remote`, null for test containers).

| File | What |
|------|------|
| `ApiClient.kt` | OkHttp + kotlinx-serialization client for `shared/api/openapi.yaml`. Typed `ApiException`s: `Unauthorized`, `Conflict(code, current: Plan?)`, `NotFound`, `Rejected` (other 4xx), `Network`, `Server`. |
| `ApiModels.kt` | Wire DTOs. Request bodies hold only schema fields (sessions: times + counts). |
| `AccountStore.kt` | Token, user, base URL (BuildConfig default: debug `http://10.0.2.2:8787`, release https), held keys, uploaded-session ids. Private SharedPreferences; the token is stored only as `TokenCipher` ciphertext, a legacy plain token is migrated on first read. |
| `TokenCipher.kt` | `KeystoreTokenCipher`: AES-256/GCM key in `AndroidKeyStore`. Tests use `FakeTokenCipher`. |
| `AuthGuard.kt` | 401 on an authenticated call (`token_expired` or revoked) → local sign-out + `SignedOutReason` shown in Settings. |
| `RemoteSyncApi.kt` | `SyncApi`; returns the server plan on 409; no-op when signed out. |
| `SyncCoordinator.kt` | `SyncMerge` (higher version wins, tie → server, local pushed as `server + 1`) and the debounced pull/merge/push loop. |
| `RemoteUnlockRepository.kt` | Decorates the local `UnlockRepository`: POST unlock request, poll every 10 s for 15 min, add an `UnlockGrant(approvedBy = keyHolderId)` on approval. Throws `NotSignedIn` / `NoKeyHolderLinked`. |
| `BillingGateway.kt`, `PlayBillingGateway.kt` | Play Billing behind an interface (`steady_plus_monthly`, `steady_plus_yearly`). |
| `RemoteEntitlementRepository.kt` | `EntitlementLogic` (pure) + repo combining Play purchases with `GET /v1/entitlements`. Free by default/offline. |
| `SessionUploader.kt` | `POST /v1/sessions` for sessions not sent yet, while signed in. |
| `RemoteModule.kt` | Builds and starts all of the above; refreshes `DetectorConfigStore` after each sync; `signOut()` (then `POST /v1/auth/logout`), `deleteSyncedData()` (`DELETE /v1/me`). |

Phase 12 endpoints (`docs/CONTRACTS.md`): logout, `DELETE /v1/me`, key-holder links/owners/remove,
`/v1/keyholder/summary`, `/v1/billing/verify` (from `RemoteEntitlementRepository.verifyPurchases`).
409 `keyholder_limit` → Partner explains and links to the Paywall.

Tests: `src/test/java/app/steady/remote/` (MockWebServer, coroutine virtual time, fake billing).
Run `cd android && ./gradlew :app:testDebugUnitTest`.
