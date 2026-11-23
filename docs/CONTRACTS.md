# Contracts between phases

These signatures are fixed. Implement them exactly. Add helpers freely, but keep these names, packages
and shapes so parallel phases fit together without coordination.

Toolchain (pinned in `android/gradle/libs.versions.toml`): Gradle 8.14.3 wrapper, AGP 8.10.1,
Kotlin 2.1.21, JDK 21 toolchain target 17, compileSdk 35, minSdk 26, kotlinx-serialization 1.8.1,
coroutines 1.10.2, JUnit 5 for JVM modules. Android SDK lives at `/opt/android-sdk`
(`android/local.properties` points there; it is git-ignored).

Package root: `app.steady`. Application id: `app.steady`.

---

## Shared data (`shared/schema/plan.schema.json`)

The Plan is the single rules document. Android, backend and extension all read and write it.

```kotlin
package app.steady.rules

@Serializable data class Plan(
  val version: Int = 1,                 // bumps on every edit, used for sync conflicts
  val distractions: List<Surface>,
  val schedules: List<Schedule>,
  val keyHolder: KeyHolder? = null,
  val unlockPolicy: UnlockPolicy = UnlockPolicy(),
  val siteBlocks: List<String> = emptyList()   // bare domains, e.g. "instagram.com"
)
@Serializable data class Surface(val id: String, val mode: SurfaceMode)
// id: "instagram.reels" | "youtube.shorts" | "snapchat.spotlight" | "facebook.reels"
//     | "app:<packageName>" | "site:<domain>"
@Serializable enum class SurfaceMode { @SerialName("hide_section") HIDE_SECTION, @SerialName("block_app") BLOCK_APP }
@Serializable data class Schedule(
  val id: String, val label: String,
  val days: Set<Int>,          // ISO day of week, 1 = Monday … 7 = Sunday
  val start: String,           // "HH:mm" local time
  val end: String,             // "HH:mm"; end < start means it crosses midnight
  val strict: Boolean = true
)
@Serializable data class KeyHolder(val id: String, val name: String, val relation: Relation)
@Serializable enum class Relation { @SerialName("self") SELF, @SerialName("friend") FRIEND, @SerialName("parent") PARENT }
@Serializable data class UnlockPolicy(
  val waitSeconds: Int = 30, val requireReason: Boolean = true,
  val notifyKeyHolder: Boolean = true, val approvalMinutes: Int = 15
)
```

## Phase 2 — `android/core/rules` (package `app.steady.rules`)

```kotlin
object PlanCodec { fun decode(json: String): Plan; fun encode(plan: Plan): String }

sealed interface BlockState {
  data object Idle : BlockState
  data class Active(val scheduleId: String, val strict: Boolean, val startedAt: Instant,
                    val endsAt: Instant, val blocked: Set<Surface>) : BlockState
}
data class UpcomingBlock(val scheduleId: String, val label: String, val startsAt: Instant, val endsAt: Instant)

data class UnlockGrant(val id: String, val surfaceId: String?, // null = everything
                       val from: Instant, val until: Instant, val approvedBy: String?)

class RulesEngine(private val zone: ZoneId) {
  fun state(plan: Plan, now: Instant): BlockState
  fun nextBlock(plan: Plan, now: Instant): UpcomingBlock?          // next start strictly after now
  fun isBlocked(plan: Plan, grants: List<UnlockGrant>, surfaceId: String, now: Instant): Boolean
  fun blocksOn(plan: Plan, date: LocalDate): List<UpcomingBlock>   // for the Today day strip
}

enum class UnlockStep { WAITING, NEEDS_REASON, READY, NEEDS_KEYHOLDER }
class UnlockFlow(private val policy: UnlockPolicy, private val hasKeyHolder: Boolean) {
  fun step(waitStartedAt: Instant, now: Instant, reason: String): UnlockStep
  fun secondsLeft(waitStartedAt: Instant, now: Instant): Int
  fun selfGrant(surfaceId: String?, now: Instant, minutes: Int = policy.approvalMinutes): UnlockGrant
}

enum class Energy { LOW, OKAY, SHARP }
object EnergyPlanner { fun blockMinutes(energy: Energy): Int }   // LOW 15, OKAY 25, SHARP 40
```
Rules: a reason counts only if it is at least 8 non-space characters. If `requireReason` and the
reason is short → NEEDS_REASON. If a key holder exists and `notifyKeyHolder` → NEEDS_KEYHOLDER
after the wait (approval comes from the key holder via backend). Non-strict schedules never block
the `Intercept` exits.

