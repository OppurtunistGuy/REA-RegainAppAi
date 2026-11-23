# Phase 8 — learning UI, recap and insights

Covers `feature/learning/`, `feature/recap/` and `feature/insights/`. Copy lives in `res/values/strings_learning.xml`.

| Screen | What |
|--------|------|
| `SwapScreen` | 60-second swap: one card from `SwapPicker`, Show answer, Again/Hard/Got it → `Scheduler.review` → `CardRepository.upsert`. Empty state offers 3 sample physics cards or a hand-written card. |
| `ReviewScreen` | Frozen queue of due cards ("4 of 9"), done state with next due time, card list with add and edit. |
| `RecapScreen` | Three recall lines → `RecallToCards` → cards, pill with the projected "Got it" review days. Today's stats and the Mon–Sun `WeeklyGoal(4)` row. No streaks. |
| `InsightsScreen` | Last 4 weeks (Monday start, user zone): focus minutes and urges caught per week (Canvas bars), top surfaces, cards due in the next 7 days, plain summary line. |

Pure logic sits in `*Models.kt` / `LearningLogic.kt` (timer, reducers, state builders, due labels, week bucketing)
and is JVM-tested in `src/test/java/app/steady/feature/{learning,recap,insights}/`.

A deck is only `Card.deckId` (`physics` for the samples, the schedule label for recall cards, else `notes`).
Recall-to-cards is free; `notes_to_cards` gates bulk notes import only.

Waiting on the data layer (Phase 12): `CardRepository.delete`, and per-surface intercept counts for Insights.

```
cd android && ./gradlew :app:testDebugUnitTest --console=plain
```