## Phase 3 — `android/core/detect` (package `app.steady.detect`)

Accessibility trees get mapped to this platform-free interface by Phase 6.

```kotlin
interface ViewNode {
  val viewId: String?; val className: String?; val text: String?
  val contentDescription: String?; val isSelected: Boolean; val children: List<ViewNode>
}
@Serializable data class DetectorConfig(val version: Int, val detectors: List<Detector>)
@Serializable data class Detector(val surfaceId: String, val packageName: String,
                                  val anyOf: List<Matcher>, val anchor: Matcher? = null)
@Serializable data class Matcher(val viewIdEquals: String? = null, val viewIdEndsWith: String? = null,
  val textEquals: String? = null, val descContains: String? = null, val classEquals: String? = null,
  val selected: Boolean? = null)     // every non-null field must match the same node
object DetectorCodec { fun decode(json: String): DetectorConfig }

data class Detection(val surfaceId: String?, val configVersion: Int, val matchedBy: Matcher?)
class SurfaceDetector(config: DetectorConfig) {
  fun detect(packageName: String, root: ViewNode): Detection    // depth-first, stops at first hit, max 4000 nodes
  fun anchorSeen(packageName: String, root: ViewNode): Boolean  // used for health
}

enum class EnforcementLevel { HIDE_SECTION, BLOCK_APP, BLOCK_DOMAIN }
class DetectorHealth(private val windowSize: Int = 20, private val minAnchorRate: Double = 0.3) {
  fun record(packageName: String, anchorSeen: Boolean)
  fun isHealthy(packageName: String): Boolean   // true until windowSize samples exist
}
object FallbackLadder {
  fun level(mode: SurfaceMode, healthy: Boolean, accessibilityOn: Boolean): EnforcementLevel
  // HIDE_SECTION + healthy + a11y → HIDE_SECTION; a11y on but unhealthy → BLOCK_APP;
  // a11y off → BLOCK_DOMAIN (VPN/DNS fallback). BLOCK_APP mode → BLOCK_APP (or BLOCK_DOMAIN if a11y off).
}
```
`core/detect` depends on `core/rules` only for `SurfaceMode`.
Seed config: `shared/detectors/detectors.v1.json` (copied into app assets at build time by Phase 5).

## Phase 4 — `android/core/learning` (package `app.steady.learning`)

```kotlin
@Serializable data class Card(val id: String, val deckId: String, val front: String, val back: String,
                              val createdAt: Instant, val state: ReviewState)
@Serializable data class ReviewState(val due: Instant, val stabilityDays: Double, val difficulty: Double,
                                     val reps: Int, val lapses: Int)
enum class Rating { AGAIN, HARD, GOOD }
object Scheduler {
  fun newState(now: Instant): ReviewState              // due = now
  fun review(state: ReviewState, rating: Rating, now: Instant): ReviewState  // FSRS-inspired, documented
}
object RecallToCards {
  fun fromRecall(lines: List<String>, deckId: String, now: Instant, idGen: () -> String): List<Card>
  // "A = B", "A: B", "A - B", "A is B" → front/back; otherwise cloze on longest noun-ish word; blank lines skipped
}
object SwapPicker { fun pick(cards: List<Card>, now: Instant): Card? }   // most overdue first, then newest
@Serializable data class FocusSession(val id: String, val scheduleId: String?, val start: Instant,
                                      val end: Instant, val intercepts: Int, val unlocks: Int)
data class GoalProgress(val targetDays: Int, val doneDays: Set<LocalDate>, val remainingDays: Int, val met: Boolean)
class WeeklyGoal(private val targetDays: Int, private val zone: ZoneId, private val minMinutes: Int = 10) {
  fun progress(sessions: List<FocusSession>, weekStart: LocalDate): GoalProgress   // no streaks, ever
}
```
Instants are `kotlinx.datetime.Instant`? **No** — use `java.time.Instant` everywhere, with a small
`InstantSerializer` (ISO-8601 string) in `app.steady.rules.serial` that core/learning reuses.
So `core/learning` depends on `core/rules`.

## Phase 5 — app shell slots (package `app.steady`)

Phase 5 creates every file below. Later phases **replace the body** of the files they own and may add
new files in their package. They never edit `nav/`, `di/AppContainer.kt` or the manifest except
the marked lines.

```
MainActivity.kt                       Compose host, edge-to-edge
SteadyApp.kt                          Application, builds AppContainer
di/AppContainer.kt                    interface AppContainer { planRepo, sessionRepo, cardRepo, unlockRepo,
                                      entitlements, syncApi, rules: RulesEngine, detectorConfig: DetectorConfig }
data/                                 Room DB + repositories (local-first)
nav/Routes.kt                         Setup, Today, Intercept, Swap, Focus, Recap, Review, Insights,
                                      Partner, Paywall, Settings, Disclosure
nav/SteadyNavHost.kt                  maps each route to the screen composable below
ui/theme/                             tokens from the prototype (Atkinson Hyperlegible body, Bricolage display)
feature/setup/SetupScreen.kt          Phase 5
feature/today/TodayScreen.kt          Phase 5
feature/intercept/InterceptScreen.kt  Phase 7  (stub from 5)
feature/intercept/InterceptContent.kt Phase 7  @Composable fun InterceptContent(state: InterceptUiState,
                                         onClose: () -> Unit, onSwap: () -> Unit, onMessagesOnly: () -> Unit)
                                         data class InterceptUiState(surfaceId, scheduleLabel, opensToday, opensBaseline)
feature/focus/FocusScreen.kt          Phase 7
feature/learning/SwapScreen.kt        Phase 8
feature/learning/ReviewScreen.kt      Phase 8
feature/recap/RecapScreen.kt          Phase 8
feature/insights/InsightsScreen.kt    Phase 8
feature/partner/PartnerScreen.kt      Phase 11
feature/paywall/PaywallScreen.kt      Phase 11
feature/settings/SettingsScreen.kt    Phase 11
feature/disclosure/DisclosureScreen.kt Phase 6 (prominent Accessibility disclosure, Play policy)
enforcement/SteadyAccessibilityService.kt  Phase 6 (stub from 5)
enforcement/FocusService.kt           Phase 6 (foreground service, type specialUse)
enforcement/InterceptOverlay.kt       Phase 6 hosts InterceptContent in a TYPE_ACCESSIBILITY_OVERLAY window
remote/                               Phase 11 (SyncApi impl, OkHttp + kotlinx-serialization)
```

Repository interfaces (in `app.steady.data`):

```kotlin
interface PlanRepository { val plan: Flow<Plan>; suspend fun save(plan: Plan) }
interface SessionRepository { val sessions: Flow<List<FocusSession>>; suspend fun add(s: FocusSession)
                              suspend fun recordIntercept(surfaceId: String, at: Instant); fun interceptsOn(date: LocalDate): Flow<Int> }
interface CardRepository { val cards: Flow<List<Card>>; suspend fun upsert(cards: List<Card>) }
interface UnlockRepository { val grants: Flow<List<UnlockGrant>>; suspend fun add(g: UnlockGrant)
                             suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String /* request id */ }
interface EntitlementRepository { val entitlement: Flow<Entitlement> }   // data class Entitlement(val tier: Tier, val features: Set<String>) enum Tier { FREE, PLUS }
interface SyncApi { suspend fun pushPlan(plan: Plan): Plan; suspend fun pullPlan(): Plan?; suspend fun detectors(): DetectorConfig? }
```
Phase 5 ships local/no-op implementations (`NoopSyncApi`, free entitlement). Phase 11 swaps in remote ones
through `AppContainer` only.

Free tier limits (Phase 11 enforces, others read `Entitlement.features`):
free = strict mode, 2 schedules, Reels/Shorts hiding, breath + swap, 1 key holder.
plus adds `"unlimited_schedules"`, `"desktop_sync"`, `"notes_to_cards"`, `"task_breakdown"`.

## Phase 9 — backend (`shared/api/openapi.yaml`)

Base `/v1`, JSON, bearer token. Node 22, TypeScript, Hono, `node:sqlite` (no native deps), vitest.

```
POST /v1/auth/otp/start      {contact}                → 204           (dev: code logged, always "000000" when NODE_ENV=test)
POST /v1/auth/otp/verify     {contact, code}          → {token, userId}
GET  /v1/plan                                         → Plan | 404
PUT  /v1/plan                Plan (version = server+1) → Plan | 409 {current: Plan}
GET  /v1/detectors                                    → DetectorConfig (serves shared/detectors/detectors.v1.json)
POST /v1/keyholder/invites                            → {code, expiresAt}
POST /v1/keyholder/invites/{code}/accept              → {ownerUserId, ownerName}
POST /v1/unlock-requests     {surfaceId|null, reason} → {id, status: "pending"}
GET  /v1/unlock-requests/{id}                         → {id, status: "pending"|"approved"|"denied", until?}
GET  /v1/keyholder/requests                           → [{id, ownerName, reason, surfaceId, createdAt}]
POST /v1/unlock-requests/{id}/decision {approve, minutes?} → {id, status, until?}
POST /v1/sessions            FocusSession             → 204    (aggregate only: times + counts)
GET  /v1/summary/weekly?weekStart=YYYY-MM-DD          → {focusMinutes, blocks, intercepts, unlocks, days: [date]}
GET  /v1/entitlements                                 → {tier: "free"|"plus", features: [string]}
```
Reject any body field not in the schema (strict validation) so screen content can never be stored.

## Phase 10 — extension

Reads `GET /v1/plan` and `GET /v1/detectors` with a token from the options page. Applies
`siteBlocks` + `site:*` surfaces with `declarativeNetRequest` during active schedules, hides YouTube
Shorts shelf / `/shorts/` and Instagram `/reels/` with content scripts, and shows a breath
interstitial page with the same three exits. Schedule logic is a TypeScript port of `RulesEngine.state`
with the same test vectors (`shared/schema/test-vectors.json`).

---

## Phase 12 additions (backend ⇄ Android)

```
POST   /v1/auth/logout                                   → 204   (revokes the calling token)
DELETE /v1/me                                            → 204   (erases plan, sessions, unlock requests, invites,
                                                                  key-holder links both ways, entitlements, tokens)
GET    /v1/keyholder/links                               → [{linkId, keyHolderUserId, keyHolderName, createdAt}]   (owner view)
GET    /v1/keyholder/owners                              → [{linkId, ownerUserId, ownerName, createdAt}]           (key-holder view)
DELETE /v1/keyholder/links/{linkId}                      → 204   (either side may remove; else 404)
GET    /v1/keyholder/summary?ownerUserId=&weekStart=&tz= → same body as /v1/summary/weekly, only for a linked key holder (else 404)
POST   /v1/billing/verify  {productId, purchaseToken}    → {tier, features}
       productId ∈ {"steady_plus_monthly","steady_plus_yearly"}. Verification goes through a `PurchaseVerifier`
       interface. Without Play credentials configured: non-production accepts tokens starting "test-";
       production returns 503 {error:{code:"billing_not_configured"}}.
```
Tokens expire after 90 days of inactivity (sliding); expired → 401 `token_expired`.
Free tier allows 1 key-holder link per owner: a second accept → 409 `keyholder_limit` unless the owner is Plus.
